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
    /**
     * How much of the window was actually observed.
     *
     * [samplesExpected] of null means "unknown" — the schedule does not predict
     * a sample count for this window at all, which happens whenever the probe
     * cadence is longer than the report period (a weekly cycle inside a daily
     * report). That used to truncate to zero expected samples and then be read
     * as a ratio of 1.0, so the least-observed case reported the fullest
     * coverage.
     */
    data class Coverage(
        val samplesCollected: Int,
        val samplesExpected: Int?,
    ) {
        val ratio: Double? get() = samplesExpected?.takeIf { it > 0 }?.let {
            (samplesCollected.toDouble() / it).coerceIn(0.0, 1.0)
        }

        /** Below this, the report says so rather than presenting itself as complete. */
        val isPartial: Boolean get() = ratio?.let { it < MIN_TRUSTWORTHY_RATIO } ?: false

        val isUnknown: Boolean get() = ratio == null

        /**
         * Enough of the window was seen for aggregate figures to mean anything.
         * An empty window is never sufficient, regardless of what was expected.
         */
        val isSufficient: Boolean get() = samplesCollected > 0 &&
            (ratio == null || ratio!! >= MIN_TRUSTWORTHY_RATIO)

        companion object {
            const val MIN_TRUSTWORTHY_RATIO = 0.8
        }
    }

    data class Segment(
        val pingCount: Int,
        val failureCount: Int,
        val latencyMedianMs: Double?,
        val latencyP95Ms: Double?,
        val jitterMs: Double?,
        /** Null when nothing was measured — distinct from a measured 0%. */
        val lossPct: Double?,
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
        expectedSamples: Int?,
    ): Report {
        // Half-open [start, end). Inclusive bounds put a sample landing exactly
        // on midnight into both the report that ended there and the one that
        // began there, so consecutive reports double-counted their boundary.
        val inWindow = pings.filter { it.atEpochMs >= windowStartEpochMs && it.atEpochMs < windowEndEpochMs }
        val tpInWindow = throughput.filter { it.atEpochMs >= windowStartEpochMs && it.atEpochMs < windowEndEpochMs }

        // Only drops that overlap the window at all. StabilityScore clips the
        // partial ones to the window itself. Also half-open: a drop that ended
        // exactly at the boundary belongs to the earlier window.
        val relevantDrops = drops.filter { drop ->
            val end = drop.endedAtEpochMs ?: windowEndEpochMs
            end > windowStartEpochMs && drop.startedAtEpochMs < windowEndEpochMs
        }

        val coverage = Report.Coverage(inWindow.size, expectedSamples)
        val overall = segment(
            pings = inWindow,
            throughput = tpInWindow,
            drops = relevantDrops,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            includeStability = coverage.isSufficient,
        )

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
            coverage = coverage,
            overall = overall,
            byNetwork = byNetwork,
            drops = relevantDrops,
        )
    }

    /**
     * @param includeStability false when the window has too few observations
     *   for a stability score to mean anything. A score computed from nothing
     *   is 100% uptime, zero drops and a perfect composite — the most confident
     *   possible claim, made on the least possible evidence.
     */
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
        // Null, not zero. "No probes ran" and "every probe succeeded" are
        // different facts, and reporting the first as 0% loss states the
        // second.
        val lossPct = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0
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
            stability = if (!includeStability || pings.isEmpty()) null else StabilityScore.compute(
                windowStartEpochMs = windowStartEpochMs,
                nowEpochMs = windowEndEpochMs,
                drops = drops,
                avgJitterMs = jitter,
                avgLossPct = lossPct ?: 0.0,
            ),
        )
    }
}
