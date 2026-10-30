package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
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
    private fun position() = JSONObject().put("n", "Alice").put("lat", 48.8566).put("lon", 2.3522).put("ts", 1730000000000L)

    @Test
    fun validSignatureIsAccepted() {
        val p = Envelope.open(tag, Envelope.seal(alice, tag, position()))
        assertNotNull(p)
        assertEquals(alice.publicHex, p!!.getString("k"))
        assertEquals(48.8566, p.getDouble("lat"), 0.0)
    }

    @Test
    fun modifiedContentIsRejected() {
        val sealed = Envelope.seal(alice, tag, position())
        val outer = JSONObject(sealed)
        outer.put("p", outer.getString("p").replace("48.8566", "40.0"))
        assertNull(Envelope.open(tag, outer.toString()))
    }

    @Test
    fun impersonationIsRejected() {
        // Mallory signs with her key but claims to be Alice.
        val sealed = JSONObject(Envelope.seal(mallory, tag, position()))
        sealed.put("p", sealed.getString("p").replace(mallory.publicHex, alice.publicHex))
        assertNull(Envelope.open(tag, sealed.toString()))
    }

    @Test
    fun replayInAnotherCircleIsRejected() {
        val other = Crypto.hex(Crypto.random(16))
        assertNull(Envelope.open(other, Envelope.seal(alice, tag, position())))
    }

    @Test
    fun unsignedOrOldFormatIsRejected() {
        assertNull(Envelope.open(tag, "{\"v\":1,\"id\":\"a\",\"lat\":1,\"lon\":2,\"ts\":3}"))
        assertNull(Envelope.open(tag, "not json"))
    }
}
