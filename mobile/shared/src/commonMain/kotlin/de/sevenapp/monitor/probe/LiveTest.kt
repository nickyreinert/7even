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

    /**
     * One reachability probe from the ping phase.
     *
     * Every probe is taken on an idle link, because phases never overlap — so a
     * failure here is an unanswered probe, and can be counted as loss without
     * qualification. Probing during a stream would measure queueing delay
     * instead: on a 64 kbit/s line a stream leaves nothing spare, so nearly
     * every concurrent probe would time out and a merely busy connection would
     * report near-total packet loss.
     */
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

    /**
     * One rung of the size sweep finishing, emitted as it happens rather than
     * only once at the end via [Sweep].
     *
     * A sweep ladder can legitimately take minutes on a slow cellular line —
     * that is the honest cost of proving the link works at 64 kbit/s instead of
     * calling it broken. Without a per-rung event, nothing observable happens
     * between "Download size sweep" appearing and the whole ladder finishing,
     * which is indistinguishable from a hang.
     */
    data class SweepStepResult(
        override val atEpochMs: Long,
        val direction: SweepRunner.Direction,
        val stepIndex: Int,
        val stepCount: Int,
        val result: SweepResult,
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

    /**
     * One uninterrupted transfer, run only on request by
     * [de.sevenapp.monitor.android.livetest.LiveTestRunner.runSustainedProbe] —
     * not part of the regular ping/stream/sweep run.
     *
     * The regular download/upload stream phases are deliberately short (a few
     * seconds to tens of seconds) and move only a few hundred KB, which is not
     * enough to distinguish a carrier's *burst* allowance from its *sustained*
     * throttle on a connection that rate-limits with a token bucket rather than
     * an instant hard cap — exactly what a "Taktung 10 kB" mobile plan does.
     * This phase exists to run long/large enough to spend that banked credit
     * and show the rate it decays to.
     */
    SUSTAINED_DOWNLOAD("Sustained download probe"),
    SUSTAINED_UPLOAD("Sustained upload probe"),
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
    val pingTimeoutMs: Long = 5_000,
    val streamRoundTimeoutMs: Long = 30_000,
    /** Per-connection; see [ProbeConfig.sweepTimeoutMs]. */
    val sweepTimeoutMs: Long = 30_000,
    /**
     * Whether [de.sevenapp.monitor.android.livetest.LiveTestRunner.runSession]
     * appends a [LivePhase.SUSTAINED_DOWNLOAD]/[LivePhase.SUSTAINED_UPLOAD]
     * pair after the regular stream and sweep phases.
     *
     * Opt-in and off by default: it moves a bounded but real amount of extra
     * data (see [ProbeConfig.sustainedProbeMaxDownBytes]) specifically to
     * settle whether a throttled connection's burst allowance, not its
     * sustained rate, is what a short test measured. It only makes sense for
     * throughput — ping has nothing to warm up, and the size sweep already
     * asks a different question (does this size get through at all).
     */
    val sustainedProbeEnabled: Boolean = false,
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

    /** Steps the UI counts through: three phases, plus two for the sweep, plus two for the sustained probe. */
    val totalSteps: Int get() = PHASE_COUNT + (if (sweepEnabled) 2 else 0) + (if (sustainedProbeEnabled) 2 else 0)

    fun totalDurationMs(sweepEstimateMs: Long = SWEEP_ESTIMATE_MS, sustainedProbeEstimateMs: Long = SUSTAINED_PROBE_ESTIMATE_MS): Long =
        if (isUnlimited) {
            Long.MAX_VALUE
        } else {
            phaseDurationMs * PHASE_COUNT +
                (if (sweepEnabled) sweepEstimateMs else 0L) +
                (if (sustainedProbeEnabled) sustainedProbeEstimateMs else 0L)
        }

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

        /** Rough allowance for the sustained probe (both directions) when estimating a whole run. */
        const val SUSTAINED_PROBE_ESTIMATE_MS = 120_000L

        /** Offered in Settings; the floor matches the web app's shortest option. */
        val DURATION_OPTIONS_MINUTES = listOf(1, 2, 5, 10)

        /**
         * Sweep timeout choices offered in Settings, in milliseconds.
         *
         * The long end is deliberate. 1MB over a 64 kbit/s line takes about
         * 125 seconds, so anything below two minutes can only ever report that
         * transfer as a failure — which is the wrong word for a link that is
         * working exactly as its rate limit dictates.
         */
        val SWEEP_TIMEOUT_OPTIONS_MS = listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

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
