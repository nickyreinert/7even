package de.sevenapp.monitor.chart

import kotlin.math.abs

/**
 * Min/avg/max axis ticks, ported from the web app's `drawValueAxisTicks`.
 *
 * Three real values from the actual data tell you more at phone size than a
 * generic 0/25/50/75/100 grid would. Extracted into commonMain because it is
 * pure arithmetic with a fiddly collision rule that is much easier to get right
 * with tests than by eye inside a Compose draw block — and it will be needed
 * identically on iOS.
 */
object AxisTicks {

    data class Tick(val value: Double, val y: Float)

    /**
     * @param toY maps a value to a y coordinate in the drawing space
     * @param minSeparationPx labels closer than this are dropped, so near-flat
     *        data does not stack three labels on top of each other
     */
    fun compute(
        values: List<Double>,
        toY: (Double) -> Float,
        minSeparationPx: Float = 11f,
    ): List<Tick> {
        if (values.isEmpty()) return emptyList()

        val min = values.min()
        val max = values.max()
        val avg = values.sum() / values.size

        val ticks = mutableListOf<Tick>()
        // Max first, then avg, then min — matching the web app's precedence, so
        // when labels collide it is the middle one that gets dropped rather
        // than an extreme.
        for (v in listOf(max, avg, min)) {
            val y = toY(v)
            if (ticks.any { abs(it.y - y) < minSeparationPx }) continue
            ticks += Tick(v, y)
        }
        return ticks
    }

    /**
     * Clamps a label's y so it stays inside the plot even when its tick sits
     * exactly on an edge — otherwise the top label is half-clipped.
     */
    fun labelY(y: Float, paddingPx: Float, plotHeightPx: Float): Float =
        y.coerceIn(paddingPx + 6f, paddingPx + plotHeightPx - 3f)
}
