package io.github.whereareyou.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps WebSocket connections to several Nostr relays at once.
 * Every event is sent to every relay and every subscription is opened on every
 * relay: losing some relays does not break the app (resilience by redundancy).
 */
class RelayPool(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onMessage: (relay: String, message: JSONArray) -> Unit,
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    private val _status = MutableStateFlow<Map<String, State>>(emptyMap())
    val status: StateFlow<Map<String, State>> = _status.asStateFlow()

    private val relays = ConcurrentHashMap<String, Relay>()
    private val subscriptions = ConcurrentHashMap<String, String>() // subId -> filter JSON

    /** Latest event per key, (re)sent to every relay on (re)connection. */
    private val outbox = ConcurrentHashMap<String, String>()

    @Synchronized
    fun setRelays(urls: Collection<String>) {
        val wanted = urls.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.toSet()
        (relays.keys - wanted).forEach { url -> relays.remove(url)?.close() }
        _status.update { it.filterKeys { k -> k in wanted } }
        (wanted - relays.keys).forEach { url -> Relay(url).also { relays[url] = it }.connect() }
    }

    @Synchronized
    fun stop() {
        relays.values.forEach { it.close() }
        relays.clear()
        _status.value = emptyMap()
    }

    fun publish(key: String, eventJson: String) {
        outbox[key] = eventJson
        relays.values.forEach { it.send("[\"EVENT\",$eventJson]") }
    }

    fun subscribe(subId: String, filterJson: String) {
        subscriptions[subId] = filterJson
        relays.values.forEach { it.send("[\"REQ\",\"$subId\",$filterJson]") }
    }

    fun unsubscribe(subId: String) {
        if (subscriptions.remove(subId) != null) {
            relays.values.forEach { it.send("[\"CLOSE\",\"$subId\"]") }
        }
    }

    private fun setState(url: String, state: State) {
        if (relays.containsKey(url)) _status.update { it + (url to state) }
    }

    private inner class Relay(val url: String) : WebSocketListener() {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var open = false
        @Volatile private var closed = false
        private var attempt = 0

        fun connect() {
            if (closed) return
            setState(url, State.CONNECTING)
            ws = client.newWebSocket(Request.Builder().url(url).build(), this)
        }

        fun send(text: String) {
            if (open) ws?.send(text)
        }

        fun close() {
            closed = true
            open = false
            ws?.close(1000, null)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            open = true
            attempt = 0
            setState(url, State.CONNECTED)
            subscriptions.forEach { (id, filter) -> webSocket.send("[\"REQ\",\"$id\",$filter]") }
            outbox.values.forEach { webSocket.send("[\"EVENT\",$it]") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            try {
                onMessage(url, JSONArray(text))
            } catch (_: Exception) {
                // ignore malformed messages
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

        private fun lost(webSocket: WebSocket) {
            if (webSocket !== ws) return
            open = false
            if (closed) return
            setState(url, State.DISCONNECTED)
            val delayMs = (1L shl minOf(attempt, 6)) * 1000L // 1s, 2s, 4s ... 64s
            attempt++
            scope.launch {
                delay(delayMs)
                if (!closed && ws === webSocket) connect()
            }
        }
    }
}
