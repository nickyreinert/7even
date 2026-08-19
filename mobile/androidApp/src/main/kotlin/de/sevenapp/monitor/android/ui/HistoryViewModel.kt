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
        _state.value = _state.value.copy(
            samples = store.pingsBetween(start, now).filter { filter == null || it.networkType == filter }.reversed(),
            throughput = store.throughputBetween(start, now).filter { filter == null || it.networkType == filter }.reversed(),
            drops = store.dropsOverlapping(start, now).reversed(),
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
