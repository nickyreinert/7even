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
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {

    private val liveConfig = LiveTestConfig(
        minDurationMs = probeConfig.liveTestMinDurationMs,
        sweepEnabled = probeConfig.liveTestSweepEnabled,
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
            val ws = WsLiveTestClient(probeConfig.wsUrl, network)

            coroutineScope {
                val pingJob = launch { runPingLoop(httpTransport, onSample) }
                try {
                    ws.connect(NativeAuth.header())
                    runStreamAndSweepLoop(ws, httpTransport, onSample)
                } finally {
                    pingJob.cancel()
                    ws.close()
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
            store.appendPings(listOf(PingSample(at, rtt, effectiveNetworkType)))
            delay(liveConfig.pingIntervalMs)
        }
    }

    private suspend fun runStreamAndSweepLoop(
        ws: WsLiveTestClient,
        httpTransport: KtorTransport,
        onSample: (LiveSample) -> Unit,
    ) {
        val sessionStart = clock.nowEpochMs()
        val sweepRunner = SweepRunner(httpTransport)
        val sweepConfig = probeConfig.copy(transferTimeoutMs = liveConfig.sweepTimeoutMs)

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
                val downSweep = sweepRunner.run(liveConfig.sweepSteps, SweepRunner.Direction.DOWN, sweepConfig)
                store.saveLatestSweep(SweepRunner.Direction.DOWN, downSweep)
                onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.DOWN, downSweep))
                val upSweep = sweepRunner.run(liveConfig.sweepSteps, SweepRunner.Direction.UP, sweepConfig)
                store.saveLatestSweep(SweepRunner.Direction.UP, upSweep)
                onSample(LiveSample.Sweep(clock.nowEpochMs(), SweepRunner.Direction.UP, upSweep))
            }
        } while (!LiveTestSchedule.isSessionDone(sessionStart, clock.nowEpochMs(), liveConfig.minDurationMs))
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
                ),
            )
        }
        if (bytesMoved > 0) {
            store.addBytesUsed(bytesMoved, effectiveNetworkType == NetworkType.CELLULAR, clock.nowEpochMs())
        }
    }
}
