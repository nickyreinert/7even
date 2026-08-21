package de.sevenapp.monitor

import de.sevenapp.monitor.chart.AxisTicks
import de.sevenapp.monitor.core.Format
import de.sevenapp.monitor.report.ReportPeriod
import de.sevenapp.monitor.report.ReportSchedule
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportScheduleTest {

    private val utc = TimeZone.UTC

    /** 2026-08-19 is a Wednesday. */
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

    @Test
    fun dailyWindowIsYesterdayMidnightToTodayMidnight() {
        val w = ReportSchedule.windowFor(ReportPeriod.DAILY, at("2026-08-19T09:00:00Z"), utc)
        assertEquals("2026-08-18T00:00", Instant.fromEpochMilliseconds(w.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-08-19T00:00", Instant.fromEpochMilliseconds(w.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun weeklyWindowIsThePreviousCompleteMondayWeek() {
        // Delivered Wed 2026-08-19 => covers Mon 08-10 .. Mon 08-17.
        val w = ReportSchedule.windowFor(ReportPeriod.WEEKLY, at("2026-08-19T09:00:00Z"), utc)
        assertEquals("2026-08-10T00:00", Instant.fromEpochMilliseconds(w.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-08-17T00:00", Instant.fromEpochMilliseconds(w.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun monthlyWindowIsThePreviousCompleteMonth() {
        val w = ReportSchedule.windowFor(ReportPeriod.MONTHLY, at("2026-08-19T09:00:00Z"), utc)
        assertEquals("2026-07-01T00:00", Instant.fromEpochMilliseconds(w.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-08-01T00:00", Instant.fromEpochMilliseconds(w.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun monthlyWindowHandlesTheJanuaryBoundary() {
        val w = ReportSchedule.windowFor(ReportPeriod.MONTHLY, at("2026-01-15T09:00:00Z"), utc)
        assertEquals("2025-12-01T00:00", Instant.fromEpochMilliseconds(w.startEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun windowsAreAlwaysCompletePeriodsNotRolling() {
        // A weekly report covering a partial current week could never be
        // compared against the previous one, which is the point of a report.
        val w = ReportSchedule.windowFor(ReportPeriod.WEEKLY, at("2026-08-19T23:59:00Z"), utc)
        val days = (w.endEpochMs - w.startEpochMs) / 86_400_000
        assertEquals(7L, days)
    }

    @Test
    fun nextDeliveryIsStrictlyInTheFuture() {
        val now = at("2026-08-19T09:00:00Z")
        val next = ReportSchedule.nextDelivery(ReportPeriod.DAILY, now, utc)
        assertTrue(next > now, "next delivery must be strictly after 'after'")
    }

    @Test
    fun dailyDeliveryLandsAtNineLocal() {
        val next = ReportSchedule.nextDelivery(ReportPeriod.DAILY, at("2026-08-19T03:00:00Z"), utc)
        assertEquals("2026-08-19T09:00", Instant.fromEpochMilliseconds(next).toLocalDateTime(utc).toString())
    }

    @Test
    fun expectedSamplesMatchesCadence() {
        // One day at 15-minute cycles, 3 pings each => 96 * 3.
        val w = ReportSchedule.windowFor(ReportPeriod.DAILY, at("2026-08-19T09:00:00Z"), utc)
        assertEquals(288, ReportSchedule.expectedSamples(w, 15, 3))
    }

    @Test
    fun expectedSamplesIsUnknownForANonsenseInterval() {
        val w = ReportSchedule.Window(0, 86_400_000)
        assertNull(ReportSchedule.expectedSamples(w, 0, 3))
        assertNull(ReportSchedule.expectedSamples(w, 15, 0))
    }

    @Test
    fun aCadenceLongerThanTheWindowPredictsNothingRatherThanZero() {
        // A weekly probe cycle inside a daily report. Truncating to zero
        // expected samples made the coverage ratio read as 1.0, so the
        // least-observed configuration claimed the most complete report.
        val w = ReportSchedule.Window(0, 86_400_000)
        assertNull(ReportSchedule.expectedSamples(w, 7 * 24 * 60, 3))
    }

    @Test
    fun weeklyWindowHonorsACustomAnchorDay() {
        // 2026-08-19 is itself a Wednesday (isoDayNumber 3), so with a
        // Wednesday anchor the previous complete period is Aug 12 .. Aug 19.
        val w = ReportSchedule.windowFor(ReportPeriod.WEEKLY, at("2026-08-19T09:00:00Z"), utc, weekAnchorDay = 3)
        assertEquals("2026-08-12T00:00", Instant.fromEpochMilliseconds(w.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-08-19T00:00", Instant.fromEpochMilliseconds(w.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun monthlyWindowHonorsACustomAnchorDay() {
        // Delivered after the 15th: the just-completed period is Jul15..Aug15.
        val after = ReportSchedule.windowFor(ReportPeriod.MONTHLY, at("2026-08-19T09:00:00Z"), utc, monthAnchorDay = 15)
        assertEquals("2026-07-15T00:00", Instant.fromEpochMilliseconds(after.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-08-15T00:00", Instant.fromEpochMilliseconds(after.endEpochMs).toLocalDateTime(utc).toString())

        // Delivered before the 15th: August's anchor has not arrived yet, so
        // the just-completed period is the one before it, Jun15..Jul15.
        val before = ReportSchedule.windowFor(ReportPeriod.MONTHLY, at("2026-08-10T09:00:00Z"), utc, monthAnchorDay = 15)
        assertEquals("2026-06-15T00:00", Instant.fromEpochMilliseconds(before.startEpochMs).toLocalDateTime(utc).toString())
        assertEquals("2026-07-15T00:00", Instant.fromEpochMilliseconds(before.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun monthlyAnchorDayClampsToShortMonths() {
        // April has 30 days: an anchor of 31 must land on April 30, not spill
        // into May.
        val w = ReportSchedule.windowFor(ReportPeriod.MONTHLY, at("2026-05-05T09:00:00Z"), utc, monthAnchorDay = 31)
        assertEquals("2026-04-30T00:00", Instant.fromEpochMilliseconds(w.endEpochMs).toLocalDateTime(utc).toString())
    }

    @Test
    fun nextDeliveryHonorsACustomWeekAnchor() {
        // Landing exactly on the anchor's delivery instant must advance to the
        // following week, not repeat the same one.
        val next = ReportSchedule.nextDelivery(
            ReportPeriod.WEEKLY,
            at("2026-08-19T09:00:00Z"),
            utc,
            weekAnchorDay = 3,
        )
        assertEquals("2026-08-26T09:00", Instant.fromEpochMilliseconds(next).toLocalDateTime(utc).toString())
    }
}

class FormatTest {

    @Test
    fun bytesUseDecimalUnitsLikeTheWebApp() {
        assertEquals("500B", Format.bytes(500))
        assertEquals("32KB", Format.bytes(32_000))
        assertEquals("10MB", Format.bytes(10_000_000))
        assertEquals("1.5MB", Format.bytes(1_500_000))
    }

    @Test
    fun durationMatchesWebAppShape() {
        assertEquals("42s", Format.duration(42_000))
        assertEquals("3m 7s", Format.duration(187_000))
        assertEquals("0s", Format.duration(0))
    }

    @Test
    fun subMegabitRatesAreShownInKbpsNotZeroPointZero() {
        // "0.0 Mbps" reads as broken instrumentation rather than a slow link.
        val r = Format.mbps(0.4)
        assertEquals("400", r.value)
        assertEquals("kbps", r.unit)
    }

    @Test
    fun normalRatesAreMbps() {
        val r = Format.mbps(53.27)
        assertEquals("53.3", r.value)
        assertEquals("Mbps", r.unit)
    }

    @Test
    fun nullRateIsADash() {
        assertEquals("—", Format.mbps(null).value)
        assertEquals("—", Format.percent(null))
        assertEquals("—", Format.millis(null))
    }
}

class AxisTicksTest {

    /** Simple inverted mapping: bigger value => smaller y, as on screen. */
    private fun toY(max: Double, height: Float): (Double) -> Float =
        { v -> (height - (v / max) * height).toFloat() }

    @Test
    fun spreadDataProducesThreeTicks() {
        val values = listOf(0.0, 50.0, 100.0)
        val ticks = AxisTicks.compute(values, toY(100.0, 100f))
        assertEquals(3, ticks.size)
        assertEquals(100.0, ticks.first().value) // max drawn first
    }

    @Test
    fun flatDataCollapsesToOneTickNotThreeStacked() {
        val values = listOf(50.0, 50.0, 50.0)
        val ticks = AxisTicks.compute(values, toY(100.0, 100f))
        assertEquals(1, ticks.size)
    }

    @Test
    fun nearlyFlatDataDropsTheCollidingMiddleLabel() {
        // min/avg/max all within a couple of pixels => only the max survives.
        val values = listOf(99.0, 99.5, 100.0)
        val ticks = AxisTicks.compute(values, toY(100.0, 100f))
        assertEquals(1, ticks.size)
        assertEquals(100.0, ticks.single().value)
    }

    @Test
    fun emptyInputProducesNoTicks() {
        assertTrue(AxisTicks.compute(emptyList(), toY(100.0, 100f)).isEmpty())
    }

    @Test
    fun labelYStaysInsideThePlot() {
        assertEquals(10f, AxisTicks.labelY(-50f, 4f, 100f))
        assertEquals(101f, AxisTicks.labelY(500f, 4f, 100f))
    }
}
