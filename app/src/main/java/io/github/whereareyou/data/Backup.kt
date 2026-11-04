package io.github.whereareyou.data

import io.github.whereareyou.crypto.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Everything needed to be the same member on another device (or after reinstalling). */
class BackupData(
    val seed: ByteArray,
    val circles: List<Circle>,
    val name: String,
    val emoji: String,
    val markerColor: Int?,
    val movingIntervalSeconds: Int,
    val stillIntervalSeconds: Int,
    val relays: List<String>,
    val shareBattery: Boolean,
)

/**
 * Backup file, encrypted with a password chosen by the user:
 * {"format":"whereareyou-backup","v":1,"kdf":"pbkdf2-sha256","iter":600000,"salt":"...","data":"..."}
 * key = PBKDF2-HMAC-SHA256(password, salt, iter), data = AES-256-GCM(JSON of [BackupData]).
 */
object Backup {
    private const val FORMAT = "whereareyou-backup"
    private const val ITERATIONS = 600_000
    private val AAD = FORMAT.toByteArray()

    fun export(data: BackupData, password: String, iterations: Int = ITERATIONS): String {
        val salt = Crypto.random(16)
        val key = deriveKey(password, salt, iterations)
        return JSONObject()
            .put("format", FORMAT)
            .put("v", 1)
            .put("kdf", "pbkdf2-sha256")
            .put("iter", iterations)
            .put("salt", Crypto.b64url(salt))
            .put("data", Crypto.encrypt(key, toJson(data).toString().toByteArray(), AAD))
            .toString(2)
    }

    /** Null if the file is not a backup, or the password is wrong. */
    fun import(text: String, password: String): BackupData? = try {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT || o.optInt("v") != 1) {
            null
        } else {
            val iterations = o.getInt("iter").coerceIn(1, 10_000_000)
            val key = deriveKey(password, Crypto.unb64url(o.getString("salt")), iterations)
            Crypto.decrypt(key, o.getString("data"), AAD)?.let { fromJson(JSONObject(String(it))) }
        }
    } catch (e: Exception) {
        null
    }

    /** True if [text] looks like a backup file (before asking for its password). */
    fun isBackup(text: String): Boolean = try {
        JSONObject(text).optString("format") == FORMAT
    } catch (e: Exception) {
        false
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun toJson(d: BackupData): JSONObject {
        val circles = JSONArray()
        d.circles.forEach {
            circles.put(
                JSONObject().put("name", it.name).put("secret", Crypto.hex(it.secret))
                    .put("share", it.share).put("visible", it.visible)
            )
        }
        return JSONObject()
            .put("seed", Crypto.hex(d.seed))
            .put("circles", circles)
            .put("name", d.name)
            .put("emoji", d.emoji)
            .apply { d.markerColor?.let { put("color", MarkerColor.format(it)) } }
            .put("moving", d.movingIntervalSeconds)
            .put("still", d.stillIntervalSeconds)
            .put("relays", JSONArray(d.relays))
            .put("battery", d.shareBattery)
    }

    private fun fromJson(o: JSONObject): BackupData? {
        val seed = Crypto.unhex(o.getString("seed"))
        if (seed.size != 32) return null
        val arr = o.getJSONArray("circles")
        val circles = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Circle(c.getString("name"), Crypto.unhex(c.getString("secret")), c.optBoolean("share", true), c.optBoolean("visible", true))
        }.filter { it.secret.size == 32 }
        val relays = o.optJSONArray("relays")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty()
        return BackupData(
            seed = seed,
            circles = circles,
            name = o.optString("name"),
            emoji = Emoji.sanitize(o.optString("emoji")),
            markerColor = MarkerColor.parse(o.optString("color")),
            movingIntervalSeconds = o.optInt("moving", 5 * 60),
            stillIntervalSeconds = o.optInt("still", 30 * 60),
            relays = relays,
            shareBattery = o.optBoolean("battery", false),
        )
    }

    // ---- password generator ----

    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SPECIAL = "!#%&*+-=?@_"
    private val rng = SecureRandom()

    /**
     * Random password with lowercase and uppercase letters, digits and special characters (at least one of each).
     * Look-alike characters (l, 1, I, O, 0, o) are left out, so it can be copied by hand.
     */
    fun generatePassword(length: Int = 18): String {
        val sets = listOf(LOWER, UPPER, DIGITS, SPECIAL)
        val all = sets.joinToString("")
        val chars = sets.map { it[rng.nextInt(it.length)] }.toMutableList()
        while (chars.size < length) chars += all[rng.nextInt(all.length)]
        for (i in chars.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            chars[i] = chars[j].also { chars[j] = chars[i] }
        }
        return chars.joinToString("")
    }
}
