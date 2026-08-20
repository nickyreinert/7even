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
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
            liveTestPhaseDurationMs = prefs[KEY_LIVE_DURATION] ?: defaults.liveTestPhaseDurationMs,
            liveTestSweepEnabled = prefs[KEY_LIVE_SWEEP] ?: defaults.liveTestSweepEnabled,
            liveTestSweepSteps = prefs[KEY_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.liveTestSweepSteps,
            wifiLiveTestSweepSteps = prefs[KEY_WIFI_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.wifiLiveTestSweepSteps,
            mobileLiveTestSweepSteps = prefs[KEY_MOBILE_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.mobileLiveTestSweepSteps,
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
            p[KEY_LIVE_DURATION] = config.liveTestPhaseDurationMs
            p[KEY_LIVE_SWEEP] = config.liveTestSweepEnabled
            p[KEY_LIVE_SWEEP_PLAN] = SweepPlan.format(config.liveTestSweepSteps)
            p[KEY_WIFI_LIVE_SWEEP_PLAN] = SweepPlan.format(config.wifiLiveTestSweepSteps)
            p[KEY_MOBILE_LIVE_SWEEP_PLAN] = SweepPlan.format(config.mobileLiveTestSweepSteps)
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
            samples.map { PingEntity(atEpochMs = it.atEpochMs, rttMs = it.rttMs, networkType = it.networkType.name, ssid = it.ssid) },
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
                ssid = sample.ssid,
            ),
        )
    }

    /**
     * Room's transaction, so a cycle's sample/drop/usage writes land together.
     *
     * DataStore-backed counters inside [block] are not covered — they are a
     * separate storage engine — so they are deliberately ordered to fail safe:
     * see the note on [MonitorStore.transaction].
     */
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun loadDropState(): MonitorStore.DropState {
        val prefs = context.settings.data.first()
        return MonitorStore.DropState(
            closedDrops = dao.closedDrops().map { it.toDomain() },
            openDropStartedAtEpochMs = dao.openDrop()?.startedAtEpochMs,
            consecutiveFailures = prefs[KEY_CONSECUTIVE_FAILURES] ?: 0,
            // Absent in state written before this key existed; the detector
            // documents how it degrades in that case.
            runStartedAtEpochMs = prefs[KEY_FAILURE_RUN_STARTED_AT],
        )
    }

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

        context.settings.edit {
            it[KEY_CONSECUTIVE_FAILURES] = state.consecutiveFailures
            val runStart = state.runStartedAtEpochMs
            if (runStart == null) it.remove(KEY_FAILURE_RUN_STARTED_AT) else it[KEY_FAILURE_RUN_STARTED_AT] = runStart
        }
    }

    override suspend fun pingsBetween(startEpochMs: Long, endEpochMs: Long): List<PingSample> =
        dao.pingsBetween(startEpochMs, endEpochMs).map {
            PingSample(it.atEpochMs, it.rttMs, it.networkType.toNetworkType(), it.ssid)
        }

    suspend fun oldestMeasurementAt(): Long? = listOfNotNull(dao.oldestPingAt(), dao.oldestThroughputAt()).minOrNull()

    override suspend fun throughputBetween(startEpochMs: Long, endEpochMs: Long): List<ThroughputSample> =
        dao.throughputBetween(startEpochMs, endEpochMs).map {
            ThroughputSample(
                atEpochMs = it.atEpochMs,
                downMbps = it.downMbps,
                upMbps = it.upMbps,
                networkType = it.networkType.toNetworkType(),
                tier = runCatching { ProbeTier.valueOf(it.tier) }.getOrDefault(ProbeTier.THROUGHPUT_LIGHT),
                partial = it.partial,
                ssid = it.ssid,
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

    suspend fun clearHistory() {
        dao.clearPings()
        dao.clearThroughput()
        dao.clearFullSweeps()
        dao.clearDataUsage()
        dao.clearDrops()
        context.settings.edit {
            it.remove(KEY_LATEST_DOWN_SWEEP)
            it.remove(KEY_LATEST_UP_SWEEP)
            it.remove(KEY_CONSECUTIVE_FAILURES)
            it.remove(KEY_FAILURE_RUN_STARTED_AT)
        }
    }

    /** Recent samples for the live dashboard, newest first. */
    suspend fun recentPings(limit: Int = 200): List<PingSample> =
        dao.recentPings(limit).map { PingSample(it.atEpochMs, it.rttMs, it.networkType.toNetworkType(), it.ssid) }

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
        private val KEY_WIFI_LIVE_SWEEP_PLAN = stringPreferencesKey("wifi_live_test_sweep_plan")
        private val KEY_MOBILE_LIVE_SWEEP_PLAN = stringPreferencesKey("mobile_live_test_sweep_plan")
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
        private val KEY_FAILURE_RUN_STARTED_AT = longPreferencesKey("failure_run_started_at")

        val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val KEY_REPORT_PERIOD = stringPreferencesKey("report_period")
        /** When a report was last **generated** — this is what drives scheduling. */
        val KEY_LAST_REPORT_AT = longPreferencesKey("last_report_at")
        /** When monitoring was first switched on; the baseline for the first report. */
        private val KEY_REPORTING_SINCE = longPreferencesKey("reporting_since")
        private val KEY_PENDING_REPORT_TITLE = stringPreferencesKey("pending_report_title")
        private val KEY_PENDING_REPORT_BODY = stringPreferencesKey("pending_report_body")
        private val KEY_PENDING_REPORT_AT = longPreferencesKey("pending_report_at")
        /** When a report was last actually **posted** as a notification. */
        private val KEY_REPORT_DELIVERED_AT = longPreferencesKey("report_delivered_at")

        @Volatile private var instance: RoomMonitorStore? = null

        fun get(context: Context): RoomMonitorStore = instance ?: synchronized(this) {
            instance ?: RoomMonitorStore(
                context = context.applicationContext,
                db = Room.databaseBuilder(
                    context.applicationContext,
                    MonitorDatabase::class.java,
                    "seven-monitor.db",
                ).addMigrations(MIGRATION_1_2).build(),
            ).also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ping_samples ADD COLUMN ssid TEXT")
                db.execSQL("ALTER TABLE throughput_samples ADD COLUMN ssid TEXT")
            }
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

        /**
         * The moment reporting started, recorded once.
         *
         * Report scheduling previously substituted epoch zero for "no report
         * delivered yet", which makes the very first worker wake immediately
         * overdue — so a fresh install could be notified about a window in
         * which it had never measured anything, presented as 100% uptime.
         */
        suspend fun reportingSince(context: Context): Long? =
            context.settings.data.first()[KEY_REPORTING_SINCE]

        suspend fun markReportingStarted(context: Context, epochMs: Long) {
            context.settings.edit { prefs ->
                if (prefs[KEY_REPORTING_SINCE] == null) prefs[KEY_REPORTING_SINCE] = epochMs
            }
        }

        /** A generated report awaiting acknowledgement, delivered or not. */
        data class PendingReport(
            val title: String,
            val body: String,
            val generatedAtEpochMs: Long,
            val deliveredAtEpochMs: Long?,
        ) {
            val wasDelivered: Boolean get() = deliveredAtEpochMs != null
        }

        /**
         * Stores a generated report **before** delivery is attempted.
         *
         * Reports used to exist only as a notification. If notification
         * permission was missing — the default on Android 13+, since it was
         * never requested — the result was computed, discarded, and its
         * timestamp advanced, so the user permanently lost a report they had
         * asked for and had no way to know it had happened.
         */
        suspend fun savePendingReport(context: Context, title: String, body: String, atEpochMs: Long) {
            context.settings.edit {
                it[KEY_PENDING_REPORT_TITLE] = title
                it[KEY_PENDING_REPORT_BODY] = body
                it[KEY_PENDING_REPORT_AT] = atEpochMs
                it.remove(KEY_REPORT_DELIVERED_AT)
            }
        }

        suspend fun markReportDelivered(context: Context, atEpochMs: Long) {
            context.settings.edit { it[KEY_REPORT_DELIVERED_AT] = atEpochMs }
        }

        suspend fun pendingReport(context: Context): PendingReport? {
            val prefs = context.settings.data.first()
            val title = prefs[KEY_PENDING_REPORT_TITLE] ?: return null
            return PendingReport(
                title = title,
                body = prefs[KEY_PENDING_REPORT_BODY].orEmpty(),
                generatedAtEpochMs = prefs[KEY_PENDING_REPORT_AT] ?: 0L,
                deliveredAtEpochMs = prefs[KEY_REPORT_DELIVERED_AT],
            )
        }

        suspend fun clearPendingReport(context: Context) {
            context.settings.edit {
                it.remove(KEY_PENDING_REPORT_TITLE)
                it.remove(KEY_PENDING_REPORT_BODY)
                it.remove(KEY_PENDING_REPORT_AT)
                it.remove(KEY_REPORT_DELIVERED_AT)
            }
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
