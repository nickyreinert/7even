package de.sevenapp.monitor.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.sevenapp.monitor.android.data.RoomMonitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WorkManager's periodic work does not survive a reboot on its own. Without
 * this the monitor stops at the next restart and stays stopped until the user
 * happens to open the app — which, for a background monitor, may be never. The
 * resulting gap would read as perfect uptime rather than as missing data.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync() so the coroutine is not killed when onReceive returns;
        // a receiver gets ~10s, which is ample for two DataStore reads.
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (RoomMonitorStore.isMonitoringEnabled(app)) {
                    val config = RoomMonitorStore.get(app).loadConfig()
                    ProbeWorker.schedule(app, config.cycleIntervalMinutes.toLong())
                    ReportWorker.schedule(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
