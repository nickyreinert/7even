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
import de.sevenapp.monitor.core.NetworkPreference
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.data.MonitorStore
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.SweepPlan
import de.sevenapp.monitor.probe.LatestSweeps
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
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
            automaticHourOfDay = prefs[KEY_AUTO_HOUR] ?: defaults.automaticHourOfDay,
            automaticDayOfWeek = prefs[KEY_AUTO_DAY] ?: defaults.automaticDayOfWeek,
            monitoringNetworks = prefs[KEY_MONITORING_NETWORKS]?.toNetworkTypes()
                ?.ifEmpty { defaults.monitoringNetworks } ?: defaults.monitoringNetworks,
            pingsPerCycle = prefs[KEY_PINGS] ?: defaults.pingsPerCycle,
            throughputEveryNCycles = prefs[KEY_TP_EVERY] ?: defaults.throughputEveryNCycles,
            throughputLightNetworks = prefs[KEY_TP_NETWORKS]?.toNetworkTypes()
                ?: defaults.throughputLightNetworks,
            fullSweepRequiresCharging = prefs[KEY_FULL_CHARGING] ?: defaults.fullSweepRequiresCharging,
            fullSweepsPerDay = prefs[KEY_FULL_PER_DAY] ?: defaults.fullSweepsPerDay,
            liveTestMinDurationMs = prefs[KEY_LIVE_DURATION] ?: defaults.liveTestMinDurationMs,
            liveTestSweepEnabled = prefs[KEY_LIVE_SWEEP] ?: defaults.liveTestSweepEnabled,
            liveTestSweepSteps = prefs[KEY_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.liveTestSweepSteps,
            automaticStreamEnabled = prefs[KEY_AUTO_STREAM] ?: defaults.automaticStreamEnabled,
            automaticSweepEnabled = prefs[KEY_AUTO_SWEEP] ?: defaults.automaticSweepEnabled,
            automaticRequiresCharging = prefs[KEY_AUTO_CHARGING] ?: defaults.automaticRequiresCharging,
            preferredTestNetwork = prefs[KEY_PREFERRED_NETWORK]?.let {
                runCatching { NetworkPreference.valueOf(it) }.getOrNull()
            } ?: defaults.preferredTestNetwork,
            lightDownBytes = prefs[KEY_LIGHT_DOWN] ?: defaults.lightDownBytes,
            lightUpBytes = prefs[KEY_LIGHT_UP] ?: defaults.lightUpBytes,
            wifiMeasurementSizes = prefs[KEY_WIFI_SIZES]?.toSizes() ?: defaults.wifiMeasurementSizes,
            cellularMeasurementSizes = prefs[KEY_CELLULAR_SIZES]?.toSizes() ?: defaults.cellularMeasurementSizes,
            traceUrl = prefs[KEY_TRACE_URL] ?: defaults.traceUrl,
            downUrlTemplate = prefs[KEY_DOWN_URL] ?: defaults.downUrlTemplate,
            upUrl = prefs[KEY_UP_URL] ?: defaults.upUrl,
            streamUrl = prefs[KEY_STREAM_URL] ?: defaults.streamUrl,
            useWebSocketStream = prefs[KEY_USE_WS_STREAM] ?: defaults.useWebSocketStream,
        )
    }

    override suspend fun saveConfig(config: ProbeConfig) {
        context.settings.edit { p ->
            p[KEY_INTERVAL] = config.cycleIntervalMinutes
            p[KEY_AUTO_HOUR] = config.automaticHourOfDay
            p[KEY_AUTO_DAY] = config.automaticDayOfWeek
            p[KEY_MONITORING_NETWORKS] = config.monitoringNetworks.map { it.name }.toSet()
            p[KEY_PINGS] = config.pingsPerCycle
            p[KEY_TP_EVERY] = config.throughputEveryNCycles
            p[KEY_TP_NETWORKS] = config.throughputLightNetworks.map { it.name }.toSet()
            p[KEY_FULL_CHARGING] = config.fullSweepRequiresCharging
            p[KEY_FULL_PER_DAY] = config.fullSweepsPerDay
            p[KEY_LIVE_DURATION] = config.liveTestMinDurationMs
            p[KEY_LIVE_SWEEP] = config.liveTestSweepEnabled
            p[KEY_LIVE_SWEEP_PLAN] = SweepPlan.format(config.liveTestSweepSteps)
            p[KEY_AUTO_STREAM] = config.automaticStreamEnabled
            p[KEY_AUTO_SWEEP] = config.automaticSweepEnabled
            p[KEY_AUTO_CHARGING] = config.automaticRequiresCharging
            p[KEY_PREFERRED_NETWORK] = config.preferredTestNetwork.name
            p[KEY_LIGHT_DOWN] = config.lightDownBytes
            p[KEY_LIGHT_UP] = config.lightUpBytes
            p[KEY_WIFI_SIZES] = config.wifiMeasurementSizes.map { it.toString() }.toSet()
            p[KEY_CELLULAR_SIZES] = config.cellularMeasurementSizes.map { it.toString() }.toSet()
            p[KEY_TRACE_URL] = config.traceUrl
            p[KEY_DOWN_URL] = config.downUrlTemplate
            p[KEY_UP_URL] = config.upUrl
            p[KEY_STREAM_URL] = config.streamUrl
            p[KEY_USE_WS_STREAM] = config.useWebSocketStream
        }
    }

    override suspend fun loadLatestSweeps(): LatestSweeps {
        val prefs = context.settings.data.first()
        return LatestSweeps(
            download = prefs[KEY_LATEST_DOWN_SWEEP]?.decodeSweep() ?: emptyList(),
            upload = prefs[KEY_LATEST_UP_SWEEP]?.decodeSweep() ?: emptyList(),
        )
    }

    override suspend fun saveLatestSweep(direction: SweepRunner.Direction, results: List<SweepResult>) {
        context.settings.edit { prefs ->
            when (direction) {
                SweepRunner.Direction.DOWN -> prefs[KEY_LATEST_DOWN_SWEEP] = results.encodeSweep()
                SweepRunner.Direction.UP -> prefs[KEY_LATEST_UP_SWEEP] = results.encodeSweep()
            }
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

    private fun Set<String>.toSizes(): Set<Int> = mapNotNull { it.toIntOrNull() }.toSet()

    private fun List<SweepResult>.encodeSweep(): String = joinToString(";") { result ->
        "${result.bytes},${result.trials},${result.passCount},${result.trialOutcomes.joinToString("") { if (it) "1" else "0" }}"
    }

    private fun String.decodeSweep(): List<SweepResult> = split(';').mapNotNull { encoded ->
        val fields = encoded.split(',')
        val bytes = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val trials = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val passCount = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
        val outcomes = fields.getOrNull(3)?.map { it == '1' } ?: emptyList()
        if (bytes <= 0 || trials <= 0 || outcomes.size != trials) return@mapNotNull null
        SweepResult(bytes, trials, passCount, avgDurationMs = null, lastError = null, trialOutcomes = outcomes)
    }

    companion object {
        private val KEY_INTERVAL = intPreferencesKey("cycle_interval_minutes")
        private val KEY_AUTO_HOUR = intPreferencesKey("automatic_hour_of_day")
        private val KEY_AUTO_DAY = intPreferencesKey("automatic_day_of_week")
        private val KEY_MONITORING_NETWORKS = stringSetPreferencesKey("monitoring_networks")
        private val KEY_PINGS = intPreferencesKey("pings_per_cycle")
        private val KEY_TP_EVERY = intPreferencesKey("throughput_every_n")
        private val KEY_TP_NETWORKS = stringSetPreferencesKey("throughput_networks")
        private val KEY_FULL_CHARGING = booleanPreferencesKey("full_requires_charging")
        private val KEY_FULL_PER_DAY = intPreferencesKey("full_sweeps_per_day")
        private val KEY_LIVE_DURATION = longPreferencesKey("live_test_min_duration_ms")
        private val KEY_LIVE_SWEEP = booleanPreferencesKey("live_test_sweep_enabled")
        private val KEY_LIVE_SWEEP_PLAN = stringPreferencesKey("live_test_sweep_plan")
        private val KEY_AUTO_STREAM = booleanPreferencesKey("automatic_stream_enabled")
        private val KEY_AUTO_SWEEP = booleanPreferencesKey("automatic_sweep_enabled")
        private val KEY_AUTO_CHARGING = booleanPreferencesKey("automatic_requires_charging")
        private val KEY_LATEST_DOWN_SWEEP = stringPreferencesKey("latest_down_sweep")
        private val KEY_LATEST_UP_SWEEP = stringPreferencesKey("latest_up_sweep")
        private val KEY_PREFERRED_NETWORK = stringPreferencesKey("preferred_test_network")
        private val KEY_LIGHT_DOWN = intPreferencesKey("light_down_bytes")
        private val KEY_LIGHT_UP = intPreferencesKey("light_up_bytes")
        private val KEY_WIFI_SIZES = stringSetPreferencesKey("wifi_measurement_sizes")
        private val KEY_CELLULAR_SIZES = stringSetPreferencesKey("cellular_measurement_sizes")
        private val KEY_TRACE_URL = stringPreferencesKey("trace_url")
        private val KEY_DOWN_URL = stringPreferencesKey("down_url_template")
        private val KEY_UP_URL = stringPreferencesKey("up_url")
        private val KEY_STREAM_URL = stringPreferencesKey("stream_url")
        private val KEY_USE_WS_STREAM = booleanPreferencesKey("use_websocket_stream")
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
