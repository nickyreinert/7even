package de.sevenapp.monitor.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * WorkManager's periodic work does not survive a reboot on its own. Without
 * this the monitor stops at the next restart and stays stopped until the user
 * happens to open the app — which for a background monitor may be never, and
 * the gap would look like perfect uptime rather than no data.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val repo = de.sevenapp.monitor.android.data.MonitorRepository.get(context)
        if (repo.isMonitoringEnabledBlocking()) {
            ProbeWorker.schedule(context, repo.loadConfigBlocking().cycleIntervalMinutes.toLong())
        }
    }
}
