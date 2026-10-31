package io.github.whereareyou

import io.github.whereareyou.data.Fix
import io.github.whereareyou.data.MotionPolicy
import io.github.whereareyou.data.MotionPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPolicyTest {
    private val minute = 60_000L

    /** A fix [north] meters north of a fixed point. */
    private fun fix(north: Double, t: Long, acc: Float = 10f) = Fix(48.0 + north / 111_195.0, 2.0, acc, t)

    private fun policy(moving: Long = minute, still: Long = 30 * minute) = MotionPolicy({ moving }, { still })

    /** Stays at the same place until the policy switches to STATIONARY; returns the time. */
    private fun settle(p: MotionPolicy): Long {
        var t = 0L
        while (p.mode == Mode.MOVING) {
            p.onFix(fix(0.0, t), t)
            t += 30_000
        }
        return t
    }

    @Test
    fun distanceIsCorrect() {
        assertEquals(1000f, MotionPolicy.distance(fix(0.0, 0), fix(1000.0, 0)), 1f)
    }

    @Test
    fun firstFixIsPublished() {
        assertNotNull(policy().onFix(fix(0.0, 0), 0))
    }

    @Test
    fun notMovingDoesNotPublishEveryInterval() {
        val p = policy()
        p.onFix(fix(0.0, 0), 0)
        assertNull(p.onFix(fix(3.0, minute), minute)) // GPS jitter only
        assertNull(p.onFix(fix(-2.0, 2 * minute), 2 * minute))
    }

    @Test
    fun movingPublishesAtTheInterval() {
        val p = policy()
        p.onFix(fix(0.0, 0), 0)
        assertNull(p.onFix(fix(40.0, 30_000), 30_000)) // moved, but interval not reached
        assertNotNull(p.onFix(fix(80.0, minute), minute))
        assertEquals(Mode.MOVING, p.mode)
    }

    @Test
    fun longMovingIntervalIsRespectedEvenForBigMoves() {
        val p = policy(moving = 60 * minute)
        p.onFix(fix(0.0, 0), 0)
        assertNull(p.onFix(fix(2000.0, 2 * minute), 2 * minute)) // driving, but the user asked for 1 h
        assertNotNull(p.onFix(fix(50_000.0, 60 * minute), 60 * minute))
    }

    @Test
    fun gpsSamplingStaysReasonable() {
        assertEquals(15_000L, policy(moving = 30_000).sampleMs)
        assertEquals(2 * minute, policy(moving = 180 * minute).sampleMs) // still notices when we stop
    }

    @Test
    fun becomesStationaryThenConfirmsAtTheStillInterval() {
        val p = policy(still = 30 * minute)
        val t = settle(p)
        assertEquals(Mode.STATIONARY, p.mode)
        assertNull(p.onTick(t + 20 * minute))
        val beat = p.onTick(t + 31 * minute)
        assertNotNull(beat)
        assertEquals(t + 31 * minute, beat!!.time) // confirmed "now"
        assertNull(p.onTick(t + 45 * minute))
    }

    @Test
    fun stillIntervalIsNeverShorterThanMovingInterval() {
        val p = policy(moving = 60 * minute, still = 5 * minute)
        assertEquals(60 * minute, p.stillMs)
        assertEquals(60 * minute, p.maxGapMs)
    }

    @Test
    fun stationaryIgnoresImpreciseNetworkJitterButDetectsRealDeparture() {
        val p = policy()
        val t = settle(p)
        p.onFix(fix(300.0, t, acc = 400f), t) // Wi-Fi/cell fix, within its own accuracy
        assertEquals(Mode.STATIONARY, p.mode)
        assertNotNull(p.onFix(fix(1500.0, t + minute, acc = 100f), t + minute))
        assertEquals(Mode.MOVING, p.mode)
    }

    @Test
    fun departureIsPublishedAtOnceThenTheIntervalApplies() {
        val p = policy(moving = 30 * minute)
        var t = settle(p)
        p.onMotion(t)
        assertEquals(Mode.MOVING, p.mode)
        assertNull(p.onTick(t + 60 * minute)) // no fake confirmation while moving
        t += 30_000
        assertNull(p.onFix(fix(40.0, t), t)) // not clearly gone yet
        t += 30_000
        assertNotNull(p.onFix(fix(300.0, t), t)) // left: the others know right away
        t += 2 * minute
        assertNull(p.onFix(fix(1000.0, t), t)) // then every 30 min as asked
    }
}
