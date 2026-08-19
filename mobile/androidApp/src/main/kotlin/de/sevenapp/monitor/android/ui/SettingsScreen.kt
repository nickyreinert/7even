package de.sevenapp.monitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.entitlement.Paywall
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.report.ReportPeriod

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()

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
            SettingsCard("Background monitoring") {
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
                    Text("Use connection", style = MaterialTheme.typography.labelMedium)
                    ConnectionChoice.entries.forEach { choice ->
                        RadioRow(
                            label = choice.label,
                            selected = state.monitoringNetworks == choice.networks,
                            onSelect = { viewModel.setMonitoringNetworks(choice.networks) },
                        )
                    }
                    Text(
                        "Only the selected connection types are measured. Time on another connection is not counted as an outage.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (state.monitoringEnabled) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("How often", style = MaterialTheme.typography.labelMedium)
                        FeatureGate.allowedIntervalMinutes(state.tier).forEach { minutes ->
                            RadioRow(
                                label = intervalLabel(minutes),
                                selected = state.intervalMinutes == minutes,
                                onSelect = { viewModel.setInterval(minutes) },
                            )
                        }
                        Text(
                            // Say the real constraint rather than implying a
                            // precision Android does not offer.
                            "Android will not run background work more often than every " +
                                "15 minutes, and may delay a cycle further to save battery.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            SettingsCard("Speed tests") {
                Text(
                    "Latency is measured every cycle and costs almost nothing. " +
                        "Speed tests move real data, so they are limited by default.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                CheckRow(
                    label = "Allow speed tests on mobile data",
                    checked = NetworkType.CELLULAR in state.throughputNetworks,
                    enabled = state.tier == Tier.PRO,
                    onCheckedChange = viewModel::setCellularThroughput,
                )
                CheckRow(
                    label = "Full sweeps only while charging",
                    checked = state.fullSweepRequiresCharging,
                    enabled = state.tier == Tier.PRO,
                    onCheckedChange = viewModel::setFullSweepRequiresCharging,
                )
            }
        }

        item {
            SettingsCard("Measurement sizes") {
                Text("Select one or more packet sizes for each connection. Every selected size is tested in both directions; more sizes use more battery and data.", style = MaterialTheme.typography.bodySmall)
                MeasurementSizes("Use Wi-Fi", NetworkType.WIFI, NetworkType.WIFI in state.monitoringNetworks, state.wifiMeasurementSizes, viewModel)
                MeasurementSizes("Use mobile data", NetworkType.CELLULAR, NetworkType.CELLULAR in state.monitoringNetworks, state.cellularMeasurementSizes, viewModel)
            }
        }

        item { SweepSettingsCard(state, viewModel) }

        item { EndpointSettingsCard(state, viewModel) }

        item {
            SettingsCard("Data use") {
                // The projection updates as the switches above change, so the
                // cost of a choice is visible before it is made rather than at
                // the end of the month.
                Text(
                    "About ${Format.bytes(state.projectedMeteredBytesPerMonth)} of mobile data per month",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Estimated from your current settings, assuming about half your time " +
                        "on mobile data. Wi-Fi use is not counted here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Used so far this month: ${Format.bytes(state.meteredBytesThisMonth)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item {
            SettingsCard("Reports") {
                if (Paywall.shouldShowPlanUi() && state.tier == Tier.FREE) {
                    Text(
                        "Scheduled reports are part of Pro — they summarise data collected " +
                            "in the background, which Free does not collect.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ReportPeriod.entries.forEach { period ->
                        RadioRow(
                            label = period.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = state.reportPeriod == period,
                            onSelect = { viewModel.setReportPeriod(period) },
                        )
                    }
                    Text("Delivered at 09:00 as a notification.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SettingsCard("Your data") {
                Text(
                    "Kept for ${FeatureGate.retentionDays(state.tier)} days.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (Paywall.shouldShowPlanUi()) {
                    Text(
                        // Worth stating explicitly: people reasonably assume
                        // cancelling destroys their history.
                        "Export is free on every plan, and your history is never deleted if " +
                            "Pro lapses — collection stops, the record stays.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::export,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Export as JSON") }
            }
        }
    }
}

@Composable
private fun SweepSettingsCard(state: SettingsState, viewModel: SettingsViewModel) {
    var planText by remember(state.sweepPlanText) { mutableStateOf(state.sweepPlanText) }
    SettingsCard("Size sweep") {
        CheckRow(
            label = "Run send and receive size checks",
            checked = state.liveTestSweepEnabled,
            enabled = true,
            onCheckedChange = viewModel::setLiveSweep,
        )
        Text(
            "Each try becomes one green or red block in the download and upload charts. " +
                "Use K or M units and × repeats, for example: 16K x6, 32K x6, 10M x1.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = planText,
            onValueChange = { planText = it },
            label = { Text("Size × repeats") },
            placeholder = { Text("32K x3, 128K x3, 512K x2, 2M x2, 5M x1, 10M x1") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.setSweepPlan(planText) }) { Text("Save sweep") }
            OutlinedButton(onClick = {
                planText = "32K x3, 128K x3, 512K x2, 2M x2, 5M x1, 10M x1"
                viewModel.setSweepPlan(planText)
            }) { Text("Use defaults") }
        }
    }
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
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
