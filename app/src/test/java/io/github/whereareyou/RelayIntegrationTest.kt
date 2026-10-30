package io.github.whereareyou

import io.github.whereareyou.crypto.Crypto
import io.github.whereareyou.data.Circle
import io.github.whereareyou.nostr.KeyPair
import io.github.whereareyou.nostr.Nostr
import io.github.whereareyou.nostr.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/**
 * End-to-end check against real public relays: device A publishes an encrypted
 * position, device B (another pool) receives and decrypts it.
 * Run with: WAY_NETWORK_TEST=1 ./gradlew testDebugUnitTest
 */
class RelayIntegrationTest {
    @Test
    fun publishAndReceiveThroughPublicRelays() {
        assumeTrue(System.getenv("WAY_NETWORK_TEST") != null)
        val relays = (System.getenv("WAY_RELAYS") ?: "wss://relay.damus.io,wss://nos.lol,wss://relay.primal.net,wss://nostr.mom,wss://relay.nostr.net")
            .split(",")
        val circle = Circle("test", Crypto.random(32), true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient()

        val accepted = ConcurrentHashMap<String, String>()
        val received = ConcurrentHashMap<String, String>()
        val gotEvent = CountDownLatch(1)

        val publisher = RelayPool(http, scope) { relay, msg ->
            if (msg.optString(0) == "OK") accepted[relay] = "${msg.optBoolean(2)} ${msg.optString(3)}"
        }
        val subscriber = RelayPool(http, scope) { relay, msg ->
            if (msg.optString(0) == "EVENT") {
                val ev = msg.getJSONObject(2)
                val plain = Crypto.decrypt(circle.key, ev.getString("content"), circle.tag.toByteArray())
                if (plain != null) {
                    received[relay] = String(plain)
                    gotEvent.countDown()
                }
            }
        }

        val key = KeyPair.derive(Crypto.random(32), "author:${circle.tag}")
        val payload = JSONObject().put("id", "a").put("n", "Alice").put("lat", 48.8566).put("lon", 2.3522).put("acc", 10).put("ts", System.currentTimeMillis())
        val content = Crypto.encrypt(circle.key, payload.toString().toByteArray(), circle.tag.toByteArray())
        val now = System.currentTimeMillis() / 1000
        val event = Nostr.signEvent(key, Nostr.KIND_APP_DATA, listOf(listOf("d", circle.tag), listOf("expiration", "${now + 3600}")), content, now)

        publisher.setRelays(relays)
        publisher.publish(circle.tag, event)
        Thread.sleep(6000) // let the relays store it

        subscriber.setRelays(relays)
        val filter = JSONObject().put("kinds", JSONArray().put(Nostr.KIND_APP_DATA)).put("#d", JSONArray().put(circle.tag))
        subscriber.subscribe("t", filter.toString())
        gotEvent.await(15, TimeUnit.SECONDS)
        Thread.sleep(3000) // collect answers from the other relays too

        println("Publish results: $accepted")
        println("Received from:   ${received.keys}")
        publisher.stop()
        subscriber.stop()
        assertTrue("no relay delivered the event", received.isNotEmpty())
    }
}
