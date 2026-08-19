package de.sevenapp.monitor.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.entitlement.Paywall
import de.sevenapp.monitor.entitlement.Tier
import de.sevenapp.monitor.report.Report

private enum class Screen { DASHBOARD, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Two screens do not justify a navigation library; when a third
                // arrives, swap this for one rather than growing the enum.
                var screen by androidx.compose.runtime.saveable.rememberSaveable {
                    androidx.compose.runtime.mutableStateOf(Screen.DASHBOARD)
                }

                Scaffold(
                    bottomBar = {
                        androidx.compose.material3.NavigationBar {
                            androidx.compose.material3.NavigationBarItem(
                                selected = screen == Screen.DASHBOARD,
                                onClick = { screen = Screen.DASHBOARD },
                                icon = {},
                                label = { Text("Monitor") },
                            )
                            androidx.compose.material3.NavigationBarItem(
                                selected = screen == Screen.SETTINGS,
                                onClick = { screen = Screen.SETTINGS },
                                icon = {},
                                label = { Text("Settings") },
                            )
                        }
                    },
                ) { padding ->
                    when (screen) {
                        Screen.DASHBOARD -> DashboardScreen(Modifier.padding(padding))
                        Screen.SETTINGS -> SettingsScreen(Modifier.padding(padding))
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The free action, given top billing rather than buried
                    // under a locked one. Someone who never pays should still
                    // find the app immediately useful.
                    androidx.compose.material3.Button(
                        onClick = viewModel::runManualTest,
                        enabled = !state.manualTestRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.manualTestRunning) "Testing…" else "Run a test now")
                    }

                    if (state.tier == Tier.PRO || !Paywall.shouldShowPlanUi()) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Measure automatically", style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = state.monitoringEnabled,
                                onCheckedChange = viewModel::setMonitoring,
                            )
                        }
                        if (state.monitoringEnabled) {
                            Text(
                                // Say the real cadence, not an aspiration.
                                // WorkManager clamps to 15 minutes and the OS
                                // may defer further; implying a tighter
                                // interval would be a promise the platform
                                // does not keep.
                                "Samples roughly every ${state.intervalMinutes} minutes. " +
                                    "Android may defer cycles to save battery.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            "Automatic background monitoring is part of Pro — see Settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item { StatRow(state) }

        if (state.manualTestRunning || state.liveSamples.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (state.manualTestRunning) "Live — testing now" else "Live — last test",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LiveRateChart(state.liveSamples)
                        Text(
                            "Down/up streaming rate (line) and ping (bars), last 65s.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Latency — recent probes", style = MaterialTheme.typography.labelMedium)
                        LatencyChart(state.recentPings)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Data used this month", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${Format.bytes(state.meteredBytesThisMonth)} on cellular",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Projected ${Format.bytes(state.projectedMeteredBytesPerMonth)}/month at current settings",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        state.latestReport?.let { report ->
            item { ReportCard(report) }
        }
    }
}

@Composable
private fun StatRow(state: DashboardState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last 24 hours", style = MaterialTheme.typography.labelMedium)
            Text("Latency  ${Format.millis(state.latencyMedianMs)} median", style = MaterialTheme.typography.bodyMedium)
            Text("Jitter   ${Format.millis(state.jitterMs)}", style = MaterialTheme.typography.bodyMedium)
            Text("Loss     ${Format.percent(state.lossPct)}", style = MaterialTheme.typography.bodyMedium)
            Text("Drops    ${state.dropCount}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReportCard(report: Report) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Latest report", style = MaterialTheme.typography.labelMedium)
            report.overall.stability?.let {
                Text("Stability ${it.composite}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Uptime ${it.uptimePct}% · jitter score ${it.jitterScore} · loss score ${it.lossScore}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    // Same caveat the web app carries. A composite nobody can
                    // interrogate is worse than no composite.
                    "Not a standard metric — a transparent composite of the three " +
                        "inputs above (weights 50/30/20), shown so you can judge it yourself.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (report.coverage.isPartial) {
                Text(
                    "Partial: ${report.coverage.samplesCollected} of ~${report.coverage.samplesExpected} " +
                        "expected samples collected.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
