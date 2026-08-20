package de.sevenapp.monitor.android.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.sevenapp.monitor.android.data.RoomMonitorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms scheduling after a reboot.
 *
 * WorkManager's own persistence normally restores periodic work on boot, so
 * this is a belt-and-braces path for OEM builds that clear it — not the primary
 * mechanism. It therefore reschedules from the **stored config** through the
 * single [ProbeWorker.scheduleFromConfig] entry point rather than passing a
 * bare interval: the old version omitted the stored hour and day, so on any
 * device that did take this path a daily 03:00 schedule silently re-anchored
 * itself to whenever the phone happened to boot.
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
                    ProbeWorker.scheduleFromConfig(app, RoomMonitorStore.get(app))
                    ReportWorker.schedule(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
