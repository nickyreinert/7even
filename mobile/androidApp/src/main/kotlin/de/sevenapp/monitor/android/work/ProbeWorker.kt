package de.sevenapp.monitor.android.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.sevenapp.monitor.android.billing.EntitlementRepository
import de.sevenapp.monitor.android.data.RoomMonitorStore
import de.sevenapp.monitor.android.net.KtorTransport
import de.sevenapp.monitor.android.livetest.LiveTestRunner
import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.entitlement.FeatureGate
import de.sevenapp.monitor.probe.AutomaticTransfers
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.MonitorCoordinator
import de.sevenapp.monitor.probe.ProbeEngine
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The Android half of the scheduling story: wakes on WorkManager's cadence,
 * reads device state, hands off to the shared coordinator.
 *
 * Deliberately thin. This is the part that cannot be unit-tested off-device, so
 * the less judgement it holds the better — everything else lives in :shared
 * where it is covered by tests.
 */
class ProbeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        MonitoringNotification.checking(applicationContext)
        // Checked on every wakeup, not only when scheduling. Hiding a toggle is
        // presentation; this is enforcement — an entitlement that lapses while
        // work is already enqueued has to actually stop collecting, without
        // waiting for the user to open the app. Grace is handled inside
        // FeatureGate, so a failed renewal does not punch a hole in the data.
        val now = System.currentTimeMillis()
        val entitlements = EntitlementRepository.get(applicationContext)
        val entitlement = entitlements.current()

        if (!FeatureGate.shouldBackgroundWorkRun(entitlement, now)) {
            cancel(applicationContext)
            // Not a failure: this is the intended end state for a free account.
            return Result.success()
        }

        val store = RoomMonitorStore.get(applicationContext)
        val deviceState = readDeviceState(applicationContext)
        val transport = KtorTransport()
        val coordinator = MonitorCoordinator(
            store = store,
            engine = ProbeEngine(transport, Clock { System.currentTimeMillis() }),
            clock = Clock { System.currentTimeMillis() },
            // Ratcheted, so neither a lapse nor switching the paywall on ever
            // prunes away history already collected.
            retentionDays = entitlements.retentionDays(now),
        )

        return try {
            // Reachability first and unconditionally. It used to sit behind the
            // charging check, so an unplugged phone recorded nothing at all —
            // and a monitor that stops observing while unplugged reports the
            // resulting gap as uptime.
            coordinator.runCycle(deviceState)
            runAutomaticTransfers(store, deviceState, now)
            MonitoringNotification.completed(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Retry, not failure: Result.failure() would stop the periodic
            // chain permanently, and the usual cause here is a transient
            // network problem — which is itself the thing we are measuring.
            Result.retry()
        } finally {
            transport.close()
        }
    }

    /**
     * The expensive half of a cycle, gated by one shared budget.
     *
     * The allowance is **reserved at its stated maximum before the work runs**
     * and reconciled down to what was actually moved afterwards. Charging only
     * after the fact meant a worker killed mid-transfer spent bytes that were
     * never recorded, so the running total drifted below reality exactly when
     * the connection was worst. Over-reserving and refunding errs toward
     * under-spending the user's data, which is the safe direction.
     */
    private suspend fun runAutomaticTransfers(
        store: RoomMonitorStore,
        deviceState: DeviceState,
        nowEpochMs: Long,
    ) {
        val config = store.loadConfig()
        // Re-checked immediately before the heavy work, not only at worker
        // start: the user may have switched monitoring off during the cheap
        // ping phase, and that decision should take effect now, not next cycle.
        if (!RoomMonitorStore.isMonitoringEnabled(applicationContext)) return

        val dayStart = nowEpochMs - 24 * 60 * 60 * 1000
        val monthStart = nowEpochMs - 30L * 24 * 60 * 60 * 1000
        val decision = AutomaticTransfers.decide(
            config = config,
            deviceState = deviceState,
            usage = AutomaticTransfers.Usage(
                fullSweepsToday = store.fullSweepCountSince(dayStart),
                meteredBytesToday = store.bytesUsedSince(dayStart, metered = true),
                meteredBytesThisMonth = store.bytesUsedSince(monthStart, metered = true),
            ),
        )
        val plan = (decision as? AutomaticTransfers.Decision.Run)?.plan ?: return

        store.addBytesUsed(plan.maxBytesPerRun, deviceState.isMetered, nowEpochMs)
        var actualBytes = 0L
        try {
            actualBytes = LiveTestRunner(
                context = applicationContext,
                store = store,
                // Automatic work is metered by the plan, not by the manual
                // test's settings: the same phase length the projection used.
                probeConfig = config.copy(liveTestPhaseDurationMs = plan.phaseDurationMs),
                fallbackNetworkType = deviceState.networkType,
                runStream = plan.runStream,
                runSweep = plan.runSweep,
                wifiSsid = deviceState.ssid,
                maxTransferBytes = plan.maxStreamBytes,
                isMeteredOverride = deviceState.isMetered,
                accountBytes = false,
            ).runSession { }
            if (plan.runSweep) store.recordFullSweep(nowEpochMs)
        } finally {
            // Refund the unused part of the reservation, including on
            // cancellation or a partial run.
            val refund = plan.maxBytesPerRun - actualBytes
            if (refund != 0L) store.addBytesUsed(-refund, deviceState.isMetered, nowEpochMs)
        }
    }

    private fun readDeviceState(context: Context): DeviceState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val type = when {
            caps == null -> NetworkType.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }

        // Ask the system rather than inferring from transport type: this
        // respects a user who has marked their Wi-Fi as metered, which
        // inferring would ignore and spend their tethered allowance.
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true

        return DeviceState(networkType = type, isCharging = charging, isMetered = metered, ssid = context.wifiSsid(type))
    }

    private fun Context.wifiSsid(networkType: NetworkType): String? {
        if (networkType != NetworkType.WIFI) return null
        val value = getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        return value?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }
    }

    companion object {
        private const val UNIQUE_NAME = "seven-probe-cycle"

        /**
         * WorkManager silently clamps anything shorter to 15 minutes, so the
         * settings UI must not offer a smaller value and imply otherwise.
         */
        const val MIN_INTERVAL_MINUTES = 15L

        fun schedule(context: Context, intervalMinutes: Long, hourOfDay: Int = 0, dayOfWeek: Int = 1) {
            val request = PeriodicWorkRequestBuilder<ProbeWorker>(
                intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES), TimeUnit.MINUTES,
            ).setInitialDelay(initialDelayMillis(intervalMinutes, hourOfDay, dayOfWeek), TimeUnit.MILLISECONDS)
                // NO network constraint at all. `NetworkType.CONNECTED` meant
                // WorkManager simply did not run this worker while the device
                // was offline — so the one event a connection monitor exists to
                // record was the one event it could never observe, and the gap
                // was later read as uptime. Connectivity is now checked inside
                // the worker, which gates transfers while still recording the
                // disconnected interval.
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // Anchor changes must actually move the next run. UPDATE keeps
                // the existing enqueue time, so editing "run at 03:00" left the
                // old anchor in place; CANCEL_AND_REENQUEUE re-applies the
                // initial delay. It is only called when something changed, so
                // this does not reset the schedule on every app launch.
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }

        /**
         * The one entry point for scheduling, so no call site can omit the
         * stored hour/day and silently re-anchor the schedule to "now".
         */
        suspend fun scheduleFromConfig(context: Context, store: RoomMonitorStore) {
            val config = store.loadConfig()
            schedule(
                context = context,
                intervalMinutes = config.cycleIntervalMinutes.toLong(),
                hourOfDay = config.automaticHourOfDay,
                dayOfWeek = config.automaticDayOfWeek,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        private fun initialDelayMillis(intervalMinutes: Long, hourOfDay: Int, dayOfWeek: Int): Long {
            if (intervalMinutes < 12 * 60) return 0L
            val now = java.util.Calendar.getInstance()
            val target = now.clone() as java.util.Calendar
            target.set(java.util.Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            target.set(java.util.Calendar.MINUTE, 0)
            target.set(java.util.Calendar.SECOND, 0)
            target.set(java.util.Calendar.MILLISECOND, 0)
            when (intervalMinutes) {
                7 * 24 * 60L -> {
                    val calendarDay = if (dayOfWeek in 1..6) dayOfWeek + 1 else java.util.Calendar.SUNDAY
                    val daysAhead = (calendarDay - now.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
                    target.add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
                    if (!target.after(now)) target.add(java.util.Calendar.WEEK_OF_YEAR, 1)
                }
                12 * 60L -> {
                    if (!target.after(now)) target.add(java.util.Calendar.HOUR_OF_DAY, 12)
                }
                else -> if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
            return (target.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        }
    }
}
