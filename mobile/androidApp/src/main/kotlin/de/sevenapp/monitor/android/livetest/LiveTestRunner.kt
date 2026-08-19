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
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.LiveTestSchedule
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.TransportResult
import de.sevenapp.monitor.probe.mbpsOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the web app's "web-style" foreground test on Android: a continuous
 * ping loop plus back-to-back streaming download/upload episodes over one
 * persistent WebSocket, with an optional chunk-size sweep between episodes,
 * for at least [LiveTestConfig.minDurationMs] — never cut off mid-episode,
 * per [LiveTestSchedule].
 *
 * Deliberately Android-specific rather than living in :shared. Unlike
 * [de.sevenapp.monitor.probe.ProbeEngine], which the platform worker only
 * feeds device state into, this drives a live WebSocket connection with a
 * UI-facing progress callback — exactly the platform glue that
 * [de.sevenapp.monitor.probe.Transport]'s own doc comment says stays out of
 * :shared. The pure "is this session done yet" decision is the one part
 * that *is* shared and unit-tested, in [LiveTestSchedule].
 */
class LiveTestRunner(
    private val context: Context,
    private val store: MonitorStore,
    private val probeConfig: ProbeConfig,
    /** Used only when [ProbeConfig.preferredTestNetwork] is [NetworkPreference.AUTO]. */
    private val fallbackNetworkType: NetworkType,
    private val runStream: Boolean = true,
    private val runSweep: Boolean = probeConfig.liveTestSweepEnabled,
    private val wifiSsid: String? = null,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {

    private val liveConfig = LiveTestConfig(
        minDurationMs = probeConfig.liveTestMinDurationMs,
        sweepEnabled = runSweep,
        sweepSteps = probeConfig.liveTestSweepSteps,
    )

    private val effectiveNetworkType: NetworkType = when (probeConfig.preferredTestNetwork) {
        NetworkPreference.WIFI -> NetworkType.WIFI
        NetworkPreference.CELLULAR -> NetworkType.CELLULAR
        NetworkPreference.AUTO -> fallbackNetworkType
    }

    suspend fun runSession(onSample: (LiveSample) -> Unit) {
        NetworkBinder.withNetwork(context, probeConfig.preferredTestNetwork) { network ->
            val httpTransport = KtorTransport(KtorTransport.defaultClient(network))
            val ws = if (runStream || runSweep) WsLiveTestClient(probeConfig.wsUrl, network) else null

            coroutineScope {
                val pingJob = launch { runPingLoop(httpTransport, onSample) }
                try {
                    if (ws != null) {
                        ws.connect(NativeAuth.header())
                        if (runStream) runStreamAndSweepLoop(ws, onSample)
                        else runWebSocketSweep(ws, onSample)
                    }
                } finally {
                    pingJob.cancel()
                    ws?.close()
                }
            }
        }
    }

    private suspend fun runPingLoop(transport: KtorTransport, onSample: (LiveSample) -> Unit) {
        while (true) {
            val result = transport.timedGet(probeConfig.traceUrl, liveConfig.pingTimeoutMs)
            val rtt = (result as? TransportResult.Ok)?.rttMs
            val at = clock.nowEpochMs()
            onSample(LiveSample.Ping(at, rtt))
            store.appendPings(listOf(PingSample(at, rtt, effectiveNetworkType, wifiSsid.takeIf { effectiveNetworkType == NetworkType.WIFI })))
            delay(liveConfig.pingIntervalMs)
        }
    }

    private suspend fun runStreamAndSweepLoop(
        ws: WsLiveTestClient,
        onSample: (LiveSample) -> Unit,
    ) {
        val sessionStart = clock.nowEpochMs()

        do {
            val down = ws.runDownEpisode(
                roundBytes = liveConfig.downRoundBytes,
                episodeDurationMs = liveConfig.streamEpisodeDurationMs,
                roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
                progressThrottleMs = liveConfig.progressThrottleMs,
            ) { bytes, elapsedMs ->
                mbpsOf(bytes, elapsedMs)?.let { onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.DOWN, it)) }
            }

            val up = ws.runUpEpisode(
                roundBytes = liveConfig.upRoundBytes,
                episodeDurationMs = liveConfig.streamEpisodeDurationMs,
                roundTimeoutMs = liveConfig.streamRoundTimeoutMs,
                progressThrottleMs = liveConfig.progressThrottleMs,
            ) { bytes, elapsedMs ->
                mbpsOf(bytes, elapsedMs)?.let { onSample(LiveSample.Rate(clock.nowEpochMs(), SweepRunner.Direction.UP, it)) }
            }

            persistEpisode(down, up)

            if (liveConfig.sweepEnabled) {
                runWebSocketSweep(ws, onSample)
            }
        } while (!LiveTestSchedule.isSessionDone(sessionStart, clock.nowEpochMs(), liveConfig.minDurationMs))
    }

    private suspend fun runWebSocketSweep(ws: WsLiveTestClient, onSample: (LiveSample) -> Unit) {
        val down = runWebSocketSweepDirection(ws, SweepRunner.Direction.DOWN)
        store.saveLatestSweep(SweepRunner.Direction.DOWN, down)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.DOWN, down))
        val up = runWebSocketSweepDirection(ws, SweepRunner.Direction.UP)
        store.saveLatestSweep(SweepRunner.Direction.UP, up)
        onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.UP, up))
    }

    private suspend fun runWebSocketSweepDirection(
        ws: WsLiveTestClient,
        direction: SweepRunner.Direction,
    ): List<SweepResult> = liveConfig.sweepSteps.map { step ->
        val outcomes = mutableListOf<Boolean>()
        var durationTotal = 0.0
        var lastError: de.sevenapp.monitor.probe.FailureReason? = null
        repeat(step.trials) {
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
                    partial = down is WsLiveTestClient.EpisodeResult.Failed || up is WsLiveTestClient.EpisodeResult.Failed,
                    ssid = wifiSsid.takeIf { effectiveNetworkType == NetworkType.WIFI },
                ),
            )
        }
        if (bytesMoved > 0) {
            store.addBytesUsed(bytesMoved, effectiveNetworkType == NetworkType.CELLULAR, clock.nowEpochMs())
        }
    }
}
