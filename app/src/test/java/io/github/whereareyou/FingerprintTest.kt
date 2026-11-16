package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.data.Fingerprint
import io.github.whereareyou.data.NameClash
import io.github.whereareyou.nostr.KeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {
    @Test
    fun emojiTableHas256DistinctSingleEmoji() {
        assertEquals(256, Fingerprint.EMOJI.size)
        assertEquals(256, Fingerprint.EMOJI.toSet().size)
        Fingerprint.EMOJI.forEach { e ->
            assertEquals("not a single code point: $e", 1, e.codePointCount(0, e.length))
            assertTrue("variation selector in $e", '️' !in e)
        }
    }

    @Test
    fun fingerprintIsStableAndDistinct() {
        val a = KeyPair.derive(Crypto.random(32), "member-identity").publicHex
        val b = KeyPair.derive(Crypto.random(32), "member-identity").publicHex
        assertEquals(Fingerprint.LENGTH, Fingerprint.of(a).size)
        assertEquals(Fingerprint.of(a), Fingerprint.of(a))
        assertNotEquals(Fingerprint.of(a), Fingerprint.of(b))
    }

    @Test
    fun knownVector() {
        // Pins the derivation: every version of the app must show the same fingerprint for a key.
        val id = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        assertEquals(FINGERPRINT_OF_G, Fingerprint.of(id).joinToString(""))
    }

    @Test
    fun sameNameDifferentIdentitiesClash() {
        val people = listOf("id1" to "Bob", "id2" to " bób ", "id3" to "Alice", "id4" to "B​ob")
        assertEquals(setOf("id1", "id2", "id4"), NameClash.find(people))
    }

    @Test
    fun sameIdentityInSeveralCirclesDoesNotClash() {
        assertEquals(emptySet<String>(), NameClash.find(listOf("id1" to "Bob", "id1" to "Bob", "id2" to "Alice")))
    }

    companion object {
        /** Public key = generator point G of secp256k1; recomputed independently with Python's hashlib. */
        const val FINGERPRINT_OF_G = "⚡⛲🛴⚽🐵🌻🍗🧭"
    }
}
