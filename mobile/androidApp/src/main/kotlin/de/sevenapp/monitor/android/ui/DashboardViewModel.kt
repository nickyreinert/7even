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
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.report.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardState(
    val tier: Tier = Tier.FREE,
    val monitoringEnabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val manualTestRunning: Boolean = false,
    val recentPings: List<PingSample> = emptyList(),
    val liveSamples: List<LiveSample> = emptyList(),
    val latencyMedianMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val dropCount: Int = 0,
    val meteredBytesThisMonth: Long = 0,
    val projectedMeteredBytesPerMonth: Long = 0,
    val latestReport: Report? = null,
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val store = RoomMonitorStore.get(app)
    private val entitlements = EntitlementRepository.get(app)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

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

        _state.update { it.copy(
            tier = entitlements.effectiveTier(now),
            monitoringEnabled = RoomMonitorStore.isMonitoringEnabled(app),
            intervalMinutes = config.cycleIntervalMinutes,
            recentPings = store.recentPings(120).reversed(), // oldest-first for the chart
            latencyMedianMs = Stats.median(rtts),
            jitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
            lossPct = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0,
            dropCount = store.dropsOverlapping(dayAgo, now).size,
            meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
            projectedMeteredBytesPerMonth = DataBudget.project(config).meteredBytesPerMonth,
        ) }
    }

    /**
     * Run the web-style foreground test right now, on demand: continuous
     * pings, back-to-back streaming download/upload episodes over one
     * WebSocket, and (if enabled in Settings) a chunk-size sweep, for at
     * least the configured minimum duration — see [LiveTestRunner].
     *
     * Free at every tier, deliberately — this is the whole app for someone who
     * just wants to check their connection, and it costs nothing per run since
     * the measurement is client-side against public endpoints. What Pro buys is
     * having this happen *without being asked*, which is the part that takes
     * ongoing work to keep reliable.
     */
    fun runManualTest() = viewModelScope.launch {
        if (_state.value.manualTestRunning) return@launch
        _state.update { it.copy(manualTestRunning = true, liveSamples = emptyList()) }

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
                // LiveTestRunner.runSession drives the ping loop and the
                // down/up episode loop as two SEPARATE concurrent coroutines,
                // and both call this callback — the ping loop roughly every 3s,
                // the episode loop roughly every 200ms while a round is active.
                // `_state.value = _state.value.copy(...)` is a read-modify-write:
                // under real concurrent writers it can lose an update (coroutine
                // A reads the list, coroutine B reads the same list and writes
                // first, then A writes back its own copy, silently dropping B's
                // sample). At these relative frequencies that would show up
                // exactly as reported: nearly all of one direction's samples
                // survive while the sparser ones get clobbered out of the list.
                // `update {}` performs an atomic compare-and-swap retry instead,
                // so no sample from either coroutine is ever lost.
                val cutoff = sample.atEpochMs - LiveTestConfig.LIVE_WINDOW_MS
                _state.update { it.copy(liveSamples = (it.liveSamples + sample).filter { s -> s.atEpochMs >= cutoff }) }
            }
        } catch (t: Throwable) {
            // A failed test still recorded whatever pings/episodes completed
            // before it threw — there is nothing further to undo here.
        } finally {
            _state.update { it.copy(manualTestRunning = false) }
            refresh()
        }
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
