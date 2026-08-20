package de.sevenapp.monitor.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.chart.AxisTicks
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.core.DropEvent
import de.sevenapp.monitor.core.Stats
import de.sevenapp.monitor.probe.SweepResult
import kotlin.math.max

/**
 * The chart port from index.html.
 *
 * The drawing translates fairly directly from the 2D canvas API to DrawScope;
 * the fiddly part — min/avg/max tick placement with collision avoidance — lives
 * in :shared as [AxisTicks] where it is unit-tested, rather than being
 * eyeballed inside a draw block.
 */

private val DownBlue = Color(0xFF3B82F6)
private val UpAmber = Color(0xFFF59E0B)
private val FailRed = Color(0xFFF87171)
private val AxisGrey = Color(0xFF888888)
private val TickGrey = Color(0xFF666666)

@Composable
fun LatencyChart(
    samples: List<PingSample>,
    modifier: Modifier = Modifier,
    heightDp: Int = 120,
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (samples.isEmpty()) return@Canvas

            val padding = 4f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            val rtts = samples.mapNotNull { it.rttMs }
            val maxRtt = max(20.0, rtts.maxOrNull() ?: 20.0)

            val barW = max(1f, (plotW / samples.size) - 1f)

            samples.forEachIndexed { i, sample ->
                val x = padding + i * (plotW / samples.size)
                if (sample.rttMs == null) {
                    // A failed probe gets a full-height translucent column with
                    // a solid base. It must not be drawn at a height that
                    // implies a latency value — "no answer" is not "measured
                    // near-zero" — but the previous 4px tick was so faint that
                    // a chart of nothing but failures read as an empty chart,
                    // which is how "it isn't pinging at all" and "every ping
                    // failed" became indistinguishable.
                    drawRect(
                        FailRed.copy(alpha = 0.25f),
                        Offset(x, padding),
                        androidx.compose.ui.geometry.Size(barW, plotH),
                    )
                    drawRect(FailRed, Offset(x, padding + plotH - 6f), androidx.compose.ui.geometry.Size(barW, 6f))
                } else {
                    val h = max(1f, ((sample.rttMs!! / maxRtt) * plotH).toFloat())
                    drawRect(DownBlue.copy(alpha = 0.7f), Offset(x, padding + plotH - h), androidx.compose.ui.geometry.Size(barW, h))
                }
            }

            drawAxisTicks(
                values = rtts,
                toY = { v -> (padding + plotH - (v / maxRtt) * plotH).toFloat() },
                padding = padding,
                plotHeight = plotH,
                label = { "${it.toInt()}ms" },
            )
        }
    }
}

@Composable
fun ThroughputBars(
    values: List<Double?>,
    upload: Boolean,
    modifier: Modifier = Modifier,
    heightDp: Int = 100,
    suffix: String = " Mbps",
) {
    ValueBarChart(values, if (upload) UpAmber else DownBlue, modifier, heightDp, suffix)
}

@Composable
fun ValueBarChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 100,
    suffix: String = "",
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (values.isEmpty()) return@Canvas

            val padding = 4f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            val present = values.filterNotNull()
            if (present.isEmpty()) return@Canvas
            val maxV = if (suffix == " Mbps") rateScaleMax(present) else max(1.0, present.max())
            val barW = max(1f, (plotW / values.size) - 2f)

            values.forEachIndexed { i, v ->
                // A failed check leaves a gap, not a zero bar — zero would
                // misleadingly imply "measured zero throughput".
                if (v == null) return@forEachIndexed
                val x = padding + i * (plotW / values.size)
                val h = max(1f, ((v / maxV) * plotH).toFloat())
                drawRect(
                    color,
                    Offset(x, padding + plotH - h),
                    androidx.compose.ui.geometry.Size(barW, h),
                )
            }

            drawAxisTicks(
                values = present,
                toY = { v -> (padding + plotH - (v / maxV) * plotH).toFloat() },
                padding = padding,
                plotHeight = plotH,
                label = { value -> if (suffix == " Mbps") formatRate(value) else if (value >= 10) "${value.toInt()}$suffix" else "${(value * 10).toInt() / 10.0}$suffix" },
            )
        }
    }
}

@Composable
fun LossChart(
    samples: List<PingSample>,
    modifier: Modifier = Modifier,
    heightDp: Int = 100,
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (samples.isEmpty()) return@Canvas
            val padding = 4f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            // A bar represents one group of three pings: a real percentage,
            // rather than implying that an individual reply was a percentage.
            val lossRates = samples.chunked(3).map { group ->
                100.0 * group.count { !it.ok } / group.size
            }
            val barW = max(1f, (plotW / lossRates.size) - 1f)
            lossRates.forEachIndexed { index, lossPct ->
                val x = padding + index * (plotW / lossRates.size)
                val h = max(1f, (lossPct / 100.0 * plotH).toFloat())
                drawRect(FailRed, Offset(x, padding + plotH - h), androidx.compose.ui.geometry.Size(barW, h))
            }
            drawAxisTicks(lossRates, { value -> (padding + plotH - value / 100.0 * plotH).toFloat() }, padding, plotH) { "${it.toInt()}%" }
        }
    }
}

@Composable
fun JitterChart(
    samples: List<PingSample>,
    modifier: Modifier = Modifier,
    heightDp: Int = 100,
) {
    val values = samples.chunked(3).mapNotNull { group ->
        group.mapNotNull { it.rttMs }.takeIf { it.size >= 2 }?.let(Stats::stdDev)
    }
    ThroughputBars(values, upload = true, modifier = modifier, heightDp = heightDp, suffix = " ms")
}

@Composable
fun DropEventChart(
    drops: List<DropEvent>,
    nowEpochMs: Long,
    modifier: Modifier = Modifier,
    heightDp: Int = 100,
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (drops.isEmpty()) return@Canvas
            val durations = drops.map { it.durationMs(nowEpochMs).toDouble() }
            val maxDuration = max(1.0, durations.max())
            val padding = 4f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            val barW = max(2f, plotW / drops.size - 2f)
            durations.forEachIndexed { index, duration ->
                val x = padding + index * (plotW / drops.size)
                val h = max(2f, (duration / maxDuration * plotH).toFloat())
                drawRect(FailRed, Offset(x, padding + plotH - h), androidx.compose.ui.geometry.Size(barW, h))
            }
            drawAxisTicks(durations, { value -> (padding + plotH - value / maxDuration * plotH).toFloat() }, padding, plotH) {
                if (it >= 60_000) "${(it / 60_000).toInt()} min" else "${(it / 1000).toInt()}s"
            }
        }
    }
}

@Composable
fun SweepResultChart(
    results: List<SweepResult>,
    modifier: Modifier = Modifier,
    heightDp: Int = 128,
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (results.isEmpty()) return@Canvas
            val labelWidth = 64.dp.toPx()
            // Room on the right for the achieved rate. A bare pass/fail grid
            // cannot distinguish "this size is blocked" from "this size is
            // simply slow", and on a rate-limited line that is the entire
            // question — so every row carries what it actually measured.
            val evidenceWidth = 96.dp.toPx()
            val rowHeight = size.height / results.size
            results.forEachIndexed { index, result ->
                val outcomes = result.trialOutcomes.ifEmpty { List(result.trials) { it < result.passCount } }
                val top = index * rowHeight + 3f
                val label = if (result.bytes >= 1_000_000) "${result.bytes / 1_000_000} MB" else "${result.bytes / 1_000} KB"
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 11.dp.toPx()
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(label, 8f, top + rowHeight * 0.65f, paint)

                val evidencePaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(160, 160, 160)
                    textSize = 10.dp.toPx()
                    textAlign = android.graphics.Paint.Align.RIGHT
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    sweepEvidenceLabel(result),
                    size.width - 4f,
                    top + rowHeight * 0.65f,
                    evidencePaint,
                )

                if (outcomes.isEmpty()) return@forEachIndexed
                val gap = 3.dp.toPx()
                val availableWidth = (size.width - labelWidth - evidenceWidth).coerceAtLeast(1f)
                val blockWidth = (availableWidth - gap * (outcomes.size - 1)) / outcomes.size
                outcomes.forEachIndexed { tryIndex, passed ->
                    val left = labelWidth + tryIndex * (blockWidth + gap)
                    drawRect(
                        // Amber, not red, for a rung that moved data and ran
                        // out of time. Red means "did not get through"; a
                        // throttled link that transferred most of the payload
                        // deserves a different colour from one that dropped it.
                        color = when {
                            passed -> Color(0xFF22C55E)
                            result.timedOut -> UpAmber
                            else -> FailRed
                        },
                        topLeft = Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(blockWidth.coerceAtLeast(1f), max(2f, rowHeight - 6f)),
                    )
                }
            }
        }
    }
}

@Composable
fun MetricLineChart(
    values: List<Double?>,
    color: Color = DownBlue,
    modifier: Modifier = Modifier,
    heightDp: Int = 140,
    suffix: String = "",
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            val present = values.filterNotNull()
            if (present.isEmpty()) return@Canvas
            val padding = 8f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            val maxValue = if (suffix == " Mbps") rateScaleMax(present) else max(1.0, present.max())
            val path = Path()
            var drawing = false
            values.forEachIndexed { index, value ->
                if (value == null) { drawing = false; return@forEachIndexed }
                val x = padding + index * (plotW / max(1, values.lastIndex))
                val y = (padding + plotH - (value / maxValue) * plotH).toFloat()
                if (drawing) path.lineTo(x, y) else { path.moveTo(x, y); drawing = true }
                // A dot per sample as well as the line. A path holding a single
                // moveTo and no lineTo strokes nothing at all, so a phase that
                // produced exactly one measurement drew an empty chart — which
                // read as "upload never ran" while the card above it showed a
                // perfectly real 200 Kbit/s.
                drawCircle(color, radius = 3f, center = Offset(x, y))
            }
            drawPath(path, color, style = Stroke(width = 3f))
            drawAxisTicks(
                values = present,
                toY = { value -> (padding + plotH - (value / maxValue) * plotH).toFloat() },
                padding = padding,
                plotHeight = plotH,
                label = { value -> if (suffix == " Mbps") formatRate(value) else if (value >= 10) "${value.toInt()}$suffix" else "${(value * 10).toInt() / 10.0}$suffix" },
            )
        }
    }
}

/**
 * What a rung actually measured, in one short line.
 *
 * "1 MB — 480KB @ 64 Kbit/s" is a finding. A red block on its own is not, and
 * on a deliberately rate-limited connection it is actively misleading: the
 * transfer was not blocked, it just needed more time than it was given.
 */
private fun sweepEvidenceLabel(result: SweepResult): String {
    val rate = result.observedMbps?.let { formatRate(it) }
    return when {
        result.cancelled -> "stopped"
        result.passCount == result.trials && rate != null -> rate
        result.bytesTransferred > 0 && rate != null ->
            "${formatBytesShort(result.bytesTransferred)} @ $rate"
        result.bytesTransferred > 0 -> formatBytesShort(result.bytesTransferred)
        else -> result.lastError?.name?.lowercase() ?: ""
    }
}

private fun formatBytesShort(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000}KB"
    else -> "${bytes}B"
}

private fun formatRate(mbps: Double): String = when {
    mbps >= 1_000 -> "%.1f Gbit/s".format(mbps / 1_000)
    mbps >= 1 -> "%.1f Mbit/s".format(mbps)
    else -> "%.0f Kbit/s".format(mbps * 1_000)
}

/**
 * Throughput is stored in Mbps, but a slow mobile link can legitimately be a
 * few Kbit/s. A fixed 1 Mbps floor turns such a line into an almost invisible
 * pixel at the chart edge; scale sub-Mbps charts to the observed range instead.
 */
private fun rateScaleMax(values: List<Double>): Double {
    val observedMax = values.maxOrNull() ?: return 1.0
    return if (observedMax >= 1.0) observedMax else max(observedMax * 1.1, 0.001)
}

private fun DrawScope.drawAxisTicks(
    values: List<Double>,
    toY: (Double) -> Float,
    padding: Float,
    plotHeight: Float,
    label: (Double) -> String,
) {
    val ticks = AxisTicks.compute(values, toY)
    if (ticks.isEmpty()) return

    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(136, 136, 136)
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
    }

    ticks.forEach { tick ->
        drawLine(
            color = TickGrey,
            start = Offset(size.width - padding - 3f, tick.y),
            end = Offset(size.width - padding, tick.y),
            strokeWidth = 1f,
        )
        val y = AxisTicks.labelY(tick.y, padding, plotHeight)
        drawContext.canvas.nativeCanvas.drawText(
            label(tick.value),
            size.width - padding - 5f,
            y + paint.textSize / 3f,
            paint,
        )
    }
}
