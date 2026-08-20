package de.sevenapp.monitor.android.livetest

import android.content.Context
import de.sevenapp.monitor.android.net.KtorTransport
import de.sevenapp.monitor.android.net.NativeAuth
import de.sevenapp.monitor.android.net.NetworkBinder
import de.sevenapp.monitor.android.net.WsLiveTestClient
import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.data.MonitorStore
import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.LivePhase
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.TransportResult
import de.sevenapp.monitor.probe.mbpsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Runs the foreground test: a ping phase, then a continuous download stream,
 * then a continuous upload stream, then one size sweep — each of the three
 * phases lasting exactly [ProbeConfig.liveTestPhaseDurationMs].
 *
 * The phase structure is the whole point. An earlier version treated the
 * configured duration as a single whole-test deadline while the stream episode
 * length stayed hardcoded at 15 seconds, so a 10-second test spent all ten
 * seconds inside the download episode and never reached upload at all: the
 * upload card stayed empty on every short run. Each phase now owns the
 * configured duration, and [withTimeout] enforces it as a hard boundary rather
 * than a minimum, so an in-flight transfer is cancelled at the deadline instead
 * of running past the countdown the user is watching.
 *
 * Deliberately Android-specific rather than living in :shared. Unlike
 * [de.sevenapp.monitor.probe.ProbeEngine], which the platform worker only
 * feeds device state into, this drives a live WebSocket connection with a
 * UI-facing progress callback — exactly the platform glue that
 * [de.sevenapp.monitor.probe.Transport]'s own doc comment says stays out of
 * :shared.
 */
class LiveTestRunner(
    private val context: Context,
    private val store: MonitorStore,
    private val probeConfig: ProbeConfig,
    /** Used only when [networkPreference] is [NetworkPreference.AUTO]. */
    private val fallbackNetworkType: NetworkType,
    private val runStream: Boolean = true,
    private val runSweep: Boolean = probeConfig.liveTestSweepEnabled,
    private val wifiSsid: String? = null,
    /**
     * Which connection to bind to.
     *
     * Separate from [ProbeConfig.preferredTestNetwork] on purpose: that field is
     * the *manual* test's choice, and reusing it for background work meant one
     * manual "test on mobile data" silently moved every future unattended run
     * onto cellular. Automatic callers pass [NetworkPreference.AUTO].
     */
    private val networkPreference: NetworkPreference = probeConfig.preferredTestNetwork,
    /**
     * Hard ceiling on stream bytes, or null for no ceiling.
     *
     * A duration-based stream has no inherent size, so this is what makes an
     * automatic run's cost knowable in advance — see
     * [de.sevenapp.monitor.probe.TransferPlan].
     */
    private val maxTransferBytes: Long? = null,
    /** The OS's metering verdict; falls back to "cellular is metered" when absent. */
    private val isMeteredOverride: Boolean? = null,
    /**
     * When false the caller is doing its own data accounting (the automatic
     * worker reserves the plan's maximum up front and reconciles afterwards),
     * so this must not charge the same bytes a second time.
     */
    private val accountBytes: Boolean = true,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {

    private val effectiveNetworkType: NetworkType = when (networkPreference) {
        NetworkPreference.WIFI -> NetworkType.WIFI
        NetworkPreference.CELLULAR -> NetworkType.CELLULAR
        NetworkPreference.AUTO -> fallbackNetworkType
    }

    /** Every byte this run put on the wire, in either direction. */
    private var bytesMovedTotal = 0L

    private val budgetExhausted: Boolean
        get() = maxTransferBytes != null && bytesMovedTotal >= maxTransferBytes

    private val liveConfig = LiveTestConfig(
        phaseDurationMs = probeConfig.liveTestPhaseDurationMs,
        sweepEnabled = runSweep,
        sweepSteps = if (effectiveNetworkType == NetworkType.CELLULAR) probeConfig.mobileLiveTestSweepSteps else probeConfig.wifiLiveTestSweepSteps,
    )

    private val ssidForSamples: String?
        get() = wifiSsid.takeIf { effectiveNetworkType == NetworkType.WIFI }

    /** @return the total bytes this run moved, for the caller's data accounting. */
    suspend fun runSession(onSample: (LiveSample) -> Unit): Long {
        emitPhase(onSample, LivePhase.CONNECTING, step = 0, durationMs = null)
        NetworkBinder.withNetwork(context, networkPreference) { network ->
            val httpTransport = KtorTransport(KtorTransport.defaultClient(network))
            val ws = if (runStream || runSweep) {
                WsLiveTestClient(probeConfig.streamUrl, network) { NativeAuth.header() }
            } else {
                null
            }

            try {
                // Connect before the ping phase even though nothing needs the
                // socket yet: a bad endpoint or a rejected handshake should
                // surface as "the test could not start" immediately, not after
                // the user has watched a full ping phase count down first.
                ws?.ensureConnected()

                do {
                    // Ping first, alone. A latency figure taken while a stream
                    // is saturating the link measures the stream, not the link.
                    runPingPhase(httpTransport, onSample)

                    if (ws != null && runStream) runStreamPhases(ws, httpTransport, onSample)
                    if (ws != null && runSweep) runWebSocketSweep(ws, onSample)
                    // "Unlimited" has no per-phase deadline to divide up, so it
                    // cycles the same phases until the user stops it.
                } while (liveConfig.isUnlimited && !budgetExhausted)
            } finally {
                httpTransport.close()
                ws?.close()
            }
        }
        return bytesMovedTotal
    }

    /**
     * Runs [body] for exactly one phase length and returns normally at the
     * deadline.
     *
     * A phase timeout is normal completion, not a failure — but only the
     * timeout this function installed is. [TimeoutCancellationException] from a
     * nested `withTimeout` (a round stall, say) is deliberately not swallowed
     * here, and neither is an outer cancellation from Stop: `withTimeout`
     * rethrows those rather than resuming, which is what stops a cancelled run
     * from quietly proceeding to the next phase.
     */
    private suspend fun runPhase(durationMs: Long, body: suspend () -> Unit) {
        try {
            withTimeout(durationMs) { body() }
        } catch (e: TimeoutCancellationException) {
            // Reaching the deadline is how a phase is supposed to end.
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun runPingPhase(transport: KtorTransport, onSample: (LiveSample) -> Unit) {
        emitPhase(onSample, LivePhase.PING, step = 1, durationMs = liveConfig.effectivePhaseDurationMs)
        runPhase(liveConfig.effectivePhaseDurationMs) { pingLoop(transport, onSample) }
    }

    private suspend fun pingLoop(transport: KtorTransport, onSample: (LiveSample) -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = transport.timedGet(probeConfig.traceUrl, liveConfig.pingTimeoutMs)
            val rtt = (result as? TransportResult.Ok)?.rttMs
            val at = clock.nowEpochMs()
            onSample(LiveSample.Ping(at, rtt))
            store.appendPings(listOf(PingSample(at, rtt, effectiveNetworkType, ssidForSamples)))
            delay(liveConfig.pingIntervalMs)
        }
    }

    /**
     * Download for one phase, then upload for one phase, with the ping loop
     * running underneath both so the latency chart keeps moving — the same
     * shape as the web app's live view.
     */
    private suspend fun runStreamPhases(
        ws: WsLiveTestClient,
        httpTransport: KtorTransport,
        onSample: (LiveSample) -> Unit,
    ) = coroutineScope {
        val phaseMs = liveConfig.effectivePhaseDurationMs
        val pingJob = launch { pingLoop(httpTransport, onSample) }
        try {
            emitPhase(onSample, LivePhase.DOWNLOAD_STREAM, step = 2, durationMs = phaseMs)
            // Half the ceiling per direction, so an upload phase still has
            // allowance left after a fast download phase.
            val perDirectionCap = maxTransferBytes?.let { it / 2 }
            var down: WsLiveTestClient.EpisodeResult =
                WsLiveTestClient.EpisodeResult.Failed(0, 0.0, FailureReason.TIMEOUT)
            // The episode enforces the phase deadline itself, and returns the
            // bytes it managed to move. This outer timeout is only a backstop
            // for a wedged socket, so it is given a grace margin rather than
            // racing the episode's own clean finish and discarding its result.
            runPhase(phaseMs + STREAM_PHASE_GRACE_MS) {
                ws.ensureConnected()
                down = ws.runDownEpisode(
                    roundBytes = liveConfig.downRoundBytes,
                    episodeDurationMs = phaseMs,
                    roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
                    progressThrottleMs = liveConfig.progressThrottleMs,
                    maxBytes = perDirectionCap,
                ) { bytes, elapsedMs ->
                    mbpsOf(bytes, elapsedMs)?.let {
                        onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.DOWN, it))
                    }
                }
            }

            emitPhase(onSample, LivePhase.UPLOAD_STREAM, step = 3, durationMs = phaseMs)
            var up: WsLiveTestClient.EpisodeResult =
                WsLiveTestClient.EpisodeResult.Failed(0, 0.0, FailureReason.TIMEOUT)
            runPhase(phaseMs + STREAM_PHASE_GRACE_MS) {
                ws.ensureConnected()
                up = ws.runUpEpisode(
                    roundBytes = liveConfig.upRoundBytes,
                    episodeDurationMs = phaseMs,
                    roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
                    progressThrottleMs = liveConfig.progressThrottleMs,
                    maxBytes = perDirectionCap,
                ) { bytes, elapsedMs ->
                    mbpsOf(bytes, elapsedMs)?.let {
                        onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.UP, it))
                    }
                }
            }

            persistEpisode(down, up)
        } finally {
            pingJob.cancelAndJoin()
        }
    }

    private suspend fun runWebSocketSweep(ws: WsLiveTestClient, onSample: (LiveSample) -> Unit) {
        ws.ensureConnected()
        emitPhase(onSample, LivePhase.DOWNLOAD_SWEEP, step = 4, durationMs = null)
        val down = runWebSocketSweepDirection(ws, SweepRunner.Direction.DOWN)
        store.saveLatestSweep(SweepRunner.Direction.DOWN, down)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.DOWN, down))

        emitPhase(onSample, LivePhase.UPLOAD_SWEEP, step = 5, durationMs = null)
        val up = runWebSocketSweepDirection(ws, SweepRunner.Direction.UP)
        store.saveLatestSweep(SweepRunner.Direction.UP, up)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.UP, up))

        chargeBytes(sweepBytesMoved(down) + sweepBytesMoved(up))
    }

    private suspend fun runWebSocketSweepDirection(
        ws: WsLiveTestClient,
        direction: SweepRunner.Direction,
    ): List<SweepResult> = liveConfig.sweepSteps.map { step ->
        val outcomes = mutableListOf<Boolean>()
        var durationTotal = 0.0
        var lastError: FailureReason? = null
        repeat(step.trials) {
            currentCoroutineContext().ensureActive()
            val result = when (direction) {
                SweepRunner.Direction.DOWN -> ws.runDownTransfer(step.bytes, liveConfig.sweepTimeoutMs)
                SweepRunner.Direction.UP -> ws.runUpTransfer(step.bytes, liveConfig.sweepTimeoutMs)
            }
            when (result) {
                is WsLiveTestClient.EpisodeResult.Ok -> {
                    outcomes += true
                    durationTotal += result.elapsedMs
                }
                is WsLiveTestClient.EpisodeResult.Failed -> {
                    outcomes += false
                    lastError = result.reason
                }
            }
        }
        val passed = outcomes.count { it }
        SweepResult(
            bytes = step.bytes,
            trials = step.trials,
            passCount = passed,
            avgDurationMs = if (passed > 0) durationTotal / passed else null,
            lastError = if (passed == step.trials) null else lastError,
            trialOutcomes = outcomes,
        )
    }

    /**
     * Attempted bytes, not successful ones. A failed 10MB rung still spent most
     * of that allowance on the wire, and a data budget that only counts
     * successes under-reports exactly when the connection is worst.
     */
    private fun sweepBytesMoved(results: List<SweepResult>): Long =
        results.sumOf { it.bytes.toLong() * it.trialOutcomes.size }

    /**
     * The OS's verdict when the caller supplied one.
     *
     * Inferring metering from "is it cellular" ignores a tethered or
     * user-marked Wi-Fi, which spends the same allowance. The fallback only
     * applies to a manual run started before device state was read.
     */
    private fun isMeteredRun(): Boolean = isMeteredOverride ?: (effectiveNetworkType == NetworkType.CELLULAR)

    /**
     * Records bytes against the running total, and against stored usage unless
     * the caller reserved them up front.
     */
    private suspend fun chargeBytes(bytes: Long) {
        if (bytes <= 0) return
        bytesMovedTotal += bytes
        if (accountBytes) store.addBytesUsed(bytes, isMeteredRun(), clock.nowEpochMs())
    }

    private fun emitPhase(onSample: (LiveSample) -> Unit, phase: LivePhase, step: Int, durationMs: Long?) {
        onSample(
            LiveSample.Phase(
                atEpochMs = clock.nowEpochMs(),
                phase = phase,
                step = step,
                totalSteps = liveConfig.totalSteps,
                durationMs = durationMs,
            ),
        )
    }

    private suspend fun persistEpisode(down: WsLiveTestClient.EpisodeResult, up: WsLiveTestClient.EpisodeResult) {
        val downMbps = if (down.bytes > 0) mbpsOf(down.bytes, down.elapsedMs) else null
        val upMbps = if (up.bytes > 0) mbpsOf(up.bytes, up.elapsedMs) else null
        val bytesMoved = down.bytes + up.bytes

        if (downMbps != null || upMbps != null) {
            store.appendThroughput(
                ThroughputSample(
                    atEpochMs = clock.nowEpochMs(),
                    downMbps = downMbps,
                    upMbps = upMbps,
                    networkType = effectiveNetworkType,
                    tier = ProbeTier.THROUGHPUT_FULL,
                    // Either direction failing, or either direction producing
                    // no rate at all, makes this an incomplete measurement.
                    partial = down is WsLiveTestClient.EpisodeResult.Failed ||
                        up is WsLiveTestClient.EpisodeResult.Failed ||
                        downMbps == null || upMbps == null,
                    ssid = ssidForSamples,
                ),
            )
        }
        chargeBytes(bytesMoved)
    }

    companion object {
        /**
         * Slack on the outer per-phase backstop. The stream client already
         * stops at the phase deadline; without this margin the backstop would
         * fire at the same instant and throw away the episode's numbers.
         */
        private const val STREAM_PHASE_GRACE_MS = 2_000L
    }
}
