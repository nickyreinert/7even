package de.sevenapp.monitor.core

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Display formatting, ported from the web app so a screenshot of the phone and
 * a screenshot of the browser can be compared without the reader wondering
 * whether "1.2MB" means the same thing in both.
 *
 * Note these use decimal units (1MB = 1,000,000 B), matching index.html and the
 * way network speeds are conventionally quoted — not 1024-based units.
 */
object Format {

    fun bytes(value: Long): String = when {
        abs(value) >= 1_000_000 -> {
            val mb = value / 1_000_000.0
            if (value % 1_000_000L == 0L) "${mb.roundToLong()}MB" else "${oneDp(mb)}MB"
        }
        abs(value) >= 1_000 -> "${(value / 1_000.0).roundToLong()}KB"
        else -> "${value}B"
    }

    /** "3m 07s" / "42s", matching the web app's formatDuration. */
    fun duration(ms: Long): String {
        val totalSec = (ms / 1000.0).roundToLong()
        val m = totalSec / 60
        val s = totalSec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    /**
     * Rates below 1 Mbps are shown in kbps rather than as "0.4 Mbps".
     * A monitor that reports a struggling connection as "0.0 Mbps" reads as
     * broken instrumentation rather than as a slow link.
     */
    data class Rate(val value: String, val unit: String)

    fun mbps(value: Double?): Rate {
        if (value == null) return Rate("—", "")
        if (value < 1.0) return Rate(((value * 1000).roundToInt()).toString(), "kbps")
        return Rate(oneDp(value), "Mbps")
    }

    fun percent(value: Double?): String = if (value == null) "—" else "${oneDp(value)}%"

    fun millis(value: Double?): String = if (value == null) "—" else "${oneDp(value)} ms"

    private fun oneDp(v: Double): String {
        val rounded = (v * 10.0).roundToLong() / 10.0
        val whole = rounded.toLong()
        val frac = ((rounded - whole) * 10).roundToLong().let { if (it < 0) -it else it }
        return "$whole.$frac"
    }
}
