package de.sevenapp.monitor.android.ui

/**
 * One documented CSV schema, shared by all three row types.
 *
 * Drop rows previously put `endedAt` under the `download_mbps` column, so a
 * spreadsheet reading the header found epoch milliseconds in a throughput
 * column. Every row now fills the same named columns or leaves them empty.
 *
 * Kept as a pure function of [HistoryState] rather than a ViewModel method so
 * the export format can be tested on the host JVM, with no Application, no Room
 * database, and no device.
 */
object HistoryCsv {

    const val HEADER = "timestamp,type,connection,ssid,ping_ms,download_mbps,upload_mbps,ended_at,partial"

    fun build(state: HistoryState): String = buildString {
        appendLine(HEADER)
        state.samples.forEach { s ->
            appendLine("${s.atEpochMs},ping,${s.networkType},${quote(s.ssid)},${s.rttMs ?: ""},,,,")
        }
        state.throughput.forEach { s ->
            appendLine("${s.atEpochMs},throughput,${s.networkType},${quote(s.ssid)},,${s.downMbps ?: ""},${s.upMbps ?: ""},,${s.partial}")
        }
        state.drops.forEach { d ->
            // Drops carry no connection: a drop is precisely the interval with
            // no usable network to attribute it to.
            appendLine("${d.startedAtEpochMs},drop,,,,,,${d.endedAtEpochMs ?: "ongoing"},")
        }
    }

    /** Minimal RFC-4180 quoting, so an SSID containing a comma cannot shift columns. */
    private fun quote(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
