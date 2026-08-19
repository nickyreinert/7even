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
import java.util.Calendar

enum class HistoryAggregation(val label: String) { NONE("None — every check"), DAY("Day"), WEEK("Week") }

data class HistoryState(
    val historyDays: Int = 0,
    val samples: List<PingSample> = emptyList(),
    val throughput: List<ThroughputSample> = emptyList(),
    val drops: List<DropEvent> = emptyList(),
    val connectionFilter: NetworkType = NetworkType.WIFI,
    val ssidFilter: String? = null,
    val ssids: List<String> = emptyList(),
    val aggregation: HistoryAggregation = HistoryAggregation.NONE,
    val chartPings: List<PingSample> = emptyList(),
    val chartThroughput: List<ThroughputSample> = emptyList(),
    val chartLossPct: List<Double?> = emptyList(),
    val chartJitterMs: List<Double?> = emptyList(),
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

    fun setConnectionFilter(network: NetworkType) {
        _state.value = _state.value.copy(connectionFilter = network)
        refresh()
    }

    fun setSsidFilter(ssid: String?) {
        _state.value = _state.value.copy(ssidFilter = ssid)
        refresh()
    }

    fun setAggregation(aggregation: HistoryAggregation) {
        _state.value = _state.value.copy(aggregation = aggregation)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val start = store.oldestMeasurementAt() ?: now
        val filter = _state.value.connectionFilter
        val ssidFilter = _state.value.ssidFilter
        val allPings = store.pingsBetween(start, now)
        val allThroughput = store.throughputBetween(start, now)
        val samples = allPings.filter { it.networkType == filter && (ssidFilter == null || it.ssid == ssidFilter) }.reversed()
        val throughput = allThroughput.filter { it.networkType == filter && (ssidFilter == null || it.ssid == ssidFilter) }.reversed()
        val drops = store.dropsOverlapping(start, now).reversed()
        val rtts = samples.mapNotNull { it.rttMs }
        val down = throughput.mapNotNull { it.downMbps }
        val up = throughput.mapNotNull { it.upMbps }
        val loss = if (samples.isEmpty()) null else samples.count { !it.ok } * 100.0 / samples.size
        val aggregated = aggregateForCharts(samples, throughput, _state.value.aggregation)
        fun consistency(values: List<Double>): Double? {
            val typical = Stats.median(values) ?: return null
            val reliable = Stats.percentile(values, 0.1) ?: return null
            return if (typical <= 0.0) null else (reliable / typical * 100.0).coerceIn(0.0, 100.0)
        }
        _state.value = _state.value.copy(
            samples = samples,
            throughput = throughput,
            drops = drops,
            historyDays = if (start == now) 0 else ((now - start) / (24L * 60 * 60 * 1000) + 1).toInt().coerceAtLeast(1),
            ssids = allPings.mapNotNull { it.ssid }.distinct().sorted(),
            chartPings = aggregated.pings,
            chartThroughput = aggregated.throughput,
            chartLossPct = aggregated.lossPct,
            chartJitterMs = aggregated.jitterMs,
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

    private data class ChartAggregation(
        val pings: List<PingSample>,
        val throughput: List<ThroughputSample>,
        val lossPct: List<Double?>,
        val jitterMs: List<Double?>,
    )

    private fun aggregateForCharts(
        pings: List<PingSample>,
        throughput: List<ThroughputSample>,
        aggregation: HistoryAggregation,
    ): ChartAggregation {
        if (aggregation == HistoryAggregation.NONE) {
            return ChartAggregation(
                pings = pings,
                throughput = throughput,
                lossPct = pings.chunked(3).map { group -> group.count { !it.ok } * 100.0 / group.size },
                jitterMs = pings.chunked(3).map { group -> group.mapNotNull { it.rttMs }.takeIf { it.size >= 2 }?.let(Stats::stdDev) },
            )
        }
        fun bucket(at: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = at }
            return if (aggregation == HistoryAggregation.DAY) c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
            else c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR)
        }
        val pingGroups = pings.groupBy { bucket(it.atEpochMs) }.values.toList()
        val throughputGroups = throughput.groupBy { bucket(it.atEpochMs) }.values.toList()
        return ChartAggregation(
            pings = pingGroups.map { group ->
                val values = group.mapNotNull { it.rttMs }
                PingSample(group.first().atEpochMs, values.takeIf { it.isNotEmpty() }?.average(), group.first().networkType, group.first().ssid)
            },
            throughput = throughputGroups.map { group ->
                ThroughputSample(
                    atEpochMs = group.first().atEpochMs,
                    downMbps = group.mapNotNull { it.downMbps }.takeIf { it.isNotEmpty() }?.average(),
                    upMbps = group.mapNotNull { it.upMbps }.takeIf { it.isNotEmpty() }?.average(),
                    networkType = group.first().networkType,
                    tier = group.first().tier,
                    partial = group.any { it.partial },
                    ssid = group.first().ssid,
                )
            },
            lossPct = pingGroups.map { group -> group.count { !it.ok } * 100.0 / group.size },
            jitterMs = pingGroups.map { group -> group.mapNotNull { it.rttMs }.takeIf { it.size >= 2 }?.let(Stats::stdDev) },
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
