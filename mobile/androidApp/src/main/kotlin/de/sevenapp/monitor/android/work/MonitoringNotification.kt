package de.sevenapp.monitor.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import de.sevenapp.monitor.android.ui.MainActivity

object MonitoringNotification {
    private const val CHANNEL = "automatic_monitoring"
    private const val ID = 701

    fun active(context: Context) = show(context, "Automatic monitoring active", "Checks run approximately on your selected schedule.", ongoing = true)
    fun checking(context: Context) = show(context, "7even is checking your connection", "Automatic measurement in progress.", ongoing = false)
    fun completed(context: Context) = show(context, "Automatic check completed", "Results were added to History.", ongoing = false)
    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    private fun show(context: Context, title: String, text: String, ongoing: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Automatic monitoring", NotificationManager.IMPORTANCE_LOW))
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setContentIntent(contentIntent)
            // Ongoing status notifications (monitoring "active") stay put when
            // tapped — they describe a state, not a one-off event. Transient
            // ones ("checking"/"completed") behave like a normal notification.
            .setAutoCancel(!ongoing)
            .build()
        manager.notify(ID, notification)
    }
}
