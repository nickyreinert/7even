package de.sevenapp.monitor.android.net

import android.net.Network
import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.TransferResult
import de.sevenapp.monitor.probe.Transport
import de.sevenapp.monitor.probe.TransportResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * The one place the engine touches the network on Android.
 *
 * Ktor with the OkHttp engine, per Ktor's own recommended pairing. The iOS
 * counterpart will be the same class against the Darwin engine, which wraps
 * NSURLSession — that symmetry is the reason [Transport] is this narrow.
 */
class KtorTransport(
    private val client: HttpClient = defaultClient(),
) : Transport {

    /**
     * Round-trip time to **response headers**, not to a completed body.
     *
     * That is the definition deliberately chosen: the probe exists to answer
     * "did the far end answer, and how quickly", and including body transfer
     * would make the figure depend on payload size and bandwidth rather than on
     * latency. The body is still drained afterwards, outside the measurement —
     * an unread body holds its connection open in the pool, which on a probe
     * that runs every three seconds leaks sockets steadily.
     */
    override suspend fun timedGet(url: String, timeoutMs: Long): TransportResult = try {
        var response: HttpResponse? = null
        val nanos = measureNanoTime {
            response = withTimeout(timeoutMs) {
                client.get(url) {
                    // A cached 200 measures the disk, not the network.
                    header("Cache-Control", "no-cache, no-store")
                    header("Pragma", "no-cache")
                }
            }
        }
        val res = response!!
        val ok = res.status.isSuccess()
        // Drain regardless of status: an error body needs releasing too.
        runCatching { withTimeout(timeoutMs) { res.bodyAsChannel().discard() } }
        // Any HTTP response used to count as a reachable link. A captive
        // portal's 302, a 429, or a 500 all prove *something* answered, but
        // none of them prove the connection works — and counting them as
        // successful probes is what hides an outage behind a green chart.
        if (ok) TransportResult.Ok(nanos / 1_000_000.0) else TransportResult.Failed(FailureReason.HTTP_ERROR)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        TransportResult.Failed(t.toFailureReason())
    }

    /**
     * A download is [TransferResult.Ok] only for a 2xx response of exactly
     * [expectBytes].
     *
     * Buffering whatever arrived and calling promise-resolution a success is
     * how a 200-byte `429` body got recorded as a passing multi-megabyte
     * transfer. Status and length are both part of "did this size get through".
     */
    override suspend fun download(url: String, expectBytes: Int, timeoutMs: Long): TransferResult {
        var received = 0L
        val started = System.nanoTime()
        return try {
            var status: io.ktor.http.HttpStatusCode? = null
            withTimeout(timeoutMs) {
                val response = client.get(url) {
                    header("Cache-Control", "no-cache, no-store")
                }
                status = response.status
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    received += n
                }
            }
            when {
                status?.isSuccess() != true -> TransferResult.Failed(FailureReason.HTTP_ERROR)
                received == expectBytes.toLong() -> TransferResult.Ok(received, elapsedMsSince(started))
                // A short or over-long body is not the transfer that was asked
                // for. It is still evidence of *some* throughput, so it is
                // reported as partial rather than discarded.
                else -> TransferResult.Partial(received, elapsedMsSince(started), FailureReason.HTTP_ERROR)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Bytes that did arrive still measure something real — the web app
            // makes the same call when a stream test stalls mid-phase.
            if (received > 0) {
                TransferResult.Partial(received, elapsedMsSince(started), t.toFailureReason())
            } else {
                TransferResult.Failed(t.toFailureReason())
            }
        }
    }

    override suspend fun upload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        // Random, not zeros: a compressible payload measures the compressor.
        val payload = ByteArray(bytes).also { Random.nextBytes(it) }
        val started = System.nanoTime()
        return try {
            val response = withTimeout(timeoutMs) {
                client.post(url) {
                    header("Content-Type", "application/octet-stream")
                    setBody(payload)
                }
            }
            if (response.status.isSuccess()) {
                TransferResult.Ok(bytes.toLong(), elapsedMsSince(started))
            } else {
                TransferResult.Failed(FailureReason.HTTP_ERROR)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Unlike download, a failed upload gives no trustworthy partial
            // count: bytes handed to the socket are not bytes the peer received.
            TransferResult.Failed(t.toFailureReason())
        }
    }

    /**
     * Releases the engine's connection pool and dispatcher threads.
     *
     * A live test builds a transport bound to one specific [Network]; leaking
     * it keeps that binding — and its sockets — alive past the run.
     */
    fun close() {
        runCatching { client.close() }
    }

    private fun elapsedMsSince(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun Throwable.toFailureReason(): FailureReason = when (this) {
        is TimeoutCancellationException -> FailureReason.TIMEOUT
        is UnknownHostException -> FailureReason.DNS
        is SSLException -> FailureReason.TLS
        is IOException -> FailureReason.NO_NETWORK
        else -> FailureReason.UNKNOWN
    }

    companion object {
        /**
         * @param network when non-null, every socket this client opens is bound to
         *   that specific network (via [Network.socketFactory]) rather than
         *   whichever one the OS currently has active — see
         *   [de.sevenapp.monitor.android.net.NetworkBinder], which is how a
         *   user's explicit Wi-Fi/mobile choice becomes a real routing
         *   decision instead of just a label on the result.
         */
        fun defaultClient(network: Network? = null): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                if (network != null) {
                    config {
                        socketFactory(network.socketFactory)
                    }
                }
            }
            install(HttpTimeout) {
                // Per-call timeouts are enforced by withTimeout above; these are
                // a backstop so a wedged socket cannot outlive the worker.
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}
