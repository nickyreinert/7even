package de.sevenapp.monitor.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Storage schema.
 *
 * Samples are stored raw rather than pre-aggregated: the reports the user
 * actually wants (per-network, per-hour, p95) are not all derivable from a
 * fixed rollup, and at this volume raw rows are cheap. `pruneOlderThan` keeps
 * the table bounded — at 96 cycles/day × 3 pings, 90 days is roughly 26k rows.
 */
@Entity(tableName = "ping_samples", indices = [androidx.room.Index("atEpochMs")])
data class PingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    /** Null means the probe failed — the distinction reports depend on. */
    val rttMs: Double?,
    val networkType: String,
    val ssid: String?,
)

@Entity(tableName = "throughput_samples", indices = [androidx.room.Index("atEpochMs")])
data class ThroughputEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    val downMbps: Double?,
    val upMbps: Double?,
    val networkType: String,
    val tier: String,
    val partial: Boolean,
    val ssid: String?,
)

@Entity(tableName = "drop_events")
data class DropEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    /** Null = still open. Exactly one row may be open at a time. */
    val endedAtEpochMs: Long?,
)

@Entity(tableName = "full_sweeps")
data class FullSweepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
)

@Entity(tableName = "data_usage")
data class DataUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atEpochMs: Long,
    val bytes: Long,
    val metered: Boolean,
)

@Dao
interface MonitorDao {

    @Insert suspend fun insertPings(rows: List<PingEntity>)
    @Insert suspend fun insertThroughput(row: ThroughputEntity)
    @Insert suspend fun insertFullSweep(row: FullSweepEntity)
    @Insert suspend fun insertDataUsage(row: DataUsageEntity)

    @Query("SELECT * FROM ping_samples WHERE atEpochMs BETWEEN :start AND :end ORDER BY atEpochMs")
    suspend fun pingsBetween(start: Long, end: Long): List<PingEntity>

    @Query("SELECT MIN(atEpochMs) FROM ping_samples")
    suspend fun oldestPingAt(): Long?

    @Query("SELECT * FROM throughput_samples WHERE atEpochMs BETWEEN :start AND :end ORDER BY atEpochMs")
    suspend fun throughputBetween(start: Long, end: Long): List<ThroughputEntity>

    @Query("SELECT MIN(atEpochMs) FROM throughput_samples")
    suspend fun oldestThroughputAt(): Long?

    @Query("SELECT * FROM ping_samples ORDER BY atEpochMs DESC LIMIT :limit")
    suspend fun recentPings(limit: Int): List<PingEntity>

    @Query("SELECT * FROM throughput_samples ORDER BY atEpochMs DESC LIMIT :limit")
    suspend fun recentThroughput(limit: Int): List<ThroughputEntity>

    // Overlap, not containment: a drop that started before the window and is
    // still running inside it is exactly the one the report must not miss.
    @Query(
        """
        SELECT * FROM drop_events
        WHERE startedAtEpochMs <= :end
          AND (endedAtEpochMs IS NULL OR endedAtEpochMs >= :start)
        ORDER BY startedAtEpochMs
        """,
    )
    suspend fun dropsOverlapping(start: Long, end: Long): List<DropEntity>

    @Query("SELECT * FROM drop_events WHERE endedAtEpochMs IS NULL LIMIT 1")
    suspend fun openDrop(): DropEntity?

    @Query("SELECT * FROM drop_events WHERE endedAtEpochMs IS NOT NULL ORDER BY startedAtEpochMs")
    suspend fun closedDrops(): List<DropEntity>

    @Insert suspend fun insertDrop(row: DropEntity): Long

    @Query("UPDATE drop_events SET endedAtEpochMs = :endedAt WHERE endedAtEpochMs IS NULL")
    suspend fun closeOpenDrop(endedAt: Long)

    @Query("SELECT COUNT(*) FROM full_sweeps WHERE atEpochMs >= :since")
    suspend fun fullSweepCountSince(since: Long): Int

    @Query("SELECT COALESCE(SUM(bytes), 0) FROM data_usage WHERE atEpochMs >= :since AND metered = :metered")
    suspend fun bytesUsedSince(since: Long, metered: Boolean): Long

    @Query("DELETE FROM ping_samples WHERE atEpochMs < :before")
    suspend fun prunePings(before: Long)

    @Query("DELETE FROM ping_samples") suspend fun clearPings()

    @Query("DELETE FROM throughput_samples WHERE atEpochMs < :before")
    suspend fun pruneThroughput(before: Long)

    @Query("DELETE FROM throughput_samples") suspend fun clearThroughput()

    @Query("DELETE FROM full_sweeps WHERE atEpochMs < :before")
    suspend fun pruneFullSweeps(before: Long)

    @Query("DELETE FROM full_sweeps") suspend fun clearFullSweeps()

    @Query("DELETE FROM data_usage WHERE atEpochMs < :before")
    suspend fun pruneDataUsage(before: Long)

    @Query("DELETE FROM data_usage") suspend fun clearDataUsage()

    // Closed drops only — pruning an open drop would orphan an ongoing outage.
    @Query("DELETE FROM drop_events WHERE endedAtEpochMs IS NOT NULL AND endedAtEpochMs < :before")
    suspend fun pruneDrops(before: Long)

    @Query("DELETE FROM drop_events") suspend fun clearDrops()
}

@Database(
    entities = [
        PingEntity::class,
        ThroughputEntity::class,
        DropEntity::class,
        FullSweepEntity::class,
        DataUsageEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun dao(): MonitorDao
}
