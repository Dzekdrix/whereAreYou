package io.github.whereareyou.nostr

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.crypto.Secp256k1
import java.math.BigInteger

/** A secp256k1 key pair used to sign Nostr events. */
class KeyPair(val secret: ByteArray) {
    val publicHex: String = Crypto.hex(Secp256k1.xOnlyPublicKey(secret))

    companion object {
        /** Deterministically derives a valid key from a seed and a context string. */
        fun derive(seed: ByteArray, context: String): KeyPair {
            val raw = Crypto.hkdf(seed, "whereareyou-key".toByteArray(), context.toByteArray(), 32)
            val d = BigInteger(1, raw).mod(Secp256k1.N.subtract(BigInteger.ONE)).add(BigInteger.ONE)
            return KeyPair(Secp256k1.bytes32(d))
        }
    }
}

/** Builds signed Nostr events (NIP-01). */
object Nostr {
    /** NIP-78 "application-specific data": addressable, relays keep only the latest per (author, d). */
    const val KIND_APP_DATA = 30078

    fun signEvent(
        key: KeyPair,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): String {
        val tagsJson = tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { quote(it) } }
        val serialized = "[0,${quote(key.publicHex)},$createdAt,$kind,$tagsJson,${quote(content)}]"
        val id = Crypto.sha256(serialized.toByteArray(Charsets.UTF_8))
        val sig = Secp256k1.sign(key.secret, id)
        return "{\"id\":\"${Crypto.hex(id)}\",\"pubkey\":\"${key.publicHex}\",\"created_at\":$createdAt," +
            "\"kind\":$kind,\"tags\":$tagsJson,\"content\":${quote(content)},\"sig\":\"${Crypto.hex(sig)}\"}"
    }

    /** JSON string escaping exactly as required by NIP-01 for the event id computation. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
