package de.sevenapp.monitor.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Statistics ported from the web app (index.html), deliberately keeping the
 * same definitions so a phone and the browser report the same numbers for the
 * same data. Where the JS was loose, the behaviour is pinned by tests rather
 * than "improved" silently — a mobile app that disagrees with the web app
 * about what jitter means is worse than one that copies a debatable choice.
 */
object Stats {

    /** Median. Even-sized inputs average the two middle values, as in the web app. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 != 0) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * Jitter, as the web app defines it: the *population* standard deviation of
     * round-trip times (divide by n, not n-1), returning 0.0 for fewer than two
     * samples.
     *
     * Note this is not the RFC 3550 inter-arrival jitter that networking people
     * may expect. It is kept because it is what the web app has always shown and
     * what its stability score is calibrated against; the UI calls it out as a
     * convention rather than a standard.
     */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.sum() / values.size
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    /**
     * Nearest-rank percentile, [p] in 0.0..1.0.
     *
     * New in the mobile app — the web app only ever showed a median, but a
     * weekly report wants a tail number (p95) to distinguish "usually fine with
     * occasional spikes" from "uniformly mediocre", which a median hides.
     */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val rank = (p.coerceIn(0.0, 1.0) * (s.size - 1)).roundToLong().toInt()
        return s[rank.coerceIn(0, s.size - 1)]
    }

    fun clamp(n: Double, minimum: Double, maximum: Double): Double = min(maximum, max(minimum, n))
}

/**
 * The composite stability score, ported verbatim from the web app including its
 * 50/30/20 weighting.
 *
 * This is explicitly NOT a standard metric. It is a transparent composite the
 * app builds from numbers it already has, and it is always displayed alongside
 * its three inputs so the reader can disregard the composite and judge for
 * themselves. Keeping the weights identical to the web app's matters more than
 * whether they are optimal — a score that changes meaning between platforms is
 * worthless for comparing this week against last week.
 */
object StabilityScore {

    data class Result(
        val composite: Double,
        val uptimePct: Double,
        val jitterScore: Double,
        val lossScore: Double,
        val totalDropMs: Long,
        val windowMs: Long,
    )

    fun compute(
        windowStartEpochMs: Long,
        nowEpochMs: Long,
        drops: List<DropEvent>,
        avgJitterMs: Double?,
        avgLossPct: Double,
    ): Result {
        val windowMs = max(1L, nowEpochMs - windowStartEpochMs)

        // Clip each drop to the window. The web app could assume every drop
        // belonged to the current session; a report over "last week" cannot,
        // because a drop may straddle the boundary and counting it whole would
        // charge one week for the other's downtime.
        val totalDropMs = drops.sumOf { drop ->
            val start = max(drop.startedAtEpochMs, windowStartEpochMs)
            val end = min(drop.endedAtEpochMs ?: nowEpochMs, nowEpochMs)
            max(0L, end - start)
        }

        val uptimePct = Stats.clamp(
            ((windowMs - totalDropMs).toDouble() / windowMs) * 100.0,
            0.0,
            100.0,
        )
        val jitterScore = if (avgJitterMs == null) 100.0 else max(0.0, 100.0 - (avgJitterMs / 2.0))
        val lossScore = max(0.0, 100.0 - (avgLossPct * 2.0))

        val composite = (uptimePct * 0.5) + (jitterScore * 0.3) + (lossScore * 0.2)

        return Result(
            composite = composite.round1(),
            uptimePct = uptimePct.round1(),
            jitterScore = jitterScore.round1(),
            lossScore = lossScore.round1(),
            totalDropMs = totalDropMs,
            windowMs = windowMs,
        )
    }

    private fun Double.round1(): Double = (this * 10.0).roundToLong() / 10.0
}
