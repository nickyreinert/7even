package de.sevenapp.monitor.android.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType as WorkNetworkType
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
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.MonitorCoordinator
import de.sevenapp.monitor.probe.ProbeEngine
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
        val configured = store.loadConfig()
        if (configured.automaticRequiresCharging && !deviceState.isCharging) return Result.success()
        val coordinator = MonitorCoordinator(
            store = store,
            engine = ProbeEngine(KtorTransport(), Clock { System.currentTimeMillis() }),
            clock = Clock { System.currentTimeMillis() },
            // Ratcheted, so neither a lapse nor switching the paywall on ever
            // prunes away history already collected.
            retentionDays = entitlements.retentionDays(now),
        )

        return try {
            coordinator.runCycle(deviceState)
            val config = store.loadConfig()
            if (config.automaticStreamEnabled || config.automaticSweepEnabled) {
                LiveTestRunner(
                    context = applicationContext,
                    store = store,
                    probeConfig = config,
                    fallbackNetworkType = deviceState.networkType,
                    wifiSsid = deviceState.ssid,
                    runStream = config.automaticStreamEnabled,
                    runSweep = config.automaticSweepEnabled,
                ).runSession { }
            }
            Result.success()
        } catch (t: Throwable) {
            // Retry, not failure: Result.failure() would stop the periodic
            // chain permanently, and the usual cause here is a transient
            // network problem — which is itself the thing we are measuring.
            Result.retry()
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
            ).setInitialDelay(initialDelayMillis(intervalMinutes, hourOfDay, dayOfWeek), TimeUnit.MILLISECONDS).setConstraints(
                Constraints.Builder()
                    // CONNECTED rather than UNMETERED: a cycle that finds no
                    // usable network is itself a measurement. Gating on
                    // unmetered here would make outages invisible, which is
                    // the opposite of what this app is for.
                    .setRequiredNetworkType(WorkNetworkType.CONNECTED)
                    .build(),
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // UPDATE so an interval change takes effect, without the
                // schedule-resetting that CANCEL_AND_REENQUEUE would cause on
                // every app launch.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
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
