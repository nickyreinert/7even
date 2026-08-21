package de.sevenapp.monitor.data

import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.probe.ProbeConfig
import de.sevenapp.monitor.probe.LatestSweeps
import de.sevenapp.monitor.probe.SweepResult
import de.sevenapp.monitor.probe.SweepRunner
import de.sevenapp.monitor.probe.SweepSizeStats

/**
 * Everything the engine needs to persist, expressed as an interface in
 * commonMain so the engine stays platform-free. Room implements this on
 * Android; the iOS implementation will satisfy the same contract.
 *
 * The methods are deliberately coarse — one call per cycle rather than a
 * general-purpose query API — because a background worker gets a few hundred
 * milliseconds of goodwill and chatty persistence is how that gets spent.
 */
interface MonitorStore {

    suspend fun loadConfig(): ProbeConfig
    suspend fun saveConfig(config: ProbeConfig)

    /** Foreground size-sweep results are retained separately from history charts. */
    suspend fun loadLatestSweeps(): LatestSweeps = LatestSweeps()
    suspend fun saveLatestSweep(direction: SweepRunner.Direction, results: List<SweepResult>) {}

    /**
     * One completed rung, for the size-sweep history overview — every rung of
     * every run, not just the latest sweep's snapshot. [loadLatestSweeps]
     * answers "what did the most recent sweep find"; this answers "how has
     * each size actually done over time."
     */
    suspend fun recordSweepRung(direction: SweepRunner.Direction, bytes: Int, trials: Int, passCount: Int) {}

    /** Cumulative pass/fail totals per (direction, size), across every recorded rung. */
    suspend fun sweepRungTotals(): List<SweepSizeStats> = emptyList()

    /** Monotonic counter driving tier selection; must survive process death. */
    suspend fun nextCycleIndex(): Long

    suspend fun appendPings(samples: List<PingSample>)
    suspend fun appendThroughput(sample: ThroughputSample)

    /**
     * Drop state, stored rather than held in memory: a two-hour outage spans
     * many worker invocations, and without persistence it would be recorded as
     * a series of unrelated short drops.
     */
    suspend fun loadDropState(): DropState
    suspend fun saveDropState(state: DropState)

    suspend fun pingsBetween(startEpochMs: Long, endEpochMs: Long): List<PingSample>
    suspend fun throughputBetween(startEpochMs: Long, endEpochMs: Long): List<ThroughputSample>
    suspend fun dropsOverlapping(startEpochMs: Long, endEpochMs: Long): List<DropEvent>

    /** Full sweeps are capped per day; [sinceEpochMs] is the local day boundary. */
    suspend fun fullSweepCountSince(sinceEpochMs: Long): Int
    suspend fun recordFullSweep(atEpochMs: Long)

    /** When the most recent full sweep ran, for the sweep-frequency cadence gate. Null if none yet. */
    suspend fun latestFullSweepAt(): Long? = null

    suspend fun addBytesUsed(bytes: Long, metered: Boolean, atEpochMs: Long)
    suspend fun bytesUsedSince(sinceEpochMs: Long, metered: Boolean): Long

    /** Discards samples older than the retention window, keeping the DB bounded. */
    suspend fun pruneOlderThan(epochMs: Long)

    /**
     * Runs [block] as one unit, so a cycle's writes cannot be split.
     *
     * A cycle persists samples, detector state, sweep counts and usage as
     * separate writes; a process death between any two of them leaves the
     * database describing a cycle that half-happened. The default is a plain
     * pass-through so a store with no transaction support still compiles and
     * behaves exactly as before.
     *
     * Note the Android implementation covers the Room database only — a few
     * counters live in DataStore and cannot join a SQLite transaction. Those
     * are written before the samples they describe, so the failure mode is a
     * counter that is momentarily ahead of the data, never behind it.
     */
    suspend fun <T> transaction(block: suspend () -> T): T = block()

    /**
     * The detector's exact snapshot, not a summary of it.
     *
     * [runStartedAtEpochMs] is the first failure of the *current* run, which
     * may not have crossed the drop threshold yet. Persisting it is what lets a
     * drop that develops across several worker invocations be backdated to when
     * the connection actually went away.
     */
    data class DropState(
        val closedDrops: List<DropEvent>,
        val openDropStartedAtEpochMs: Long?,
        val consecutiveFailures: Int,
        val runStartedAtEpochMs: Long? = null,
    ) {
        companion object {
            val EMPTY = DropState(emptyList(), null, 0, null)
        }
    }
}
