package de.sevenapp.monitor.probe

import de.sevenapp.monitor.core.NetworkType

/**
 * What one automatic cycle is allowed to move, and whether it may run at all.
 *
 * This type exists because the projection the settings screen showed and the
 * work the worker actually did were computed in two different places from two
 * different sets of fields. Enabling automatic streams barely moved the
 * projected number while adding roughly 342MB/day of real traffic at the
 * default 15-minute cadence, and the per-day sweep cap was consulted only by a
 * code path that no longer ran. Projection and execution now read the same
 * plan, so a number shown before saving is a number the runtime is bound by.
 */
data class TransferPlan(
    val runStream: Boolean,
    val runSweep: Boolean,
    /** Per phase: download stream, then upload stream. */
    val phaseDurationMs: Long,
    val sweepSteps: List<SweepStep>,
    /**
     * Hard ceiling on stream bytes for one run, enforced by the runner.
     *
     * A duration-based stream moves whatever the link can carry, so its cost is
     * a function of the connection's speed rather than of any configured size.
     * On a fast link, "15 seconds of streaming" is hundreds of megabytes. This
     * is the number that makes the cost knowable in advance, which is the whole
     * point of showing a projection.
     */
    val maxStreamBytes: Long,
) {
    /** Both directions of one full pass of the ladder. */
    val maxSweepBytes: Long get() = if (runSweep) SweepPlan.totalBytes(sweepSteps) * 2 else 0L

    /** The number the settings screen shows and the runtime may not exceed. */
    val maxBytesPerRun: Long get() = (if (runStream) maxStreamBytes else 0L) + maxSweepBytes

    val movesNothing: Boolean get() = !runStream && !runSweep
}

/**
 * Decides whether an automatic cycle's heavy work may run, and with what plan.
 *
 * Kept as a pure function of config, device state, and current usage — the same
 * reasoning [TierPolicy] documents — so "would this have run, and what would it
 * have cost" is answerable in a unit test rather than only by watching a phone
 * for a day.
 */
object AutomaticTransfers {

    sealed interface Decision {
        data class Run(val plan: TransferPlan) : Decision
        data class Skip(val reason: String) : Decision
    }

    /** Usage counters the caller reads from the store. */
    data class Usage(
        val fullSweepsToday: Int,
        val meteredBytesToday: Long,
        val meteredBytesThisMonth: Long,
    )

    fun planFor(config: ProbeConfig, network: NetworkType): TransferPlan = TransferPlan(
        runStream = config.automaticStreamEnabled,
        runSweep = config.automaticSweepEnabled,
        // Automatic runs are capped tighter than a manual one: nobody is
        // watching, and the cost repeats on every cycle for the rest of time.
        phaseDurationMs = config.liveTestPhaseDurationMs.coerceIn(
            MIN_AUTOMATIC_PHASE_MS,
            MAX_AUTOMATIC_PHASE_MS,
        ),
        sweepSteps = if (network == NetworkType.CELLULAR) config.mobileLiveTestSweepSteps else config.wifiLiveTestSweepSteps,
        maxStreamBytes = if (network == NetworkType.CELLULAR) {
            config.automaticStreamMaxBytesMetered
        } else {
            config.automaticStreamMaxBytesUnmetered
        },
    )

    fun decide(
        config: ProbeConfig,
        deviceState: DeviceState,
        usage: Usage,
    ): Decision {
        val plan = planFor(config, deviceState.networkType)
        if (plan.movesNothing) return Decision.Skip("no automatic transfers enabled")
        if (deviceState.networkType == NetworkType.NONE) return Decision.Skip("no network")
        if (deviceState.networkType !in config.monitoringNetworks) {
            return Decision.Skip("connection type not selected")
        }
        // Applied to the heavy work only. Reachability already ran, and gating
        // it on charging too would make an unplugged phone look permanently
        // online — the opposite of what this app is for.
        if (config.automaticRequiresCharging && !deviceState.isCharging) {
            return Decision.Skip("not charging")
        }

        // The per-day sweep cap applies to the path that actually runs sweeps.
        // It used to be consulted only by the dormant HTTP throughput tier.
        val sweepAllowed = plan.runSweep && usage.fullSweepsToday < config.fullSweepsPerDay
        val effective = plan.copy(runSweep = sweepAllowed)
        if (effective.movesNothing) return Decision.Skip("daily sweep limit reached")

        // The OS decides what is metered, not the transport type: a tethered or
        // user-marked Wi-Fi spends the same allowance as cellular, and
        // inferring from "is it cellular" quietly ignored that.
        if (deviceState.isMetered) {
            if (usage.meteredBytesToday + effective.maxBytesPerRun > config.automaticDailyMeteredBytes) {
                return Decision.Skip("daily data budget reached")
            }
            if (usage.meteredBytesThisMonth + effective.maxBytesPerRun > config.automaticMonthlyMeteredBytes) {
                return Decision.Skip("monthly data budget reached")
            }
        }
        return Decision.Run(effective)
    }

    /** A phase longer than this is not "background" work on a 15-minute cadence. */
    const val MAX_AUTOMATIC_PHASE_MS = 30_000L
    const val MIN_AUTOMATIC_PHASE_MS = 5_000L
}
