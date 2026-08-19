package de.sevenapp.monitor.core

/**
 * How the device was connected when a sample was taken.
 *
 * Reports segment by this: a week that mixes home Wi-Fi with commuting LTE
 * averages into noise, and the average is the one number nobody can act on.
 * Deliberately coarse — labelling the *specific* network (SSID) needs location
 * permission on both platforms, which is a privacy ask this doesn't make.
 */
enum class NetworkType { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

/**
 * Which network a user-triggered test should run on.
 *
 * AUTO defers to whatever the OS currently has active — the historical
 * behaviour. WIFI/CELLULAR mean the platform must actually bind the test's
 * sockets to that transport (Android's ConnectivityManager.requestNetwork),
 * not just read which one happens to be active: someone testing "is my
 * mobile data slow right now" while sitting on home Wi-Fi needs the app to
 * genuinely route over cellular, not silently measure Wi-Fi instead.
 */
enum class NetworkPreference { AUTO, WIFI, CELLULAR }

/**
 * Probe tiers, cheapest first. The foreground web app can afford to stream
 * continuously for 30s; a background job running every 15 minutes cannot —
 * it would cost gigabytes of cellular data a month. So background measurement
 * is tiered, and the expensive tiers are gated on network and power.
 */
enum class ProbeTier {
    /** Reachability + latency. ~3KB. Every cycle. */
    REACHABILITY,

    /** Light throughput, small fixed payloads. ~400KB. Gated. */
    THROUGHPUT_LIGHT,

    /** Full sweep + stream test. ~30MB. Wi-Fi and charging only. */
    THROUGHPUT_FULL,
}

/** One reachability probe: either a round-trip time, or a failure. */
data class PingSample(
    val atEpochMs: Long,
    val rttMs: Double?,
    val networkType: NetworkType,
) {
    val ok: Boolean get() = rttMs != null
}

/** One throughput measurement. Either direction may be absent. */
data class ThroughputSample(
    val atEpochMs: Long,
    val downMbps: Double?,
    val upMbps: Double?,
    val networkType: NetworkType,
    val tier: ProbeTier,
    /** True when the number came from a run that stalled or was cut short. */
    val partial: Boolean = false,
)

/**
 * A stretch where the connection was considered down.
 *
 * Defined structurally from a run of consecutive failures rather than stored
 * as a separate flag — see [DropDetector]. `endedAtEpochMs == null` means the
 * drop is still open.
 */
data class DropEvent(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
) {
    fun durationMs(nowEpochMs: Long): Long = (endedAtEpochMs ?: nowEpochMs) - startedAtEpochMs
    val ongoing: Boolean get() = endedAtEpochMs == null
}

/** Injected so tests are not at the mercy of the wall clock. */
fun interface Clock {
    fun nowEpochMs(): Long
}
