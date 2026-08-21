package de.sevenapp.monitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Paywall
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.report.ReportPeriod
import de.sevenapp.monitor.probe.SweepPlan
import de.sevenapp.monitor.probe.SweepStep

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var recurrenceMenuOpen by remember { mutableStateOf(false) }
    var clearHistoryDialogOpen by remember { mutableStateOf(false) }
    // Mirrors the same fix on DashboardScreen: this screen's own ViewModel
    // instance doesn't otherwise notice a setting (like "automatic
    // measurement" or "sustained speed check") changed from the Monitor
    // screen's switches for the same persisted config.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nothing to sell yet, so no plan UI. An "Upgrade" button that cannot
        // take money is worse than no button — and during closed testing the
        // whole app is unlocked anyway.
        if (Paywall.shouldShowPlanUi()) {
            item { PlanCard(state, viewModel) }
        }

        item {
            SettingsCard("Automatic measurement") {
                if (Paywall.shouldShowPlanUi() && state.tier == Tier.FREE) {
                    // Explain, don't just grey out. A disabled control with no
                    // reason is the most annoying possible paywall.
                    Text(
                        "Repeated automatic testing is part of Pro. You can still run a " +
                            "test yourself any time from the dashboard — that stays free.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Measure automatically", style = MaterialTheme.typography.bodyLarge)
                        Switch(state.monitoringEnabled, onCheckedChange = viewModel::setMonitoring)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (state.monitoringEnabled) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("How often", style = MaterialTheme.typography.labelMedium)
                        Box {
                            OutlinedButton(onClick = { recurrenceMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(intervalLabel(state.intervalMinutes), modifier = Modifier.weight(1f))
                                Text("⌄")
                            }
                            DropdownMenu(expanded = recurrenceMenuOpen, onDismissRequest = { recurrenceMenuOpen = false }) {
                                FeatureGate.allowedIntervalMinutes(state.tier).forEach { minutes ->
                                    DropdownMenuItem(
                                        text = { Text(intervalLabel(minutes)) },
                                        onClick = {
                                            recurrenceMenuOpen = false
                                            viewModel.setInterval(minutes)
                                        },
                                    )
                                }
                            }
                        }
                        AutomaticScheduleTimeControls(state, viewModel)
                    }
                }
            }
        }

        stickyHeader {
            SettingsDataUseCard(state)
        }

        item {
            SettingsCard("Connections and tests") {
                Text("Use connection", style = MaterialTheme.typography.labelMedium)
                CheckRow("Wi-Fi", NetworkType.WIFI in state.monitoringNetworks, true) {
                    viewModel.toggleMonitoringNetwork(NetworkType.WIFI, it)
                }
                CheckRow("Mobile data", NetworkType.CELLULAR in state.monitoringNetworks, true) {
                    viewModel.toggleMonitoringNetwork(NetworkType.CELLULAR, it)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Ping runs on every automatic cycle, including while the device is offline — that is " +
                        "how an outage gets recorded. Choose whether automatic cycles also run the same " +
                        "sustained stream and/or size sweep used by a manual test; both are capped by the " +
                        "data budget shown at the top of this screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                CheckRow(
                    label = "Run continuous download/upload stream",
                    checked = state.automaticStreamEnabled,
                    enabled = true,
                    onCheckedChange = viewModel::setAutomaticStream,
                )
                CheckRow(
                    label = "Run upload/download size sweep",
                    checked = state.automaticSweepEnabled,
                    enabled = true,
                    onCheckedChange = viewModel::setAutomaticSweep,
                )
                CheckRow(
                    label = "When charging only",
                    checked = state.automaticRequiresCharging,
                    enabled = true,
                    onCheckedChange = viewModel::setAutomaticRequiresCharging,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Manual test duration (per phase)", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "10 sec" to 10_000L,
                        "30 sec" to 30_000L,
                        "1 min" to 60_000L,
                        "5 min" to 300_000L,
                        "15m" to 900_000L,
                        "Unlimited" to Long.MAX_VALUE,
                    ).forEach { (label, duration) ->
                        FilterChip(selected = state.manualStreamDurationMs == duration, onClick = { viewModel.setManualStreamDuration(duration) }, label = { Text(label) })
                    }
                }
                Text(
                    "A manual test runs three phases strictly one after another, each for the chosen " +
                        "length: ping alone, then a continuous download stream, then a continuous upload " +
                        "stream. Only one thing ever touches the connection at a time, so each phase " +
                        "measures the whole link rather than a share of it. The connection-specific size " +
                        "sweep runs once at the end. A 10 sec choice therefore takes about 30 seconds " +
                        "plus the sweep. Automatic background tests cap each phase at 30 seconds " +
                        "regardless of this setting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckRow(
                        label = "Sustained speed check",
                        checked = state.sustainedProbeEnabled,
                        enabled = true,
                        onCheckedChange = viewModel::setSustainedProbe,
                        modifier = Modifier.weight(1f),
                    )
                    var sizeMbText by remember(state.sustainedProbeTotalMb) { mutableStateOf(state.sustainedProbeTotalMb.toString()) }
                    OutlinedTextField(
                        value = sizeMbText,
                        onValueChange = { value ->
                            sizeMbText = value
                            value.toIntOrNull()?.let { viewModel.setSustainedProbeSizeMb(it) }
                        },
                        label = { Text("MB") },
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text(
                    "Adds one uninterrupted download then upload after the regular test, specifically " +
                        "to \"use up\" any banked-up burst allowance a throttled mobile plan quietly " +
                        "built up while idle, so the number left over is the real, sustained speed. " +
                        "Bigger drains a deeper allowance but takes longer and costs more data; too " +
                        "small and it may finish before the allowance actually runs out. See the Help " +
                        "screen for the full explanation. Manual tests only — never part of automatic " +
                        "background monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item { SweepSettingsCard(state, viewModel) }

        item { EndpointSettingsCard(state, viewModel) }

        item {
            SettingsCard("History") {
                Text("Delete all recorded ping, stream, sweep, drop, and data-use history from this device. Your settings stay unchanged.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clearHistoryDialogOpen = true }) { Text("Clear history") }
            }
        }

    }
    if (clearHistoryDialogOpen) {
        AlertDialog(
            onDismissRequest = { clearHistoryDialogOpen = false },
            title = { Text("Clear all history?") },
            text = { Text("This permanently removes all saved measurements and cannot be undone.") },
            confirmButton = { Button(onClick = { clearHistoryDialogOpen = false; viewModel.clearHistory() }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { clearHistoryDialogOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AutomaticScheduleTimeControls(state: SettingsState, viewModel: SettingsViewModel) {
    val interval = state.intervalMinutes
    if (interval < 12 * 60) {
        Text(intervalExplanation(interval), style = MaterialTheme.typography.bodySmall)
        return
    }
    var hourMenuOpen by remember { mutableStateOf(false) }
    var dayMenuOpen by remember { mutableStateOf(false) }
    val hourLabel = "%02d:00".format(state.automaticHourOfDay)
    val needsDay = interval == 7 * 24 * 60
    val timeLabel = if (interval == 12 * 60) "First daily hour" else "Hour of day"

    Text(timeLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Box {
        OutlinedButton(onClick = { hourMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(hourLabel, modifier = Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded = hourMenuOpen, onDismissRequest = { hourMenuOpen = false }) {
            (0..23).forEach { hour ->
                DropdownMenuItem(
                    text = { Text("%02d:00".format(hour)) },
                    onClick = { hourMenuOpen = false; viewModel.setAutomaticScheduleTime(hour) },
                )
            }
        }
    }
    if (needsDay) {
        Text("Day of week", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        Box {
            OutlinedButton(onClick = { dayMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(dayOfWeekLabel(state.automaticDayOfWeek), modifier = Modifier.weight(1f))
                Text("⌄")
            }
            DropdownMenu(expanded = dayMenuOpen, onDismissRequest = { dayMenuOpen = false }) {
                (1..7).forEach { day ->
                    DropdownMenuItem(
                        text = { Text(dayOfWeekLabel(day)) },
                        onClick = { dayMenuOpen = false; viewModel.setAutomaticScheduleTime(state.automaticHourOfDay, day) },
                    )
                }
            }
        }
    }
    val explanation = when (interval) {
        12 * 60 -> "Runs around $hourLabel and ${"%02d:00".format((state.automaticHourOfDay + 12) % 24)} each day. Android may defer a run briefly to protect battery."
        24 * 60 -> "Runs around $hourLabel every day. Android may defer a run briefly to protect battery."
        48 * 60 -> "Runs around $hourLabel every second day. Android may defer a run briefly to protect battery."
        else -> "Runs around ${dayOfWeekLabel(state.automaticDayOfWeek)} at $hourLabel. Android may defer a run briefly to protect battery."
    }
    Text(explanation, style = MaterialTheme.typography.bodySmall)
}

private fun dayOfWeekLabel(day: Int): String = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
).getOrElse(day - 1) { "Monday" }

@Composable
private fun SweepSettingsCard(state: SettingsState, viewModel: SettingsViewModel) {
    var editMobile by rememberSaveable { mutableStateOf(false) }
    val network = if (editMobile) NetworkType.CELLULAR else NetworkType.WIFI
    val plan = if (editMobile) state.mobileSweepSteps else state.wifiSweepSteps
    var rows by remember(plan) { mutableStateOf(plan.map { SweepEditorRow.from(it) }) }
    var openUnitMenuFor by remember { mutableStateOf<Int?>(null) }
    SettingsCard("Size sweeps by connection") {
        CheckRow(
            label = "Run send and receive size checks",
            checked = state.liveTestSweepEnabled,
            enabled = true,
            onCheckedChange = viewModel::setLiveSweep,
        )
        Text(
            "Set separate send/receive checks for Wi-Fi and mobile. The selected plan is used for both manual and automatic tests on that connection.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !editMobile, onClick = { editMobile = false }, label = { Text("Wi-Fi") })
            FilterChip(selected = editMobile, onClick = { editMobile = true }, label = { Text("Mobile") })
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "Time allowed per transfer (${if (editMobile) "mobile" else "Wi-Fi"})",
            style = MaterialTheme.typography.labelMedium,
        )
        val timeout = if (editMobile) state.mobileSweepTimeoutMs else state.wifiSweepTimeoutMs
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveTestConfig.SWEEP_TIMEOUT_OPTIONS_MS.forEach { option ->
                FilterChip(
                    selected = timeout == option,
                    onClick = { viewModel.setSweepTimeout(network, option) },
                    label = { Text(Format.duration(option)) },
                )
            }
        }
        Text(
            "The same allowance applies to every size in the plan, on purpose: giving a larger " +
                "transfer more time would let a merely slow one pass, which hides the size cutoff " +
                "these checks exist to find. " +
                "Set it long enough for your connection to finish the biggest size. A 1 MB transfer " +
                "needs about ${sweepHintSeconds(1_000_000)} on a 64 kbit/s line — with less than that, " +
                "a working-but-throttled connection is reported as a failure instead of as slow. " +
                "Transfers that run out of time still report how far they got and at what rate.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = row.repeats,
                    onValueChange = { value -> rows = rows.update(index) { it.copy(repeats = value) } },
                    label = { Text("How many") },
                    modifier = Modifier.width(88.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("×", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = row.size,
                    onValueChange = { value -> rows = rows.update(index) { it.copy(size = value) } },
                    label = { Text("Size") },
                    modifier = Modifier.width(88.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Box {
                    OutlinedButton(onClick = { openUnitMenuFor = index }) { Text(row.unit) }
                    DropdownMenu(expanded = openUnitMenuFor == index, onDismissRequest = { openUnitMenuFor = null }) {
                        listOf("KB", "MB").forEach { unit ->
                            DropdownMenuItem(text = { Text(unit) }, onClick = {
                                rows = rows.update(index) { it.copy(unit = unit) }
                                openUnitMenuFor = null
                            })
                        }
                    }
                }
                if (rows.size > 1) TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }) { Text("−") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { rows = rows + SweepEditorRow("1", "16", "KB") }) { Text("Add row") }
            Button(onClick = { viewModel.setSweepSteps(network, rows.mapNotNull { it.toStep() }) }) { Text("Save ${if (editMobile) "mobile" else "Wi-Fi"} sweep") }
            OutlinedButton(onClick = {
                val defaults = if (editMobile) SweepPlan.MOBILE_DEFAULT else SweepPlan.DEFAULT
                rows = defaults.map { SweepEditorRow.from(it) }
                viewModel.setSweepSteps(network, defaults)
            }) { Text("Use defaults") }
        }
    }
}

@Composable
private fun SettingsDataUseCard(state: SettingsState) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Data use", style = MaterialTheme.typography.titleMedium)
            // A duration-based stream moves whatever the link can carry, so the
            // only honest figure to state before the fact is the ceiling — and
            // it is the same ceiling the worker refuses to exceed.
            Text(
                if (state.projectionIsMaximum) {
                    "Up to ${Format.bytes(state.projectedMeteredBytesPerMonth)} mobile data / month"
                } else {
                    "About ${Format.bytes(state.projectedMeteredBytesPerMonth)} mobile data / month"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Used in the last 30 days: ${Format.bytes(state.meteredBytesThisMonth)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.projectionIsMaximum) {
                Text(
                    "Automatic streams are capped per run and per day, so this is a hard ceiling rather than an estimate.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** How long [bytes] takes on a 64 kbit/s line, as a plain-language hint. */
private fun sweepHintSeconds(bytes: Long): String =
    Format.duration((bytes * 8 * 1000L) / 64_000L)

private data class SweepEditorRow(val repeats: String, val size: String, val unit: String) {
    fun toStep(): SweepStep? {
        val count = repeats.toIntOrNull() ?: return null
        val numericSize = size.toLongOrNull() ?: return null
        val bytes = numericSize * if (unit == "MB") 1_000_000 else 1_000
        return if (count in 1..SweepPlan.MAX_TRIALS && bytes in 1..SweepPlan.MAX_BYTES) SweepStep(bytes.toInt(), count) else null
    }

    companion object {
        fun from(step: SweepStep): SweepEditorRow = if (step.bytes % 1_000_000 == 0) {
            SweepEditorRow(step.trials.toString(), (step.bytes / 1_000_000).toString(), "MB")
        } else {
            SweepEditorRow(step.trials.toString(), (step.bytes / 1_000).toString(), "KB")
        }
    }
}

private fun <T> List<T>.update(index: Int, transform: (T) -> T): List<T> = mapIndexed { current, item -> if (current == index) transform(item) else item }

private fun intervalExplanation(minutes: Int): String = when (minutes) {
    15 -> "Runs around :00, :15, :30 and :45 each hour. Android may delay a run to save battery."
    60 -> "Runs once every hour."
    120 -> "Runs every second hour."
    240 -> "Runs every fourth hour."
    360 -> "Runs every sixth hour."
    720 -> "Runs every 12 hours."
    1440 -> "Runs once every 24 hours."
    2880 -> "Runs once every 48 hours."
    10080 -> "Runs once a week."
    else -> "Android may delay automatic work to save battery."
}

@Composable
private fun MeasurementSizes(label: String, network: NetworkType, enabled: Boolean, selected: Set<Int>, viewModel: SettingsViewModel) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    CheckRow(label, enabled, true) { viewModel.toggleMonitoringNetwork(network, it) }
    if (enabled) {
        val sizes = listOf(16, 32, 64, 128, 256, 512).map { it * 1024 } + listOf(1, 2, 5, 10).map { it * 1024 * 1024 }
        sizes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { bytes ->
                    FilterChip(
                        selected = bytes in selected,
                        onClick = { viewModel.toggleMeasurementSize(network, bytes, bytes !in selected) },
                        label = { Text(Format.bytes(bytes.toLong())) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointSettingsCard(state: SettingsState, viewModel: SettingsViewModel) {
    var traceUrl by remember(state.traceUrl) { mutableStateOf(state.traceUrl) }
    var downUrl by remember(state.downUrlTemplate) { mutableStateOf(state.downUrlTemplate) }
    var upUrl by remember(state.upUrl) { mutableStateOf(state.upUrl) }
    var streamUrl by remember(state.streamUrl) { mutableStateOf(state.streamUrl) }
    var useStream by remember(state.useWebSocketStream) { mutableStateOf(state.useWebSocketStream) }

    SettingsCard("Endpoints") {
        Text(
            "Use a trusted HTTPS server you control. Generic websites such as Google are not guaranteed to accept uploads or give stable speed-test results.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(traceUrl, { traceUrl = it }, Modifier.fillMaxWidth(), label = { Text("Latency URL") }, singleLine = true)
        OutlinedTextField(downUrl, { downUrl = it }, Modifier.fillMaxWidth(), label = { Text("Download URL — include {bytes}") }, singleLine = true)
        OutlinedTextField(upUrl, { upUrl = it }, Modifier.fillMaxWidth(), label = { Text("HTTP upload URL") }, singleLine = true)
        CheckRow(
            label = "Use bounded WebSocket stream tests",
            checked = useStream,
            enabled = true,
            onCheckedChange = { useStream = it },
        )
        if (useStream) {
            OutlinedTextField(streamUrl, { streamUrl = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket URL (wss://)") }, singleLine = true)
            Text(
                "The server must implement 7even's down_start/up_start protocol. This is a bounded transfer using the packet size selected on Monitor.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { viewModel.setEndpoints(traceUrl, downUrl, upUrl, streamUrl, useStream) },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Save endpoints") }
    }
}

@Composable
private fun PlanCard(state: SettingsState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (state.tier == Tier.PRO) "7even Pro" else "7even Free",
                style = MaterialTheme.typography.titleLarge,
            )

            if (state.inGrace) {
                Text(
                    "Your subscription did not renew. Monitoring keeps running for a few " +
                        "days so your history does not get a gap — please update your payment method.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (state.tier == Tier.FREE) {
                Text("Always free:", style = MaterialTheme.typography.labelMedium)
                Paywall.freeAlwaysIncludes().forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }

                Text("Pro adds:", style = MaterialTheme.typography.labelMedium)
                Paywall.lockedFor(Tier.FREE).forEach { locked ->
                    Text("• ${locked.name}", style = MaterialTheme.typography.bodyMedium)
                    Text("   ${locked.reason}", style = MaterialTheme.typography.bodySmall)
                }

                Button(onClick = viewModel::startUpgrade, modifier = Modifier.fillMaxWidth()) {
                    Text("Upgrade to Pro")
                }
                if (state.billingUnavailableMessage != null) {
                    Text(state.billingUnavailableMessage, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    state.expiryLabel ?: "Active",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::manageSubscription) { Text("Manage subscription") }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "Every $minutes minutes"
    minutes == 60 -> "Every hour"
    else -> "Every ${minutes / 60} hours"
}

private enum class ConnectionChoice(val label: String, val networks: Set<NetworkType>) {
    WIFI("Wi-Fi only", setOf(NetworkType.WIFI)),
    MOBILE("Mobile data only", setOf(NetworkType.CELLULAR)),
    BOTH("Wi-Fi and mobile data", setOf(NetworkType.WIFI, NetworkType.CELLULAR)),
}
