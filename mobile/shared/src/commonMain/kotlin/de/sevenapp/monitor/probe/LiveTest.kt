package de.sevenapp.monitor.probe

/**
 * One thing worth drawing on the foreground test's live chart, timestamped at
 * the moment it happened.
 *
 * Deliberately not persisted as-is: [Rate] samples are a rolling, high-rate
 * view of "what's happening right now" (the same role the web app's
 * `liveRateSamples`/`livePingSamples` play), while what actually gets written
 * to [de.sevenapp.monitor.data.MonitorStore] is the aggregated per-episode
 * [de.sevenapp.monitor.core.ThroughputSample] and the individual
 * [de.sevenapp.monitor.core.PingSample]s — the session-long trend, not the
 * live wiggle.
 */
sealed interface LiveSample {
    val atEpochMs: Long

    data class Ping(override val atEpochMs: Long, val rttMs: Double?) : LiveSample

    data class Rate(
        override val atEpochMs: Long,
        val direction: SweepRunner.Direction,
        val mbps: Double,
    ) : LiveSample

    /** The completed pass/fail matrix for the most recent size sweep. */
    data class Sweep(
        override val atEpochMs: Long,
        val direction: SweepRunner.Direction,
        val results: List<SweepResult>,
    ) : LiveSample
}

/**
 * Settings for one foreground, user-triggered test run.
 *
 * Mirrors the web app's advanced settings (`PING_INTERVAL_MS`,
 * `DOWN_ROUND_BYTES`/`UP_ROUND_BYTES`, `STREAM_PHASE_DURATION_MS`,
 * `PROGRESS_THROTTLE_MS`) so the mobile session reads the same way: a
 * continuous ping loop plus back-to-back streaming rounds, with a
 * configurable size sweep run between streaming episodes.
 */
data class LiveTestConfig(
    val minDurationMs: Long = MIN_DURATION_MS,
    val pingIntervalMs: Long = 3_000,
    val streamEpisodeDurationMs: Long = 15_000,
    val downRoundBytes: Int = 500_000,
    val upRoundBytes: Int = 250_000,
    val progressThrottleMs: Long = 200,
    val sweepEnabled: Boolean = true,
    val sweepSteps: List<SweepStep> = SweepPlan.DEFAULT,
    val pingTimeoutMs: Long = 2_000,
    val streamRoundTimeoutMs: Long = 30_000,
    val sweepTimeoutMs: Long = 10_000,
) {
    companion object {
        const val MIN_DURATION_MS = 60_000L

        /** Offered in Settings; the floor matches the web app's shortest option. */
        val DURATION_OPTIONS_MINUTES = listOf(1, 2, 5, 10)

        /** How much of the live chart's rolling window to keep, same span as the web app's Live chart. */
        const val LIVE_WINDOW_MS = 65_000L
    }
}

/**
 * Decides when a running session has done enough to stop — never mid-episode,
 * only at a clean boundary between rounds, and never before the configured
 * minimum has actually elapsed.
 *
 * Split out as a pure function so "should this cycle happen again" is
 * testable without a network, a clock mock library, or a running coroutine —
 * the same reasoning [TierPolicy] documents for the background cycle.
 */
object LiveTestSchedule {
    fun isSessionDone(startedAtEpochMs: Long, nowEpochMs: Long, minDurationMs: Long): Boolean =
        nowEpochMs - startedAtEpochMs >= minDurationMs
}
