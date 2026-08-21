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
import de.sevenapp.monitor.probe.SweepSizeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.Calendar

enum class HistoryAggregation(val label: String) { NONE("None — every check"), DAY("Day"), WEEK("Week") }

/** Which query window the date-navigation row selects — independent of [HistoryAggregation], which only buckets points inside that window for the charts. */
enum class HistoryRangeMode(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

/** A scheduled automatic cycle the system blocked, and why — see [de.sevenapp.monitor.android.data.CycleOutcomeEntity]. */
data class MissedCycle(val atEpochMs: Long, val reason: String)

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
    /** Every recorded rung, totalled per size — not filtered by [connectionFilter], since the two sweep ladders barely share sizes anyway. */
    val sweepSizeStats: List<SweepSizeStats> = emptyList(),
    val rangeMode: HistoryRangeMode = HistoryRangeMode.WEEK,
    /** The period currently on screen; null only when there is no data at all yet. */
    val rangeAnchor: LocalDate? = null,
    val rangeStartEpochMs: Long = 0L,
    val rangeEndEpochMs: Long = 0L,
    val canStepBackward: Boolean = false,
    val canStepForward: Boolean = false,
    /** Automatic cycles the system blocked within the selected range, newest first. */
    val missedCycles: List<MissedCycle> = emptyList(),
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

    /**
     * Changing the connection clears an SSID that cannot apply to it.
     *
     * An SSID names a Wi-Fi network, so keeping "Home-5G" selected while
     * switching to Mobile data asks for cellular samples taken on a Wi-Fi —
     * a filter that matches nothing, presented as an empty history rather than
     * as an impossible combination.
     */
    fun setConnectionFilter(network: NetworkType) {
        _state.value = _state.value.copy(
            connectionFilter = network,
            ssidFilter = _state.value.ssidFilter.takeIf { network == NetworkType.WIFI },
        )
        refresh()
    }

    fun setSsidFilter(ssid: String?) {
        // Selecting a network name implies Wi-Fi; the two cannot disagree.
        _state.value = _state.value.copy(
            ssidFilter = ssid,
            connectionFilter = if (ssid != null) NetworkType.WIFI else _state.value.connectionFilter,
        )
        refresh()
    }

    fun setAggregation(aggregation: HistoryAggregation) {
        _state.value = _state.value.copy(aggregation = aggregation)
        refresh()
    }

    fun setRangeMode(mode: HistoryRangeMode) {
        _state.value = _state.value.copy(rangeMode = mode)
        refresh()
    }

    fun stepRange(forward: Boolean) {
        val current = _state.value.rangeAnchor ?: return
        val sign = if (forward) 1 else -1
        val next = when (_state.value.rangeMode) {
            HistoryRangeMode.DAY -> current.plus(sign, DateTimeUnit.DAY)
            HistoryRangeMode.WEEK -> current.plus(7 * sign, DateTimeUnit.DAY)
            // Normalized to the 1st of the target month, so repeated stepping
            // cannot drift a day-of-month anchor into the wrong month length.
            HistoryRangeMode.MONTH -> LocalDate(current.year, current.month, 1).plus(sign, DateTimeUnit.MONTH)
        }
        _state.value = _state.value.copy(rangeAnchor = next)
        refresh()
    }

    private val zone = TimeZone.currentSystemDefault()

    private fun Long.toLocalDate(): LocalDate = Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date
    private fun LocalDate.toEpochMs(): Long = atStartOfDayIn(zone).toEpochMilliseconds()

    private fun LocalDate.startOfWeek(): LocalDate {
        val shift = dayOfWeek.isoDayNumber() - DayOfWeek.MONDAY.isoDayNumber()
        return plus(-shift, DateTimeUnit.DAY)
    }

    private fun DayOfWeek.isoDayNumber(): Int = ordinal + 1

    /** The period containing [anchor]: the day itself, Monday-to-Monday for a week, or 1st-to-1st for a month. */
    private fun periodFor(anchor: LocalDate, mode: HistoryRangeMode): Pair<LocalDate, LocalDate> = when (mode) {
        HistoryRangeMode.DAY -> anchor to anchor.plus(1, DateTimeUnit.DAY)
        HistoryRangeMode.WEEK -> {
            val start = anchor.startOfWeek()
            start to start.plus(7, DateTimeUnit.DAY)
        }
        HistoryRangeMode.MONTH -> {
            val start = LocalDate(anchor.year, anchor.month, 1)
            start to start.plus(1, DateTimeUnit.MONTH)
        }
    }

    fun refresh() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val oldest = store.oldestMeasurementAt()
        val latest = store.latestMeasurementAt()
        val filter = _state.value.connectionFilter
        val ssidFilter = _state.value.ssidFilter
        val mode = _state.value.rangeMode
        val historyDays = if (oldest == null) 0 else ((now - oldest) / (24L * 60 * 60 * 1000) + 1).toInt().coerceAtLeast(1)

        if (latest == null) {
            // No data at all — reset any stale anchor rather than keep
            // navigating a range that no longer refers to anything.
            _state.value = _state.value.copy(
                samples = emptyList(),
                throughput = emptyList(),
                drops = emptyList(),
                historyDays = 0,
                ssids = emptyList(),
                chartPings = emptyList(),
                chartThroughput = emptyList(),
                chartLossPct = emptyList(),
                chartJitterMs = emptyList(),
                sweepSizeStats = store.sweepRungTotals(),
                summary = HistorySummary(),
                rangeAnchor = null,
                rangeStartEpochMs = 0L,
                rangeEndEpochMs = 0L,
                canStepBackward = false,
                canStepForward = false,
                missedCycles = emptyList(),
            )
            return@launch
        }

        val latestDate = latest.toLocalDate()
        val oldestDate = (oldest ?: latest).toLocalDate()
        val anchor = _state.value.rangeAnchor ?: latestDate
        val (rangeStartDate, rangeEndDate) = periodFor(anchor, mode)
        val rangeStart = rangeStartDate.toEpochMs()
        val rangeEnd = rangeEndDate.toEpochMs()
        val oldestPeriodStart = periodFor(oldestDate, mode).first
        val latestPeriodStart = periodFor(latestDate, mode).first

        val allPings = store.pingsBetween(rangeStart, rangeEnd)
        val allThroughput = store.throughputBetween(rangeStart, rangeEnd)
        // Ascending by timestamp. These lists feed the charts, and `reversed()`
        // made the x axis run newest-to-oldest — every trend line was drawn
        // backwards in time.
        val samples = allPings
            .filter { it.networkType == filter && (ssidFilter == null || it.ssid == ssidFilter) }
            .sortedBy { it.atEpochMs }
        val throughput = allThroughput
            .filter { it.networkType == filter && (ssidFilter == null || it.ssid == ssidFilter) }
            .sortedBy { it.atEpochMs }
        val drops = store.dropsOverlapping(rangeStart, rangeEnd).sortedBy { it.startedAtEpochMs }
        val missedCycles = store.missedCycleOutcomesBetween(rangeStart, rangeEnd)
            .sortedByDescending { it.atEpochMs }
            .map { MissedCycle(it.atEpochMs, it.reason ?: "unknown") }
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
            historyDays = historyDays,
            ssids = allPings.mapNotNull { it.ssid }.distinct().sorted(),
            chartPings = aggregated.pings,
            chartThroughput = aggregated.throughput,
            chartLossPct = aggregated.lossPct,
            chartJitterMs = aggregated.jitterMs,
            sweepSizeStats = store.sweepRungTotals(),
            rangeAnchor = anchor,
            rangeStartEpochMs = rangeStart,
            rangeEndEpochMs = rangeEnd,
            canStepBackward = rangeStartDate > oldestPeriodStart,
            canStepForward = rangeStartDate < latestPeriodStart,
            missedCycles = missedCycles,
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
                // Drops and the stability score they feed are deliberately
                // NOT filtered: a drop is the period with no usable network, so
                // it cannot be attributed to Wi-Fi or mobile. The UI labels
                // this figure as covering all connections rather than letting
                // it look like a per-filter number.
                stability = if (samples.isEmpty()) null else StabilityScore.compute(
                    windowStartEpochMs = rangeStart,
                    nowEpochMs = minOf(rangeEnd, now),
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

    fun shareExport() = viewModelScope.launch {
        val app = getApplication<Application>()
        val csv = HistoryCsv.build(_state.value)
        // Written to a file and shared by URI. A 90-day export in
        // Intent.EXTRA_TEXT can exceed Android's Binder transaction limit,
        // which surfaces as a crash rather than a handled failure.
        val uri = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "7even-history.csv")
            file.writeText(csv)
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        }
        app.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, "7even-history.csv")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Export 7even history",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
