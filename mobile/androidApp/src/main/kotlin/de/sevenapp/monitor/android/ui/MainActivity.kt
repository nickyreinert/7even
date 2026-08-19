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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.report.Report

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    DashboardScreen(Modifier.padding(padding))
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
                Column(Modifier.padding(16.dp)) {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Monitoring", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = state.monitoringEnabled,
                            onCheckedChange = viewModel::setMonitoring,
                        )
                    }
                    Text(
                        // Say the real cadence, not an aspiration. WorkManager
                        // clamps to 15 minutes and the OS may defer further;
                        // implying a tighter interval would be a promise the
                        // platform does not keep.
                        "Samples roughly every ${state.intervalMinutes} minutes. " +
                            "Android may defer cycles to save battery.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item { StatRow(state) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Latency — recent probes", style = MaterialTheme.typography.labelMedium)
                    LatencyChart(state.recentPings)
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
