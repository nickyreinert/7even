package de.sevenapp.monitor.data

import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ThroughputSample
import de.sevenapp.monitor.probe.ProbeConfig

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

    suspend fun addBytesUsed(bytes: Long, metered: Boolean, atEpochMs: Long)
    suspend fun bytesUsedSince(sinceEpochMs: Long, metered: Boolean): Long

    /** Discards samples older than the retention window, keeping the DB bounded. */
    suspend fun pruneOlderThan(epochMs: Long)

    data class DropState(
        val closedDrops: List<DropEvent>,
        val openDropStartedAtEpochMs: Long?,
        val consecutiveFailures: Int,
    ) {
        companion object {
            val EMPTY = DropState(emptyList(), null, 0)
        }
    }
}
