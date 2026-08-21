package de.sevenapp.monitor.android.livetest

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
        // Both were hardcoded here. The ping timeout ignored the user's
        // configured value and defaulted to 2s, which a high-latency mobile
        // link fails on merely for being slow — producing a latency chart with
        // no successful probes on it at all.
        pingTimeoutMs = probeConfig.pingTimeoutMs,
        sweepTimeoutMs = probeConfig.sweepTimeoutMs(effectiveNetworkType),
        // Also used to be hardcoded to the Wi-Fi-sized default regardless of
        // network — see the doc comment on these fields in ProbeConfig for why
        // that broke both the upload chart and the download rate's accuracy on
        // a slow cellular link.
        downRoundBytes = probeConfig.downRoundBytes(effectiveNetworkType),
        upRoundBytes = probeConfig.upRoundBytes(effectiveNetworkType),
        streamRoundTimeoutMs = probeConfig.streamRoundTimeoutMs(effectiveNetworkType),
        sustainedProbeEnabled = probeConfig.sustainedProbeEnabled,
    )

    private val ssidForSamples: String?
        get() = wifiSsid.takeIf { effectiveNetworkType == NetworkType.WIFI }

    /** @return the total bytes this run moved, for the caller's data accounting. */
    suspend fun runSession(onSample: (LiveSample) -> Unit): Long {
        Log.d(
            TAG,
            "session start: network=$effectiveNetworkType preference=$networkPreference " +
                "downRoundBytes=${liveConfig.downRoundBytes} upRoundBytes=${liveConfig.upRoundBytes} " +
                "streamRoundTimeoutMs=${liveConfig.streamRoundTimeoutMs} sweepTimeoutMs=${liveConfig.sweepTimeoutMs}",
        )
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

                    if (ws != null && runStream) runStreamPhases(ws, onSample)
                    if (ws != null && runSweep) runWebSocketSweep(ws, onSample)
                    if (ws != null && liveConfig.sustainedProbeEnabled) runSustainedProbePhases(ws, onSample)
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
     * One uninterrupted download, then one uninterrupted upload, each bounded
     * by [ProbeConfig.sustainedProbeMaxDownBytes]/[ProbeConfig.sustainedProbeMaxUpBytes]
     * and [ProbeConfig.sustainedProbeMaxDurationMs] rather than by the user's
     * chosen phase duration — appended after [runStreamPhases]/[runWebSocketSweep]
     * only when [LiveTestConfig.sustainedProbeEnabled] is on (a Settings
     * toggle, off by default). Automatic/background monitoring never sets it:
     * that path has its own strict, pre-reserved data budget that a silent
     * extra few MB per cycle would break.
     *
     * The regular stream phase is deliberately short and moves only a few
     * hundred KB, which cannot tell a carrier's *burst* allowance apart from
     * its *sustained* throttle on a plan that rate-limits with a token bucket
     * (a "Taktung 10 kB" mobile plan grants bandwidth credit in quanta, not an
     * instant hard per-packet cap — a fresh transfer can spend banked-up credit
     * at full radio speed before settling to the enforced rate). This probe
     * exists purely to move enough data, uninterrupted, to spend that credit
     * and show what the connection actually sustains once it is gone — which
     * is why [LiveSample.Rate]'s cumulative-average samples are exactly what
     * this needs: an early sample is burst-dominated, a late one (once several
     * hundred KB to a few MB have crossed) is not.
     */
    private suspend fun runSustainedProbePhases(ws: WsLiveTestClient, onSample: (LiveSample) -> Unit) {
        ws.ensureConnected()
        // Follows however many steps the regular phases already claimed, so
        // numbering stays correct whether the sweep ran or not.
        val stepBase = LiveTestConfig.PHASE_COUNT + if (liveConfig.sweepEnabled) 2 else 0

        emitPhase(onSample, LivePhase.SUSTAINED_DOWNLOAD, step = stepBase + 1, durationMs = null)
        val down = ws.runDownEpisode(
            roundBytes = liveConfig.downRoundBytes,
            episodeDurationMs = probeConfig.sustainedProbeMaxDurationMs,
            roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
            progressThrottleMs = liveConfig.progressThrottleMs,
            maxBytes = probeConfig.sustainedProbeMaxDownBytes,
        ) { bytes, elapsedMs ->
            // Cumulative, not instantaneous — same reasoning as everywhere
            // else this shape of callback is used. Logged at every throttled
            // step (not just the final number) specifically so the curve is
            // reconstructable afterwards: where a token-bucket-throttled
            // connection's cumulative average *stops decaying and flattens*
            // is, by construction, its refill rate — the number the "burst
            // vs steady" summary can only approximate from one aggregate.
            Log.d(TAG, "sustained download progress: bytes=$bytes elapsedMs=$elapsedMs rate=${mbpsOf(bytes, elapsedMs)}Mbps")
            mbpsOf(bytes, elapsedMs)?.let {
                onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.DOWN, it))
            }
        }
        Log.d(TAG, "sustained download: $down rate=${mbpsOf(down.bytes, down.elapsedMs)}Mbps")

        emitPhase(onSample, LivePhase.SUSTAINED_UPLOAD, step = stepBase + 2, durationMs = null)
        // The download episode's last round very often drops the socket on
        // its way out — see runDownEpisode's own comment: a round timeout
        // that coincides with the episode's time ceiling leaves the server
        // mid-protocol, so the client deliberately closes rather than reusing
        // it. Without reconnecting here, that shows up as the upload
        // instantly failing with NO_NETWORK — a null session, not an actual
        // network problem. runStreamPhases avoids this the same way, once per
        // phase; this only did it once for the whole probe.
        ws.ensureConnected()
        val up = ws.runUpEpisode(
            roundBytes = liveConfig.upRoundBytes,
            episodeDurationMs = probeConfig.sustainedProbeMaxDurationMs,
            roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
            progressThrottleMs = liveConfig.progressThrottleMs,
            maxBytes = probeConfig.sustainedProbeMaxUpBytes,
        ) { bytes, elapsedMs ->
            Log.d(TAG, "sustained upload progress: bytes=$bytes elapsedMs=$elapsedMs rate=${mbpsOf(bytes, elapsedMs)}Mbps")
            mbpsOf(bytes, elapsedMs)?.let {
                onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.UP, it))
            }
        }
        Log.d(TAG, "sustained upload: $up rate=${mbpsOf(up.bytes, up.elapsedMs)}Mbps")

        persistEpisode(down, up)
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

    /**
     * Probes until the phase deadline cancels it.
     *
     * Every sample here was taken on an idle link, because this only runs
     * during the ping phase — so a failure is a genuinely unanswered probe and
     * is safe to record as loss.
     */
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
     * Download for one phase, then upload for one phase. Nothing else runs.
     *
     * Strictly one measurement at a time is the point of the phase structure:
     * a probe competing with a stream measures the stream, and a stream sharing
     * the link with a probe is not measuring the link's full capacity either.
     * On a 64 kbit/s connection there is no spare bandwidth for a second thing
     * to happen in, so overlapping them does not produce two measurements — it
     * produces two wrong ones. Latency belongs to the ping phase.
     */
    private suspend fun runStreamPhases(
        ws: WsLiveTestClient,
        onSample: (LiveSample) -> Unit,
    ) {
        val phaseMs = liveConfig.effectivePhaseDurationMs
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

        Log.d(TAG, "download episode: $down rate=${mbpsOf(down.bytes, down.elapsedMs)}Mbps")
        Log.d(TAG, "upload episode: $up rate=${mbpsOf(up.bytes, up.elapsedMs)}Mbps")
        persistEpisode(down, up)
    }

    private suspend fun runWebSocketSweep(ws: WsLiveTestClient, onSample: (LiveSample) -> Unit) {
        ws.ensureConnected()
        emitPhase(onSample, LivePhase.DOWNLOAD_SWEEP, step = 4, durationMs = null)
        val down = runWebSocketSweepDirection(ws, SweepRunner.Direction.DOWN, onSample)
        store.saveLatestSweep(SweepRunner.Direction.DOWN, down)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.DOWN, down))

        emitPhase(onSample, LivePhase.UPLOAD_SWEEP, step = 5, durationMs = null)
        val up = runWebSocketSweepDirection(ws, SweepRunner.Direction.UP, onSample)
        store.saveLatestSweep(SweepRunner.Direction.UP, up)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.UP, up))

        chargeBytes(sweepBytesMoved(down) + sweepBytesMoved(up))
    }

    /**
     * Climbs the ladder, and stops early only when the link is *dead*.
     *
     * The stopping rule is deliberately "a rung failed every trial without
     * moving a single byte", not "a rung failed". A throttled connection fails
     * large sizes while still transferring most of the payload, and that is
     * precisely the finding worth waiting for — abandoning the ladder there
     * would throw away the evidence the test exists to collect. A link that
     * moves nothing, on the other hand, will not move anything at a larger
     * size either, and without this the mobile ladder would spend eleven
     * consecutive timeouts (over half an hour at a two-minute allowance)
     * re-proving the same thing.
     */
    private suspend fun runWebSocketSweepDirection(
        ws: WsLiveTestClient,
        direction: SweepRunner.Direction,
        onSample: (LiveSample) -> Unit,
    ): List<SweepResult> {
        val results = mutableListOf<SweepResult>()
        val steps = liveConfig.sweepSteps
        for (index in steps.indices) {
            val result = runSweepStep(ws, direction, steps[index])
            results += result
            Log.d(
                TAG,
                "sweep $direction ${index + 1}/${steps.size}: bytes=${result.bytes} passed=${result.passCount}/${result.attempted} " +
                    "moved=${result.bytesTransferred} elapsedMs=${result.totalElapsedMs} rate=${result.observedMbps}Mbps lastError=${result.lastError}",
            )
            // Attempted, not the configured trial count: a cancelled rung
            // recorded as if every trial had run would inflate the failure
            // count for a size that was never actually finished testing.
            store.recordSweepRung(direction, result.bytes, result.attempted, result.passCount)
            onSample(
                LiveSample.SweepStepResult(
                    atEpochMs = clock.nowEpochMs(),
                    direction = direction,
                    stepIndex = index + 1,
                    stepCount = steps.size,
                    result = result,
                ),
            )
            val linkIsDead = result.passCount == 0 &&
                result.attempted > 0 &&
                result.bytesTransferred == 0L
            if (linkIsDead) break
        }
        return results
    }

    private suspend fun runSweepStep(
        ws: WsLiveTestClient,
        direction: SweepRunner.Direction,
        step: de.sevenapp.monitor.probe.SweepStep,
    ): SweepResult = run {
        val outcomes = mutableListOf<Boolean>()
        var durationTotal = 0.0
        var lastError: FailureReason? = null
        var bytesMoved = 0L
        var elapsedTotal = 0.0
        repeat(step.trials) {
            currentCoroutineContext().ensureActive()
            val result = when (direction) {
                SweepRunner.Direction.DOWN -> ws.runDownTransfer(step.bytes, liveConfig.sweepTimeoutMs)
                SweepRunner.Direction.UP -> ws.runUpTransfer(step.bytes, liveConfig.sweepTimeoutMs)
            }
            // Bytes and time are recorded for failures too. A rung that timed
            // out after moving most of its payload is the single most useful
            // measurement this app can produce on a rate-limited line, and
            // discarding it leaves only an unexplained red block.
            bytesMoved += result.bytes
            elapsedTotal += result.elapsedMs
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
            bytesTransferred = bytesMoved,
            totalElapsedMs = elapsedTotal,
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

        private const val TAG = "SevenLiveTest"
    }
}
