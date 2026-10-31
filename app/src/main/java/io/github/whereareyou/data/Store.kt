package io.github.whereareyou.data

import android.content.Context
import android.os.Build
import io.github.whereareyou.crypto.Crypto
import org.json.JSONArray
import org.json.JSONObject

/** Local persistence (app-private SharedPreferences, excluded from backups). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("whereareyou", Context.MODE_PRIVATE)

    /** Random secret from which all of this device's keys are derived. Never leaves the device. */
    fun identitySeed(): ByteArray {
        prefs.getString("seed", null)?.let { return Crypto.unhex(it) }
        return Crypto.random(32).also { prefs.edit().putString("seed", Crypto.hex(it)).apply() }
    }

    var displayName: String
        get() = prefs.getString("name", null) ?: Build.MODEL
        set(value) = prefs.edit().putString("name", value.trim()).apply()

    /** Emoji shown as our marker ("" = initial of the name). */
    var emoji: String
        get() = prefs.getString("emoji", "") ?: ""
        set(value) = prefs.edit().putString("emoji", Emoji.sanitize(value)).apply()

    /** Background color of our marker (null = automatic). */
    var markerColor: Int?
        get() = MarkerColor.parse(prefs.getString("marker_color", null))
        set(value) = prefs.edit().putString("marker_color", value?.let { MarkerColor.format(it) }).apply()

    /** Publication interval while moving. */
    var movingIntervalSeconds: Int
        get() = prefs.getInt("moving_interval", 5 * 60)
        set(value) = prefs.edit().putInt("moving_interval", value).apply()

    /** Confirmation interval while the device does not move. */
    var stillIntervalSeconds: Int
        get() = prefs.getInt("still_interval", 30 * 60)
        set(value) = prefs.edit().putInt("still_interval", value).apply()

    /** The user wants to share (switched on and not switched off since). Survives the app being killed. */
    var sharingWanted: Boolean
        get() = prefs.getBoolean("sharing_wanted", false)
        set(value) = prefs.edit().putBoolean("sharing_wanted", value).apply()

    /** "Background location" setting: restart sharing when Android kills the app. */
    var backgroundLocation: Boolean
        get() = prefs.getBoolean("background_location", false)
        set(value) = prefs.edit().putBoolean("background_location", value).apply()

    var relays: List<String>
        get() = prefs.getString("relays", null)?.lines()?.filter { it.isNotBlank() } ?: DEFAULT_RELAYS
        set(value) = prefs.edit().putString("relays", value.joinToString("\n")).apply()

    fun loadCircles(): List<Circle> {
        val arr = JSONArray(prefs.getString("circles", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Circle(o.getString("name"), Crypto.unhex(o.getString("secret")), o.optBoolean("share", true))
        }
    }

    fun saveCircles(circles: List<Circle>) {
        val arr = JSONArray()
        circles.forEach {
            arr.put(JSONObject().put("name", it.name).put("secret", Crypto.hex(it.secret)).put("share", it.share))
        }
        prefs.edit().putString("circles", arr.toString()).apply()
    }

    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.nostr.net",
        )
    }
}
