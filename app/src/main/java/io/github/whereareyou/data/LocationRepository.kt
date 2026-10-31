package io.github.whereareyou.data

import android.util.Log
import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.nostr.KeyPair
import io.github.whereareyou.nostr.Nostr
import io.github.whereareyou.nostr.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Central place of the app: circles, received positions, publishing our own position.
 *
 * Protocol (see docs/ARCHITECTURE.md):
 *  - each circle has a secret -> public tag "d" + AES key
 *  - each member publishes a Nostr event kind 30078, tag d=<circle tag>,
 *    signed with a key derived for this circle only (circles are not linkable)
 *  - content = AES-GCM(JSON {id, n, lat, lon, acc, ts})
 *  - relays keep only the latest event per (author, d): it is the "last known position"
 */
class LocationRepository(private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().pingInterval(2, TimeUnit.MINUTES) /* fewer radio wake-ups */.build()
    private val pool = RelayPool(http, scope, ::onRelayMessage)
    val relayStatus: StateFlow<Map<String, RelayPool.State>> = pool.status

    private val _circles = MutableStateFlow(store.loadCircles())
    val circles: StateFlow<List<Circle>> = _circles.asStateFlow()

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _ownFix = MutableStateFlow<Fix?>(null)
    val ownFix: StateFlow<Fix?> = _ownFix.asStateFlow()

    val displayName: String get() = store.displayName
    val emoji: String get() = store.emoji
    val markerColor: Int? get() = store.markerColor

    /** True while the LocationService is running. */
    val sharingActive = MutableStateFlow(false)

    private val seed = store.identitySeed()

    /**
     * Personal identity key: every message is signed with it (see [Envelope]).
     * Its public key is our member id, only visible (encrypted) to circle members,
     * used to merge one person seen in several circles.
     */
    private val identity = KeyPair.derive(seed, "member-identity")
    private val memberId = identity.publicHex

    /** Our member id: also gives the color the others see for us. */
    val myId: String get() = memberId
    private val authorKeys = ConcurrentHashMap<String, KeyPair>()

    private val lock = Any()
    /** What a member told one circle. */
    private class Report(val name: String, val emoji: String, val color: Int?, val fix: Fix)

    private val entries = HashMap<String, HashMap<String, Report>>() // memberId -> tag -> latest report
    private val lastSeen = HashMap<String, Long>() // "memberId|tag" -> ts of the latest accepted message (anti-replay)
    private val seenIds = LinkedHashSet<String>()
    private var users = 0

    private fun authorKey(c: Circle) = authorKeys.getOrPut(c.tag) { KeyPair.derive(seed, "author:${c.tag}") }

    // ---- lifecycle: connected while the UI is visible or the sharing service runs ----

    @Synchronized
    fun acquire() {
        if (users++ == 0) {
            pool.setRelays(store.relays)
            resubscribe()
        }
    }

    @Synchronized
    fun release() {
        if (--users <= 0) {
            users = 0
            pool.stop()
        }
    }

    fun updateRelays(relays: List<String>) {
        store.relays = relays
        synchronized(this) { if (users > 0) pool.setRelays(relays) }
    }

    // ---- circles ----

    fun createCircle(name: String) = addCircle(Circle(name.trim(), Crypto.random(32), share = true))

    /** Returns false if this circle is already known. */
    fun joinCircle(name: String, secret: ByteArray, share: Boolean): Boolean {
        val c = Circle(name, secret, share)
        if (_circles.value.any { it.tag == c.tag }) return false
        addCircle(c)
        return true
    }

    private fun addCircle(c: Circle) {
        saveCircles(_circles.value + c)
        if (c.share && sharingActive.value) _ownFix.value?.let { publishTo(c, it) }
    }

    fun setShare(circle: Circle, share: Boolean) {
        val updated = circle.copy(share = share)
        saveCircles(_circles.value.map { if (it.tag == circle.tag) updated else it })
        if (sharingActive.value) {
            if (share) _ownFix.value?.let { publishTo(updated, it) } else publishOff(updated)
        }
    }

    fun deleteCircle(circle: Circle) {
        if (circle.share && sharingActive.value) publishOff(circle)
        saveCircles(_circles.value.filter { it.tag != circle.tag })
        synchronized(lock) { entries.values.forEach { it.remove(circle.tag) } }
        rebuildMembers()
    }

    private fun saveCircles(list: List<Circle>) {
        _circles.value = list
        store.saveCircles(list)
        resubscribe()
    }

    private fun resubscribe() {
        val tags = _circles.value.map { it.tag }
        if (tags.isEmpty()) {
            pool.unsubscribe(SUB_ID)
            return
        }
        val filter = JSONObject()
            .put("kinds", JSONArray().put(Nostr.KIND_APP_DATA))
            .put("#d", JSONArray(tags))
            .put("since", System.currentTimeMillis() / 1000 - EXPIRATION_SECONDS)
        pool.subscribe(SUB_ID, filter.toString())
    }

    // ---- publishing ----

    fun publish(fix: Fix) {
        _ownFix.value = fix
        _circles.value.filter { it.share }.forEach { publishTo(it, fix) }
    }

    /** Name sent in our last message (to avoid re-sending an unchanged name). */
    @Volatile var lastPublishedName: String = ""
        private set

    /** Sends our last position again, e.g. so the circles see a new name or marker right away. */
    fun republish() {
        if (sharingActive.value) _ownFix.value?.let { publish(it) }
    }

    /** Tells the circles we stopped sharing, so our marker disappears for them. */
    fun publishStop() = _circles.value.filter { it.share }.forEach { publishOff(it) }

    private fun publishTo(c: Circle, fix: Fix) {
        val payload = JSONObject()
            .put("n", store.displayName.also { lastPublishedName = it })
            .apply { store.emoji.takeIf { it.isNotEmpty() }?.let { put("e", it) } }
            .apply { store.markerColor?.let { put("c", MarkerColor.format(it)) } }
            .put("lat", Math.round(fix.lat * 1e6) / 1e6)
            .put("lon", Math.round(fix.lon * 1e6) / 1e6)
            .put("acc", Math.round(fix.accuracy))
            .put("ts", fix.time)
            .put("gap", fix.maxGapMs)
        send(c, payload)
    }

    private fun publishOff(c: Circle) = send(c, JSONObject().put("off", true).put("ts", System.currentTimeMillis()))

    private fun send(c: Circle, payload: JSONObject) {
        scope.launch {
            val signed = Envelope.seal(identity, c.tag, payload)
            val content = Crypto.encrypt(c.key, signed.toByteArray(), c.tag.toByteArray())
            val now = System.currentTimeMillis() / 1000
            val tags = listOf(listOf("d", c.tag), listOf("expiration", (now + EXPIRATION_SECONDS).toString()))
            val event = Nostr.signEvent(authorKey(c), Nostr.KIND_APP_DATA, tags, content, now)
            pool.publish(c.tag, event)
        }
    }

    // ---- receiving ----

    private fun onRelayMessage(relay: String, msg: JSONArray) {
        when (msg.optString(0)) {
            "EVENT" -> msg.optJSONObject(2)?.let { handleEvent(it) }
            "OK" -> if (!msg.optBoolean(2)) Log.w(TAG, "$relay refused event: ${msg.optString(3)}")
            "NOTICE", "CLOSED" -> Log.i(TAG, "$relay: $msg")
        }
    }

    private fun handleEvent(ev: JSONObject) {
        if (ev.optInt("kind") != Nostr.KIND_APP_DATA) return
        val id = ev.optString("id")
        synchronized(lock) {
            if (!seenIds.add(id)) return
            if (seenIds.size > 2000) seenIds.remove(seenIds.first())
        }
        val tags = ev.optJSONArray("tags") ?: return
        val tag = (0 until tags.length()).map { tags.optJSONArray(it) }
            .firstOrNull { it != null && it.optString(0) == "d" }?.optString(1) ?: return
        val circle = _circles.value.firstOrNull { it.tag == tag } ?: return
        if (ev.optString("pubkey") == authorKey(circle).publicHex) return // our own event
        val plain = Crypto.decrypt(circle.key, ev.optString("content"), tag.toByteArray()) ?: return
        // Rejects unsigned messages and messages signed by someone else than the member they claim to be.
        val p = Envelope.open(tag, String(plain)) ?: run {
            Log.w(TAG, "dropped message with invalid signature")
            return
        }
        val mid = p.getString("k")
        if (mid == memberId) return
        val ts = p.getLong("ts")
        if (ts < System.currentTimeMillis() - EXPIRATION_SECONDS * 1000) return // too old: replay
        synchronized(lock) {
            val key = "$mid|$tag"
            if (ts < (lastSeen[key] ?: Long.MIN_VALUE)) return // older than what we already have: replay
            lastSeen[key] = ts
            val perCircle = entries.getOrPut(mid) { HashMap() }
            if (p.optBoolean("off")) {
                perCircle.remove(tag)
            } else {
                perCircle[tag] = Report(
                    name = p.optString("n", "?"),
                    emoji = Emoji.sanitize(p.optString("e", "")),
                    color = MarkerColor.parse(p.optString("c", "")),
                    fix = Fix(p.getDouble("lat"), p.getDouble("lon"), p.optDouble("acc", 0.0).toFloat(), ts, p.optLong("gap", 0)),
                )
            }
        }
        rebuildMembers()
    }

    private fun rebuildMembers() {
        val names = _circles.value.associate { it.tag to it.name }
        val list = synchronized(lock) {
            entries.mapNotNull { (mid, perCircle) ->
                val visible = perCircle.filterKeys { it in names }
                val latest = visible.values.maxByOrNull { it.fix.time } ?: return@mapNotNull null
                Member(mid, latest.name, latest.fix, visible.keys.mapNotNull { names[it] }.sorted(), visible.keys.toSet(), latest.emoji, latest.color)
            }
        }
        _members.value = list.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val TAG = "WhereAreYou"
        private const val SUB_ID = "way"
        /** Positions older than this are dropped by relays (NIP-40) and ignored by the app. */
        const val EXPIRATION_SECONDS = 24 * 3600L
    }
}
