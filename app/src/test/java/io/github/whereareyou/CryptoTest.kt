package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.crypto.Secp256k1
import io.github.whereareyou.data.Circle
import io.github.whereareyou.data.Invite
import io.github.whereareyou.nostr.KeyPair
import io.github.whereareyou.nostr.Nostr
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun hex(s: String) = Crypto.unhex(s.lowercase())

    @Test
    fun bip340_vector0() {
        val sk = hex("0000000000000000000000000000000000000000000000000000000000000003")
        val msg = ByteArray(32)
        val aux = ByteArray(32)
        assertEquals(
            "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            Crypto.hex(Secp256k1.xOnlyPublicKey(sk))
        )
        val sig = Secp256k1.sign(sk, msg, aux)
        assertEquals(
            "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0",
            Crypto.hex(sig)
        )
        assertTrue(Secp256k1.verify(Secp256k1.xOnlyPublicKey(sk), msg, sig))
    }

    @Test
    fun signVerifyRandom() {
        repeat(5) {
            val kp = KeyPair.derive(Crypto.random(32), "test$it")
            val msg = Crypto.random(32)
            val sig = Secp256k1.sign(kp.secret, msg)
            assertTrue(Secp256k1.verify(Crypto.unhex(kp.publicHex), msg, sig))
            msg[0] = (msg[0] + 1).toByte()
            assertFalse(Secp256k1.verify(Crypto.unhex(kp.publicHex), msg, sig))
        }
    }

    @Test
    fun eventIdAndSignatureAreValid() {
        val kp = KeyPair.derive(Crypto.random(32), "event")
        val content = "a/b+c==\n\"quoted\" é"
        val ev = JSONObject(Nostr.signEvent(kp, 30078, listOf(listOf("d", "abc")), content, 1700000000))
        assertEquals(content, ev.getString("content"))
        val serialized = "[0,\"${kp.publicHex}\",1700000000,30078,[[\"d\",\"abc\"]],${Nostr.quote(content)}]"
        val id = Crypto.sha256(serialized.toByteArray())
        assertEquals(Crypto.hex(id), ev.getString("id"))
        assertTrue(Secp256k1.verify(Crypto.unhex(kp.publicHex), id, Crypto.unhex(ev.getString("sig"))))
    }

    @Test
    fun encryptDecrypt() {
        val key = Crypto.random(32)
        val aad = "tag".toByteArray()
        val ct = Crypto.encrypt(key, "hello".toByteArray(), aad)
        assertEquals("hello", String(Crypto.decrypt(key, ct, aad)!!))
        assertNull(Crypto.decrypt(Crypto.random(32), ct, aad))
        assertNull(Crypto.decrypt(key, ct, "other".toByteArray()))
    }

    @Test
    fun hkdfRfc5869Case1() {
        val okm = Crypto.hkdf(
            hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            hex("000102030405060708090a0b0c"),
            hex("f0f1f2f3f4f5f6f7f8f9"),
            42
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hex(okm)
        )
    }

    @Test
    fun inviteRoundTrip() {
        val c = Circle("Famille", Crypto.random(32), true)
        val link = Invite.encode(c)
        val decoded = Invite.decode("Rejoins-moi : $link !")!!
        assertEquals("Famille", decoded.first)
        assertArrayEquals(c.secret, decoded.second)
        assertEquals(c.tag, Circle(decoded.first, decoded.second, true).tag)
        assertNull(Invite.decode("whereareyou://join/garbage"))
    }

    @Test
    fun perCircleKeysAreUnlinkable() {
        val seed = Crypto.random(32)
        assertNotEquals(KeyPair.derive(seed, "author:a").publicHex, KeyPair.derive(seed, "author:b").publicHex)
        assertEquals(KeyPair.derive(seed, "author:a").publicHex, KeyPair.derive(seed, "author:a").publicHex)
    }
}
