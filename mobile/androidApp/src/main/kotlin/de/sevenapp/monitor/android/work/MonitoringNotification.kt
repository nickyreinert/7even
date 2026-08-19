package de.sevenapp.monitor.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

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
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle(title).setContentText(text).setOngoing(ongoing).build()
        manager.notify(ID, notification)
    }
}
