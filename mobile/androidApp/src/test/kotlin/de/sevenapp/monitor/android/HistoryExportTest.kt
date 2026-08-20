package de.sevenapp.monitor.android

import de.sevenapp.monitor.android.ui.HistoryCsv
import de.sevenapp.monitor.android.ui.HistoryState
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.NetworkType
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.ProbeTier
import de.sevenapp.monitor.core.ThroughputSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CSV schema, tested as a pure function of state.
 *
 * `HistoryViewModel.buildCsv` deliberately takes the state it renders rather
 * than reading a field, so the export format is testable on the host JVM
 * without an Application, a Room database, or a device.
 */
class HistoryExportTest {

    private val state = HistoryState(
        samples = listOf(
            PingSample(1_000, 12.5, NetworkType.WIFI, "Home"),
            PingSample(2_000, null, NetworkType.WIFI, "Home"),
        ),
        throughput = listOf(
            ThroughputSample(3_000, 42.0, 7.5, NetworkType.WIFI, ProbeTier.THROUGHPUT_FULL, partial = false, ssid = "Home"),
        ),
        drops = listOf(DropEvent(4_000, 5_000), DropEvent(6_000, null)),
    )

    private fun csv() = HistoryCsv.build(state)

    @Test
    fun everyRowMatchesTheHeaderWidth() {
        // Drop rows used to put endedAt under the download_mbps column, so a
        // reader following the header found epoch millis in a throughput field.
        val lines = csv().trim().lines()
        val columns = lines.first().split(',').size
        lines.drop(1).forEach { row ->
            assertEquals(columns, splitCsv(row).size, "row does not match the header: $row")
        }
    }

    @Test
    fun dropRowsCarryTheirEndTimeInTheEndedAtColumn() {
        val header = csv().lines().first().split(',')
        val endedAtIndex = header.indexOf("ended_at")
        val downloadIndex = header.indexOf("download_mbps")
        val dropRow = csv().lines().first { it.contains(",drop,") }
        val cells = splitCsv(dropRow)
        assertEquals("5000", cells[endedAtIndex])
        assertEquals("", cells[downloadIndex])
    }

    @Test
    fun anOngoingDropSaysSoRatherThanLookingLikeAnEndTime() {
        assertTrue(csv().lines().any { it.contains(",drop,") && it.contains("ongoing") })
    }

    @Test
    fun anSsidContainingACommaDoesNotShiftColumns() {
        val tricky = state.copy(
            samples = listOf(PingSample(1_000, 12.5, NetworkType.WIFI, "Cafe, Downstairs")),
            throughput = emptyList(),
            drops = emptyList(),
        )
        val lines = HistoryCsv.build(tricky).trim().lines()
        val columns = lines.first().split(',').size
        assertEquals(columns, splitCsv(lines[1]).size)
        assertTrue(lines[1].contains("\"Cafe, Downstairs\""))
    }

    /** Minimal RFC-4180 reader, enough to verify the writer does not shift columns. */
    private fun splitCsv(row: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuotes && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
}
