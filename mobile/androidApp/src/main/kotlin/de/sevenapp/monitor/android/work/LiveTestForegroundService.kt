package de.sevenapp.monitor.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps a manual live test alive and visible while the app is minimized.
 *
 * Before this, a manual run lived entirely in [android.view.ViewModel] scope:
 * nothing raised the process's importance once the user left the app, and
 * nothing told them a multi-minute mobile sweep was still working. Android is
 * free to deprioritize or kill a background process with no foreground
 * service, and even short of that, there was no notification or lock-screen
 * surface showing progress — the two things this service exists to fix. It
 * holds no test logic itself; [de.sevenapp.monitor.android.ui.DashboardViewModel]
 * still drives [de.sevenapp.monitor.android.livetest.LiveTestRunner] and only
 * pushes progress text here.
 */
class LiveTestForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Starting…"
        startForegroundWith(text)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startForegroundWith(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Live test progress", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun update(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("7even — live test running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL = "live_test_progress"
        private const val ID = 702
        private const val EXTRA_TEXT = "text"

        @Volatile private var instance: LiveTestForegroundService? = null

        /** Starts (or reuses) the foreground notification with an initial line. */
        fun start(context: Context, text: String) {
            val intent = Intent(context, LiveTestForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Updates the visible text. A no-op if the service has not finished
         * starting yet — the next call (at most a second later, driven by the
         * manual test's own phase timer) catches up.
         */
        fun update(text: String) {
            instance?.update(text)
        }

        fun stop(context: Context) {
            instance = null
            context.stopService(Intent(context, LiveTestForegroundService::class.java))
        }
    }
}
