package de.sevenapp.monitor

import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.report.ReportBuilder
import de.sevenapp.monitor.report.ReportPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportTest {

    private val day = 86_400_000L

    private fun ping(t: Long, rtt: Double?, net: NetworkType = NetworkType.WIFI) =
        PingSample(t, rtt, net)

    @Test
    fun segmentsByNetworkType() {
        // A week mixing home Wi-Fi with commuting LTE averages into noise; the
        // per-network split is the whole point of the report.
        val pings = listOf(
            ping(1000, 10.0, NetworkType.WIFI),
            ping(2000, 12.0, NetworkType.WIFI),
            ping(3000, 90.0, NetworkType.CELLULAR),
            ping(4000, 110.0, NetworkType.CELLULAR),
        )
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 4,
        )

        assertEquals(2, report.byNetwork.size)
        assertEquals(11.0, report.byNetwork[NetworkType.WIFI]?.latencyMedianMs)
        assertEquals(100.0, report.byNetwork[NetworkType.CELLULAR]?.latencyMedianMs)
        // Overall blends them, which is exactly why the split matters.
        assertEquals(51.0, report.overall.latencyMedianMs)
    }

    @Test
    fun lossPercentCountsFailedProbes() {
        val pings = listOf(ping(1, 10.0), ping(2, null), ping(3, 10.0), ping(4, null))
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 4,
        )
        assertEquals(50.0, report.overall.lossPct)
        assertEquals(2, report.overall.failureCount)
    }

    @Test
    fun samplesOutsideTheWindowAreExcluded() {
        val pings = listOf(ping(-5000, 10.0), ping(1000, 20.0), ping(day + 5000, 30.0))
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 1,
        )
        assertEquals(1, report.overall.pingCount)
        assertEquals(20.0, report.overall.latencyMedianMs)
    }

    @Test
    fun coverageFlagsAPartialReport() {
        // The iOS case: far fewer samples than a day should have produced.
        val pings = (1..10).map { ping(it * 1000L, 20.0) }
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 96,
        )
        assertTrue(report.coverage.isPartial)
        assertEquals(10, report.coverage.samplesCollected)
        assertTrue(assertNotNull(report.coverage.ratio) < 0.2)
    }

    @Test
    fun fullCoverageIsNotFlaggedPartial() {
        val pings = (1..96).map { ping(it * 1000L, 20.0) }
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 96,
        )
        assertTrue(!report.coverage.isPartial)
        assertEquals(1.0, report.coverage.ratio)
    }

    @Test
    fun coverageRatioIsCappedAtOne() {
        val pings = (1..200).map { ping(it * 1000L, 20.0) }
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 96,
        )
        assertEquals(1.0, report.coverage.ratio)
    }

    @Test
    fun throughputMediansIgnoreMissingDirections() {
        val tp = listOf(
            ThroughputSample(1000, 50.0, null, NetworkType.WIFI, ProbeTier.THROUGHPUT_LIGHT),
            ThroughputSample(2000, 70.0, 10.0, NetworkType.WIFI, ProbeTier.THROUGHPUT_LIGHT),
        )
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, emptyList(), tp, emptyList(), expectedSamples = 0,
        )
        assertEquals(60.0, report.overall.downMedianMbps)
        assertEquals(10.0, report.overall.upMedianMbps)
        assertEquals(2, report.overall.throughputSampleCount)
    }

    @Test
    fun dropsOutsideTheWindowAreDropped() {
        val drops = listOf(
            DropEvent(-day * 2, -day),          // entirely before
            DropEvent(day / 2, day / 2 + 1000), // inside
        )
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, emptyList(), emptyList(), drops, expectedSamples = 0,
        )
        assertEquals(1, report.drops.size)
    }

    @Test
    fun openDropIsRetainedAndCountedAgainstStability() {
        val drops = listOf(DropEvent(day / 2, null))
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, listOf(ping(1000, 10.0)), emptyList(), drops, expectedSamples = 1,
        )
        assertEquals(1, report.drops.size)
        val stability = assertNotNull(report.overall.stability)
        assertEquals(50.0, stability.uptimePct)
    }

    @Test
    fun perNetworkSegmentsCarryNoStabilityScore() {
        // A drop is by definition the period with no usable network, so it
        // cannot be attributed to one. Inventing a per-network score would be
        // making up data.
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, listOf(ping(1000, 10.0)), emptyList(), emptyList(), expectedSamples = 1,
        )
        assertNull(report.byNetwork[NetworkType.WIFI]?.stability)
        assertNotNull(report.overall.stability)
    }

    @Test
    fun emptyWindowProducesAReportRatherThanFailing() {
        val report = ReportBuilder.build(
            ReportPeriod.WEEKLY, 0, day * 7, emptyList(), emptyList(), emptyList(), expectedSamples = 672,
        )
        assertEquals(0, report.overall.pingCount)
        assertNull(report.overall.latencyMedianMs)
        assertTrue(report.coverage.isPartial)
    }

    @Test
    fun anEmptyWindowNeverClaimsPerfectStability() {
        // The REPORT-02 regression. With no observations at all, the old
        // builder produced 100% uptime, 0% loss and a top composite score —
        // the strongest possible claim on the weakest possible evidence, which
        // a fresh install could receive as its very first report.
        val report = ReportBuilder.build(
            ReportPeriod.WEEKLY, 0, day * 7, emptyList(), emptyList(), emptyList(), expectedSamples = 672,
        )
        assertNull(report.overall.stability, "an unobserved window must not be scored")
        assertNull(report.overall.lossPct, "no probes is not the same as no loss")
        assertTrue(!report.coverage.isSufficient)
    }

    @Test
    fun aLowCoverageWindowIsNotScoredEither() {
        val pings = (1..5).map { ping(it * 1000L, 20.0) }
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, pings, emptyList(), emptyList(), expectedSamples = 288,
        )
        assertNull(report.overall.stability)
        assertTrue(report.coverage.isPartial)
    }

    @Test
    fun aSampleExactlyAtTheBoundaryBelongsToOneWindowOnly() {
        // REPORT-03: inclusive start..end put a midnight sample in both the
        // report that ended there and the one that began there.
        val boundary = day
        val pings = listOf(ping(boundary, 20.0))

        val earlier = ReportBuilder.build(
            ReportPeriod.DAILY, 0, boundary, pings, emptyList(), emptyList(), expectedSamples = 96,
        )
        val later = ReportBuilder.build(
            ReportPeriod.DAILY, boundary, boundary + day, pings, emptyList(), emptyList(), expectedSamples = 96,
        )
        assertEquals(1, earlier.overall.pingCount + later.overall.pingCount)
        assertEquals(0, earlier.overall.pingCount)
        assertEquals(1, later.overall.pingCount)
    }

    @Test
    fun unknownExpectedCoverageIsNotReportedAsFull() {
        // A probe cadence longer than the report window predicts no samples at
        // all. That used to truncate to zero expected and read as ratio 1.0.
        val report = ReportBuilder.build(
            ReportPeriod.DAILY, 0, day, listOf(ping(1000, 20.0)), emptyList(), emptyList(),
            expectedSamples = null,
        )
        assertTrue(report.coverage.isUnknown)
        assertNull(report.coverage.ratio)
        assertTrue(!report.coverage.isPartial, "unknown is not the same as partial")
    }
}
