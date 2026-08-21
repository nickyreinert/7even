package de.sevenapp.monitor.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.probe.LiveTestConfig
import kotlinx.coroutines.launch

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
                val context = LocalContext.current
                var ssidPermissionGranted by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                }
                val ssidPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> ssidPermissionGranted = granted }
                // Asked when the user turns monitoring on, not at launch: that
                // is the moment the request has a reason the user can evaluate.
                // The permission was declared in the manifest but never
                // requested at all, so on Android 13+ every scheduled report
                // was silently undeliverable.
                val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* Denial is handled in-app: reports stay visible on the Monitor screen. */ }
                val requestNotificationPermission: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                BackHandler(enabled = screen == Screen.HELP) { screen = Screen.MAIN }
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
                    val contentModifier = Modifier.padding(padding).pointerInput(screen) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount },
                            onDragEnd = {
                                if (totalDrag <= -96f) screen = screen.nextSwipeScreen()
                                if (totalDrag >= 96f) screen = screen.previousSwipeScreen()
                                totalDrag = 0f
                            },
                        )
                    }
                    when (screen) {
                        Screen.MAIN -> DashboardScreen(
                            contentModifier,
                            requestNotificationPermission = requestNotificationPermission,
                        )
                        Screen.HISTORY -> HistoryScreen(contentModifier, onRequestSsidPermission = {
                            ssidPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }, ssidPermissionGranted = ssidPermissionGranted)
                        Screen.SETTINGS -> SettingsScreen(contentModifier)
                        Screen.HELP -> HelpScreen(Modifier.padding(padding), onBack = { screen = Screen.MAIN })
                    }
                }
            }
        }
    }
}

private fun Screen.nextSwipeScreen(): Screen = when (this) {
    Screen.MAIN -> Screen.HISTORY
    Screen.HISTORY -> Screen.SETTINGS
    else -> this
}

private fun Screen.previousSwipeScreen(): Screen = when (this) {
    Screen.SETTINGS -> Screen.HISTORY
    Screen.HISTORY -> Screen.MAIN
    else -> this
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
    /**
     * Requests POST_NOTIFICATIONS if it is not already granted.
     *
     * Every kind of run that shows a notification calls this before starting —
     * not just the background-monitoring switch. A manual test raises
     * [de.sevenapp.monitor.android.work.LiveTestForegroundService]'s
     * notification too, and without ever asking for the permission first, the
     * OS silently drops that notification with no error: the run still works,
     * it is just invisible once the app is minimized.
     */
    requestNotificationPermission: () -> Unit = {},
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var scheduleLogOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // The Dashboard and Settings screens each hold their own ViewModel
    // instance over the same persisted config — switching to Settings and
    // flipping "automatic measurement" there left this screen's copy of
    // monitoringEnabled stale until something else happened to call
    // refresh(). Composables are torn down and rebuilt on every screen
    // switch here (there's no shared nav back-stack), so re-running this on
    // each entry is what actually re-syncs it.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), state = listState) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (state.manualTestRunning) {
                                viewModel.stopManualTest()
                            } else {
                                requestNotificationPermission()
                                viewModel.runManualTest()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(manualTestButtonLabel(state))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Measure automatically", style = MaterialTheme.typography.titleMedium)
                            Text("Battery-aware background checks", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(state.monitoringEnabled, onCheckedChange = { enabled ->
                            if (enabled) requestNotificationPermission()
                            viewModel.setMonitoring(enabled)
                        })
                    }
                    if (state.monitoringEnabled) {
                        Text(
                            when (val tests = state.testsSinceActivation) {
                                null, 0 -> "No tests run yet since activated"
                                else -> {
                                    val missed = state.missedSinceActivation ?: 0
                                    "$tests test${if (tests == 1) "" else "s"} run" +
                                        if (missed > 0) " · $missed missed since activated" else " since activated"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            // Jumps down to the "Schedule log" section at the
                            // bottom instead of making the user hunt for why a
                            // run was missed.
                            modifier = Modifier.clickable {
                                scheduleLogOpen = true
                                // Coerced to the last real item by LazyColumn itself.
                                coroutineScope.launch { listState.animateScrollToItem(Int.MAX_VALUE) }
                            },
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    }
                    Text("Use connection", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.manualNetwork == de.sevenapp.monitor.core.NetworkPreference.WIFI,
                            onClick = { viewModel.setManualNetwork(de.sevenapp.monitor.core.NetworkPreference.WIFI) },
                            enabled = state.wifiAvailable,
                            label = { Text("Wi-Fi") },
                        )
                        FilterChip(state.manualNetwork == de.sevenapp.monitor.core.NetworkPreference.CELLULAR, { viewModel.setManualNetwork(de.sevenapp.monitor.core.NetworkPreference.CELLULAR) }, label = { Text("Mobile") })
                    }
                    // Carrier-side throttling — the whole reason this probe
                    // exists — is a cellular/mobile-plan thing. Wi-Fi has no
                    // "Taktung" burst allowance to drain, so offering this
                    // choice there would just be a confusing no-op.
                    if (state.manualNetwork == de.sevenapp.monitor.core.NetworkPreference.CELLULAR) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sustained speed check", style = MaterialTheme.typography.titleMedium)
                                Text("Adds ~4 MB to reveal the real throttled speed — see Help", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(state.sustainedProbeEnabled, onCheckedChange = viewModel::setSustainedProbe)
                        }
                    }
                    state.manualTestError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        state.pendingReport?.let { report ->
            item { PendingReportCard(report, onDismiss = viewModel::acknowledgeReport) }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    val hasLatestTest = state.manualTestRunning || state.livePings.isNotEmpty() ||
                        state.liveDownloadMbps.isNotEmpty() || state.liveUploadMbps.isNotEmpty()
                    if (!hasLatestTest) {
                        Text("Latest completed test", style = MaterialTheme.typography.labelMedium)
                        Text("Ping (ms)", style = MaterialTheme.typography.labelMedium)
                        LatencyChart(state.recentPings)
                        Text("Download rate", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.recentThroughput.map { it.downMbps }, suffix = " Mbps", heightDp = 90)
                        Text("Upload rate", style = MaterialTheme.typography.labelMedium)
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
                        LatencyChart(state.livePings, heightDp = 90)
                        Text(if (state.ranSustainedProbe) "Download rate (sustained probe)" else "Download rate", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.liveDownloadMbps.map { it }, suffix = " Mbps", heightDp = 90)
                        Text(if (state.ranSustainedProbe) "Upload rate (sustained probe)" else "Upload rate", style = MaterialTheme.typography.labelMedium)
                        MetricLineChart(state.liveUploadMbps.map { it }, color = androidx.compose.ui.graphics.Color(0xFFF59E0B), suffix = " Mbps", heightDp = 90)
                        if (state.ranSustainedProbe) {
                            // The whole point of the sustained probe: an early
                            // sample is burst-credit-dominated, a late one
                            // (once enough data has crossed) is not — see
                            // "Why a 64 kbit/s plan can show 4 Mbit/s" in Help.
                            // "Steady" here IS the estimated refill rate: the
                            // speed the connection settles to once any burst
                            // allowance is spent.
                            Text(
                                "Estimated refill rate — down: ${formatMbps(steadyStateMbps(state.liveDownloadMbps))} " +
                                    "(burst was ${formatMbps(peakMbps(state.liveDownloadMbps))}) · " +
                                    "up: ${formatMbps(steadyStateMbps(state.liveUploadMbps))} " +
                                    "(burst was ${formatMbps(peakMbps(state.liveUploadMbps))})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
        item {
            TextButton(onClick = { logOpen = !logOpen }, modifier = Modifier.fillMaxWidth()) { Text(if (logOpen) "Hide probe log" else "Show probe log") }
            if (logOpen) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val showManualResult = state.manualTestRunning || state.livePings.isNotEmpty() ||
                        state.liveDownloadMbps.isNotEmpty() || state.liveUploadMbps.isNotEmpty()
                    if (showManualResult) {
                        // Every phase of the current/last manual run — ping,
                        // download/upload stream, and each sweep rung as it
                        // finishes — not just ping. A stream or a multi-minute
                        // cellular sweep used to leave this list showing
                        // nothing while it ran, indistinguishable from a hang.
                        state.liveLog.takeLast(60).reversed().forEach { entry ->
                            Text(
                                "${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(entry.atEpochMs))}  ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (state.liveLog.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        state.recentPings.takeLast(20).reversed().forEach { sample ->
                            Text("${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sample.atEpochMs))}  " + (sample.rttMs?.let { "${it.toInt()} ms" } ?: "no reply") + "  ${sample.networkType.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.recentPings.isEmpty()) Text("No probes yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text("Last results", style = MaterialTheme.typography.titleMedium) }
        item { SummaryCards(state) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val showManualResult = state.manualTestRunning || state.livePings.isNotEmpty() ||
                        state.liveDownloadMbps.isNotEmpty() || state.liveUploadMbps.isNotEmpty()
                    Text("Request Loss (%)", style = MaterialTheme.typography.labelMedium)
                    LossChart(if (showManualResult) state.livePings else state.recentPings)
                    Text(
                        "Measured during the ping phase, which runs on its own — no download or " +
                            "upload is competing for the connection, so a probe that goes unanswered " +
                            "is a lost request rather than a busy link.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Size Sweep · download — green=passed, red=failed", style = MaterialTheme.typography.labelMedium)
                    SweepResultChart(state.latestDownloadSweep, heightDp = sweepHeight(state.latestDownloadSweep))
                    Text("Size Sweep · upload — green=passed, red=failed", style = MaterialTheme.typography.labelMedium)
                    SweepResultChart(state.latestUploadSweep, heightDp = sweepHeight(state.latestUploadSweep))
                    Text("Showing only the latest checks. Browse History for older measurements.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ScheduleLogCard(state.scheduleLog, expanded = scheduleLogOpen, onToggle = { scheduleLogOpen = !scheduleLogOpen }) }
    }
}

/**
 * Every recent [de.sevenapp.monitor.android.work.ProbeWorker] wakeup — whether
 * it ran, and briefly why not when it didn't (no power, Android blocked it,
 * and so on). Collapsed by default; the "N missed since activated" summary
 * above links straight down to it.
 */
@Composable
private fun ScheduleLogCard(entries: List<ScheduleLogEntry>, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide schedule log" else "Show schedule log")
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Schedule log", style = MaterialTheme.typography.titleMedium)
                    if (entries.isEmpty()) {
                        Text("No scheduled checks yet.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        entries.forEach { entry ->
                            val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                .format(java.util.Date(entry.atEpochMs))
                            Text(
                                "$at · " + if (entry.ran) "Successful" else "Not run – ${entry.reason ?: "unknown reason"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Title and body for the Help entry explaining why a throttled plan can
 * briefly show a much higher speed than its stated cap — the "Taktung 10 kB"
 * behavior a user reading their own tariff spotted and asked about directly.
 * Help-only: it's background for interpreting a result, not a result itself,
 * so it doesn't compete with the charts on the Monitor screen.
 */
private const val BURST_THROTTLE_TITLE = "Why a \"64 kbit/s\" plan can show 4 Mbit/s"

private val burstThrottleBody =
    "Not a measuring mistake — throttled plans often work like a phone battery: " +
        "\"full-speed time\" quietly recharges while you're not downloading, up to some " +
        "limit. Your next download spends that recharge FIRST, at your phone's real " +
        "speed (often several Mbit/s), before dropping to the guaranteed 64 kbit/s once " +
        "it runs out. A short test can finish entirely on recharged time and never show " +
        "the real, throttled speed.\n\n" +
        "Example (numbers made up): a 3 MB \"tank\", phone speed 8 Mbit/s. A 1 MB photo " +
        "fits inside the tank, so it flies down at 8 Mbit/s in about a second. A 20 MB " +
        "video doesn't: 3 MB burns through the tank in 3 seconds, then the remaining " +
        "17 MB crawl in at 64 kbit/s — over 35 minutes. Same connection, wildly " +
        "different results, because file size decided how much of the tank got used.\n\n" +
        "\"Sustained speed check\" in Settings runs long enough to drain the tank and " +
        "show the speed that's actually left."

@Composable
private fun SummaryCards(state: DashboardState) {
    // While displaying a manual test, never fall back to an older persisted
    // Wi-Fi sample. An empty live rate is honest evidence that this run has not
    // measured that direction yet; a previous network's result is not.
    val showManualResult = state.manualTestRunning || state.livePings.isNotEmpty() ||
        state.liveDownloadMbps.isNotEmpty() || state.liveUploadMbps.isNotEmpty()
    // Every live ping came from the ping phase, which runs on its own with no
    // stream competing for the link — so these failures are unanswered probes
    // rather than a saturated connection queueing.
    val pings = if (showManualResult) state.livePings else state.recentPings
    val rtts = pings.mapNotNull { it.rttMs }
    val failures = pings.count { !it.ok }
    val latency = Format.millis(Stats.median(rtts))
    val jitter = Format.millis(if (rtts.size >= 2) Stats.stdDev(rtts) else null)
    val loss = if (pings.isEmpty()) null else (failures.toDouble() / pings.size) * 100.0
    // The LAST live sample, not the mean of all of them. Live samples are
    // cumulative averages over the phase so far, so the final one already IS
    // the phase's average — and averaging a converging series again drags the
    // result toward the noisy first few samples taken before the rate had
    // settled, which on a slow link is where the wildest numbers are.
    val averageDownload = if (showManualResult) {
        state.liveDownloadMbps.lastOrNull()
    } else {
        state.recentThroughput.mapNotNull { it.downMbps }.takeIf { it.isNotEmpty() }?.average()
    }
    val averageUpload = if (showManualResult) {
        state.liveUploadMbps.lastOrNull()
    } else {
        state.recentThroughput.mapNotNull { it.upMbps }.takeIf { it.isNotEmpty() }?.average()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(if (showManualResult) "Manual download" else "Avg download", formatMbps(averageDownload), Modifier.weight(1f))
            MetricCard(if (showManualResult) "Manual upload" else "Avg upload", formatMbps(averageUpload), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Latency", latency, Modifier.weight(1f))
            MetricCard("Jitter", jitter, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Request loss", Format.percent(loss), Modifier.weight(1f))
            MetricCard("Drops", if (showManualResult) "—" else state.dropCount.toString(), Modifier.weight(1f))
        }
        // "Last 30 days", not "this month": the counter is a rolling window, and
        // calling it a calendar month invites the reader to compare it against a
        // carrier bill that resets on a different day.
        MetricCard("Mobile data, last 30 days", Format.bytes(state.meteredBytesThisMonth), Modifier.fillMaxWidth())
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

/** The highest cumulative-average rate seen — on a decaying burst-then-throttle curve, that's near the start. */
private fun peakMbps(samples: List<Double>): Double? = samples.maxOrNull()

/** The last fifth of samples, averaged — where a long-enough transfer has settled after spending any burst credit. */
private fun steadyStateMbps(samples: List<Double>): Double? {
    if (samples.isEmpty()) return null
    return samples.takeLast((samples.size / 5).coerceAtLeast(1)).average()
}

internal fun formatMbps(value: Double?): String = value?.let {
    when {
        it >= 1_000 -> "%.1f Gbit/s".format(it / 1_000)
        it >= 1 -> "%.1f Mbit/s".format(it)
        else -> "%.0f Kbit/s".format(it * 1_000)
    }
} ?: "—"

/**
 * "Stop · 2/5 · Download stream · 00:07".
 *
 * The clock is the *current phase's*, not the whole run's: the configured
 * duration is what each of ping, download, and upload gets, so a whole-run
 * stopwatch would run past the number the user picked. A phase with no
 * deadline (the size sweep, or an unlimited run) counts up instead of down.
 */
private fun manualTestButtonLabel(state: DashboardState): String {
    if (!state.manualTestRunning) return "Start monitoring"
    return "Stop · ${phaseProgressLabel(state)}"
}

/**
 * A generated report shown in the app itself.
 *
 * The report is the product; a notification is one way to deliver it. When
 * delivery is impossible — notification permission denied, notifications
 * switched off — this is what stops the result from being computed and thrown
 * away, which is what used to happen.
 */
@Composable
private fun PendingReportCard(
    report: de.sevenapp.monitor.android.data.RoomMonitorStore.Companion.PendingReport,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(report.title, style = MaterialTheme.typography.titleMedium)
            Text(
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(java.util.Date(report.generatedAtEpochMs)),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(report.body, style = MaterialTheme.typography.bodySmall)
            if (!report.wasDelivered) {
                Text(
                    "This report could not be delivered as a notification — notifications are off for 7even. " +
                        "It is kept here so nothing is lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

private fun sweepHeight(results: List<de.sevenapp.monitor.probe.SweepResult>): Int =
    (results.size * 36).coerceAtLeast(128)

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Card(modifier) { Column(Modifier.padding(12.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.headlineSmall)
    } }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistoryScreen(
    modifier: Modifier,
    onRequestSsidPermission: () -> Unit,
    ssidPermissionGranted: Boolean,
    viewModel: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var ssidMenuOpen by rememberSaveable { mutableStateOf(false) }
    var aggregationMenuOpen by rememberSaveable { mutableStateOf(false) }
    var missedCyclesOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (state.historyDays == 0) "No measurements recorded yet" else "${state.historyDays} days of recorded history",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = viewModel::shareExport) { Text("Export") }
            }
            Text("Connection", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.connectionFilter == NetworkType.WIFI, onClick = { viewModel.setConnectionFilter(NetworkType.WIFI) }, label = { Text("Wi-Fi") })
                FilterChip(selected = state.connectionFilter == NetworkType.CELLULAR, onClick = { viewModel.setConnectionFilter(NetworkType.CELLULAR) }, label = { Text("Mobile") })
            }
            if (state.connectionFilter == NetworkType.WIFI) {
                Text("Wi-Fi network", style = MaterialTheme.typography.labelMedium)
                if (state.ssids.isEmpty()) {
                    if (ssidPermissionGranted) {
                        Text("No named Wi-Fi checks recorded yet. Run a new Wi-Fi measurement to add this network.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        TextButton(onClick = onRequestSsidPermission) { Text("Allow Wi-Fi name access") }
                        Text("New Wi-Fi checks can be filtered by network after permission is allowed.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Box {
                        OutlinedButton(onClick = { ssidMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(state.ssidFilter ?: "All Wi-Fi networks", modifier = Modifier.weight(1f))
                            Text("⌄")
                        }
                        DropdownMenu(expanded = ssidMenuOpen, onDismissRequest = { ssidMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("All Wi-Fi networks") }, onClick = {
                                ssidMenuOpen = false
                                viewModel.setSsidFilter(null)
                            })
                            state.ssids.forEach { ssid ->
                                DropdownMenuItem(text = { Text(ssid) }, onClick = {
                                    ssidMenuOpen = false
                                    viewModel.setSsidFilter(ssid)
                                })
                            }
                        }
                    }
                }
            }
        }
        stickyHeader { HistoryDateNavigationRow(state, viewModel) }
        item { HistorySummaryCards(state.summary) }
        item { HistoryEvidenceCard(state.summary) }
        item {
            Text("Chart aggregation", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { aggregationMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(state.aggregation.label, modifier = Modifier.weight(1f))
                    Text("⌄")
                }
                DropdownMenu(expanded = aggregationMenuOpen, onDismissRequest = { aggregationMenuOpen = false }) {
                    HistoryAggregation.entries.forEach { aggregation ->
                        DropdownMenuItem(text = { Text(aggregation.label) }, onClick = {
                            aggregationMenuOpen = false
                            viewModel.setAggregation(aggregation)
                        })
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Ping", style = MaterialTheme.typography.titleMedium)
                Text("Latency per probe; red bars are timed-out requests.", style = MaterialTheme.typography.bodySmall)
                LatencyChart(state.chartPings)
            } }
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Jitter", style = MaterialTheme.typography.titleMedium)
                Text("Variation within each probe group. Taller bars mean a less steady connection.", style = MaterialTheme.typography.bodySmall)
                ValueBarChart(state.chartJitterMs, androidx.compose.ui.graphics.Color(0xFFF59E0B), suffix = " ms")
            } }
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Request loss", style = MaterialTheme.typography.titleMedium)
                Text("Failed requests as a percentage of each probe group.", style = MaterialTheme.typography.bodySmall)
                ValueBarChart(state.chartLossPct, androidx.compose.ui.graphics.Color(0xFFF87171), suffix = "%")
            } }
        }
        item { HistoryChartCard("Download", "Each completed stream test. Compare the average with the reliable p10 result above.", state.chartThroughput.map { it.downMbps }, " Mbps") }
        item { HistoryChartCard("Upload", "Each completed stream test. Gaps mean that direction did not complete.", state.chartThroughput.map { it.upMbps }, " Mbps") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Drop events", style = MaterialTheme.typography.titleMedium)
                    Text("Each bar is one sustained outage; height is its duration.", style = MaterialTheme.typography.bodySmall)
                    DropEventChart(state.drops, System.currentTimeMillis())
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
        item { SweepOverviewCard(state.sweepSizeStats) }
        item {
            MissedCyclesCard(
                state.missedCycles,
                expanded = missedCyclesOpen,
                onToggle = { missedCyclesOpen = !missedCyclesOpen },
            )
        }
        if (state.samples.isEmpty()) item { Text("No measurements yet. Start monitoring to build your history.") }
    }
}

/**
 * Unsuccessful scheduled checks for the currently selected day/week/month —
 * brief reason per entry, not a text wall. Collapsed by default, same pattern
 * as the Dashboard's "Schedule log".
 */
@Composable
private fun MissedCyclesCard(missedCycles: List<MissedCycle>, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide schedule log" else "Show schedule log (${missedCycles.size} missed)")
        }
        if (expanded) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Missed automatic checks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Cycles the system blocked in this period — not due to your own sweep-frequency setting.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (missedCycles.isEmpty()) {
                        Text("No missed checks in this period.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        missedCycles.forEach { missed ->
                            val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(missed.atEpochMs))
                            Text("$at · ${missed.reason}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every recorded sweep rung, totalled per size across every run — as opposed
 * to the Monitor screen's pass/fail bars, which only ever show the latest
 * sweep. Both directions side by side per size, since that comparison (does
 * this size behave differently downloading vs. uploading) is usually the
 * interesting one.
 */
@Composable
private fun SweepOverviewCard(stats: List<de.sevenapp.monitor.probe.SweepSizeStats>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Size sweep overview", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every completed rung, added up across every run — not just the latest sweep. " +
                    "Counts are trials, not rungs: a 2-of-3 rung counts as 2 passed, 1 failed.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (stats.isEmpty()) {
                Text("No sweep checks recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                val bySize = stats.groupBy { it.bytes }.toSortedMap()
                Row(Modifier.fillMaxWidth()) {
                    Text("Size", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Download", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    Text("Upload", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                }
                HorizontalDivider()
                bySize.forEach { (bytes, rows) ->
                    val down = rows.firstOrNull { it.direction == de.sevenapp.monitor.probe.SweepRunner.Direction.DOWN }
                    val up = rows.firstOrNull { it.direction == de.sevenapp.monitor.probe.SweepRunner.Direction.UP }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatBytesShort(bytes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(sweepCountLabel(down), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(sweepCountLabel(up), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun sweepCountLabel(stats: de.sevenapp.monitor.probe.SweepSizeStats?): String =
    if (stats == null || stats.trialsTotal == 0) "—" else "${stats.trialsPassed}/${stats.trialsTotal} passed"

@Composable
private fun HistoryDateNavigationRow(state: HistoryState, viewModel: HistoryViewModel) {
    var rangeMenuOpen by remember { mutableStateOf(false) }
    // A Card, not a bare Row: as a sticky header this must visually occlude
    // the content scrolling underneath it, which needs an opaque surface.
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.stepRange(forward = false) }, enabled = state.canStepBackward) {
                Text("‹")
            }
            Box {
                TextButton(onClick = { if (state.rangeAnchor != null) rangeMenuOpen = true }) {
                    Text(historyRangeLabel(state), style = MaterialTheme.typography.bodyLarge)
                }
                DropdownMenu(expanded = rangeMenuOpen, onDismissRequest = { rangeMenuOpen = false }) {
                    HistoryRangeMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                rangeMenuOpen = false
                                viewModel.setRangeMode(mode)
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { viewModel.stepRange(forward = true) }, enabled = state.canStepForward) {
                Text("›")
            }
        }
    }
}

private fun historyRangeLabel(state: HistoryState): String {
    if (state.rangeAnchor == null) return "No data yet"
    val locale = java.util.Locale.getDefault()
    return when (state.rangeMode) {
        HistoryRangeMode.DAY ->
            java.text.SimpleDateFormat("EEE, MMM d", locale).format(java.util.Date(state.rangeStartEpochMs))
        HistoryRangeMode.WEEK -> {
            val dayFormat = java.text.SimpleDateFormat("MMM d", locale)
            val start = dayFormat.format(java.util.Date(state.rangeStartEpochMs))
            val end = dayFormat.format(java.util.Date(state.rangeEndEpochMs - 24L * 60 * 60 * 1000))
            "$start – $end"
        }
        HistoryRangeMode.MONTH ->
            java.text.SimpleDateFormat("MMMM yyyy", locale).format(java.util.Date(state.rangeStartEpochMs))
    }
}

@Composable
private fun HistorySummaryCards(summary: HistorySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Period overview", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Average download", formatMbps(summary.averageDownloadMbps), Modifier.weight(1f))
            MetricCard("Average upload", formatMbps(summary.averageUploadMbps), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Median latency", summary.medianLatencyMs?.let { "%.0f ms".format(it) } ?: "—", Modifier.weight(1f))
            MetricCard("Jitter", summary.jitterMs?.let { "%.0f ms".format(it) } ?: "—", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Request loss", summary.requestLossPct?.let { "%.1f%%".format(it) } ?: "—", Modifier.weight(1f))
            MetricCard("Stability", summary.stability?.let { "%.1f".format(it.composite) } ?: "—", Modifier.weight(1f))
        }
    }
}

@Composable
private fun HistoryEvidenceCard(summary: HistorySummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Typical speed vs. dependable speed", style = MaterialTheme.typography.titleMedium)
            Text(
                "Average can hide bad periods. P10 is the speed that 90% of completed checks met or exceeded; consistency compares it with the typical result.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Download: ${formatMbps(summary.downloadP10Mbps)} dependable · ${summary.downloadConsistencyPct?.let { "%.0f%%".format(it) } ?: "—"} consistency",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Upload: ${formatMbps(summary.uploadP10Mbps)} dependable · ${summary.uploadConsistencyPct?.let { "%.0f%%".format(it) } ?: "—"} consistency",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
        item { HelpBlock(BURST_THROTTLE_TITLE, burstThrottleBody) }
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
