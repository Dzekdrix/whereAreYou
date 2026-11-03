package io.github.whereareyou.data

import io.github.whereareyou.crypto.Crypto
import org.json.JSONObject

private val SALT = "whereareyou-v1".toByteArray()

/**
 * A circle (group) is just a shared random secret. Everyone holding the secret
 * can publish and read encrypted positions in the circle.
 * Sharing with a single person = a circle of two.
 * [share]: we publish our position here; [visible]: its members are shown on our map.
 */
class Circle(val name: String, val secret: ByteArray, val share: Boolean, val visible: Boolean = true) {
    /** Public, random-looking identifier used as the Nostr "d" tag. Reveals nothing about the circle. */
    val tag: String = Crypto.hex(Crypto.hkdf(secret, SALT, "tag".toByteArray(), 16))

    /** AES-256 key used to encrypt positions. */
    val key: ByteArray = Crypto.hkdf(secret, SALT, "enc".toByteArray(), 32)

    fun copy(name: String = this.name, share: Boolean = this.share, visible: Boolean = this.visible) =
        Circle(name, secret, share, visible)
}

/** A position report. [maxGapMs]: longest silence announced by its author before the next one (0 = unknown). */
data class Fix(val lat: Double, val lon: Double, val accuracy: Float, val time: Long, val maxGapMs: Long = 0)

/**
 * A person seen on the map. [circles] = names (for display), [circleTags] = the circles they share with us,
 * [emoji] = chosen marker ("" = initial of the name), [battery] = battery level in % (null = not shared).
 */
data class Member(
    val id: String,
    val name: String,
    val fix: Fix,
    val circles: List<String>,
    val circleTags: Set<String>,
    val emoji: String = "",
    val color: Int? = null,
    val battery: Int? = null,
)

/** Marker background color chosen by a member, sent as "#RRGGBB" (null = automatic, derived from the member id). */
object MarkerColor {
    fun parse(text: String?): Int? {
        val t = text?.trim() ?: return null
        if (t.length != 7 || t[0] != '#') return null
        return t.substring(1).toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    fun format(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
}

/** Marker emoji chosen by a member. */
object Emoji {
    /** Keeps a single character (emoji, flag, skin tone... included); "" if none. Also cleans received values. */
    fun sanitize(text: String): String {
        val s = text.trim().take(32)
        if (s.isEmpty()) return ""
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val end = it.next()
        return if (end == java.text.BreakIterator.DONE) "" else s.substring(0, end)
    }
}

/** Invitation link / QR code content: whereareyou://join/<base64url(json)> */
object Invite {
    const val PREFIX = "whereareyou://join/"

    fun encode(circle: Circle): String {
        val json = JSONObject().put("v", 1).put("n", circle.name).put("k", Crypto.b64url(circle.secret))
        return PREFIX + Crypto.b64url(json.toString().toByteArray())
    }

    /** Accepts the bare link or any text containing it (e.g. a forwarded message). */
    fun decode(text: String): Pair<String, ByteArray>? = try {
        val start = text.indexOf(PREFIX)
        if (start < 0) {
            null
        } else {
            val payload = text.substring(start + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            val json = JSONObject(String(Crypto.unb64url(payload)))
            val secret = Crypto.unb64url(json.getString("k"))
            if (secret.size != 32) null else json.optString("n", "?") to secret
        }
    } catch (e: Exception) {
        null
    }
}
