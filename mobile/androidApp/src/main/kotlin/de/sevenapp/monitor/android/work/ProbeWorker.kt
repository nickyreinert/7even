package de.sevenapp.monitor.android.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType as WorkNetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.sevenapp.monitor.android.data.MonitorRepository
import de.sevenapp.monitor.android.net.KtorTransport
import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.probe.DeviceState
import de.sevenapp.monitor.probe.ProbeEngine
import java.util.concurrent.TimeUnit

/**
 * The Android half of the scheduling story: wakes on WorkManager's cadence,
 * gathers device state, runs exactly one engine cycle, persists the result.
 *
 * All the judgement lives in :shared. This class is deliberately thin — it is
 * the part that cannot be unit-tested off-device, so the less logic it holds
 * the better.
 */
class ProbeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = MonitorRepository.get(applicationContext)
        val engine = ProbeEngine(
            transport = KtorTransport(),
            clock = Clock { System.currentTimeMillis() },
        )

        return try {
            val output = engine.runCycle(
                ProbeEngine.CycleInput(
                    config = repo.loadConfig(),
                    cycleIndex = repo.nextCycleIndex(),
                    deviceState = readDeviceState(applicationContext),
                    fullSweepsToday = repo.fullSweepsToday(),
                    dropDetector = repo.loadDropDetector(),
                ),
            )
            repo.persistCycle(output)
            Result.success()
        } catch (t: Throwable) {
            // Retry rather than fail: a transient failure here is usually the
            // network being unavailable, which is itself data we want, and
            // Result.failure() would stop the periodic chain entirely.
            Result.retry()
        }
    }

    private fun readDeviceState(context: Context): DeviceState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)

        val type = when {
            caps == null -> NetworkType.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }

        // NET_CAPABILITY_NOT_METERED is the system's own answer, which respects
        // a user marking a Wi-Fi network as metered — inferring from transport
        // type alone would ignore that and spend their tethered data.
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true

        val bm = context.getSystemService(BatteryManager::class.java)
        val charging = bm?.isCharging == true

        return DeviceState(networkType = type, isCharging = charging, isMetered = metered)
    }

    companion object {
        private const val UNIQUE_NAME = "seven-probe-cycle"

        /**
         * WorkManager's floor is 15 minutes; anything shorter is silently
         * clamped, so the UI must not offer a smaller value and pretend.
         */
        const val MIN_INTERVAL_MINUTES = 15L

        fun schedule(context: Context, intervalMinutes: Long) {
            val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

            val request = PeriodicWorkRequestBuilder<ProbeWorker>(interval, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        // CONNECTED, not UNMETERED: a cycle that finds no network
                        // is a measurement, not a wasted wakeup. Gating on
                        // unmetered here would make outages invisible.
                        .setRequiredNetworkType(WorkNetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP, so re-opening the app doesn't reset the schedule and
                // push the next run 15 minutes out every time.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
