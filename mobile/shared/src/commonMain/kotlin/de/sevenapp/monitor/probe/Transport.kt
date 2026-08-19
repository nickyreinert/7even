package de.sevenapp.monitor.probe

/**
 * The only way the engine touches the network.
 *
 * Everything platform-specific lives behind this: Ktor with the OkHttp engine
 * on Android, Darwin on iOS. Keeping it this narrow is what lets the engine be
 * tested deterministically — the tests drive a fake that returns scripted
 * timings and failures, so probe logic is verified without a network, a server,
 * or flakiness.
 */
interface Transport {

    /**
     * Timed GET whose body is fetched and discarded. Used for reachability.
     * Implementations must not follow a cache — a cached 200 measures nothing.
     */
    suspend fun timedGet(url: String, timeoutMs: Long): TransportResult

    /** Downloads [expectBytes] and reports how long it took and how much arrived. */
    suspend fun download(url: String, expectBytes: Int, timeoutMs: Long): TransferResult

    /** Uploads [bytes] of incompressible data and reports the same. */
    suspend fun upload(url: String, bytes: Int, timeoutMs: Long): TransferResult
}

sealed interface TransportResult {
    data class Ok(val rttMs: Double) : TransportResult
    data class Failed(val reason: FailureReason) : TransportResult
}

sealed interface TransferResult {
    data class Ok(val bytes: Long, val elapsedMs: Double) : TransferResult

    /**
     * A transfer that moved *some* bytes before failing still measures
     * something real, so partial progress is reported rather than discarded —
     * the same "best effort beats nothing" rule the web app applies when a
     * stream test stalls mid-phase.
     */
    data class Partial(val bytes: Long, val elapsedMs: Double, val reason: FailureReason) : TransferResult

    data class Failed(val reason: FailureReason) : TransferResult
}

enum class FailureReason { TIMEOUT, NO_NETWORK, DNS, TLS, HTTP_ERROR, CANCELLED, UNKNOWN }

/** Mbps from bytes and milliseconds. Null when the inputs cannot support a rate. */
fun mbpsOf(bytes: Long, elapsedMs: Double): Double? {
    if (bytes <= 0L || elapsedMs <= 0.0) return null
    return (bytes * 8.0) / (elapsedMs / 1000.0) / 1_000_000.0
}
