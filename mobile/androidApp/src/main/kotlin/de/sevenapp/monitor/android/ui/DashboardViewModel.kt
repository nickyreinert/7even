package de.sevenapp.monitor.android.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sevenapp.monitor.android.billing.EntitlementRepository
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.livetest.LiveTestRunner
import de.sevenapp.monitor.android.work.ProbeWorker
import de.sevenapp.monitor.android.work.ReportWorker
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.core.StabilityScore
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.report.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class DashboardState(
    val tier: Tier = Tier.FREE,
    val monitoringEnabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val liveTestDurationMs: Long = LiveTestConfig.MIN_DURATION_MS,
    val monitoringNetworks: Set<NetworkType> = setOf(NetworkType.WIFI, NetworkType.CELLULAR),
    val lightDownBytes: Int = 256 * 1024,
    val lightUpBytes: Int = 128 * 1024,
    val manualTestRunning: Boolean = false,
    val recentPings: List<PingSample> = emptyList(),
    val recentThroughput: List<ThroughputSample> = emptyList(),
    val liveDownloadMbps: List<Double> = emptyList(),
    val liveUploadMbps: List<Double> = emptyList(),
    val latestDownloadSweep: List<SweepResult> = emptyList(),
    val latestUploadSweep: List<SweepResult> = emptyList(),
    val latencyMedianMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val dropCount: Int = 0,
    val meteredBytesThisMonth: Long = 0,
    val projectedMeteredBytesPerMonth: Long = 0,
    val latestReport: Report? = null,
    val stability: StabilityScore.Result? = null,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RoomMonitorStore.get(app)
    private val entitlements = EntitlementRepository.get(app)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()
    private var manualTestJob: Job? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val dayAgo = now - 24 * 60 * 60 * 1000
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        val config = store.loadConfig()
        val pings = store.pingsBetween(dayAgo, now)
        val rtts = pings.mapNotNull { it.rttMs }
        val failures = pings.count { !it.ok }

        val drops = store.dropsOverlapping(dayAgo, now)
        val lossPct = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0
        _state.value = _state.value.copy(
            tier = entitlements.effectiveTier(now),
            monitoringEnabled = RoomMonitorStore.isMonitoringEnabled(app),
            intervalMinutes = config.cycleIntervalMinutes,
            liveTestDurationMs = config.liveTestMinDurationMs,
            monitoringNetworks = config.monitoringNetworks,
            lightDownBytes = config.lightDownBytes,
            lightUpBytes = config.lightUpBytes,
            recentPings = store.recentPings(120).reversed(), // oldest-first for the chart
            recentThroughput = store.throughputBetween(dayAgo, now).takeLast(8),
            latencyMedianMs = Stats.median(rtts),
            jitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
            lossPct = lossPct,
            dropCount = drops.size,
            stability = if (pings.isEmpty()) null else StabilityScore.compute(
                windowStartEpochMs = dayAgo,
                nowEpochMs = now,
                drops = drops,
                avgJitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
                avgLossPct = lossPct ?: 0.0,
            ),
            meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
            projectedMeteredBytesPerMonth = DataBudget.project(config).meteredBytesPerMonth,
        )
    }

    /**
     * Run one cycle right now, on demand.
     *
     * Free at every tier, deliberately — this is the whole app for someone who
     * just wants to check their connection, and it costs nothing per run since
     * the measurement is client-side against public endpoints. What Pro buys is
     * having this happen *without being asked*, which is the part that takes
     * ongoing work to keep reliable.
     */
    fun runManualTest() {
        if (_state.value.manualTestRunning) return
        manualTestJob = viewModelScope.launch {
        _state.update {
            it.copy(
            manualTestRunning = true,
            liveDownloadMbps = emptyList(),
            liveUploadMbps = emptyList(),
            latestDownloadSweep = emptyList(),
            latestUploadSweep = emptyList(),
            )
        }

        val app = getApplication<Application>()
        val config = store.loadConfig()
        val deviceState = readDeviceState()
        try {
            LiveTestRunner(
                context = app,
                store = store,
                probeConfig = config,
                fallbackNetworkType = deviceState.networkType,
            ).runSession { sample ->
                _state.update { current ->
                    when (sample) {
                        is LiveSample.Rate -> when (sample.direction) {
                            SweepRunner.Direction.DOWN -> current.copy(
                                liveDownloadMbps = (current.liveDownloadMbps + sample.mbps).takeLast(120),
                            )
                            SweepRunner.Direction.UP -> current.copy(
                                liveUploadMbps = (current.liveUploadMbps + sample.mbps).takeLast(120),
                            )
                        }
                        is LiveSample.Ping -> current
                        is LiveSample.Sweep -> when (sample.direction) {
                            SweepRunner.Direction.DOWN -> current.copy(latestDownloadSweep = sample.results)
                            SweepRunner.Direction.UP -> current.copy(latestUploadSweep = sample.results)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // A failed manual test is itself a measurement — the cycle records
            // failed probes before it can throw, so there is nothing to undo.
        } finally {
            _state.update { it.copy(manualTestRunning = false) }
            refresh()
            manualTestJob = null
        }
        }
    }

    fun stopManualTest() {
        manualTestJob?.cancel()
        manualTestJob = null
        _state.update { it.copy(manualTestRunning = false) }
    }

    fun setLiveTestDuration(durationMs: Long) = viewModelScope.launch {
        store.saveConfig(store.loadConfig().copy(liveTestMinDurationMs = durationMs))
        refresh()
    }

    fun setMonitoring(enabled: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()

        // Checked here as well as in the worker. Hiding a switch is
        // presentation; this is the gate next to the effect.
        if (enabled && !FeatureGate.shouldBackgroundWorkRun(entitlements.current(), now)) {
            refresh()
            return@launch
        }

        RoomMonitorStore.setMonitoringEnabled(app, enabled)
        if (enabled) {
            ProbeWorker.schedule(app, store.loadConfig().cycleIntervalMinutes.toLong())
            ReportWorker.schedule(app)
        } else {
            ProbeWorker.cancel(app)
            ReportWorker.cancel(app)
        }
        refresh()
    }

    fun setInterval(minutes: Int) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (minutes !in FeatureGate.allowedIntervalMinutes(entitlements.effectiveTier())) return@launch
        store.saveConfig(store.loadConfig().copy(
            cycleIntervalMinutes = minutes,
            // A daily or weekly recurrence is normally chosen specifically to
            // run one bounded speed test at that cadence, not to wait through
            // four calendar cycles before the first one.
            throughputEveryNCycles = if (minutes >= 24 * 60) 1 else store.loadConfig().throughputEveryNCycles,
        ))
        if (RoomMonitorStore.isMonitoringEnabled(app)) ProbeWorker.schedule(app, minutes.toLong())
        refresh()
    }

    fun setMonitoringNetworks(networks: Set<NetworkType>) = viewModelScope.launch {
        val selected = networks.intersect(setOf(NetworkType.WIFI, NetworkType.CELLULAR))
        if (selected.isEmpty()) return@launch
        val preference = when (selected) {
            setOf(NetworkType.WIFI) -> NetworkPreference.WIFI
            setOf(NetworkType.CELLULAR) -> NetworkPreference.CELLULAR
            else -> NetworkPreference.AUTO
        }
        store.saveConfig(store.loadConfig().copy(
            monitoringNetworks = selected,
            preferredTestNetwork = preference,
        ))
        refresh()
    }

    fun setMeasurementSize(bytes: Int) = viewModelScope.launch {
        // Mobile's light test intentionally uses the same size in both
        // directions. That makes its battery/data cost easy to understand.
        store.saveConfig(store.loadConfig().copy(lightDownBytes = bytes, lightUpBytes = bytes))
        refresh()
    }

    private fun readDeviceState(): DeviceState {
        val context = getApplication<Application>()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val type = when {
            caps == null -> NetworkType.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true

        return DeviceState(networkType = type, isCharging = charging, isMetered = metered)
    }
}
