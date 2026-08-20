package de.sevenapp.monitor

import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.StabilityScore
import de.sevenapp.monitor.core.Stats
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {

    private fun assertClose(expected: Double, actual: Double?, tol: Double = 1e-9) {
        assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected, got $actual")
    }

    @Test
    fun medianOfOddCountIsMiddleValue() {
        assertClose(3.0, Stats.median(listOf(5.0, 1.0, 3.0)))
    }

    @Test
    fun medianOfEvenCountAveragesTheTwoMiddle() {
        // Matches the web app: (2+3)/2, not "pick the lower".
        assertClose(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun medianOfEmptyIsNull() {
        assertNull(Stats.median(emptyList()))
    }

    @Test
    fun medianDoesNotMutateInput() {
        val input = listOf(3.0, 1.0, 2.0)
        Stats.median(input)
        assertEquals(listOf(3.0, 1.0, 2.0), input)
    }

    @Test
    fun stdDevIsPopulationNotSample() {
        // [2,4,4,4,5,5,7,9]: population sd = 2.0, sample sd would be ~2.138.
        // Pinning this guards the web-app-compatible definition against someone
        // "fixing" it to n-1 later and silently changing every jitter number.
        val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
        assertClose(2.0, Stats.stdDev(values), 1e-9)
    }

    @Test
    fun stdDevOfFewerThanTwoIsZero() {
        assertEquals(0.0, Stats.stdDev(emptyList()))
        assertEquals(0.0, Stats.stdDev(listOf(42.0)))
    }

    @Test
    fun percentileP95PicksTheTail() {
        // Nearest-rank over (n-1): index = round(0.95 * 99) = 94 => value 95.
        // This indexing is chosen because it anchors the ends exactly — p=0 is
        // the minimum and p=1 the maximum — which an n-based rank does not.
        val values = (1..100).map { it.toDouble() }
        assertClose(95.0, Stats.percentile(values, 0.95))
        assertClose(1.0, Stats.percentile(values, 0.0))
        assertClose(100.0, Stats.percentile(values, 1.0))
    }

    @Test
    fun percentileClampsOutOfRangeInputs() {
        val values = (1..10).map { it.toDouble() }
        assertClose(1.0, Stats.percentile(values, -5.0))
        assertClose(10.0, Stats.percentile(values, 5.0))
    }

    @Test
    fun percentileOfSingleValueIsThatValue() {
        assertClose(7.0, Stats.percentile(listOf(7.0), 0.95))
    }
}

class StabilityScoreTest {

    private val hour = 3_600_000L

    @Test
    fun perfectConditionsScoreOneHundred() {
        val r = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = emptyList(),
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertEquals(100.0, r.composite)
        assertEquals(100.0, r.uptimePct)
    }

    @Test
    fun nullJitterIsTreatedAsPerfectNotZeroScore() {
        // No samples yet must not be punished as if jitter were terrible.
        val r = StabilityScore.compute(0, hour, emptyList(), null, 0.0)
        assertEquals(100.0, r.jitterScore)
    }

    @Test
    fun weightsAreFiftyThirtyTwenty() {
        // uptime 100, jitter 50ms -> 75, loss 10% -> 80
        // 100*0.5 + 75*0.3 + 80*0.2 = 50 + 22.5 + 16 = 88.5
        val r = StabilityScore.compute(0, hour, emptyList(), 50.0, 10.0)
        assertEquals(88.5, r.composite)
    }

    @Test
    fun openDropCountsAgainstUptime() {
        // A drop that started halfway through and has not ended must cost ~50%
        // uptime. Reporting a currently-down connection as 100% up would be the
        // single most misleading thing this app could do.
        val r = StabilityScore.compute(0, hour, listOf(DropEvent(hour / 2, null)), 0.0, 0.0)
        assertEquals(50.0, r.uptimePct)
    }

    @Test
    fun dropStraddlingWindowStartIsClippedToTheWindow() {
        // Drop ran from -30min to +30min. Only the 30 minutes inside the window
        // may be charged to it, otherwise this week is billed for last week's
        // downtime and uptime can even go negative.
        val r = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = listOf(DropEvent(-hour / 2, hour / 2)),
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertEquals(50.0, r.uptimePct)
    }

    @Test
    fun uptimeNeverGoesNegativeEvenWithAbsurdDrops() {
        val r = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = listOf(DropEvent(-hour * 10, hour * 10)),
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertTrue(r.uptimePct >= 0.0, "uptime went negative: ${r.uptimePct}")
    }

    @Test
    fun extremeLossAndJitterFloorAtZeroNotNegative() {
        val r = StabilityScore.compute(0, hour, emptyList(), 10_000.0, 100.0)
        assertEquals(0.0, r.jitterScore)
        assertEquals(0.0, r.lossScore)
        assertEquals(50.0, r.composite) // uptime alone
    }
}

class NumericHygieneTest {

    @Test
    fun aggregatesIgnoreImpossibleValues() {
        // NaN propagates silently through every subsequent average and chart
        // coordinate, so one broken measurement used to void a whole window.
        val values = listOf(10.0, Double.NaN, 20.0, Double.POSITIVE_INFINITY, -5.0, 30.0)
        assertEquals(listOf(10.0, 20.0, 30.0), Stats.usable(values))
        assertEquals(20.0, Stats.median(values))
        assertEquals(30.0, Stats.percentile(values, 1.0))
        assertTrue(!Stats.stdDev(values).isNaN())
    }

    @Test
    fun anAllGarbageInputHasNoStatistics() {
        val values = listOf(Double.NaN, Double.NEGATIVE_INFINITY, -1.0)
        assertNull(Stats.median(values))
        assertNull(Stats.percentile(values, 0.5))
        assertEquals(0.0, Stats.stdDev(values))
    }
}

class DropAggregationTest {

    private val hour = 3_600_000L

    @Test
    fun overlappingDropsAreMergedNotAdded() {
        // The same outage recorded twice across a process restart. Adding the
        // durations turned a 10-minute outage into 20 minutes of downtime.
        val drops = listOf(
            DropEvent(0, 10 * 60_000),
            DropEvent(5 * 60_000, 10 * 60_000),
        )
        val result = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = drops,
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertEquals(10 * 60_000L, result.totalDropMs)
    }

    @Test
    fun adjacentButSeparateDropsStillCountSeparately() {
        val drops = listOf(
            DropEvent(0, 5 * 60_000),
            DropEvent(20 * 60_000, 25 * 60_000),
        )
        val result = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = drops,
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertEquals(10 * 60_000L, result.totalDropMs)
    }

    @Test
    fun downtimeCanNeverExceedTheWindow() {
        val drops = (0..9).map { DropEvent(0, hour) }
        val result = StabilityScore.compute(
            windowStartEpochMs = 0,
            nowEpochMs = hour,
            drops = drops,
            avgJitterMs = 0.0,
            avgLossPct = 0.0,
        )
        assertEquals(hour, result.totalDropMs)
        assertEquals(0.0, result.uptimePct)
    }
}
