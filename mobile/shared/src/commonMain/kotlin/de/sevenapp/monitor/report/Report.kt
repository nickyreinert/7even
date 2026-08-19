package de.sevenapp.monitor.report

import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.StabilityScore
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.core.ThroughputSample

enum class ReportPeriod { DAILY, WEEKLY, MONTHLY }

/**
 * A report over one window, segmented by network type.
 *
 * [coverage] is not decoration. On iOS the OS decides how often the app may
 * wake, so a "daily" report may rest on a handful of samples. Stating what was
 * actually collected against what was expected is the difference between a
 * report the user can weigh and one that quietly overstates its own authority.
 */
data class Report(
    val period: ReportPeriod,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val coverage: Coverage,
    val overall: Segment,
    val byNetwork: Map<NetworkType, Segment>,
    val drops: List<DropEvent>,
) {
    data class Coverage(
        val samplesCollected: Int,
        val samplesExpected: Int,
    ) {
        val ratio: Double get() = if (samplesExpected <= 0) 1.0 else
            (samplesCollected.toDouble() / samplesExpected).coerceIn(0.0, 1.0)

        /** Below this, the report says so rather than presenting itself as complete. */
        val isPartial: Boolean get() = ratio < 0.8
    }

    data class Segment(
        val pingCount: Int,
        val failureCount: Int,
        val latencyMedianMs: Double?,
        val latencyP95Ms: Double?,
        val jitterMs: Double?,
        val lossPct: Double,
        val throughputSampleCount: Int,
        val downMedianMbps: Double?,
        val upMedianMbps: Double?,
        val stability: StabilityScore.Result?,
    )
}

object ReportBuilder {

    fun build(
        period: ReportPeriod,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        pings: List<PingSample>,
        throughput: List<ThroughputSample>,
        drops: List<DropEvent>,
        expectedSamples: Int,
    ): Report {
        val inWindow = pings.filter { it.atEpochMs in windowStartEpochMs..windowEndEpochMs }
        val tpInWindow = throughput.filter { it.atEpochMs in windowStartEpochMs..windowEndEpochMs }

        // Only drops that overlap the window at all. StabilityScore clips the
        // partial ones to the window itself.
        val relevantDrops = drops.filter { drop ->
            val end = drop.endedAtEpochMs ?: windowEndEpochMs
            end >= windowStartEpochMs && drop.startedAtEpochMs <= windowEndEpochMs
        }

        val overall = segment(inWindow, tpInWindow, relevantDrops, windowStartEpochMs, windowEndEpochMs)

        val networks = (inWindow.map { it.networkType } + tpInWindow.map { it.networkType }).toSet()
        val byNetwork = networks.associateWith { net ->
            segment(
                pings = inWindow.filter { it.networkType == net },
                throughput = tpInWindow.filter { it.networkType == net },
                // Drops are not attributable to a single network — a drop is
                // precisely the period when there was no usable network to
                // attribute it to. Per-network segments therefore carry no
                // stability score rather than an invented one.
                drops = emptyList(),
                windowStartEpochMs = windowStartEpochMs,
                windowEndEpochMs = windowEndEpochMs,
                includeStability = false,
            )
        }

        return Report(
            period = period,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            coverage = Report.Coverage(inWindow.size, expectedSamples),
            overall = overall,
            byNetwork = byNetwork,
            drops = relevantDrops,
        )
    }

    private fun segment(
        pings: List<PingSample>,
        throughput: List<ThroughputSample>,
        drops: List<DropEvent>,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        includeStability: Boolean = true,
    ): Report.Segment {
        val rtts = pings.mapNotNull { it.rttMs }
        val failures = pings.count { !it.ok }
        val lossPct = if (pings.isEmpty()) 0.0 else (failures.toDouble() / pings.size) * 100.0
        val jitter = if (rtts.size >= 2) Stats.stdDev(rtts) else null

        return Report.Segment(
            pingCount = pings.size,
            failureCount = failures,
            latencyMedianMs = Stats.median(rtts),
            latencyP95Ms = Stats.percentile(rtts, 0.95),
            jitterMs = jitter,
            lossPct = lossPct,
            throughputSampleCount = throughput.size,
            downMedianMbps = Stats.median(throughput.mapNotNull { it.downMbps }),
            upMedianMbps = Stats.median(throughput.mapNotNull { it.upMbps }),
            stability = if (!includeStability) null else StabilityScore.compute(
                windowStartEpochMs = windowStartEpochMs,
                nowEpochMs = windowEndEpochMs,
                drops = drops,
                avgJitterMs = jitter,
                avgLossPct = lossPct,
            ),
        )
    }
}
