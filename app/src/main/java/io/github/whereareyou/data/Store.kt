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

    var intervalSeconds: Int
        get() = prefs.getInt("interval", 60)
        set(value) = prefs.edit().putInt("interval", value).apply()

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
