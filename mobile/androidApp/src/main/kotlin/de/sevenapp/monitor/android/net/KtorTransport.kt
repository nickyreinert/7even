package de.sevenapp.monitor.android.net

import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.TransferResult
import de.sevenapp.monitor.probe.Transport
import de.sevenapp.monitor.probe.TransportResult
import de.sevenapp.monitor.probe.mbpsOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
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
    private val onStreamProgress: (downMbps: Double?, upMbps: Double?) -> Unit = { _, _ -> },
) : Transport {

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
        if (res.status.isSuccess()) {
            TransportResult.Ok(nanos / 1_000_000.0)
        } else {
            TransportResult.Failed(FailureReason.HTTP_ERROR)
        }
    } catch (t: Throwable) {
        TransportResult.Failed(t.toFailureReason())
    }

    override suspend fun download(url: String, expectBytes: Int, timeoutMs: Long): TransferResult {
        var received = 0L
        val started = System.nanoTime()
        return try {
            withTimeout(timeoutMs) {
                val channel = client.get(url) {
                    header("Cache-Control", "no-cache, no-store")
                }.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n <= 0) break
                    received += n
                }
            }
            TransferResult.Ok(received, elapsedMsSince(started))
        } catch (t: Throwable) {
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
            // Unlike download, a failed upload gives no trustworthy partial
            // count: bytes handed to the socket are not bytes the peer received.
            TransferResult.Failed(t.toFailureReason())
        }
    }

    override suspend fun webSocketDownload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        var received = 0L
        val started = System.nanoTime()
        return try {
            withTimeout(timeoutMs) {
                client.webSocket(url) {
                    send(Frame.Text("{\"type\":\"down_start\",\"bytes\":$bytes}"))
                    while (true) {
                        when (val frame = incoming.receive()) {
                            is Frame.Binary -> {
                                received += frame.readBytes().size
                                onStreamProgress(mbpsOf(received, elapsedMsSince(started)), null)
                            }
                            is Frame.Text -> if (frame.readText().contains("\"down_end\"")) break
                            else -> Unit
                        }
                    }
                }
            }
            if (received > 0) TransferResult.Ok(received, elapsedMsSince(started))
            else TransferResult.Failed(FailureReason.HTTP_ERROR)
        } catch (t: Throwable) {
            if (received > 0) TransferResult.Partial(received, elapsedMsSince(started), t.toFailureReason())
            else TransferResult.Failed(t.toFailureReason())
        }
    }

    override suspend fun webSocketUpload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        val started = System.nanoTime()
        return try {
            withTimeout(timeoutMs) {
                client.webSocket(url) {
                    send(Frame.Text("{\"type\":\"up_start\"}"))
                    var remaining = bytes
                    while (remaining > 0) {
                        val chunk = ByteArray(minOf(64 * 1024, remaining)).also { Random.nextBytes(it) }
                        send(Frame.Binary(true, chunk))
                        remaining -= chunk.size
                        onStreamProgress(null, mbpsOf((bytes - remaining).toLong(), elapsedMsSince(started)))
                    }
                    send(Frame.Text("{\"type\":\"up_end\"}"))
                    while (true) {
                        val frame = incoming.receive()
                        if (frame is Frame.Text && frame.readText().contains("\"up_ack\"")) break
                    }
                }
            }
            TransferResult.Ok(bytes.toLong(), elapsedMsSince(started))
        } catch (t: Throwable) {
            TransferResult.Failed(t.toFailureReason())
        }
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
        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            install(WebSockets)
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
