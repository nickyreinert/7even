package de.sevenapp.monitor.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.probe.LiveTestConfig

private enum class Screen { MAIN, HISTORY, SETTINGS, HELP }

private val SevenColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2563EB),
    secondary = androidx.compose.ui.graphics.Color(0xFFF59E0B),
    background = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
    surface = androidx.compose.ui.graphics.Color(0xFF161616),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF202020),
)

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = SevenColors) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("7even", fontWeight = FontWeight.Bold) },
                            actions = { TextButton(onClick = { screen = Screen.HELP }) { Text("?") } },
                        )
                    },
                    bottomBar = {
                        if (screen != Screen.HELP) NavigationBar {
                            Row(Modifier.fillMaxWidth()) {
                                NavItem("Monitor", screen == Screen.MAIN, Modifier.weight(1f)) { screen = Screen.MAIN }
                                NavItem("History", screen == Screen.HISTORY, Modifier.weight(1f)) { screen = Screen.HISTORY }
                                NavItem("Settings", screen == Screen.SETTINGS, Modifier.weight(1f)) { screen = Screen.SETTINGS }
                            }
                        }
                    },
                ) { padding ->
                    when (screen) {
                        Screen.MAIN -> DashboardScreen(Modifier.padding(padding))
                        Screen.HISTORY -> HistoryScreen(Modifier.padding(padding))
                        Screen.SETTINGS -> SettingsScreen(Modifier.padding(padding))
                        Screen.HELP -> HelpScreen(Modifier.padding(padding), onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.padding(horizontal = 4.dp)) {
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var logOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = if (state.manualTestRunning) viewModel::stopManualTest else viewModel::runManualTest,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.manualTestRunning) "Stop monitoring" else "Start monitoring")
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Measure automatically", style = MaterialTheme.typography.titleMedium)
                            Text("Battery-aware background checks", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.monitoringEnabled, onCheckedChange = viewModel::setMonitoring)
                    }
                    Text("Test duration", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DurationChip("1 min", LiveTestConfig.MIN_DURATION_MS, state.liveTestDurationMs, viewModel)
                        DurationChip("5 min", 5 * 60_000L, state.liveTestDurationMs, viewModel)
                        DurationChip("15 min", 15 * 60_000L, state.liveTestDurationMs, viewModel)
                        DurationChip("Until stopped", Long.MAX_VALUE, state.liveTestDurationMs, viewModel)
                    }
                    Text("Use connection", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.monitoringNetworks == setOf(NetworkType.WIFI), { viewModel.setMonitoringNetworks(setOf(NetworkType.WIFI)) }, label = { Text("Wi-Fi") })
                        FilterChip(state.monitoringNetworks == setOf(NetworkType.CELLULAR), { viewModel.setMonitoringNetworks(setOf(NetworkType.CELLULAR)) }, label = { Text("Mobile") })
                        FilterChip(state.monitoringNetworks == setOf(NetworkType.WIFI, NetworkType.CELLULAR), { viewModel.setMonitoringNetworks(setOf(NetworkType.WIFI, NetworkType.CELLULAR)) }, label = { Text("Both") })
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val hasLatestTest = state.manualTestRunning || state.livePingMs.isNotEmpty() ||
                        state.liveDownloadMbps.isNotEmpty() || state.liveUploadMbps.isNotEmpty()
                    if (!hasLatestTest) {
                        Text("Latest completed test", style = MaterialTheme.typography.labelMedium)
                        Text("Ping (ms)", style = MaterialTheme.typography.labelMedium)
                        LatencyChart(state.recentPings)
                        Text("Download (Mbps)", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.recentThroughput.map { it.downMbps }, suffix = " Mbps", heightDp = 90)
                        Text("Upload (Mbps)", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(
                            state.recentThroughput.map { it.upMbps },
                            color = androidx.compose.ui.graphics.Color(0xFFF59E0B),
                            suffix = " Mbps",
                            heightDp = 90,
                        )
                    } else {
                        Text(
                            if (state.manualTestRunning) "Live — current test" else "Latest test result",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text("Ping (ms)", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.livePingMs, color = androidx.compose.ui.graphics.Color(0xFFA0A0A0), suffix = " ms", heightDp = 90)
                        Text("Download (Mbps)", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.liveDownloadMbps.map { it }, suffix = " Mbps", heightDp = 90)
                        Text("Upload (Mbps)", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.liveUploadMbps.map { it }, color = androidx.compose.ui.graphics.Color(0xFFF59E0B), suffix = " Mbps", heightDp = 90)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { logOpen = !logOpen }, modifier = Modifier.fillMaxWidth()) { Text(if (logOpen) "Hide probe log" else "Show probe log") }
            if (logOpen) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    state.recentPings.takeLast(20).reversed().forEach { sample ->
                        Text("${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sample.atEpochMs))}  " + (sample.rttMs?.let { "${it.toInt()} ms" } ?: "no reply") + "  ${sample.networkType.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.recentPings.isEmpty()) Text("No probes yet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SummaryCards(state) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Latest results", style = MaterialTheme.typography.titleMedium)
                    Text("Download — per-check result (Mbps)", style = MaterialTheme.typography.labelMedium)
                    ThroughputBars(state.recentThroughput.map { it.downMbps }, upload = false)
                    Text("Upload — per-check result (Mbps)", style = MaterialTheme.typography.labelMedium)
                    ThroughputBars(state.recentThroughput.map { it.upMbps }, upload = true)
                    Text("Request Loss (%)", style = MaterialTheme.typography.labelMedium)
                    LossChart(state.recentPings)
                    Text("Size Sweep · download — green=passed, red=failed", style = MaterialTheme.typography.labelMedium)
                    SweepResultChart(state.latestDownloadSweep, heightDp = sweepHeight(state.latestDownloadSweep))
                    Text("Size Sweep · upload — green=passed, red=failed", style = MaterialTheme.typography.labelMedium)
                    SweepResultChart(state.latestUploadSweep, heightDp = sweepHeight(state.latestUploadSweep))
                    Text("Showing only the latest checks. Browse History for older measurements.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DurationChip(label: String, durationMs: Long, selectedDurationMs: Long, viewModel: DashboardViewModel) {
    FilterChip(
        selected = selectedDurationMs == durationMs,
        onClick = { viewModel.setLiveTestDuration(durationMs) },
        label = { Text(label) },
    )
}

@Composable
private fun SummaryCards(state: DashboardState) {
    val averageDownload = state.recentThroughput.mapNotNull { it.downMbps }.takeIf { it.isNotEmpty() }?.average()
    val averageUpload = state.recentThroughput.mapNotNull { it.upMbps }.takeIf { it.isNotEmpty() }?.average()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Avg download", formatMbps(averageDownload), Modifier.weight(1f))
            MetricCard("Avg upload", formatMbps(averageUpload), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Latency", Format.millis(state.latencyMedianMs), Modifier.weight(1f))
            MetricCard("Jitter", Format.millis(state.jitterMs), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Request loss", Format.percent(state.lossPct), Modifier.weight(1f))
            MetricCard("Drops", state.dropCount.toString(), Modifier.weight(1f))
        }
        MetricCard("Mobile data this month", Format.bytes(state.meteredBytesThisMonth), Modifier.fillMaxWidth())
        state.stability?.let { score ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Stability Score", style = MaterialTheme.typography.titleMedium)
                        Text("%.1f".format(score.composite), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Uptime: %.1f%%   Jitter score: %.1f   Loss score: %.1f".format(
                            score.uptimePct,
                            score.jitterScore,
                            score.lossScore,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Not a standard metric — a transparent composite of uptime, jitter, and loss " +
                            "(weights: 50/30/20). Shown with its inputs so you can judge it yourself, not as an authoritative score.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatMbps(value: Double?): String = value?.let { "%.1f Mbps".format(it) } ?: "—"

private fun sweepHeight(results: List<de.sevenapp.monitor.probe.SweepResult>): Int =
    (results.sumOf { it.trials } * 20).coerceAtLeast(128)

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    } }
}

@Composable
private fun HistoryScreen(modifier: Modifier, viewModel: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by viewModel.state.collectAsState()
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.headlineMedium)
                    Text("Browse measurements collected over time.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = viewModel::shareExport) { Text("Export") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 7, 30, 90).forEach { days ->
                    FilterChip(selected = state.days == days, onClick = { viewModel.setDays(days) }, label = { Text("${days}d") })
                }
            }
            Text("Connection", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.connectionFilter == null, onClick = { viewModel.setConnectionFilter(null) }, label = { Text("All") })
                FilterChip(selected = state.connectionFilter == NetworkType.WIFI, onClick = { viewModel.setConnectionFilter(NetworkType.WIFI) }, label = { Text("Wi-Fi") })
                FilterChip(selected = state.connectionFilter == NetworkType.CELLULAR, onClick = { viewModel.setConnectionFilter(NetworkType.CELLULAR) }, label = { Text("Mobile") })
            }
        }
        item { HistoryChartCard("Ping", "Latency per probe", state.samples.map { it.rttMs }, "ms") }
        item { HistoryChartCard("Download", "Speed-test download", state.throughput.map { it.downMbps }, " Mbps") }
        item { HistoryChartCard("Upload", "Speed-test upload", state.throughput.map { it.upMbps }, " Mbps") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Drop events", style = MaterialTheme.typography.titleMedium)
                    if (state.drops.isEmpty()) {
                        Text("No connection drops in this period.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.drops.forEach { drop ->
                            val started = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(drop.startedAtEpochMs))
                            val duration = if (drop.ongoing) "ongoing" else Format.duration(drop.durationMs(System.currentTimeMillis()))
                            Text("$started · $duration", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (state.samples.isEmpty()) item { Text("No measurements yet. Start monitoring to build your history.") }
    }
}

@Composable
private fun HistoryChartCard(title: String, description: String, values: List<Double?>, suffix: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
            MetricLineChart(values = values, suffix = suffix)
        }
    }
}

@Composable
private fun HelpScreen(modifier: Modifier, onBack: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Help", style = MaterialTheme.typography.headlineMedium); Text("How 7even measures your connection", style = MaterialTheme.typography.bodyMedium) }
        item { HelpBlock("What is measured", "7even records latency, jitter, request loss, download and upload throughput, plus sustained connection drops.") }
        item { HelpBlock("Battery and data", "Background monitoring uses lightweight probes. Speed tests are less frequent, and their size is configurable from the Monitor screen.") }
        item { HelpBlock("Protocol", "Latency uses an HTTP request to Cloudflare's trace endpoint. Throughput uses download and upload requests to the speed-test service. A timeout means no reply was received in time; it does not prove the whole connection was down.") }
        item { HelpBlock("Background timing", "Android runs scheduled work approximately, not exactly. It may defer a check to protect battery life, especially in Doze mode.") }
        item { TextButton(onClick = onBack) { Text("Back to monitor") } }
    }
}

@Composable
private fun HelpBlock(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall)
    } }
}

private fun intervalLabel(minutes: Int): String = when (minutes) {
    15 -> "Every 15 minutes"
    60 -> "Every hour"
    1440 -> "Every 24 hours"
    10080 -> "Once a week"
    else -> "Every $minutes minutes"
}
