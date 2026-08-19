package de.sevenapp.monitor.android.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.core.StabilityScore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryState(
    val days: Int = 7,
    val samples: List<PingSample> = emptyList(),
    val throughput: List<ThroughputSample> = emptyList(),
    val drops: List<DropEvent> = emptyList(),
    val connectionFilter: NetworkType? = null,
    val summary: HistorySummary = HistorySummary(),
)

/**
 * Averages describe the centre; the p10 and consistency ratio show whether
 * that centre is something a customer can usually rely on.
 */
data class HistorySummary(
    val averageDownloadMbps: Double? = null,
    val averageUploadMbps: Double? = null,
    val medianLatencyMs: Double? = null,
    val jitterMs: Double? = null,
    val requestLossPct: Double? = null,
    val downloadP10Mbps: Double? = null,
    val uploadP10Mbps: Double? = null,
    val downloadConsistencyPct: Double? = null,
    val uploadConsistencyPct: Double? = null,
    val stability: StabilityScore.Result? = null,
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RoomMonitorStore.get(app)
    private val _state = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = _state.asStateFlow()

    init { refresh() }

    fun setDays(days: Int) {
        _state.value = _state.value.copy(days = days)
        refresh()
    }

    fun setConnectionFilter(network: NetworkType?) {
        _state.value = _state.value.copy(connectionFilter = network)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val start = now - _state.value.days * 24L * 60 * 60 * 1000
        val filter = _state.value.connectionFilter
        val samples = store.pingsBetween(start, now).filter { filter == null || it.networkType == filter }.reversed()
        val throughput = store.throughputBetween(start, now).filter { filter == null || it.networkType == filter }.reversed()
        val drops = store.dropsOverlapping(start, now).reversed()
        val rtts = samples.mapNotNull { it.rttMs }
        val down = throughput.mapNotNull { it.downMbps }
        val up = throughput.mapNotNull { it.upMbps }
        val loss = if (samples.isEmpty()) null else samples.count { !it.ok } * 100.0 / samples.size
        fun consistency(values: List<Double>): Double? {
            val typical = Stats.median(values) ?: return null
            val reliable = Stats.percentile(values, 0.1) ?: return null
            return if (typical <= 0.0) null else (reliable / typical * 100.0).coerceIn(0.0, 100.0)
        }
        _state.value = _state.value.copy(
            samples = samples,
            throughput = throughput,
            drops = drops,
            summary = HistorySummary(
                averageDownloadMbps = down.takeIf { it.isNotEmpty() }?.average(),
                averageUploadMbps = up.takeIf { it.isNotEmpty() }?.average(),
                medianLatencyMs = Stats.median(rtts),
                jitterMs = rtts.takeIf { it.size >= 2 }?.let(Stats::stdDev),
                requestLossPct = loss,
                downloadP10Mbps = Stats.percentile(down, 0.1),
                uploadP10Mbps = Stats.percentile(up, 0.1),
                downloadConsistencyPct = consistency(down),
                uploadConsistencyPct = consistency(up),
                stability = if (samples.isEmpty()) null else StabilityScore.compute(
                    windowStartEpochMs = start,
                    nowEpochMs = now,
                    drops = drops,
                    avgJitterMs = rtts.takeIf { it.size >= 2 }?.let(Stats::stdDev),
                    avgLossPct = loss ?: 0.0,
                ),
            ),
        )
    }

    fun shareExport() {
        val state = _state.value
        val rows = buildString {
            appendLine("timestamp,type,connection,ping_ms,download_mbps,upload_mbps")
            state.samples.forEach { sample ->
                appendLine("${sample.atEpochMs},ping,${sample.networkType},${sample.rttMs ?: ""},,")
            }
            state.throughput.forEach { sample ->
                appendLine("${sample.atEpochMs},throughput,${sample.networkType},,${sample.downMbps ?: ""},${sample.upMbps ?: ""}")
            }
            state.drops.forEach { drop ->
                appendLine("${drop.startedAtEpochMs},drop,,,${drop.endedAtEpochMs ?: "ongoing"},")
            }
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TEXT, rows)
                    putExtra(Intent.EXTRA_TITLE, "7even-history.csv")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                "Export 7even history",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
