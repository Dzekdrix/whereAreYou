package io.github.whereareyou.data

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.crypto.Secp256k1
import io.github.whereareyou.nostr.KeyPair
import org.json.JSONObject

/**
 * Signs a member's message with their personal identity key, so another member of the
 * circle (who knows the shared circle key) cannot send messages in their name.
 *
 * Plaintext (before AES-GCM encryption): {"v":2, "p":"<inner JSON>", "s":"<Schnorr signature>"}
 * Inner JSON always contains "k" (identity public key = member id) and "ts" (milliseconds).
 * The signature covers the circle tag, so a message cannot be replayed in another circle.
 */
object Envelope {
    const val VERSION = 2
    private const val DOMAIN = "whereareyou/v2/message"

    fun seal(identity: KeyPair, tag: String, payload: JSONObject): String {
        val inner = JSONObject(payload.toString()).put("k", identity.publicHex).toString()
        val sig = Secp256k1.sign(identity.secret, digest(tag, inner))
        return JSONObject().put("v", VERSION).put("p", inner).put("s", Crypto.hex(sig)).toString()
    }

    /** Returns the inner payload if the signature is valid, null otherwise. */
    fun open(tag: String, plaintext: String): JSONObject? = try {
        val outer = JSONObject(plaintext)
        val inner = outer.getString("p")
        val payload = JSONObject(inner)
        val key = Crypto.unhex(payload.getString("k"))
        val sig = Crypto.unhex(outer.getString("s"))
        val valid = outer.optInt("v") == VERSION && payload.has("ts") &&
            Secp256k1.verify(key, digest(tag, inner), sig)
        if (valid) payload else null
    } catch (e: Exception) {
        null
    }

    private fun digest(tag: String, inner: String) =
        Secp256k1.taggedHash(DOMAIN, tag.toByteArray(), inner.toByteArray(Charsets.UTF_8))
}
