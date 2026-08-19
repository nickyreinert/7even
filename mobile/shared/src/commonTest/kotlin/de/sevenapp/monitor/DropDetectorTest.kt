package de.sevenapp.monitor

import de.sevenapp.monitor.core.DropDetector
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DropDetectorTest {

    private fun ok(t: Long) = PingSample(t, 20.0, NetworkType.WIFI)
    private fun fail(t: Long) = PingSample(t, null, NetworkType.WIFI)

    @Test
    fun singleFailureIsNotADrop() {
        val d = DropDetector()
        d.onSample(fail(1000))
        assertFalse(d.isDropOpen)
        assertTrue(d.allDrops().isEmpty())
    }

    @Test
    fun twoConsecutiveFailuresOpenADrop() {
        val d = DropDetector()
        d.onSample(fail(1000))
        val t = d.onSample(fail(2000))
        assertIs<DropDetector.Transition.DropStarted>(t)
        assertTrue(d.isDropOpen)
    }

    @Test
    fun dropIsBackdatedToTheFirstFailureNotTheThreshold() {
        // The connection was already down at t=1000; we only became sure at
        // t=2000. Dating the drop from 2000 would systematically under-report
        // downtime, and on mobile the gap between probes can be minutes.
        val d = DropDetector()
        d.onSample(fail(1000))
        d.onSample(fail(2000))
        assertEquals(1000L, d.allDrops().single().startedAtEpochMs)
    }

    @Test
    fun successClosesAnOpenDrop() {
        val d = DropDetector()
        d.onSample(fail(1000))
        d.onSample(fail(2000))
        val t = d.onSample(ok(3000))
        assertIs<DropDetector.Transition.DropEnded>(t)
        assertFalse(d.isDropOpen)

        val drop = d.allDrops().single()
        assertEquals(1000L, drop.startedAtEpochMs)
        assertEquals(3000L, drop.endedAtEpochMs)
        assertEquals(2000L, drop.durationMs(9999))
    }

    @Test
    fun successBetweenFailuresResetsTheRun() {
        // fail, ok, fail => never two in a row => no drop.
        val d = DropDetector()
        d.onSample(fail(1000))
        d.onSample(ok(2000))
        d.onSample(fail(3000))
        assertFalse(d.isDropOpen)
        assertTrue(d.allDrops().isEmpty())
    }

    @Test
    fun longFailureRunProducesExactlyOneDropNotOnePerSample() {
        val d = DropDetector()
        (1..10).forEach { d.onSample(fail(it * 1000L)) }
        assertEquals(1, d.allDrops().size)
        assertEquals(1000L, d.allDrops().single().startedAtEpochMs)
    }

    @Test
    fun openDropIsIncludedInAllDropsWithNullEnd() {
        val d = DropDetector()
        d.onSample(fail(1000))
        d.onSample(fail(2000))
        val drop = d.allDrops().single()
        assertTrue(drop.ongoing)
        assertEquals(4000L, drop.durationMs(5000))
    }

    @Test
    fun separateOutagesProduceSeparateDrops() {
        val d = DropDetector()
        d.onSample(fail(1000)); d.onSample(fail(2000)); d.onSample(ok(3000))
        d.onSample(fail(4000)); d.onSample(fail(5000)); d.onSample(ok(6000))
        assertEquals(2, d.allDrops().size)
    }

    @Test
    fun restoreRehydratesAnOpenDropAcrossProcessDeath() {
        // The background worker is killed between cycles constantly; if state
        // did not survive, every outage longer than one cycle would be recorded
        // as a series of unrelated short drops.
        val d = DropDetector()
        d.restore(
            closedDrops = listOf(DropEvent(0, 500)),
            openDropStartedAtEpochMs = 1000,
            consecutiveFailures = 2,
        )
        assertTrue(d.isDropOpen)

        val t = d.onSample(ok(3000))
        assertIs<DropDetector.Transition.DropEnded>(t)
        assertEquals(2, d.allDrops().size)
        assertEquals(1000L, d.allDrops()[1].startedAtEpochMs)
    }

    @Test
    fun thresholdOfOneOpensOnFirstFailure() {
        val d = DropDetector(thresholdConsecutive = 1)
        assertIs<DropDetector.Transition.DropStarted>(d.onSample(fail(1000)))
    }
}
