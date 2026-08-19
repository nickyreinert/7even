package de.sevenapp.monitor.probe

import de.sevenapp.monitor.core.Clock
import de.sevenapp.monitor.core.DropDetector
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.data.MonitorStore
import de.sevenapp.monitor.report.Report
import de.sevenapp.monitor.report.ReportBuilder
import de.sevenapp.monitor.report.ReportPeriod
import de.sevenapp.monitor.report.ReportSchedule
import kotlinx.datetime.TimeZone

/**
 * Ties the store to the engine: load state, run one cycle, persist what came
 * back. This is the piece each platform's scheduler calls.
 *
 * It exists so that the platform worker (Android's [CoroutineWorker], iOS's
 * BGTask handler) contains no logic beyond gathering device state — everything
 * else is here, in commonMain, where it can be tested.
 */
class MonitorCoordinator(
    private val store: MonitorStore,
    private val engine: ProbeEngine,
    private val clock: Clock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    retentionDays: Int = 90,
) {

    /**
     * Samples older than this are pruned so the database stays bounded.
     *
     * Supplied by the caller rather than fixed here, because the correct window
     * depends on entitlement — and specifically on whether Pro has *ever* been
     * held, not just whether it is active now. See
     * `FeatureGate.effectiveRetentionDays`: shrinking the window on lapse would
     * delete history the user cannot recreate.
     */
    private val retentionMs: Long = retentionDays * 24L * 60 * 60 * 1000

    suspend fun runCycle(
        deviceState: DeviceState,
        forceSpeedTest: Boolean = false,
    ): ProbeEngine.CycleOutput {
        val config = store.loadConfig().let {
            if (forceSpeedTest) it.copy(throughputEveryNCycles = 1) else it
        }
        val dropState = store.loadDropState()

        val detector = DropDetector().apply {
            restore(
                closedDrops = dropState.closedDrops,
                openDropStartedAtEpochMs = dropState.openDropStartedAtEpochMs,
                consecutiveFailures = dropState.consecutiveFailures,
            )
        }

        val now = clock.nowEpochMs()
        val output = engine.runCycle(
            ProbeEngine.CycleInput(
                config = config,
                cycleIndex = store.nextCycleIndex(),
                deviceState = deviceState,
                fullSweepsToday = store.fullSweepCountSince(startOfDay(now)),
                dropDetector = detector,
            ),
        )

        store.appendPings(output.pings)
        output.throughput?.let { store.appendThroughput(it) }

        // Persist the detector's *whole* state, not just the closed drops —
        // dropping the open-drop marker would split an ongoing outage into
        // separate events at every worker invocation.
        store.saveDropState(
            MonitorStore.DropState(
                closedDrops = detector.allDrops().filter { !it.ongoing },
                openDropStartedAtEpochMs = detector.allDrops().firstOrNull { it.ongoing }?.startedAtEpochMs,
                consecutiveFailures = if (detector.isDropOpen) config.pingsPerCycle else 0,
            ),
        )

        if (output.tier == ProbeTier.THROUGHPUT_FULL) store.recordFullSweep(now)
        if (output.bytesUsed > 0) store.addBytesUsed(output.bytesUsed, deviceState.isMetered, now)

        store.pruneOlderThan(now - retentionMs)

        return output
    }

    /**
     * Builds the report for [period] if one is due at [nowEpochMs].
     * Returns null when nothing is due, so the caller can simply not notify.
     */
    suspend fun buildDueReport(
        period: ReportPeriod,
        nowEpochMs: Long,
        lastDeliveredEpochMs: Long?,
    ): Report? {
        val due = ReportSchedule.nextDelivery(period, lastDeliveredEpochMs ?: 0L, timeZone)
        if (nowEpochMs < due) return null
        return buildReport(period, nowEpochMs)
    }

    suspend fun buildReport(period: ReportPeriod, atEpochMs: Long): Report {
        val window = ReportSchedule.windowFor(period, atEpochMs, timeZone)
        val config = store.loadConfig()

        return ReportBuilder.build(
            period = period,
            windowStartEpochMs = window.startEpochMs,
            windowEndEpochMs = window.endEpochMs,
            pings = store.pingsBetween(window.startEpochMs, window.endEpochMs),
            throughput = store.throughputBetween(window.startEpochMs, window.endEpochMs),
            drops = store.dropsOverlapping(window.startEpochMs, window.endEpochMs),
            expectedSamples = ReportSchedule.expectedSamples(
                window, config.cycleIntervalMinutes, config.pingsPerCycle,
            ),
        )
    }

    private fun startOfDay(epochMs: Long): Long {
        val window = ReportSchedule.windowFor(ReportPeriod.DAILY, epochMs, timeZone)
        // windowFor(DAILY) returns yesterday..today; today's 00:00 is its end.
        return window.endEpochMs
    }
}
