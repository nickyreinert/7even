package de.sevenapp.monitor.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import de.sevenapp.monitor.chart.AxisTicks
import de.sevenapp.monitor.core.PingSample
import de.sevenapp.monitor.probe.LiveSample
import de.sevenapp.monitor.probe.LiveTestConfig
import de.sevenapp.monitor.probe.SweepRunner
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
                    // A failed probe is a short red tick at the floor, not a
                    // zero-height bar — "no answer" must not read as
                    // "measured near-zero latency".
                    drawRect(FailRed, Offset(x, padding + plotH - 4f), androidx.compose.ui.geometry.Size(barW, 4f))
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
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (values.isEmpty()) return@Canvas

            val padding = 4f
            val plotH = size.height - padding * 2
            val plotW = size.width - padding * 2
            val present = values.filterNotNull()
            if (present.isEmpty()) return@Canvas
            val maxV = max(1.0, present.max())
            val barW = max(1f, (plotW / values.size) - 2f)

            values.forEachIndexed { i, v ->
                // A failed check leaves a gap, not a zero bar — zero would
                // misleadingly imply "measured zero throughput".
                if (v == null) return@forEachIndexed
                val x = padding + i * (plotW / values.size)
                val h = max(1f, ((v / maxV) * plotH).toFloat())
                drawRect(
                    if (upload) UpAmber else DownBlue,
                    Offset(x, padding + plotH - h),
                    androidx.compose.ui.geometry.Size(barW, h),
                )
            }

            drawAxisTicks(
                values = present,
                toY = { v -> (padding + plotH - (v / maxV) * plotH).toFloat() },
                padding = padding,
                plotHeight = plotH,
                label = { if (it >= 10) "${it.toInt()} Mbps" else "${(it * 10).toInt() / 10.0} Mbps" },
            )
        }
    }
}

/**
 * The foreground live test's chart: down/up streaming rate as scrolling
 * lines, ping RTT as bars underneath, both on one shared real-time axis
 * spanning [LiveTestConfig.LIVE_WINDOW_MS] — the mobile port of the web
 * app's combined "Live — last 65s" chart. Genuinely live: it redraws as
 * [samples] grows during the run, not just once at the end.
 */
@Composable
fun LiveRateChart(
    samples: List<LiveSample>,
    modifier: Modifier = Modifier,
    heightDp: Int = 160,
) {
    Box(modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (samples.isEmpty()) return@Canvas

            val padding = 4f
            val pingH = size.height * 0.28f
            val rateH = size.height - pingH - padding
            val plotW = size.width - padding * 2

            val now = samples.maxOf { it.atEpochMs }
            val windowStart = now - LiveTestConfig.LIVE_WINDOW_MS
            fun xFor(atEpochMs: Long): Float =
                padding + ((atEpochMs - windowStart).toFloat() / LiveTestConfig.LIVE_WINDOW_MS) * plotW

            val rates = samples.filterIsInstance<LiveSample.Rate>()
            val maxMbps = max(1.0, rates.maxOfOrNull { it.mbps } ?: 1.0)

            fun drawRateLine(direction: SweepRunner.Direction, color: Color) {
                val points = rates.filter { it.direction == direction }.sortedBy { it.atEpochMs }
                if (points.size < 2) return
                for (i in 1 until points.size) {
                    val p0 = points[i - 1]
                    val p1 = points[i]
                    drawLine(
                        color = color,
                        start = Offset(xFor(p0.atEpochMs), (rateH - (p0.mbps / maxMbps) * rateH).toFloat()),
                        end = Offset(xFor(p1.atEpochMs), (rateH - (p1.mbps / maxMbps) * rateH).toFloat()),
                        strokeWidth = 3f,
                    )
                }
            }
            drawRateLine(SweepRunner.Direction.DOWN, DownBlue)
            drawRateLine(SweepRunner.Direction.UP, UpAmber)

            val pings = samples.filterIsInstance<LiveSample.Ping>()
            val maxRtt = max(20.0, pings.mapNotNull { it.rttMs }.maxOrNull() ?: 20.0)
            val barW = 3f
            pings.forEach { ping ->
                val x = xFor(ping.atEpochMs)
                if (ping.rttMs == null) {
                    drawRect(FailRed, Offset(x, rateH + padding + pingH - 4f), androidx.compose.ui.geometry.Size(barW, 4f))
                } else {
                    val h = max(1f, ((ping.rttMs / maxRtt) * pingH).toFloat())
                    drawRect(AxisGrey.copy(alpha = 0.8f), Offset(x, rateH + padding + pingH - h), androidx.compose.ui.geometry.Size(barW, h))
                }
            }

            drawLine(
                color = TickGrey,
                start = Offset(padding, rateH + padding / 2),
                end = Offset(size.width - padding, rateH + padding / 2),
                strokeWidth = 1f,
            )
        }
    }
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
