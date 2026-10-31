package io.github.whereareyou.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides when to publish and when the GPS can be turned off, to save battery.
 *
 * - MOVING: GPS sampled every [sampleMs]; we publish every [movingMs] (user setting),
 *   only if we actually moved.
 * - STATIONARY: after [STILL_MS] without leaving a small radius, the service turns the
 *   GPS off and waits for the hardware motion sensor. The position is only confirmed
 *   every [stillMs] (user setting).
 *
 * Both settings are read on every call, so changes apply immediately.
 * Pure Kotlin (no Android types) so it can be unit tested.
 */
class MotionPolicy(
    private val movingSetting: () -> Long,
    private val stillSetting: () -> Long,
) {
    enum class Mode { MOVING, STATIONARY }

    var mode = Mode.MOVING
        private set

    /** Last published position. */
    var lastPublished: Fix? = null
        private set
    private var lastPublishAt = Long.MIN_VALUE / 2

    /** Where we have been staying since [stillSince]. */
    private var anchor: Fix? = null
    private var stillSince = 0L

    /** We just left a place: the first clear move is published at once, whatever the interval. */
    private var departing = false

    val movingMs get() = movingSetting()

    /** Never confirm more often while still than while moving. */
    val stillMs get() = max(stillSetting(), movingSetting())

    /** GPS sampling while moving: often enough to notice that we stopped, not more. */
    val sampleMs get() = (movingMs / 2).coerceIn(MIN_SAMPLE_MS, MAX_SAMPLE_MS)

    /** Longest silence the others should expect from us (announced with each position). */
    val maxGapMs get() = stillMs

    /** A new fix arrived. Returns the position to publish, if any. Check [mode] afterwards. */
    fun onFix(fix: Fix, now: Long): Fix? {
        val a = anchor
        if (mode == Mode.STATIONARY) {
            // Cheap sources are imprecise: only a clear jump means we left.
            if (a != null && distance(a, fix) > fix.accuracy + LEAVE_RADIUS_M) {
                startMoving(fix, now)
                departing = false
                return publish(fix, now)
            }
            return heartbeat(now)
        }

        if (a == null || distance(a, fix) > max(STILL_RADIUS_M, fix.accuracy)) {
            anchor = fix
            stillSince = now
        } else if (fix.accuracy < a.accuracy) {
            anchor = fix // keep the most accurate fix of the place we stay at
        }

        val last = lastPublished
        val sinceLast = now - lastPublishAt
        val moved = last?.let { distance(it, fix) } ?: Float.MAX_VALUE
        val result = when {
            last == null -> publish(fix, now)
            departing && moved > DEPARTURE_M -> {
                departing = false
                publish(fix, now)
            }
            sinceLast >= movingMs && (
                moved > max(MIN_MOVE_M, fix.accuracy) ||
                    fix.accuracy < last.accuracy / 2 ||
                    sinceLast >= stillMs
                ) -> publish(fix, now)
            else -> null
        }

        if (now - stillSince >= STILL_MS) {
            mode = Mode.STATIONARY
            departing = false
        }
        return result
    }

    /** The hardware motion sensor fired: the GPS must come back. */
    fun onMotion(now: Long) {
        if (mode == Mode.STATIONARY) {
            startMoving(anchor, now)
            departing = true
        }
    }

    /** Called regularly: confirms our (unchanged) position while stationary. */
    fun onTick(now: Long): Fix? = if (mode == Mode.STATIONARY) heartbeat(now) else null

    private fun heartbeat(now: Long): Fix? {
        val place = anchor ?: lastPublished ?: return null
        if (now - lastPublishAt < stillMs) return null
        // Still at the same place: same position, confirmed now.
        return publish(place.copy(time = now), now)
    }

    private fun startMoving(from: Fix?, now: Long) {
        mode = Mode.MOVING
        anchor = from
        stillSince = now
    }

    private fun publish(fix: Fix, now: Long): Fix {
        lastPublished = fix
        lastPublishAt = now
        return fix
    }

    companion object {
        const val STILL_MS = 3 * 60_000L
        const val STILL_RADIUS_M = 50f
        const val LEAVE_RADIUS_M = 200f
        const val MIN_MOVE_M = 25f
        const val DEPARTURE_M = 100f
        const val MIN_SAMPLE_MS = 15_000L
        const val MAX_SAMPLE_MS = 2 * 60_000L

        /** Great-circle distance in meters. */
        fun distance(a: Fix, b: Fix): Float {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return (2 * r * asin(sqrt(h))).toFloat()
        }
    }
}
