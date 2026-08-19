package de.sevenapp.monitor.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.data.MonitorStore
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.report.ReportPeriod
import kotlinx.coroutines.flow.first

private val Context.settings by preferencesDataStore("monitor_settings")

/**
 * Android implementation of the engine's persistence contract.
 *
 * Room for the sample tables, DataStore for scalar settings and counters —
 * DataStore because a config read happens on every worker wakeup and pulling
 * in the Room machinery for six scalars is more ceremony than it earns.
 */
class RoomMonitorStore(
    private val context: Context,
    private val db: MonitorDatabase,
) : MonitorStore {

    private val dao get() = db.dao()

    override suspend fun loadConfig(): ProbeConfig {
        val prefs = context.settings.data.first()
        val defaults = ProbeConfig()
        return defaults.copy(
            cycleIntervalMinutes = prefs[KEY_INTERVAL] ?: defaults.cycleIntervalMinutes,
            pingsPerCycle = prefs[KEY_PINGS] ?: defaults.pingsPerCycle,
            throughputEveryNCycles = prefs[KEY_TP_EVERY] ?: defaults.throughputEveryNCycles,
            throughputLightNetworks = prefs[KEY_TP_NETWORKS]?.toNetworkTypes()
                ?: defaults.throughputLightNetworks,
            fullSweepRequiresCharging = prefs[KEY_FULL_CHARGING] ?: defaults.fullSweepRequiresCharging,
            fullSweepsPerDay = prefs[KEY_FULL_PER_DAY] ?: defaults.fullSweepsPerDay,
        )
    }

    override suspend fun saveConfig(config: ProbeConfig) {
        context.settings.edit { p ->
            p[KEY_INTERVAL] = config.cycleIntervalMinutes
            p[KEY_PINGS] = config.pingsPerCycle
            p[KEY_TP_EVERY] = config.throughputEveryNCycles
            p[KEY_TP_NETWORKS] = config.throughputLightNetworks.map { it.name }.toSet()
            p[KEY_FULL_CHARGING] = config.fullSweepRequiresCharging
            p[KEY_FULL_PER_DAY] = config.fullSweepsPerDay
        }
    }

    override suspend fun nextCycleIndex(): Long {
        var next = 0L
        context.settings.edit { p ->
            next = (p[KEY_CYCLE_INDEX] ?: 0L) + 1
            p[KEY_CYCLE_INDEX] = next
        }
        return next
    }

    override suspend fun appendPings(samples: List<PingSample>) {
        if (samples.isEmpty()) return
        dao.insertPings(
            samples.map { PingEntity(atEpochMs = it.atEpochMs, rttMs = it.rttMs, networkType = it.networkType.name) },
        )
    }

    override suspend fun appendThroughput(sample: ThroughputSample) {
        dao.insertThroughput(
            ThroughputEntity(
                atEpochMs = sample.atEpochMs,
                downMbps = sample.downMbps,
                upMbps = sample.upMbps,
                networkType = sample.networkType.name,
                tier = sample.tier.name,
                partial = sample.partial,
            ),
        )
    }

    override suspend fun loadDropState(): MonitorStore.DropState = MonitorStore.DropState(
        closedDrops = dao.closedDrops().map { it.toDomain() },
        openDropStartedAtEpochMs = dao.openDrop()?.startedAtEpochMs,
        consecutiveFailures = context.settings.data.first()[KEY_CONSECUTIVE_FAILURES] ?: 0,
    )

    override suspend fun saveDropState(state: MonitorStore.DropState) {
        val existingOpen = dao.openDrop()

        when {
            // Drop just opened.
            existingOpen == null && state.openDropStartedAtEpochMs != null ->
                dao.insertDrop(DropEntity(startedAtEpochMs = state.openDropStartedAtEpochMs!!, endedAtEpochMs = null))

            // Drop just closed. The end time comes from the detector's own
            // record rather than "now" — the connection came back when the
            // successful probe landed, which may be well before this write.
            existingOpen != null && state.openDropStartedAtEpochMs == null -> {
                val closedEnd = state.closedDrops
                    .lastOrNull { it.startedAtEpochMs == existingOpen.startedAtEpochMs }
                    ?.endedAtEpochMs
                dao.closeOpenDrop(closedEnd ?: System.currentTimeMillis())
            }
        }

        context.settings.edit { it[KEY_CONSECUTIVE_FAILURES] = state.consecutiveFailures }
    }

    override suspend fun pingsBetween(startEpochMs: Long, endEpochMs: Long): List<PingSample> =
        dao.pingsBetween(startEpochMs, endEpochMs).map {
            PingSample(it.atEpochMs, it.rttMs, it.networkType.toNetworkType())
        }

    override suspend fun throughputBetween(startEpochMs: Long, endEpochMs: Long): List<ThroughputSample> =
        dao.throughputBetween(startEpochMs, endEpochMs).map {
            ThroughputSample(
                atEpochMs = it.atEpochMs,
                downMbps = it.downMbps,
                upMbps = it.upMbps,
                networkType = it.networkType.toNetworkType(),
                tier = runCatching { ProbeTier.valueOf(it.tier) }.getOrDefault(ProbeTier.THROUGHPUT_LIGHT),
                partial = it.partial,
            )
        }

    override suspend fun dropsOverlapping(startEpochMs: Long, endEpochMs: Long): List<DropEvent> =
        dao.dropsOverlapping(startEpochMs, endEpochMs).map { it.toDomain() }

    override suspend fun fullSweepCountSince(sinceEpochMs: Long): Int = dao.fullSweepCountSince(sinceEpochMs)

    override suspend fun recordFullSweep(atEpochMs: Long) = dao.insertFullSweep(FullSweepEntity(atEpochMs = atEpochMs))

    override suspend fun addBytesUsed(bytes: Long, metered: Boolean, atEpochMs: Long) =
        dao.insertDataUsage(DataUsageEntity(atEpochMs = atEpochMs, bytes = bytes, metered = metered))

    override suspend fun bytesUsedSince(sinceEpochMs: Long, metered: Boolean): Long =
        dao.bytesUsedSince(sinceEpochMs, metered)

    override suspend fun pruneOlderThan(epochMs: Long) {
        dao.prunePings(epochMs)
        dao.pruneThroughput(epochMs)
        dao.pruneFullSweeps(epochMs)
        dao.pruneDataUsage(epochMs)
        dao.pruneDrops(epochMs)
    }

    /** Recent samples for the live dashboard, newest first. */
    suspend fun recentPings(limit: Int = 200): List<PingSample> =
        dao.recentPings(limit).map { PingSample(it.atEpochMs, it.rttMs, it.networkType.toNetworkType()) }

    private fun DropEntity.toDomain() = DropEvent(startedAtEpochMs, endedAtEpochMs)

    // Unknown enum names must not crash a background worker; an old row from a
    // renamed constant degrades to OTHER rather than taking the cycle down.
    private fun String.toNetworkType(): NetworkType =
        runCatching { NetworkType.valueOf(this) }.getOrDefault(NetworkType.OTHER)

    private fun Set<String>.toNetworkTypes(): Set<NetworkType> =
        mapNotNull { runCatching { NetworkType.valueOf(it) }.getOrNull() }.toSet()

    companion object {
        private val KEY_INTERVAL = intPreferencesKey("cycle_interval_minutes")
        private val KEY_PINGS = intPreferencesKey("pings_per_cycle")
        private val KEY_TP_EVERY = intPreferencesKey("throughput_every_n")
        private val KEY_TP_NETWORKS = stringSetPreferencesKey("throughput_networks")
        private val KEY_FULL_CHARGING = booleanPreferencesKey("full_requires_charging")
        private val KEY_FULL_PER_DAY = intPreferencesKey("full_sweeps_per_day")
        private val KEY_CYCLE_INDEX = longPreferencesKey("cycle_index")
        private val KEY_CONSECUTIVE_FAILURES = intPreferencesKey("consecutive_failures")

        val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val KEY_REPORT_PERIOD = stringPreferencesKey("report_period")
        val KEY_LAST_REPORT_AT = longPreferencesKey("last_report_at")

        @Volatile private var instance: RoomMonitorStore? = null

        fun get(context: Context): RoomMonitorStore = instance ?: synchronized(this) {
            instance ?: RoomMonitorStore(
                context = context.applicationContext,
                db = Room.databaseBuilder(
                    context.applicationContext,
                    MonitorDatabase::class.java,
                    "seven-monitor.db",
                ).build(),
            ).also { instance = it }
        }

        suspend fun isMonitoringEnabled(context: Context): Boolean =
            context.settings.data.first()[KEY_MONITORING_ENABLED] ?: false

        suspend fun setMonitoringEnabled(context: Context, enabled: Boolean) {
            context.settings.edit { it[KEY_MONITORING_ENABLED] = enabled }
        }

        suspend fun lastReportAt(context: Context): Long? =
            context.settings.data.first()[KEY_LAST_REPORT_AT]

        suspend fun setLastReportAt(context: Context, epochMs: Long) {
            context.settings.edit { it[KEY_LAST_REPORT_AT] = epochMs }
        }

        suspend fun reportPeriod(context: Context): ReportPeriod {
            val stored = context.settings.data.first()[KEY_REPORT_PERIOD]
            // Unknown value degrades to WEEKLY rather than crashing a worker.
            return stored?.let { runCatching { ReportPeriod.valueOf(it) }.getOrNull() }
                ?: ReportPeriod.WEEKLY
        }

        suspend fun setReportPeriod(context: Context, period: ReportPeriod) {
            context.settings.edit { it[KEY_REPORT_PERIOD] = period.name }
        }
    }
}
