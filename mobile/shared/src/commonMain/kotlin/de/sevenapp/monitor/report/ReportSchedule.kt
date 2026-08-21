package de.sevenapp.monitor.report

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Works out when a report is due and what window it covers.
 *
 * Uses the device's local time zone deliberately: a "daily" report must align
 * with the user's day, not UTC's. Someone in UTC+13 receiving Monday's report
 * covering Sunday afternoon through Monday afternoon would find it useless.
 *
 * kotlinx-datetime rather than java.time — commonMain cannot import a platform
 * (see the build's checkNoPlatformImports gate).
 */
object ReportSchedule {

    /** Reports land mid-morning, when they will actually be read. */
    val DELIVERY_TIME: LocalTime = LocalTime(9, 0)

    data class Window(val startEpochMs: Long, val endEpochMs: Long)

    /**
     * The window a report delivered at [deliveryEpochMs] should cover: the
     * complete preceding period, not a rolling window ending now. A "weekly"
     * report that covers a partial current week can never be compared against
     * the previous one.
     *
     * [weekAnchorDay] (Monday=1..Sunday=7) and [monthAnchorDay] (1..31, clamped
     * to the shorter month when it doesn't have that many days) let the user
     * choose which day a week/month period starts on; they default to the
     * calendar's own Monday/1st.
     */
    fun windowFor(
        period: ReportPeriod,
        deliveryEpochMs: Long,
        zone: TimeZone,
        weekAnchorDay: Int = 1,
        monthAnchorDay: Int = 1,
    ): Window {
        val deliveryDate = Instant.fromEpochMilliseconds(deliveryEpochMs).toLocalDateTime(zone).date

        val (start, end) = when (period) {
            ReportPeriod.DAILY -> {
                val yesterday = deliveryDate.minusDays(1)
                yesterday to deliveryDate
            }
            ReportPeriod.WEEKLY -> {
                val thisWeekStart = deliveryDate.startOfWeek(weekAnchorDay)
                thisWeekStart.minusDays(7) to thisWeekStart
            }
            ReportPeriod.MONTHLY -> {
                val thisPeriodStart = monthPeriodStart(deliveryDate, monthAnchorDay)
                val prevPeriodStart = monthPeriodStart(thisPeriodStart.minusDays(1), monthAnchorDay)
                prevPeriodStart to thisPeriodStart
            }
        }

        return Window(
            startEpochMs = start.atStartOfDayIn(zone).toEpochMilliseconds(),
            endEpochMs = end.atStartOfDayIn(zone).toEpochMilliseconds(),
        )
    }

    /** Next delivery instant strictly after [afterEpochMs]. See [windowFor] for the anchor parameters. */
    fun nextDelivery(
        period: ReportPeriod,
        afterEpochMs: Long,
        zone: TimeZone,
        weekAnchorDay: Int = 1,
        monthAnchorDay: Int = 1,
    ): Long {
        val after = Instant.fromEpochMilliseconds(afterEpochMs).toLocalDateTime(zone)
        var candidateDate = when (period) {
            ReportPeriod.DAILY -> after.date
            ReportPeriod.WEEKLY -> after.date.startOfWeek(weekAnchorDay)
            ReportPeriod.MONTHLY -> monthPeriodStart(after.date, monthAnchorDay)
        }

        fun instantOf(d: LocalDate) = LocalDateTime(d, DELIVERY_TIME).toInstant(zone).toEpochMilliseconds()

        if (instantOf(candidateDate) <= afterEpochMs) {
            candidateDate = when (period) {
                ReportPeriod.DAILY -> candidateDate.plusDays(1)
                ReportPeriod.WEEKLY -> candidateDate.plusDays(7)
                ReportPeriod.MONTHLY -> monthPeriodStart(candidateDate.plus(1, DateTimeUnit.MONTH), monthAnchorDay)
            }
        }
        return instantOf(candidateDate)
    }

    /**
     * How many probe cycles the window *should* have produced, which is what
     * the report's coverage figure is measured against. Without this, a report
     * built from 6 samples looks the same as one built from 96.
     *
     * Returns null — "not predictable" — rather than a number when the cadence
     * does not fit inside the window. A weekly probe cycle in a daily report
     * truncated to zero expected samples, which the coverage ratio then read as
     * full coverage: the least-observed configuration claimed the most
     * complete report.
     */
    fun expectedSamples(window: Window, cycleIntervalMinutes: Int, pingsPerCycle: Int): Int? {
        if (cycleIntervalMinutes <= 0 || pingsPerCycle <= 0) return null
        val minutes = (window.endEpochMs - window.startEpochMs) / 60_000.0
        if (minutes < cycleIntervalMinutes) return null
        val expected = ((minutes / cycleIntervalMinutes) * pingsPerCycle).toInt()
        return expected.takeIf { it > 0 }
    }

    /** The most recent [anchorIsoDay] (Monday=1..Sunday=7) on or before this date. */
    private fun LocalDate.startOfWeek(anchorIsoDay: Int): LocalDate {
        val shift = ((dayOfWeek.isoDayNumber() - anchorIsoDay) % 7 + 7) % 7
        return minusDays(shift.toLong())
    }

    /** The last valid day of [year]/[month], for clamping an anchor day that doesn't fit every month. */
    private fun lastDayOfMonth(year: Int, month: Month): Int =
        LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minusDays(1).dayOfMonth

    private fun clampedAnchor(year: Int, month: Month, anchorDay: Int): LocalDate =
        LocalDate(year, month, anchorDay.coerceAtMost(lastDayOfMonth(year, month)))

    /** The most recent month-anchor date (clamped per month) on or before this date. */
    private fun monthPeriodStart(date: LocalDate, anchorDay: Int): LocalDate {
        val thisMonthAnchor = clampedAnchor(date.year, date.month, anchorDay)
        if (date >= thisMonthAnchor) return thisMonthAnchor
        val prevMonth = LocalDate(date.year, date.month, 1).plus(-1, DateTimeUnit.MONTH)
        return clampedAnchor(prevMonth.year, prevMonth.month, anchorDay)
    }

    private fun DayOfWeek.isoDayNumber(): Int = ordinal + 1
    private fun LocalDate.plusDays(n: Long): LocalDate = plus(n.toInt(), DateTimeUnit.DAY)
    private fun LocalDate.minusDays(n: Long): LocalDate = plus(-n.toInt(), DateTimeUnit.DAY)
}
