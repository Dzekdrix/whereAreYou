package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.crypto.Secp256k1
import io.github.whereareyou.data.Envelope
import io.github.whereareyou.nostr.KeyPair
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EnvelopeTest {
    private val alice = KeyPair.derive(Crypto.random(32), "member-identity")
    private val mallory = KeyPair.derive(Crypto.random(32), "member-identity")
    private val tag = Crypto.hex(Crypto.random(16))
    /** Nostr author keys (one per member and per circle). */
    private val aliceAuthor = KeyPair.derive(Crypto.random(32), "author:$tag").publicHex
    private val malloryAuthor = KeyPair.derive(Crypto.random(32), "author:$tag").publicHex
    private fun position() = JSONObject().put("n", "Alice").put("lat", 48.8566).put("lon", 2.3522).put("ts", 1730000000000L)

    @Test
    fun validSignatureIsAccepted() {
        val p = Envelope.open(tag, aliceAuthor, Envelope.seal(alice, aliceAuthor, tag, position()))
        assertNotNull(p)
        assertEquals(alice.publicHex, p!!.getString("k"))
        assertEquals(48.8566, p.getDouble("lat"), 0.0)
    }

    @Test
    fun modifiedContentIsRejected() {
        val sealed = Envelope.seal(alice, aliceAuthor, tag, position())
        val outer = JSONObject(sealed)
        outer.put("p", outer.getString("p").replace("48.8566", "40.0"))
        assertNull(Envelope.open(tag, aliceAuthor, outer.toString()))
    }

    @Test
    fun impersonationIsRejected() {
        // Mallory signs with her key but claims to be Alice.
        val sealed = JSONObject(Envelope.seal(mallory, malloryAuthor, tag, position()))
        sealed.put("p", sealed.getString("p").replace(mallory.publicHex, alice.publicHex))
        assertNull(Envelope.open(tag, malloryAuthor, sealed.toString()))
    }

    @Test
    fun replayInAnotherCircleIsRejected() {
        val other = Crypto.hex(Crypto.random(16))
        assertNull(Envelope.open(other, aliceAuthor, Envelope.seal(alice, aliceAuthor, tag, position())))
    }

    @Test
    fun unsignedOrOldFormatIsRejected() {
        assertNull(Envelope.open(tag, aliceAuthor, "{\"v\":1,\"id\":\"a\",\"lat\":1,\"lon\":2,\"ts\":3}"))
        assertNull(Envelope.open(tag, aliceAuthor, "not json"))
    }

    @Test
    fun rewrappedMessageIsRejected() {
        // Mallory re-publishes Alice's (valid, signed) message inside an event of her own.
        val sealed = Envelope.seal(alice, aliceAuthor, tag, position())
        assertNull(Envelope.open(tag, malloryAuthor, sealed))
    }

    @Test
    fun authorCannotBeChanged() {
        val sealed = JSONObject(Envelope.seal(alice, aliceAuthor, tag, position()))
        sealed.put("p", sealed.getString("p").replace(aliceAuthor, malloryAuthor))
        assertNull(Envelope.open(tag, malloryAuthor, sealed.toString()))
    }

    @Test
    fun messageOfOlderVersionWithoutAuthorIsAccepted() {
        // Sealed like versions before 0.6.0: no "a" in the signed payload.
        val inner = JSONObject(position().toString()).put("k", alice.publicHex).toString()
        val digest = Secp256k1.taggedHash("whereareyou/v2/message", tag.toByteArray(), inner.toByteArray())
        val sealed = JSONObject().put("v", 2).put("p", inner).put("s", Crypto.hex(Secp256k1.sign(alice.secret, digest)))
        val p = Envelope.open(tag, malloryAuthor, sealed.toString())
        assertNotNull(p)
        assertEquals(alice.publicHex, p!!.getString("k"))
    }

    @Test
    fun newMessageStaysReadableByOlderVersions() {
        // Older versions check the signature of the same "p" string and ignore unknown fields.
        val outer = JSONObject(Envelope.seal(alice, aliceAuthor, tag, position()))
        val inner = outer.getString("p")
        val digest = Secp256k1.taggedHash("whereareyou/v2/message", tag.toByteArray(), inner.toByteArray())
        assertEquals(2, outer.getInt("v"))
        assertEquals(true, Secp256k1.verify(Crypto.unhex(alice.publicHex), digest, Crypto.unhex(outer.getString("s"))))
    }
}
