package de.sevenapp.monitor

import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.DropDetector
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.ProbeEngine
import de.sevenapp.monitor.probe.TierPolicy
import de.sevenapp.monitor.probe.Transport
import de.sevenapp.monitor.probe.TransferResult
import de.sevenapp.monitor.probe.TransportResult
import de.sevenapp.monitor.probe.mbpsOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted transport — no network, no flakiness, exact control over outcomes. */
private class FakeTransport(
    var pingResult: () -> TransportResult = { TransportResult.Ok(25.0) },
    var downResult: () -> TransferResult = { TransferResult.Ok(256 * 1024L, 100.0) },
    var upResult: () -> TransferResult = { TransferResult.Ok(128 * 1024L, 100.0) },
) : Transport {
    var pingCalls = 0
    var downCalls = 0
    var upCalls = 0
    var webSocketDownCalls = 0
    var webSocketUpCalls = 0

    override suspend fun timedGet(url: String, timeoutMs: Long): TransportResult {
        pingCalls++; return pingResult()
    }

    override suspend fun download(url: String, expectBytes: Int, timeoutMs: Long): TransferResult {
        downCalls++; return downResult()
    }

    override suspend fun upload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        upCalls++; return upResult()
    }

    override suspend fun webSocketDownload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        webSocketDownCalls++; return downResult()
    }

    override suspend fun webSocketUpload(url: String, bytes: Int, timeoutMs: Long): TransferResult {
        webSocketUpCalls++; return upResult()
    }
}

private class FixedClock(var now: Long = 1_000_000L) : Clock {
    override fun nowEpochMs(): Long = now
}

class ProbeEngineTest {

    private fun input(
        config: ProbeConfig = ProbeConfig(),
        cycleIndex: Long = 1,
        state: DeviceState = DeviceState(NetworkType.WIFI, isCharging = false, isMetered = false),
        fullSweepsToday: Int = 0,
        detector: DropDetector = DropDetector(),
    ) = ProbeEngine.CycleInput(config, cycleIndex, state, fullSweepsToday, detector)

    @Test
    fun reachabilityCycleRunsConfiguredPingsAndNoTransfers() = runTest {
        val t = FakeTransport()
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 1))

        assertEquals(ProbeTier.REACHABILITY, out.tier)
        assertEquals(3, t.pingCalls)
        assertEquals(0, t.downCalls)
        assertEquals(0, t.upCalls)
        assertNull(out.throughput)
        assertEquals(3, out.pings.size)
    }

    @Test
    fun throughputCycleMeasuresBothDirections() = runTest {
        val t = FakeTransport()
        // cycleIndex 4 with throughputEveryNCycles=4 => due
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 4))

        assertEquals(1, t.downCalls)
        assertEquals(1, t.upCalls)
        val tp = assertNotNull(out.throughput)
        // 256KB in 100ms => 262144*8/0.1/1e6 ≈ 20.97 Mbps
        assertEquals(mbpsOf(262144, 100.0), tp.downMbps)
        assertNotNull(tp.upMbps)
    }

    @Test
    fun websocketModeUsesBoundedStreamTransfers() = runTest {
        val t = FakeTransport()
        ProbeEngine(t, FixedClock()).runCycle(
            input(config = ProbeConfig(useWebSocketStream = true), cycleIndex = 4),
        )

        assertEquals(1, t.webSocketDownCalls)
        assertEquals(1, t.webSocketUpCalls)
        assertEquals(0, t.downCalls)
        assertEquals(0, t.upCalls)
    }

    @Test
    fun throughputIsSkippedWhenEveryPingFailed() = runTest {
        // Spending 400KB measuring the speed of a link that just failed three
        // reachability probes is wasted data and yields a meaningless number.
        val t = FakeTransport(pingResult = { TransportResult.Failed(FailureReason.TIMEOUT) })
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 4))

        assertEquals(0, t.downCalls)
        assertEquals(0, t.upCalls)
        assertNull(out.throughput)
        assertTrue(out.skippedReason?.contains("all pings failed") == true)
    }

    @Test
    fun noNetworkStillRecordsAFailedProbe() = runTest {
        // "The phone had no connection" is the event a connection monitor
        // exists to capture. Skipping it silently would let the report claim
        // uptime it never observed.
        val t = FakeTransport()
        val out = ProbeEngine(t, FixedClock()).runCycle(
            input(state = DeviceState(NetworkType.NONE, isCharging = false, isMetered = false)),
        )

        assertNull(out.tier)
        assertEquals(1, out.pings.size)
        assertTrue(out.pings.single().rttMs == null)
        assertEquals(0, t.pingCalls)
        assertEquals("no network", out.skippedReason)
    }

    @Test
    fun excludedConnectionIsSkippedWithoutRecordingAnOutage() = runTest {
        val t = FakeTransport()
        val out = ProbeEngine(t, FixedClock()).runCycle(
            input(
                config = ProbeConfig(monitoringNetworks = setOf(NetworkType.WIFI)),
                state = DeviceState(NetworkType.CELLULAR, isCharging = false, isMetered = true),
            ),
        )

        assertNull(out.tier)
        assertTrue(out.pings.isEmpty())
        assertEquals(0, t.pingCalls)
        assertEquals("connection type not selected", out.skippedReason)
    }

    @Test
    fun partialTransferStillYieldsARateAndIsFlagged() = runTest {
        val t = FakeTransport(
            downResult = { TransferResult.Partial(100_000, 50.0, FailureReason.TIMEOUT) },
        )
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 4))
        val tp = assertNotNull(out.throughput)
        assertNotNull(tp.downMbps)
        assertTrue(tp.partial)
    }

    @Test
    fun fullyFailedTransfersProduceNoThroughputSample() = runTest {
        val t = FakeTransport(
            downResult = { TransferResult.Failed(FailureReason.TIMEOUT) },
            upResult = { TransferResult.Failed(FailureReason.TIMEOUT) },
        )
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 4))
        assertNull(out.throughput)
    }

    @Test
    fun failedPingsFeedTheDropDetector() = runTest {
        val t = FakeTransport(pingResult = { TransportResult.Failed(FailureReason.NO_NETWORK) })
        val detector = DropDetector()
        ProbeEngine(t, FixedClock()).runCycle(input(detector = detector))

        assertTrue(detector.isDropOpen)
        assertEquals(1, detector.allDrops().size)
    }

    @Test
    fun bytesUsedCountsOnlySuccessfulPings() = runTest {
        val t = FakeTransport()
        val out = ProbeEngine(t, FixedClock()).runCycle(input(cycleIndex = 1))
        assertEquals(3 * DataBudget.PING_BYTES, out.bytesUsed)
    }
}

class TierPolicyTest {

    private val wifi = DeviceState(NetworkType.WIFI, isCharging = false, isMetered = false)
    private val wifiCharging = DeviceState(NetworkType.WIFI, isCharging = true, isMetered = false)
    private val cellular = DeviceState(NetworkType.CELLULAR, isCharging = false, isMetered = true)

    @Test
    fun nonThroughputCyclesAreReachabilityOnly() {
        val tier = TierPolicy.tierFor(ProbeConfig(), cycleIndex = 1, state = wifi, fullSweepsToday = 0)
        assertEquals(ProbeTier.REACHABILITY, tier)
    }

    @Test
    fun throughputCycleOnWifiWithoutChargingIsLightNotFull() {
        val tier = TierPolicy.tierFor(ProbeConfig(), cycleIndex = 4, state = wifi, fullSweepsToday = 0)
        assertEquals(ProbeTier.THROUGHPUT_LIGHT, tier)
    }

    @Test
    fun throughputCycleOnWifiWhileChargingIsFull() {
        val tier = TierPolicy.tierFor(ProbeConfig(), cycleIndex = 4, state = wifiCharging, fullSweepsToday = 0)
        assertEquals(ProbeTier.THROUGHPUT_FULL, tier)
    }

    @Test
    fun fullSweepDailyCapFallsBackToLight() {
        val tier = TierPolicy.tierFor(ProbeConfig(), cycleIndex = 4, state = wifiCharging, fullSweepsToday = 2)
        assertEquals(ProbeTier.THROUGHPUT_LIGHT, tier)
    }

    @Test
    fun meteredCellularNeverRunsThroughputByDefault() {
        // The whole 10MB/month promise rests on this.
        val tier = TierPolicy.tierFor(ProbeConfig(), cycleIndex = 4, state = cellular, fullSweepsToday = 0)
        assertEquals(ProbeTier.REACHABILITY, tier)
    }

    @Test
    fun cellularRunsThroughputWhenExplicitlyOptedIn() {
        val config = ProbeConfig(
            throughputLightNetworks = setOf(NetworkType.WIFI, NetworkType.CELLULAR),
        )
        val tier = TierPolicy.tierFor(config, cycleIndex = 4, state = cellular, fullSweepsToday = 0)
        assertEquals(ProbeTier.THROUGHPUT_LIGHT, tier)
    }

    @Test
    fun noNetworkYieldsNoTier() {
        val state = DeviceState(NetworkType.NONE, isCharging = true, isMetered = false)
        assertNull(TierPolicy.tierFor(ProbeConfig(), cycleIndex = 4, state = state, fullSweepsToday = 0))
    }

    @Test
    fun excludedNetworkYieldsNoTier() {
        val config = ProbeConfig(monitoringNetworks = setOf(NetworkType.WIFI))
        assertNull(TierPolicy.tierFor(config, cycleIndex = 4, state = cellular, fullSweepsToday = 0))
    }
}

class DataBudgetTest {

    @Test
    fun defaultConfigStaysUnderTenMegabytesOfCellularPerMonth() {
        // This is the number we intend to put in the store listing, so it is
        // asserted rather than assumed. If a default changes and pushes this
        // over, the build should say so.
        val projection = DataBudget.project(ProbeConfig())
        val mb = projection.meteredBytesPerMonth / 1_000_000.0
        assertTrue(mb < 10.0, "default cellular use is ${mb}MB/month, expected < 10MB")
    }

    @Test
    fun optingCellularIntoThroughputRaisesTheProjectionSharply() {
        val base = DataBudget.project(ProbeConfig())
        val opted = DataBudget.project(
            ProbeConfig(throughputLightNetworks = setOf(NetworkType.WIFI, NetworkType.CELLULAR)),
        )
        assertTrue(
            opted.meteredBytesPerMonth > base.meteredBytesPerMonth * 10,
            "expected opting in to be visibly more expensive",
        )
    }

    @Test
    fun wifiOnlyMonitoringProjectsNoMobileDataUse() {
        val projection = DataBudget.project(
            ProbeConfig(monitoringNetworks = setOf(NetworkType.WIFI)),
        )
        assertEquals(0, projection.meteredBytesPerMonth)
    }

    @Test
    fun shorterIntervalIncreasesProjection() {
        val quarterHourly = DataBudget.project(ProbeConfig(cycleIntervalMinutes = 15))
        val fiveMinutely = DataBudget.project(ProbeConfig(cycleIntervalMinutes = 5))
        assertTrue(fiveMinutely.meteredBytesPerMonth > quarterHourly.meteredBytesPerMonth)
    }
}
