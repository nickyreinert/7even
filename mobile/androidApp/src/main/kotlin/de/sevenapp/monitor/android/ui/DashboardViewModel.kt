package de.sevenapp.monitor.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.work.ProbeWorker
import de.sevenapp.monitor.android.work.ReportWorker
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.probe.DataBudget
import de.sevenapp.monitor.report.Report
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardState(
    val monitoringEnabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val recentPings: List<PingSample> = emptyList(),
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
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val dayAgo = now - 24 * 60 * 60 * 1000
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        val config = store.loadConfig()
        val pings = store.pingsBetween(dayAgo, now)
        val rtts = pings.mapNotNull { it.rttMs }
        val failures = pings.count { !it.ok }

        _state.value = DashboardState(
            monitoringEnabled = RoomMonitorStore.isMonitoringEnabled(app),
            intervalMinutes = config.cycleIntervalMinutes,
            recentPings = store.recentPings(120).reversed(), // oldest-first for the chart
            latencyMedianMs = Stats.median(rtts),
            jitterMs = if (rtts.size >= 2) Stats.stdDev(rtts) else null,
            lossPct = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0,
            dropCount = store.dropsOverlapping(dayAgo, now).size,
            meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
            projectedMeteredBytesPerMonth = DataBudget.project(config).meteredBytesPerMonth,
        )
    }

    fun setMonitoring(enabled: Boolean) = viewModelScope.launch {
        val app = getApplication<Application>()
        RoomMonitorStore.setMonitoringEnabled(app, enabled)

        if (enabled) {
            val config = store.loadConfig()
            ProbeWorker.schedule(app, config.cycleIntervalMinutes.toLong())
            ReportWorker.schedule(app)
        } else {
            ProbeWorker.cancel(app)
            ReportWorker.cancel(app)
        }
        refresh()
    }
}
