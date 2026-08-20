package de.sevenapp.monitor

import de.sevenapp.monitor.core.DropDetector
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
            DropDetector.Snapshot(
                closedDrops = listOf(DropEvent(0, 500)),
                openDropStartedAtEpochMs = 1000,
                consecutiveFailures = 2,
                runStartedAtEpochMs = 1000,
            ),
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

    @Test
    fun failuresAccumulateAcrossProcessDeath() {
        // The MON-01 regression. Each worker run records ONE failed probe and
        // then dies; the persisted state used to zero the consecutive-failure
        // count unless a drop was already open, so the two-failure threshold
        // was never reached and an offline device recorded no outage at all.
        var stored = DropDetector.Snapshot(emptyList(), null, 0, null)

        fun oneWorkerRun(atEpochMs: Long, ok: Boolean): DropDetector.Transition {
            val detector = DropDetector()
            detector.restore(stored)
            val transition = detector.onSample(
                PingSample(atEpochMs, if (ok) 12.0 else null, NetworkType.NONE),
            )
            stored = detector.snapshot()
            return transition
        }

        assertIs<DropDetector.Transition.None>(oneWorkerRun(1_000, ok = false))
        assertEquals(1, stored.consecutiveFailures)
        assertEquals(1_000L, stored.runStartedAtEpochMs)

        val started = oneWorkerRun(2_000, ok = false)
        assertIs<DropDetector.Transition.DropStarted>(started)
        // Backdated to the FIRST failure, which happened in the previous
        // process — not to the one that crossed the threshold.
        assertEquals(1_000L, started.atEpochMs)

        val ended = oneWorkerRun(5_000, ok = true)
        assertIs<DropDetector.Transition.DropEnded>(ended)
        assertEquals(1_000L, ended.event.startedAtEpochMs)
        assertEquals(5_000L, ended.event.endedAtEpochMs)
        assertEquals(0, stored.consecutiveFailures)
        assertNull(stored.runStartedAtEpochMs)
    }
}
