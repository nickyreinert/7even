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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.random.Random

/**
 * The mobile counterpart of the web app's persistent WebSocket to the
 * ws-speedtest Worker (src/index.js): one connection, driven through repeated
 * back-to-back download/upload rounds for a whole streaming phase — matching
 * the Worker's ping/down_start/down_end/up_start/up_end protocol — rather than
 * one-shot fixed-size requests.
 *
 * Two properties this type is responsible for:
 *
 * - **Round correlation.** Every round carries a unique id that the Worker
 *   echoes in `down_end`/`up_ack`. Without it, an acknowledgement that arrived
 *   after round N timed out was accepted as round N+1's, so a stalled link
 *   could report a pass for a round that never completed.
 * - **A connection is not reused after a failed round.** A timed-out round
 *   leaves unread frames queued on the socket. Closing and reconnecting is the
 *   only way to guarantee the next round starts from a clean stream, and it is
 *   also how the client recovers when the Worker closes a connection that hit
 *   its byte or lifetime quota.
 */
class WsLiveTestClient(
    private val wsUrl: String,
    private val network: Network? = null,
    /**
     * Called for each connection attempt rather than once, because the native
     * handshake token is time-limited: a reconnect an hour into a long session
     * needs a freshly signed one.
     */
    private val authHeaderProvider: suspend () -> Pair<String, String>? = { null },
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
    private var roundCounter = 0L

    /** Opens the persistent connection every round below runs over. */
    suspend fun connect() {
        val header = authHeaderProvider()
        session = client.webSocketSession {
            url(wsUrl)
            header?.let { (name, value) -> header(name, value) }
        }
    }

    /**
     * Opens a connection if there isn't a usable one.
     *
     * Called before each phase so a round that poisoned the previous socket —
     * or a Worker-side quota close — does not fail every subsequent phase.
     */
    suspend fun ensureConnected() {
        val current = session
        if (current != null && current.isActive()) return
        dropSession()
        connect()
    }

    private fun DefaultClientWebSocketSession.isActive(): Boolean =
        !incoming.isClosedForReceive && !outgoing.isClosedForSend

    private suspend fun dropSession() {
        val current = session ?: return
        session = null
        runCatching { current.close() }
    }

    suspend fun close() {
        dropSession()
        client.close()
    }

    /** Ids only need to be unique within one connection's lifetime. */
    private fun nextRoundId(): String = "r${++roundCounter}"

    /** Round ids in the frame, or null when the frame is not the expected type. */
    private fun matchingFrame(frame: Frame, expectedType: String, roundId: String): JSONObject? {
        if (frame !is Frame.Text) return null
        val json = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: return null
        if (json.optString("type") != expectedType) return null
        // A frame with no id at all is accepted so a client update can ship
        // ahead of the Worker; a frame with the *wrong* id never is, because
        // that is precisely the stale acknowledgement being guarded against.
        val id = json.optString("id", "")
        if (id.isNotEmpty() && id != roundId) return null
        return json
    }

    /**
     * Runs back-to-back download rounds of [roundBytes] each until
     * [episodeDurationMs] has elapsed, calling [onProgress] roughly every
     * [progressThrottleMs] with the bytes/elapsed *since the last call* — an
     * instantaneous rate rather than a cumulative average, so a live chart fed
     * from it shows what's happening right now.
     *
     * The episode deadline is a hard boundary, enforced *within* a round rather
     * than only between rounds: each round gets whatever time is left, so a
     * 10-second phase ends after 10 seconds even if a round would otherwise be
     * allowed to stall for its full 30-second round timeout. Bytes transferred
     * before the deadline still count.
     */
    suspend fun runDownEpisode(
        roundBytes: Int,
        episodeDurationMs: Long,
        roundTimeoutMs: Long,
        progressThrottleMs: Long,
        /** Hard byte ceiling for this episode, or null for none. See TransferPlan. */
        maxBytes: Long? = null,
        onProgress: (bytes: Long, elapsedMs: Double) -> Unit,
    ): EpisodeResult {
        val episodeStart = System.nanoTime()
        var totalBytes = 0L
        var sinceLastSample = 0L
        var lastSampleAt = episodeStart

        try {
            while (elapsedMs(episodeStart) < episodeDurationMs) {
                // The byte ceiling is checked between rounds: a round is at
                // most roundBytes, so the overshoot is bounded and known.
                if (maxBytes != null && totalBytes >= maxBytes) break
                currentCoroutineContext().ensureActive()
                val s = session ?: return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.NO_NETWORK)
                val budget = roundBudgetMs(episodeStart, episodeDurationMs, roundTimeoutMs)
                if (budget <= 0) break
                val roundId = nextRoundId()

                val completed = withTimeoutOrNull(budget) {
                    s.send(Frame.Text(JSONObject().put("type", "down_start").put("bytes", roundBytes).put("id", roundId).toString()))
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
                            else -> if (matchingFrame(frame, "down_end", roundId) != null) return@withTimeoutOrNull true
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }

                if (completed != true) {
                    // Either way the socket is now mid-transfer from the
                    // server's point of view: frames it already queued are
                    // unread, and its protocol state machine still says
                    // "download in progress". Reusing it for the next phase
                    // would send a command the server rejects as a violation
                    // and close the connection. Drop it and let ensureConnected
                    // open a clean one.
                    dropSession()
                    // Out of episode time is normal completion with whatever
                    // arrived; out of round time while the episode still had
                    // budget is a genuine stall.
                    if (elapsedMs(episodeStart) >= episodeDurationMs) break
                    return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.TIMEOUT)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            dropSession()
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
        /** Hard byte ceiling for this episode, or null for none. See TransferPlan. */
        maxBytes: Long? = null,
        onProgress: (bytes: Long, elapsedMs: Double) -> Unit,
    ): EpisodeResult {
        val episodeStart = System.nanoTime()
        var totalBytes = 0L
        var sinceLastSample = 0L
        var lastSampleAt = episodeStart

        try {
            while (elapsedMs(episodeStart) < episodeDurationMs) {
                // The byte ceiling is checked between rounds: a round is at
                // most roundBytes, so the overshoot is bounded and known.
                if (maxBytes != null && totalBytes >= maxBytes) break
                currentCoroutineContext().ensureActive()
                val s = session ?: return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.NO_NETWORK)
                val budget = roundBudgetMs(episodeStart, episodeDurationMs, roundTimeoutMs)
                if (budget <= 0) break
                val roundId = nextRoundId()

                val completed = withTimeoutOrNull(budget) {
                    s.send(
                        Frame.Text(
                            JSONObject().put("type", "up_start").put("bytes", roundBytes).put("id", roundId).toString(),
                        ),
                    )

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

                    s.send(Frame.Text(JSONObject().put("type", "up_end").put("bytesSent", sent).put("id", roundId).toString()))
                    while (true) {
                        if (matchingFrame(s.incoming.receive(), "up_ack", roundId) != null) return@withTimeoutOrNull true
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }

                if (completed != true) {
                    // Same reasoning as the download side: the server is still
                    // in its "upload in progress" state and has unread frames,
                    // so this connection cannot be reused for the next phase.
                    dropSession()
                    if (elapsedMs(episodeStart) >= episodeDurationMs) break
                    return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), FailureReason.TIMEOUT)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            dropSession()
            return EpisodeResult.Failed(totalBytes, elapsedMs(episodeStart), t.toFailureReason())
        }

        if (sinceLastSample > 0) onProgress(sinceLastSample, (System.nanoTime() - lastSampleAt) / 1_000_000.0)
        return EpisodeResult.Ok(totalBytes, elapsedMs(episodeStart))
    }

    /** One exact-size download used by the reliability sweep. */
    suspend fun runDownTransfer(bytes: Int, timeoutMs: Long): EpisodeResult {
        ensureConnected()
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.NO_NETWORK)
        val started = System.nanoTime()
        var received = 0L
        val roundId = nextRoundId()
        return try {
            val completed = withTimeoutOrNull(timeoutMs) {
                s.send(Frame.Text(JSONObject().put("type", "down_start").put("bytes", bytes).put("id", roundId).toString()))
                while (true) {
                    when (val frame = s.incoming.receive()) {
                        is Frame.Binary -> received += frame.data.size
                        else -> if (matchingFrame(frame, "down_end", roundId) != null) return@withTimeoutOrNull true
                    }
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            // A rung passes only on an exact-size delivery. A short body is a
            // failure for the sweep's purpose, which is "does this size get
            // through" — not "did something arrive".
            if (completed == true && received == bytes.toLong()) {
                EpisodeResult.Ok(received, elapsedMs(started))
            } else {
                dropSession()
                EpisodeResult.Failed(received, elapsedMs(started), FailureReason.TIMEOUT)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            dropSession()
            EpisodeResult.Failed(received, elapsedMs(started), t.toFailureReason())
        }
    }

    /** One exact-size upload used by the reliability sweep, verified by the Worker's byte acknowledgement. */
    suspend fun runUpTransfer(bytes: Int, timeoutMs: Long): EpisodeResult {
        ensureConnected()
        val s = session ?: return EpisodeResult.Failed(0, 0.0, FailureReason.NO_NETWORK)
        val started = System.nanoTime()
        val roundId = nextRoundId()
        return try {
            val acknowledged = withTimeoutOrNull(timeoutMs) {
                s.send(Frame.Text(JSONObject().put("type", "up_start").put("bytes", bytes).put("id", roundId).toString()))
                var sent = 0
                while (sent < bytes) {
                    val n = minOf(UP_CHUNK_SIZE, bytes - sent)
                    s.send(Frame.Binary(fin = true, data = Random.nextBytes(n)))
                    sent += n
                }
                s.send(Frame.Text(JSONObject().put("type", "up_end").put("bytesSent", sent).put("id", roundId).toString()))
                while (true) {
                    val ack = matchingFrame(s.incoming.receive(), "up_ack", roundId)
                    if (ack != null) return@withTimeoutOrNull ack.optLong("bytesReceived", -1)
                }
                @Suppress("UNREACHABLE_CODE") -1L
            }
            if (acknowledged == bytes.toLong()) {
                EpisodeResult.Ok(bytes.toLong(), elapsedMs(started))
            } else {
                dropSession()
                EpisodeResult.Failed(0, elapsedMs(started), FailureReason.TIMEOUT)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            dropSession()
            EpisodeResult.Failed(0, elapsedMs(started), t.toFailureReason())
        }
    }

    /** Whatever is left of the episode, never more than one round's stall allowance. */
    private fun roundBudgetMs(episodeStart: Long, episodeDurationMs: Long, roundTimeoutMs: Long): Long =
        minOf(roundTimeoutMs, (episodeDurationMs - elapsedMs(episodeStart)).toLong())

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
