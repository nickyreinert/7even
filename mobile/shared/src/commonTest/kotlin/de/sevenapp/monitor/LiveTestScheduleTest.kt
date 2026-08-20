package de.sevenapp.monitor

import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.LiveTestSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTestScheduleTest {

    @Test
    fun notDoneBeforeThePhaseDurationElapses() {
        assertFalse(LiveTestSchedule.isPhaseDone(startedAtEpochMs = 0, nowEpochMs = 59_999, phaseDurationMs = 60_000))
    }

    @Test
    fun doneExactlyAtThePhaseDuration() {
        assertTrue(LiveTestSchedule.isPhaseDone(startedAtEpochMs = 0, nowEpochMs = 60_000, phaseDurationMs = 60_000))
    }

    @Test
    fun doneWellPastThePhaseDuration() {
        assertTrue(LiveTestSchedule.isPhaseDone(startedAtEpochMs = 1_000, nowEpochMs = 500_000, phaseDurationMs = 60_000))
    }

    @Test
    fun unlimitedNeverCompletesOnItsOwn() {
        assertFalse(
            LiveTestSchedule.isPhaseDone(
                startedAtEpochMs = 0,
                nowEpochMs = Long.MAX_VALUE / 2,
                phaseDurationMs = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun defaultPhaseDurationMatchesTheOneMinuteFloor() {
        assertEquals(60_000L, LiveTestConfig.DEFAULT_PHASE_DURATION_MS)
    }

    @Test
    fun aTenSecondChoiceBudgetsEveryPhaseNotTheWholeTest() {
        // The regression this encodes: a 10s choice used to be a whole-test
        // deadline consumed entirely by a 15s download episode, so the upload
        // phase never ran. Each phase now gets the full 10s.
        val config = LiveTestConfig(phaseDurationMs = 10_000, sweepEnabled = false)
        assertEquals(30_000L, config.totalDurationMs())
        assertFalse(config.isUnlimited)
    }

    @Test
    fun unlimitedHasNoTotalDuration() {
        assertEquals(Long.MAX_VALUE, LiveTestConfig(phaseDurationMs = Long.MAX_VALUE).totalDurationMs())
    }

    @Test
    fun theSweepEstimateIsAddedOnlyWhenTheSweepRuns() {
        assertEquals(
            30_000L + LiveTestConfig.SWEEP_ESTIMATE_MS,
            LiveTestConfig(phaseDurationMs = 10_000, sweepEnabled = true).totalDurationMs(),
        )
    }
}
