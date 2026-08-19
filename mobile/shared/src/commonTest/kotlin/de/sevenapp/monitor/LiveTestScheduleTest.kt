package de.sevenapp.monitor

import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.LiveTestSchedule
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTestScheduleTest {

    @Test
    fun notDoneBeforeMinimumElapses() {
        assertFalse(LiveTestSchedule.isSessionDone(startedAtEpochMs = 0, nowEpochMs = 59_999, minDurationMs = 60_000))
    }

    @Test
    fun doneExactlyAtMinimum() {
        assertTrue(LiveTestSchedule.isSessionDone(startedAtEpochMs = 0, nowEpochMs = 60_000, minDurationMs = 60_000))
    }

    @Test
    fun doneWellPastMinimum() {
        assertTrue(LiveTestSchedule.isSessionDone(startedAtEpochMs = 1_000, nowEpochMs = 500_000, minDurationMs = 60_000))
    }

    @Test
    fun defaultMinDurationMatchesTheOneMinuteFloor() {
        assertTrue(LiveTestConfig.MIN_DURATION_MS == 60_000L)
    }
}
