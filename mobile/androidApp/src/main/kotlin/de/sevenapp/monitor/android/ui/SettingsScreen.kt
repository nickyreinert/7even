package de.sevenapp.monitor.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
