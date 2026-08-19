package de.sevenapp.monitor.android.net

import android.net.Network
import de.sevenapp.monitor.probe.FailureReason
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.random.Random

/**
 * The mobile counterpart of the web app's persistent WebSocket to the
 * ws-speedtest Worker (src/index.js): one connection, driven through
 * repeated back-to-back download/upload rounds for a whole streaming
 * episode — matching the Worker's ping/down_start/down_end/up_start/up_end
 * protocol — rather than one-shot fixed-size requests.
 */
class WsLiveTestClient(
    private val wsUrl: String,
    network: Network? = null,
) {
    sealed interface EpisodeResult {
        val bytes: Long
        val elapsedMs: Double

        data class Ok(override val bytes: Long, override val elapsedMs: Double) : EpisodeResult
        data class Failed(override val bytes: Long, override val elapsedMs: Double, val reason: FailureReason) : EpisodeResult
    }

    private val client: HttpClient = HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            if (network != null) {
                config { socketFactory(network.socketFactory) }
            }
        }
    }

    private var session: DefaultClientWebSocketSession? = null

    /** Opens the one persistent connection every episode below runs over. */
    suspend fun connect(authHeader: Pair<String, String>?) {
        session = client.webSocketSession {
            url(wsUrl)
            authHeader?.let { (name, value) -> header(name, value) }
        }
    }

    suspend fun close() {
        runCatching { session?.close() }
        session = null
        client.close()
    }

    /**
     * Runs back-to-back download rounds of [roundBytes] each for
     * [episodeDurationMs], calling [onProgress] roughly every
     * [progressThrottleMs] with the bytes/elapsed *since the last call* — an
     * instantaneous rate rather than a cumulative average, so a live chart
     * fed from it shows what's happening right now, the way the web app's
     * live-rate chart does.
     */
    suspend fun runDownEpisode(
        roundBytes: Int,
        episodeDurationMs: Long,
        roundTimeoutMs: Long,
        progressThrottleMs: Long,
        onProgress: (bytes: Long, elapsedMs: Double) -> Unit,
    ): EpisodeResult {
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.UNKNOWN)
        val episodeStart = System.nanoTime()
        var totalBytes = 0L
        var sinceLastSample = 0L
        var lastSampleAt = episodeStart

        try {
            while (elapsedMs(episodeStart) < episodeDurationMs) {
                val completed = withTimeoutOrNull(roundTimeoutMs) {
                    s.send(Frame.Text(JSONObject().put("type", "down_start").put("bytes", roundBytes).toString()))
                    while (true) {
                        when (val frame = s.incoming.receive()) {
                            is Frame.Binary -> {
                                val n = frame.data.size
                                totalBytes += n
                                sinceLastSample += n
                                val now = System.nanoTime()
                                if ((now - lastSampleAt) / 1_000_000.0 >= progressThrottleMs) {
                                    onProgress(sinceLastSample, (now - lastSampleAt) / 1_000_000.0)
                                    sinceLastSample = 0
                                    lastSampleAt = now
                                }
                            }
                            is Frame.Text -> {
                                val type = runCatching { JSONObject(frame.readText()).optString("type") }.getOrNull()
                                if (type == "down_end") return@withTimeoutOrNull true
                            }
                            else -> {}
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (completed != true) {
                    return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.TIMEOUT)
                }
            }
        } catch (t: Throwable) {
            return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), t.toFailureReason())
        }

        if (sinceLastSample > 0) onProgress(sinceLastSample, (System.nanoTime() - lastSampleAt) / 1_000_000.0)
        return EpisodeResult.Ok(totalBytes, elapsedMs(episodeStart))
    }

    /** Same shape as [runDownEpisode], upload direction: send rounds, wait for each round's `up_ack`. */
    suspend fun runUpEpisode(
        roundBytes: Int,
        episodeDurationMs: Long,
        roundTimeoutMs: Long,
        progressThrottleMs: Long,
        onProgress: (bytes: Long, elapsedMs: Double) -> Unit,
    ): EpisodeResult {
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.UNKNOWN)
        val episodeStart = System.nanoTime()
        var totalBytes = 0L
        var sinceLastSample = 0L
        var lastSampleAt = episodeStart

        try {
            while (elapsedMs(episodeStart) < episodeDurationMs) {
                val completed = withTimeoutOrNull(roundTimeoutMs) {
                    s.send(Frame.Text(JSONObject().put("type", "up_start").toString()))

                    var sent = 0
                    while (sent < roundBytes) {
                        val n = minOf(UP_CHUNK_SIZE, roundBytes - sent)
                        s.send(Frame.Binary(fin = true, data = Random.nextBytes(n)))
                        sent += n
                        totalBytes += n
                        sinceLastSample += n
                        val now = System.nanoTime()
                        if ((now - lastSampleAt) / 1_000_000.0 >= progressThrottleMs) {
                            onProgress(sinceLastSample, (now - lastSampleAt) / 1_000_000.0)
                            sinceLastSample = 0
                            lastSampleAt = now
                        }
                    }

                    s.send(Frame.Text(JSONObject().put("type", "up_end").put("bytesSent", sent).toString()))
                    while (true) {
                        val frame = s.incoming.receive()
                        if (frame is Frame.Text) {
                            val type = runCatching { JSONObject(frame.readText()).optString("type") }.getOrNull()
                            if (type == "up_ack") return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                if (completed != true) {
                    return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.TIMEOUT)
                }
            }
        } catch (t: Throwable) {
            return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), t.toFailureReason())
        }

        if (sinceLastSample > 0) onProgress(sinceLastSample, (System.nanoTime() - lastSampleAt) / 1_000_000.0)
        return EpisodeResult.Ok(totalBytes, elapsedMs(episodeStart))
    }

    /** One exact-size download used by the reliability sweep. */
    suspend fun runDownTransfer(bytes: Int, timeoutMs: Long): EpisodeResult {
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.UNKNOWN)
        val started = System.nanoTime()
        var received = 0L
        return try {
            val completed = withTimeoutOrNull(timeoutMs) {
                s.send(Frame.Text(JSONObject().put("type", "down_start").put("bytes", bytes).toString()))
                while (true) {
                    when (val frame = s.incoming.receive()) {
                        is Frame.Binary -> received += frame.data.size
                        is Frame.Text -> if (JSONObject(frame.readText()).optString("type") == "down_end") return@withTimeoutOrNull true
                        else -> Unit
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (completed == true && received == bytes.toLong()) EpisodeResult.Ok(received, elapsedMs(started))
            else EpisodeResult.Failed(received, elapsedMs(started), FailureReason.TIMEOUT)
        } catch (t: Throwable) {
            EpisodeResult.Failed(received, elapsedMs(started), t.toFailureReason())
        }
    }

    /** One exact-size upload used by the reliability sweep, verified by the Worker's byte acknowledgement. */
    suspend fun runUpTransfer(bytes: Int, timeoutMs: Long): EpisodeResult {
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.UNKNOWN)
        val started = System.nanoTime()
        return try {
            val acknowledged = withTimeoutOrNull(timeoutMs) {
                s.send(Frame.Text(JSONObject().put("type", "up_start").toString()))
                var sent = 0
                while (sent < bytes) {
                    val n = minOf(UP_CHUNK_SIZE, bytes - sent)
                    s.send(Frame.Binary(fin = true, data = Random.nextBytes(n)))
                    sent += n
                }
                s.send(Frame.Text(JSONObject().put("type", "up_end").put("bytesSent", sent).toString()))
                while (true) {
                    val frame = s.incoming.receive()
                    if (frame is Frame.Text) {
                        val message = JSONObject(frame.readText())
                        if (message.optString("type") == "up_ack") return@withTimeoutOrNull message.optLong("bytesReceived", -1)
                    }
                }
                @Suppress("UNREACHABLE_CODE") -1L
            }
            if (acknowledged == bytes.toLong()) EpisodeResult.Ok(bytes.toLong(), elapsedMs(started))
            else EpisodeResult.Failed(0, elapsedMs(started), FailureReason.TIMEOUT)
        } catch (t: Throwable) {
            EpisodeResult.Failed(0, elapsedMs(started), t.toFailureReason())
        }
    }

    private fun elapsedMs(startNanos: Long): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    private fun Throwable.toFailureReason(): FailureReason = when (this) {
        is java.net.UnknownHostException -> FailureReason.DNS
        is javax.net.ssl.SSLException -> FailureReason.TLS
        is java.io.IOException -> FailureReason.NO_NETWORK
        else -> FailureReason.UNKNOWN
    }

    companion object {
        private const val UP_CHUNK_SIZE = 65_536
    }
}
