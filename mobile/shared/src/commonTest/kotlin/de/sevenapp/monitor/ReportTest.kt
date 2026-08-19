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
        assertTrue(report.coverage.ratio < 0.2)
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
        assertEquals(0.0, report.overall.lossPct)
        assertTrue(report.coverage.isPartial)
    }
}
