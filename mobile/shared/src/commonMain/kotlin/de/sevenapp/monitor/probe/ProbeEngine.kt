package de.sevenapp.monitor.probe

import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.DropDetector
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample

/**
 * Runs exactly one measurement cycle and returns what it found.
 *
 * Deliberately *not* a scheduler. The engine has no opinion about when it runs
 * or how often — Android's WorkManager and iOS's BGTaskScheduler have genuinely
 * different semantics (near-guaranteed periodic vs purely opportunistic), and
 * an abstraction pretending otherwise would hide the single most important
 * truth about this product. Each platform decides when to call [runCycle];
 * this class only decides what a cycle *is*.
 *
 * It is also stateless across calls. Background processes are killed
 * constantly, so any state that must survive lives in the database and is
 * handed back in via [CycleInput].
 */
class ProbeEngine(
    private val transport: Transport,
    private val clock: Clock,
) {

    data class CycleInput(
        val config: ProbeConfig,
        val cycleIndex: Long,
        val deviceState: DeviceState,
        val fullSweepsToday: Int,
        /** Detector rehydrated from storage, so drops survive process death. */
        val dropDetector: DropDetector,
    )

    data class CycleOutput(
        val tier: ProbeTier?,
        val pings: List<PingSample>,
        val throughput: ThroughputSample?,
        val dropTransitions: List<DropDetector.Transition>,
        /** Bytes this cycle put on the wire, for the running data-use total. */
        val bytesUsed: Long,
        val skippedReason: String? = null,
    )

    suspend fun runCycle(input: CycleInput): CycleOutput {
        val tier = TierPolicy.tierFor(
            config = input.config,
            cycleIndex = input.cycleIndex,
            state = input.deviceState,
            fullSweepsToday = input.fullSweepsToday,
        )

        if (tier == null) {
            if (input.deviceState.networkType != de.sevenapp.monitor.core.NetworkType.NONE) {
                // This is an intentional user choice, not a failed connection.
                // Recording it as a failed ping would turn time on an excluded
                // connection into a false outage in the report.
                return CycleOutput(
                    tier = null,
                    pings = emptyList(),
                    throughput = null,
                    dropTransitions = emptyList(),
                    bytesUsed = 0,
                    skippedReason = "connection type not selected",
                )
            }
            // No usable network. Record it as a failed probe rather than
            // silently skipping: "the phone had no connection" is exactly the
            // event a connection monitor exists to capture, and dropping it
            // would make the report claim uptime it did not observe.
            val sample = PingSample(clock.nowEpochMs(), null, input.deviceState.networkType, input.deviceState.ssid)
            val transition = input.dropDetector.onSample(sample)
            return CycleOutput(
                tier = null,
                pings = listOf(sample),
                throughput = null,
                dropTransitions = listOf(transition),
                bytesUsed = 0,
                skippedReason = "no network",
            )
        }

        val pings = mutableListOf<PingSample>()
        val transitions = mutableListOf<DropDetector.Transition>()
        var bytes = 0L

        repeat(input.config.pingsPerCycle) {
            val result = transport.timedGet(input.config.traceUrl, input.config.pingTimeoutMs)
            val sample = PingSample(
                atEpochMs = clock.nowEpochMs(),
                rttMs = (result as? TransportResult.Ok)?.rttMs,
                networkType = input.deviceState.networkType,
                ssid = input.deviceState.ssid,
            )
            pings += sample
            transitions += input.dropDetector.onSample(sample)
            if (result is TransportResult.Ok) bytes += DataBudget.PING_BYTES
        }

        // Don't spend megabytes measuring throughput on a link that just failed
        // every reachability probe — the number would be meaningless and the
        // data would be wasted on a connection that cannot carry it.
        val anyPingOk = pings.any { it.ok }
        val throughput = if (tier == ProbeTier.REACHABILITY || !anyPingOk) {
            null
        } else {
            measureThroughput(input, tier).also { bytes += it.second }.first
        }

        return CycleOutput(
            tier = tier,
            pings = pings,
            throughput = throughput,
            dropTransitions = transitions,
            bytesUsed = bytes,
            skippedReason = if (!anyPingOk && tier != ProbeTier.REACHABILITY) {
                "throughput skipped: all pings failed"
            } else {
                null
            },
        )
    }

    /** @return the sample plus the bytes it actually moved. */
    private suspend fun measureThroughput(
        input: CycleInput,
        tier: ProbeTier,
    ): Pair<ThroughputSample?, Long> {
        val config = input.config
        // THROUGHPUT_FULL's sweep ladder and WebSocket stream test are Phase 2;
        // until then a full-tier cycle runs the light measurement rather than
        // nothing, so a charging-and-on-Wi-Fi cycle still yields a data point.
        var moved = 0L
        val downRates = mutableListOf<Double>()
        val upRates = mutableListOf<Double>()
        var partial = false
        config.measurementSizes(input.deviceState.networkType).forEach { bytes ->
            val down = if (config.useWebSocketStream) transport.webSocketDownload(config.streamUrl, bytes, config.transferTimeoutMs)
            else transport.download(config.downUrl(bytes), bytes, config.transferTimeoutMs)
            when (down) {
                is TransferResult.Ok -> { moved += down.bytes; mbpsOf(down.bytes, down.elapsedMs)?.let(downRates::add) }
                is TransferResult.Partial -> { moved += down.bytes; partial = true; mbpsOf(down.bytes, down.elapsedMs)?.let(downRates::add) }
                is TransferResult.Failed -> Unit
            }
            val up = if (config.useWebSocketStream) transport.webSocketUpload(config.streamUrl, bytes, config.transferTimeoutMs)
            else transport.upload(config.upUrl, bytes, config.transferTimeoutMs)
            when (up) {
                is TransferResult.Ok -> { moved += up.bytes; mbpsOf(up.bytes, up.elapsedMs)?.let(upRates::add) }
                is TransferResult.Partial -> { moved += up.bytes; partial = true; mbpsOf(up.bytes, up.elapsedMs)?.let(upRates::add) }
                is TransferResult.Failed -> Unit
            }
        }
        val downMbps = downRates.average().takeIf { downRates.isNotEmpty() }
        val upMbps = upRates.average().takeIf { upRates.isNotEmpty() }

        if (downMbps == null && upMbps == null) return null to moved

        return ThroughputSample(
            atEpochMs = clock.nowEpochMs(),
            downMbps = downMbps,
            upMbps = upMbps,
            networkType = input.deviceState.networkType,
            tier = tier,
            partial = partial,
            ssid = input.deviceState.ssid,
        ) to moved
    }
}
