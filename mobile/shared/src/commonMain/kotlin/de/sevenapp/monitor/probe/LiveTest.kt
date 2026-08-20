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

    /**
     * The run has entered a new phase.
     *
     * Emitted explicitly rather than inferred from the first data point of each
     * kind, because a phase that measures nothing is exactly the case worth
     * showing: on a dead link the download phase produces no [Rate] samples at
     * all, and a UI that derives its step from arriving data would sit on
     * "Connecting" for the whole test. It also keeps the continuous ping loop
     * from repainting the step while a stream phase is running.
     */
    data class Phase(
        override val atEpochMs: Long,
        val phase: LivePhase,
        /** 1-based position in the run; 0 while connecting. */
        val step: Int,
        val totalSteps: Int,
        /** How long this phase will run, or null when it is not time-bounded. */
        val durationMs: Long?,
    ) : LiveSample

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
 * The ordered stages of one manual run.
 *
 * Ping, download, and upload are each given the user's configured duration in
 * turn; the size sweep runs once at the end. Labels live here rather than in
 * the Android UI so the phone and a future iOS host describe the same run the
 * same way.
 */
enum class LivePhase(val label: String) {
    CONNECTING("Connecting"),
    PING("Ping"),
    DOWNLOAD_STREAM("Download stream"),
    UPLOAD_STREAM("Upload stream"),
    DOWNLOAD_SWEEP("Download size sweep"),
    UPLOAD_SWEEP("Upload size sweep"),
}

/**
 * Settings for one foreground, user-triggered test run.
 *
 * [phaseDurationMs] is what the Settings screen calls "Manual ping and stream
 * duration", and it means what that label says: **each** phase runs for that
 * long. A 10-second choice is a 10-second ping phase, then a 10-second
 * download stream, then a 10-second upload stream, then one size sweep — not a
 * 10-second budget for the whole test.
 *
 * That distinction is the whole bug this type used to encode. As a single
 * whole-test deadline, a 10-second choice was consumed entirely by a download
 * episode hardcoded to 15 seconds, so the upload phase never ran at all and no
 * upload rate was ever measured on a short test.
 *
 * Ping keeps running underneath the stream phases as well, matching the web
 * app; the dedicated ping phase is what guarantees a clean latency reading
 * taken while nothing else is saturating the link.
 */
data class LiveTestConfig(
    val phaseDurationMs: Long = DEFAULT_PHASE_DURATION_MS,
    val pingIntervalMs: Long = 3_000,
    val downRoundBytes: Int = 500_000,
    val upRoundBytes: Int = 250_000,
    val progressThrottleMs: Long = 200,
    val sweepEnabled: Boolean = true,
    val sweepSteps: List<SweepStep> = SweepPlan.DEFAULT,
    val pingTimeoutMs: Long = 2_000,
    val streamRoundTimeoutMs: Long = 30_000,
    val sweepTimeoutMs: Long = 10_000,
) {
    /** True when the user picked "Unlimited": phases run until they are stopped. */
    val isUnlimited: Boolean get() = phaseDurationMs == Long.MAX_VALUE

    /**
     * How long the whole test takes, for the countdown and for the data
     * projection. Three phases plus the sweep, which is not duration-bounded
     * and so is only estimated here.
     */
    /** How long one phase actually runs, resolving "Unlimited" to its cycle length. */
    val effectivePhaseDurationMs: Long
        get() = if (isUnlimited) UNLIMITED_PHASE_MS else phaseDurationMs

    /** Steps the UI counts through: three phases, plus two sweep directions. */
    val totalSteps: Int get() = PHASE_COUNT + if (sweepEnabled) 2 else 0

    fun totalDurationMs(sweepEstimateMs: Long = SWEEP_ESTIMATE_MS): Long =
        if (isUnlimited) Long.MAX_VALUE
        else phaseDurationMs * PHASE_COUNT + (if (sweepEnabled) sweepEstimateMs else 0L)

    companion object {
        const val DEFAULT_PHASE_DURATION_MS = 60_000L

        /** Ping, download stream, upload stream. The sweep runs once, after them. */
        const val PHASE_COUNT = 3

        /**
         * Phase length used when the user picked "Unlimited".
         *
         * Unlimited has no per-phase deadline to divide up, so the run instead
         * cycles ping -> download -> upload -> sweep on this cadence until it
         * is stopped, matching the web app's continuous behaviour.
         */
        const val UNLIMITED_PHASE_MS = 15_000L

        /** Rough allowance for the size sweep when estimating a whole run. */
        const val SWEEP_ESTIMATE_MS = 20_000L

        /** Offered in Settings; the floor matches the web app's shortest option. */
        val DURATION_OPTIONS_MINUTES = listOf(1, 2, 5, 10)

        /** How much of the live chart's rolling window to keep, same span as the web app's Live chart. */
        const val LIVE_WINDOW_MS = 65_000L
    }
}

/**
 * "Has this phase run long enough yet" as a pure function.
 *
 * Split out so the decision is testable without a network, a clock mock
 * library, or a running coroutine — the same reasoning [TierPolicy] documents
 * for the background cycle. The Android runner enforces the same boundary with
 * `withTimeout` so that an in-flight transfer is actually cancelled at it
 * rather than merely not being started again.
 */
object LiveTestSchedule {
    fun isPhaseDone(startedAtEpochMs: Long, nowEpochMs: Long, phaseDurationMs: Long): Boolean =
        phaseDurationMs != Long.MAX_VALUE && nowEpochMs - startedAtEpochMs >= phaseDurationMs
}
