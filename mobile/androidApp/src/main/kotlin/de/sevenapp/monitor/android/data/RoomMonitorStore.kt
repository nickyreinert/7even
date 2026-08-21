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
import de.sevenapp.monitor.probe.SweepFrequency
import de.sevenapp.monitor.probe.SweepPlan
import de.sevenapp.monitor.probe.FailureReason
import de.sevenapp.monitor.probe.LatestSweeps
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.SweepSizeStats
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
            sustainedProbeEnabled = prefs[KEY_SUSTAINED_PROBE] ?: defaults.sustainedProbeEnabled,
            sustainedProbeTotalBytes = prefs[KEY_SUSTAINED_PROBE_BYTES] ?: defaults.sustainedProbeTotalBytes,
            wifiSweepTimeoutMs = prefs[KEY_WIFI_SWEEP_TIMEOUT] ?: defaults.wifiSweepTimeoutMs,
            // A persisted value equal to the old default is indistinguishable
            // from "never customized" — see LEGACY_MOBILE_SWEEP_TIMEOUT_MS —
            // so it is treated as such and upgraded, rather than silently
            // shadowing the new, safe default for every install that predates
            // this change.
            mobileSweepTimeoutMs = prefs[KEY_MOBILE_SWEEP_TIMEOUT]
                ?.takeUnless { it == LEGACY_MOBILE_SWEEP_TIMEOUT_MS }
                ?: defaults.mobileSweepTimeoutMs,
            liveTestSweepSteps = prefs[KEY_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.liveTestSweepSteps,
            wifiLiveTestSweepSteps = prefs[KEY_WIFI_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.wifiLiveTestSweepSteps,
            mobileLiveTestSweepSteps = prefs[KEY_MOBILE_LIVE_SWEEP_PLAN]?.let(SweepPlan::parse) ?: defaults.mobileLiveTestSweepSteps,
            automaticStreamEnabled = prefs[KEY_AUTO_STREAM] ?: defaults.automaticStreamEnabled,
            automaticSweepEnabled = prefs[KEY_AUTO_SWEEP] ?: defaults.automaticSweepEnabled,
            automaticRequiresCharging = prefs[KEY_AUTO_CHARGING] ?: defaults.automaticRequiresCharging,
            sweepFrequency = prefs[KEY_SWEEP_FREQUENCY]?.let {
                runCatching { SweepFrequency.valueOf(it) }.getOrNull()
            } ?: defaults.sweepFrequency,
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
            p[KEY_SUSTAINED_PROBE] = config.sustainedProbeEnabled
            p[KEY_SUSTAINED_PROBE_BYTES] = config.sustainedProbeTotalBytes
            p[KEY_WIFI_SWEEP_TIMEOUT] = config.wifiSweepTimeoutMs
            p[KEY_MOBILE_SWEEP_TIMEOUT] = config.mobileSweepTimeoutMs
            p[KEY_LIVE_SWEEP_PLAN] = SweepPlan.format(config.liveTestSweepSteps)
            p[KEY_WIFI_LIVE_SWEEP_PLAN] = SweepPlan.format(config.wifiLiveTestSweepSteps)
            p[KEY_MOBILE_LIVE_SWEEP_PLAN] = SweepPlan.format(config.mobileLiveTestSweepSteps)
            p[KEY_AUTO_STREAM] = config.automaticStreamEnabled
            p[KEY_AUTO_SWEEP] = config.automaticSweepEnabled
            p[KEY_AUTO_CHARGING] = config.automaticRequiresCharging
            p[KEY_SWEEP_FREQUENCY] = config.sweepFrequency.name
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

    override suspend fun recordSweepRung(direction: SweepRunner.Direction, bytes: Int, trials: Int, passCount: Int) {
        dao.insertSweepRung(
            SweepRungEntity(
                atEpochMs = System.currentTimeMillis(),
                direction = direction.name,
                bytes = bytes,
                trials = trials,
                passCount = passCount,
            ),
        )
    }

    override suspend fun sweepRungTotals(): List<SweepSizeStats> =
        dao.sweepRungTotals().mapNotNull { row ->
            val direction = runCatching { SweepRunner.Direction.valueOf(row.direction) }.getOrNull() ?: return@mapNotNull null
            SweepSizeStats(direction = direction, bytes = row.bytes, trialsPassed = row.trialsPassed, trialsTotal = row.trialsTotal)
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

    suspend fun latestMeasurementAt(): Long? = listOfNotNull(dao.latestPingAt(), dao.latestThroughputAt()).maxOrNull()

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

    override suspend fun latestFullSweepAt(): Long? = dao.latestFullSweepAt()

    override suspend fun recordFullSweep(atEpochMs: Long) = dao.insertFullSweep(FullSweepEntity(atEpochMs = atEpochMs))

    /**
     * One row per [de.sevenapp.monitor.android.work.ProbeWorker] wakeup — see
     * [CycleOutcomeEntity]'s doc comment for exactly which skips count as
     * `ran = false`.
     */
    suspend fun recordCycleOutcome(atEpochMs: Long, ran: Boolean, reason: String? = null) =
        dao.insertCycleOutcome(CycleOutcomeEntity(atEpochMs = atEpochMs, ran = ran, reason = reason))

    suspend fun cycleOutcomeCountSince(sinceEpochMs: Long, ran: Boolean): Int =
        dao.cycleOutcomeCountSince(sinceEpochMs, ran)

    suspend fun missedCycleOutcomesBetween(startEpochMs: Long, endEpochMs: Long): List<CycleOutcomeEntity> =
        dao.missedCycleOutcomesBetween(startEpochMs, endEpochMs)

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
        dao.pruneSweepRungs(epochMs)
    }

    suspend fun clearHistory() {
        dao.clearPings()
        dao.clearThroughput()
        dao.clearFullSweeps()
        dao.clearDataUsage()
        dao.clearDrops()
        dao.clearSweepRungs()
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

    /**
     * Positional CSV: bytes,trials,passCount,outcomes,bytesTransferred,elapsedMs,lastError.
     *
     * Fields are appended, never reordered, so a value written by an older
     * build still decodes — the trailing fields simply come back as defaults.
     */
    private fun List<SweepResult>.encodeSweep(): String = joinToString(";") { result ->
        listOf(
            result.bytes.toString(),
            result.trials.toString(),
            result.passCount.toString(),
            result.trialOutcomes.joinToString("") { if (it) "1" else "0" },
            result.bytesTransferred.toString(),
            result.totalElapsedMs.toLong().toString(),
            result.lastError?.name.orEmpty(),
        ).joinToString(",")
    }

    private fun String.decodeSweep(): List<SweepResult> = split(';').mapNotNull { encoded ->
        val fields = encoded.split(',')
        val bytes = fields.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val trials = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val passCount = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
        val outcomes = fields.getOrNull(3)?.map { it == '1' } ?: emptyList()
        // A cancelled rung legitimately records fewer outcomes than trials, so
        // this only rejects an outcome list that is longer than the plan.
        if (bytes <= 0 || trials <= 0 || outcomes.size > trials) return@mapNotNull null
        SweepResult(
            bytes = bytes,
            trials = trials,
            passCount = passCount,
            avgDurationMs = null,
            lastError = fields.getOrNull(6)?.takeIf { it.isNotEmpty() }
                ?.let { name -> runCatching { FailureReason.valueOf(name) }.getOrNull() },
            trialOutcomes = outcomes,
            bytesTransferred = fields.getOrNull(4)?.toLongOrNull() ?: 0L,
            totalElapsedMs = fields.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
        )
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
        private val KEY_SUSTAINED_PROBE = booleanPreferencesKey("sustained_probe_enabled")
        private val KEY_SUSTAINED_PROBE_BYTES = longPreferencesKey("sustained_probe_total_bytes")
        private val KEY_WIFI_SWEEP_TIMEOUT = longPreferencesKey("wifi_sweep_timeout_ms")
        private val KEY_MOBILE_SWEEP_TIMEOUT = longPreferencesKey("mobile_sweep_timeout_ms")

        /**
         * The old hardcoded [ProbeConfig.mobileSweepTimeoutMs] default, before it
         * was raised to clear the ladder's own documented ~125s worst case (a
         * 1MB rung at 64 kbit/s) with real margin. DataStore persists whatever
         * value was in memory the first time *any* setting was saved — which for
         * an install that predates this change is this exact number — so simply
         * raising the compiled-in default does nothing for an existing install:
         * [loadConfig] would keep reading 120,000 back from disk forever. A
         * device that measured a real ~64 kbit/s connection can independently
         * prove this: the 1MB rung times out at exactly 120,006ms, having moved
         * every requested byte, because the round's own budget ran out a few
         * seconds before the transfer it was sized for could finish.
         */
        private const val LEGACY_MOBILE_SWEEP_TIMEOUT_MS = 120_000L
        private val KEY_LIVE_SWEEP_PLAN = stringPreferencesKey("live_test_sweep_plan")
        private val KEY_WIFI_LIVE_SWEEP_PLAN = stringPreferencesKey("wifi_live_test_sweep_plan")
        private val KEY_MOBILE_LIVE_SWEEP_PLAN = stringPreferencesKey("mobile_live_test_sweep_plan")
        private val KEY_AUTO_STREAM = booleanPreferencesKey("automatic_stream_enabled")
        private val KEY_AUTO_SWEEP = booleanPreferencesKey("automatic_sweep_enabled")
        private val KEY_AUTO_CHARGING = booleanPreferencesKey("automatic_requires_charging")
        private val KEY_SWEEP_FREQUENCY = stringPreferencesKey("sweep_frequency")
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
        /** Monday=1 through Sunday=7, matching ProbeConfig.automaticDayOfWeek. Only consulted when the period is WEEKLY. */
        val KEY_REPORT_DAY_OF_WEEK = intPreferencesKey("report_day_of_week")
        /** 1..31, clamped to the shorter month when it doesn't have that many days. Only consulted when the period is MONTHLY. */
        val KEY_REPORT_DAY_OF_MONTH = intPreferencesKey("report_day_of_month")
        /** Always overwritten on enable — unlike [KEY_REPORTING_SINCE], which tracks the first-ever activation. */
        val KEY_MONITORING_ACTIVATED_AT = longPreferencesKey("monitoring_activated_at")
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build(),
            ).also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ping_samples ADD COLUMN ssid TEXT")
                db.execSQL("ALTER TABLE throughput_samples ADD COLUMN ssid TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sweep_rungs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        atEpochMs INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        bytes INTEGER NOT NULL,
                        trials INTEGER NOT NULL,
                        passCount INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sweep_rungs_bytes ON sweep_rungs(bytes)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sweep_rungs_direction ON sweep_rungs(direction)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cycle_outcomes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        atEpochMs INTEGER NOT NULL,
                        ran INTEGER NOT NULL,
                        reason TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cycle_outcomes_atEpochMs ON cycle_outcomes(atEpochMs)")
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

        suspend fun reportDayOfWeek(context: Context): Int =
            context.settings.data.first()[KEY_REPORT_DAY_OF_WEEK] ?: 1

        suspend fun setReportDayOfWeek(context: Context, isoDayOfWeek: Int) {
            context.settings.edit { it[KEY_REPORT_DAY_OF_WEEK] = isoDayOfWeek.coerceIn(1, 7) }
        }

        suspend fun reportDayOfMonth(context: Context): Int =
            context.settings.data.first()[KEY_REPORT_DAY_OF_MONTH] ?: 1

        suspend fun setReportDayOfMonth(context: Context, dayOfMonth: Int) {
            context.settings.edit { it[KEY_REPORT_DAY_OF_MONTH] = dayOfMonth.coerceIn(1, 31) }
        }

        suspend fun monitoringActivatedAt(context: Context): Long? =
            context.settings.data.first()[KEY_MONITORING_ACTIVATED_AT]

        suspend fun markMonitoringActivated(context: Context, epochMs: Long) {
            context.settings.edit { it[KEY_MONITORING_ACTIVATED_AT] = epochMs }
        }
    }
}
