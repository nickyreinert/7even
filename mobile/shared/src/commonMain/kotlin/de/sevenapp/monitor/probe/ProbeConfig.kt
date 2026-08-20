package de.sevenapp.monitor.probe

import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.ProbeTier

/**
 * User-facing configuration. Defaults are chosen so that a user who installs
 * the app and never opens settings costs themselves well under 10MB of
 * cellular data a month — see [DataBudget], which turns these values into the
 * projection the settings screen shows.
 */
data class ProbeConfig(
    /** How often the scheduler is asked to run a cycle. Android's floor is 15 min. */
    val cycleIntervalMinutes: Int = 15,
    /** Local hour at which long automatic schedules begin (0..23). */
    val automaticHourOfDay: Int = 0,
    /** Monday=1 through Sunday=7; used by the weekly schedule. */
    val automaticDayOfWeek: Int = 1,

    /** Connections on which this device is allowed to collect measurements. */
    val monitoringNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.CELLULAR),

    /** Reachability probes per cycle. Three gives a median and a jitter figure. */
    val pingsPerCycle: Int = 3,

    /** Run light throughput every Nth cycle. 4 => hourly at a 15-minute cycle. */
    val throughputEveryNCycles: Int = 4,

    /** Networks on which light throughput may run. */
    val throughputLightNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.ETHERNET),

    /** Full sweeps additionally require charging, and are capped per day. */
    val fullSweepNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.ETHERNET),
    val fullSweepRequiresCharging: Boolean = true,
    val fullSweepsPerDay: Int = 2,

    val lightDownBytes: Int = 256 * 1024,
    val lightUpBytes: Int = 128 * 1024,
    val wifiMeasurementSizes: Set<Int> = setOf(256 * 1024),
    val cellularMeasurementSizes: Set<Int> = setOf(64 * 1024),

    val pingTimeoutMs: Long = 5_000,
    val transferTimeoutMs: Long = 20_000,

    val traceUrl: String = "https://speed.cloudflare.com/cdn-cgi/trace",
    val downUrlTemplate: String = "https://speed.cloudflare.com/__down?bytes={bytes}",
    val upUrl: String = "https://ws-speedtest.nyrt.workers.dev/__up",

    /**
     * The one 7even-compatible WebSocket endpoint, used by both the foreground
     * live test and the background stream tier.
     *
     * There used to be two fields holding the same default — `streamUrl`, which
     * Settings edited and persisted, and `wsUrl`, which the live-test runner
     * actually dialled. Editing the visible endpoint changed nothing.
     */
    val streamUrl: String = "wss://ws-speedtest.nyrt.workers.dev",
    val useWebSocketStream: Boolean = false,

    /**
     * How long **each** phase of a manual test runs: ping, then download
     * stream, then upload stream. See [LiveTestConfig] — this is not a
     * whole-test budget.
     */
    val liveTestPhaseDurationMs: Long = LiveTestConfig.DEFAULT_PHASE_DURATION_MS,
    val liveTestSweepEnabled: Boolean = true,
    /** Packet sizes and repeats for the foreground send/receive reliability sweep. */
    val liveTestSweepSteps: List<SweepStep> = SweepPlan.DEFAULT,
    val wifiLiveTestSweepSteps: List<SweepStep> = SweepPlan.DEFAULT,
    val mobileLiveTestSweepSteps: List<SweepStep> = SweepPlan.MOBILE_DEFAULT,
    /** Expensive tests explicitly selected for an automatic WorkManager cycle. */
    val automaticStreamEnabled: Boolean = false,
    val automaticSweepEnabled: Boolean = false,
    val automaticRequiresCharging: Boolean = false,

    /**
     * Hard per-run ceilings on automatic stream traffic, and the daily/monthly
     * ceilings on automatic metered traffic overall.
     *
     * A duration-based stream has no inherent size — it moves as much as the
     * link can carry — so without these the projected cost and the real cost
     * are unrelated numbers. See [AutomaticTransfers], which is the only place
     * that reads them, and [DataBudget], which projects from the same plan.
     */
    val automaticStreamMaxBytesMetered: Long = 4L * 1000 * 1000,
    val automaticStreamMaxBytesUnmetered: Long = 40L * 1000 * 1000,
    val automaticDailyMeteredBytes: Long = 20L * 1000 * 1000,
    val automaticMonthlyMeteredBytes: Long = 300L * 1000 * 1000,

    val preferredTestNetwork: NetworkPreference = NetworkPreference.AUTO,
) {
    fun downUrl(bytes: Int): String = downUrlTemplate.replace("{bytes}", bytes.toString())

    fun cyclesPerDay(): Int = if (cycleIntervalMinutes <= 0) 0 else (24 * 60) / cycleIntervalMinutes

    fun measurementSizes(network: NetworkType): List<Int> = when (network) {
        NetworkType.WIFI -> wifiMeasurementSizes
        NetworkType.CELLULAR -> cellularMeasurementSizes
        else -> setOf(lightDownBytes)
    }.filter { it in 16 * 1024..10 * 1024 * 1024 }.sorted().ifEmpty { listOf(lightDownBytes) }
}

/** Conditions at the moment a cycle runs, supplied by the platform host. */
data class DeviceState(
    val networkType: NetworkType,
    val isCharging: Boolean,
    val isMetered: Boolean,
    val ssid: String? = null,
)

/**
 * Decides which tier a given cycle should run.
 *
 * Split out from the engine because "should this run at all" is the decision
 * most likely to be argued about and tuned, and it is far easier to reason
 * about — and test — as a pure function of config, cycle index and device
 * state than as branches buried inside the measurement loop.
 */
object TierPolicy {

    fun tierFor(
        config: ProbeConfig,
        cycleIndex: Long,
        state: DeviceState,
        fullSweepsToday: Int,
    ): ProbeTier? {
        if (state.networkType == NetworkType.NONE) return null
        if (state.networkType !in config.monitoringNetworks) return null

        val throughputDue = config.throughputEveryNCycles > 0 &&
            cycleIndex % config.throughputEveryNCycles == 0L

        if (throughputDue) {
            val fullAllowed = state.networkType in config.fullSweepNetworks &&
                (!config.fullSweepRequiresCharging || state.isCharging) &&
                fullSweepsToday < config.fullSweepsPerDay
            if (fullAllowed) return ProbeTier.THROUGHPUT_FULL

            val lightAllowed = state.networkType in config.throughputLightNetworks ||
                (!state.isMetered)
            if (lightAllowed) return ProbeTier.THROUGHPUT_LIGHT
        }

        // Reachability is cheap enough to run on any connection, always.
        return ProbeTier.REACHABILITY
    }
}

/**
 * Projects monthly data use from a config, so the settings screen can show the
 * cost of a change *before* the user commits to it.
 *
 * This exists because the failure mode for an app like this is not a crash —
 * it is a user discovering at the end of the month that a background monitor
 * quietly consumed their data allowance. Showing the number up front is the
 * fix, and it matches the web app's habit of showing its workings rather than
 * asking to be trusted.
 *
 * The projection reads [AutomaticTransfers.planFor] — the same plan the worker
 * is bound by — rather than modelling a separate, dormant code path. It is a
 * projection of the **maximum**: a duration-based stream can move anything up
 * to its cap, so the honest figure to show before the fact is the ceiling.
 */
object DataBudget {

    /** Rough bytes on the wire for one reachability probe, headers included. */
    const val PING_BYTES = 1_000L

    data class Projection(
        val meteredBytesPerDay: Long,
        val unmeteredBytesPerDay: Long,
        /** True when a duration-based stream is included, so this is a ceiling. */
        val isMaximum: Boolean = false,
    ) {
        val meteredBytesPerMonth: Long get() = meteredBytesPerDay * 30
        val unmeteredBytesPerMonth: Long get() = unmeteredBytesPerDay * 30
    }

    /**
     * @param assumeMeteredNetwork model the phone as being on cellular for this
     *        fraction of the day (0.0..1.0). Defaults to half — most people are
     *        on Wi-Fi at home and at work.
     */
    fun project(config: ProbeConfig, assumeMeteredNetwork: Double = 0.5): Projection {
        val cycles = config.cyclesPerDay()
        val meteredCycles = (cycles * assumeMeteredNetwork).toLong()
        val unmeteredCycles = cycles - meteredCycles

        val pingBytesPerCycle = PING_BYTES * config.pingsPerCycle

        // Reachability runs on every selected connection. If mobile data is
        // excluded, its projected cost must be zero — otherwise the settings
        // screen would contradict the user's connection preference.
        var metered = if (NetworkType.CELLULAR in config.monitoringNetworks) {
            meteredCycles * pingBytesPerCycle
        } else {
            0L
        }
        var unmetered = if (NetworkType.WIFI in config.monitoringNetworks) {
            unmeteredCycles * pingBytesPerCycle
        } else {
            0L
        }

        // Automatic stream/sweep work: the actual expensive path, and the one
        // the old projection ignored entirely. Every cycle can run it, so the
        // per-cycle ceiling multiplies by the cycle count — bounded, on metered
        // connections, by the daily budget the runtime also enforces.
        val cellularPlan = AutomaticTransfers.planFor(config, NetworkType.CELLULAR)
        val wifiPlan = AutomaticTransfers.planFor(config, NetworkType.WIFI)

        if (NetworkType.CELLULAR in config.monitoringNetworks) {
            val sweepRuns = minOf(meteredCycles, config.fullSweepsPerDay.toLong())
            val streamRuns = if (cellularPlan.runStream) meteredCycles else 0L
            val raw = streamRuns * cellularPlan.maxStreamBytes +
                sweepRuns * cellularPlan.maxSweepBytes
            metered += minOf(raw, config.automaticDailyMeteredBytes)
        }
        if (NetworkType.WIFI in config.monitoringNetworks) {
            val sweepRuns = minOf(unmeteredCycles, config.fullSweepsPerDay.toLong())
            val streamRuns = if (wifiPlan.runStream) unmeteredCycles else 0L
            unmetered += streamRuns * wifiPlan.maxStreamBytes + sweepRuns * wifiPlan.maxSweepBytes
        }

        return Projection(
            meteredBytesPerDay = metered,
            unmeteredBytesPerDay = unmetered,
            isMaximum = cellularPlan.runStream || wifiPlan.runStream,
        )
    }
}
