package de.sevenapp.monitor.android.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.sevenapp.monitor.android.billing.EntitlementRepository
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.net.KtorTransport
import de.sevenapp.monitor.android.ui.MainActivity
import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.probe.MonitorCoordinator
import de.sevenapp.monitor.probe.ProbeEngine
import de.sevenapp.monitor.report.Report
import de.sevenapp.monitor.report.ReportPeriod
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Builds the due report and delivers it as a notification — the actual product
 * deliverable. Runs hourly and does nothing unless a report is due, which is
 * cheaper and far more reliable than trying to schedule a one-shot for exactly
 * 09:00 across reboots, time-zone changes and Doze.
 */
class ReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = RoomMonitorStore.get(ctx)

        val coordinator = MonitorCoordinator(
            store = store,
            engine = ProbeEngine(KtorTransport(), Clock { System.currentTimeMillis() }),
            clock = Clock { System.currentTimeMillis() },
        )

        return try {
            val now = System.currentTimeMillis()

            // Scheduled reports follow background monitoring, so the same gate
            // applies here. Same reasoning as ProbeWorker: enforce on wakeup,
            // not only at schedule time.
            val entitlements = EntitlementRepository.get(ctx)
            if (!FeatureGate.canReceiveScheduledReports(entitlements.effectiveTier(now))) {
                cancel(ctx)
                return Result.success()
            }

            val period = RoomMonitorStore.reportPeriod(ctx)
            val weekAnchorDay = RoomMonitorStore.reportDayOfWeek(ctx)
            val monthAnchorDay = RoomMonitorStore.reportDayOfMonth(ctx)
            // First install has no prior delivery. Using epoch zero as the
            // baseline made the very first wake immediately overdue, producing
            // a report about a window the app had not been installed for. The
            // baseline is when reporting was switched on.
            val baseline = RoomMonitorStore.lastReportAt(ctx)
                ?: RoomMonitorStore.reportingSince(ctx)
                ?: run {
                    RoomMonitorStore.markReportingStarted(ctx, now)
                    return Result.success()
                }

            val report = coordinator.buildDueReport(period, now, baseline, weekAnchorDay, monthAnchorDay)
                ?: return Result.success() // nothing due; not a failure

            // Generated and delivered are separate facts. The report is stored
            // first so it survives a delivery that cannot happen, and the
            // generation timestamp advances either way so the schedule does not
            // rebuild the same window on the next hourly wake.
            RoomMonitorStore.savePendingReport(ctx, title(report), detail(report), now)
            RoomMonitorStore.setLastReportAt(ctx, now)
            if (notify(ctx, report)) RoomMonitorStore.markReportDelivered(ctx, now)
            Result.success()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Result.retry()
        }
    }

    /** @return true only when a notification was actually posted. */
    private fun notify(context: Context, report: Report): Boolean {
        ensureChannel(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Permission refused or not yet granted. The report is persisted
            // and shown in-app, so nothing is lost — but this is emphatically
            // not a delivery, and reporting it as one is what let a report
            // vanish without trace.
            return false
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title(report))
            .setContentText(summary(report))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail(report)))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun title(report: Report): String = when (report.period) {
        ReportPeriod.DAILY -> "Daily connection report"
        ReportPeriod.WEEKLY -> "Weekly connection report"
        ReportPeriod.MONTHLY -> "Monthly connection report"
    }

    /** Average download, average upload, and the calculated connection-quality score — the three figures the user reads at a glance. */
    private fun summary(report: Report): String {
        val s = report.overall
        val down = Format.mbps(s.downMedianMbps)
        val up = Format.mbps(s.upMedianMbps)
        val quality = s.stability?.composite?.let { "%.0f/100".format(it) } ?: "—"
        return "${down.value} ${down.unit} down · ${up.value} ${up.unit} up · Quality $quality"
    }

    private fun detail(report: Report): String = buildString {
        appendLine(summary(report))

        if (report.coverage.isPartial) {
            appendLine()
            appendLine(
                "Note: ${report.coverage.samplesCollected} of ~${report.coverage.samplesExpected} " +
                    "expected samples were collected. The OS limits how often background " +
                    "measurement may run, so this report covers part of the period.",
            )
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Connection reports",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Periodic summaries of your connection quality" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "reports"
        private const val NOTIFICATION_ID = 1001
        private const val UNIQUE_NAME = "seven-report"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReportWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
