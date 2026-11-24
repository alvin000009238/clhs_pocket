package com.clhs.score.shared.time

import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Runs against `java.time` on Android and the portable types on iOS, so both platforms share one contract. */
class SchoolTimeTest {
    private fun at(date: String, time: String) = parseLocalDate(date).at(parseLocalTime(time))

    @Test
    fun datesAndDateTimesCompareChronologically() {
        val dates = listOf("0000-01-01", "1999-12-31", "2026-02-28", "2026-03-01", "9999-12-31").map(::parseLocalDate)
        for ((i, a) in dates.withIndex()) {
            for ((j, b) in dates.withIndex()) assertEquals(i.compareTo(j).sign, a.compareTo(b).sign, "$a <=> $b")
        }
        val earlier = at("2026-10-05", "23:59:59")
        val later = at("2026-10-06", "00:00")
        assertTrue(earlier < later)
        assertTrue(later > earlier)
        assertEquals(0, later.compareTo(at("2026-10-06", "00:00")))
        assertTrue(LOCAL_DATE_TIME_MIN < at("0000-01-01", "00:01"))
        assertEquals(listOf(earlier, later), listOf(later, earlier).sortedWith(LOCAL_DATE_TIME_ORDER))
    }

    @Test
    fun durationUntilIsSignedAndCoversTheWholeSupportedRange() {
        assertEquals(1.minutes, at("2026-10-05", "23:59").durationUntil(at("2026-10-06", "00:00")))
        assertEquals(-(1.days + 1.hours), at("2026-10-06", "01:00").durationUntil(at("2026-10-05", "00:00")))
        val span = at("0000-01-01", "00:00").durationUntil(at("9999-12-31", "23:59"))
        assertEquals(3_652_424L, span.inWholeDays)
        assertEquals(-span, at("9999-12-31", "23:59").durationUntil(at("0000-01-01", "00:00")))
        assertTrue(at("2026-10-05", "08:00").durationUntil(at("2500-01-01", "12:00")) > 172_848.days)
    }

    @Test
    fun monthShiftsClampToTheTargetMonthLength() {
        assertEquals(parseLocalDate("2024-02-29"), parseLocalDate("2024-01-31").shiftMonths(1))
        assertEquals(parseLocalDate("2025-02-28"), parseLocalDate("2024-02-29").shiftMonths(12))
        assertEquals(parseLocalDate("2025-12-31"), parseLocalDate("2026-01-31").shiftMonths(-1))
        assertFailsWith<Exception> { parseLocalDate("2026-01-01").shiftMonths(Long.MAX_VALUE) }
    }

    @Test
    fun instantsRoundTripUtcTextAndMillis() {
        val instant = parseInstant("2026-08-09T05:00:00Z")
        assertEquals(1_786_251_600_000L, instant.epochMillis())
        assertEquals("2026-08-09T05:00:00Z", instant.toString())
        assertEquals("2026-08-09T05:00:00.250Z", instantOfEpochMilli(1_786_251_600_250L).toString())
        assertEquals(1_786_251_600_250L, parseInstant("2026-08-09T05:00:00.250Z").epochMillis())
        assertEquals("1969-12-31T23:59:59.999Z", instantOfEpochMilli(-1).toString())
        assertEquals("1970-01-01T00:00:00Z", instantOfEpochMilli(0).toString())
        assertTrue(instantOfEpochMilli(1) > instantOfEpochMilli(0))
        assertFailsWith<Exception> { parseInstant("2026-08-09 05:00:00Z") }
        assertFailsWith<Exception> { parseInstant("2026-08-09T05:00:00") }
    }

    @Test
    fun schoolClockUsesTaipeiOffset() {
        val dateTime = schoolDateTimeAt(1_786_251_600_000L)
        assertEquals(parseLocalDate("2026-08-09"), dateTime.datePart())
        assertEquals(13, dateTime.timePart().hourNumber())
        assertEquals(1_786_251_600_000L, dateTime.schoolEpochMillis())
        assertEquals(parseLocalDate("2026-08-10"), schoolDateTimeAt(1_786_251_600_000L + 11.hours.inWholeMilliseconds).datePart())
        assertEquals(1.milliseconds, dateTime.durationUntil(schoolDateTimeAt(1_786_251_600_001L)))
    }
}
