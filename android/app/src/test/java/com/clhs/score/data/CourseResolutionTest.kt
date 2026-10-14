package com.clhs.score.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class CourseResolutionTest {
    @Test fun advanceReminderUsesFiveMinuteWindowAndSwitchesAtEveryClassStart() {
        PERIOD_TIMES.forEachIndexed { index, time ->
            val source = report.copy(items = listOf(ScheduleItem(1, index + 1, "課程")))
            val start = LocalDate.parse("2026-09-21").atTime(java.time.LocalTime.parse(time.start))
                .atZone(SCHOOL_COURSE_ZONE).toInstant()
            val advance = start.minusSeconds(300)
            fun at(instant: Instant, enabled: Boolean = true) =
                resolveCourses(source, Clock.fixed(instant, SCHOOL_COURSE_ZONE), enabled)
            assertNull(at(advance.minusSeconds(1)).upcoming)
            assertEquals(advance, at(advance.minusSeconds(1)).nextBoundary)
            assertEquals(index + 1, at(advance).upcoming?.item?.period)
            assertEquals(start, at(advance).nextBoundary)
            assertNull(at(start.minusSeconds(1), false).upcoming)
            assertEquals(start, at(advance, false).nextBoundary)
            assertNull(at(start).upcoming)
            assertEquals(index + 1, at(start).current?.item?.period)
        }
        assertNull(resolveCourses(null, clock("2026-09-21T00:05:00Z"), true).upcoming)
        assertNull(resolveCourses(report, clock("2026-09-28T00:05:00Z"), true).upcoming)
    }

    private val report = ScheduleReport("1151", "", ScheduleScope.CURRENT_WEEK,
        weekStartDate = "2026-09-21", weekEndDate = "2026-09-27",
        items = listOf(ScheduleItem(1, 1, "國文"), ScheduleItem(1, 2, "國文"), ScheduleItem(2, 1, "數學")))
    private fun clock(time: String) = Clock.fixed(Instant.parse(time), ZoneId.of("America/New_York"))
    private fun resolve(time: String, source: ScheduleReport? = report) = resolveCourses(source, clock(time))

    @Test fun allPeriodBoundariesAreHalfOpenInSchoolTime() {
        PERIOD_TIMES.forEachIndexed { index, time ->
            val source = report.copy(items = listOf(ScheduleItem(1, index + 1, "課程")))
            val start = LocalDate.parse("2026-09-21").atTime(java.time.LocalTime.parse(time.start)).atZone(SCHOOL_COURSE_ZONE).toInstant()
            val end = LocalDate.parse("2026-09-21").atTime(java.time.LocalTime.parse(time.end)).atZone(SCHOOL_COURSE_ZONE).toInstant()
            fun at(instant: Instant) = resolveCourses(source, Clock.fixed(instant, ZoneId.of("UTC")))
            assertNull(at(start.minusSeconds(1)).current)
            assertEquals(start, at(start.minusSeconds(1)).nextBoundary)
            assertEquals(index + 1, at(start).current?.item?.period)
            assertEquals(end, at(start.plusSeconds(1)).nextBoundary)
            assertNotNull(at(end.minusSeconds(1)).current)
            assertNull(at(end).current)
            assertEquals(Instant.parse("2026-09-27T16:00:00Z"), at(end.plusSeconds(1)).nextBoundary)
        }
    }

    @Test fun lateAlarmUsesActualTime() {
        val second = resolve("2026-09-21T01:15:00Z").current!!
        assertEquals(2, second.item.period)
        assertNull(resolve("2026-09-21T01:05:00Z").current)
        assertEquals(Instant.parse("2026-09-21T01:10:00Z"), resolve("2026-09-21T01:05:00Z").nextBoundary)
    }

    @Test fun alarmsDeliveredFiveAndTwentyMinutesLateResolveFromCurrentTime() {
        val fiveMinutesLate = resolve("2026-09-21T01:15:00Z")
        assertEquals(2, fiveMinutesLate.current?.item?.period)
        assertEquals(Instant.parse("2026-09-21T02:00:00Z"), fiveMinutesLate.nextBoundary)

        val twentyMinutesLate = resolve("2026-09-21T01:30:00Z")
        assertEquals(2, twentyMinutesLate.current?.item?.period)
        assertEquals(Instant.parse("2026-09-21T02:00:00Z"), twentyMinutesLate.nextBoundary)
    }

    @Test fun missingUndatedExpiredMalformedAndOverlappingReportsFailClosed() {
        val now = "2026-09-21T00:30:00Z"
        listOf(null, report.copy(weekEndDate = null),
            report.copy(weekEndDate = "2027-01-01"), report.copy(items = emptyList()),
            report.copy(items = listOf(ScheduleItem(1, 999, "未知時間"))),
            report.copy(items = report.items + report.items.first())).forEach {
            assertFalse(resolve(now, it).valid)
            assertNull(resolve(now, it).current)
        }
        assertFalse(resolve("2026-09-28T00:30:00Z").valid)
    }

    @Test fun semesterScheduleRepeatsOnItsWeekdayAfterTheCurrentOccurrence() {
        val semester = report.copy(
            scope = ScheduleScope.SEMESTER,
            weekStartDate = null,
            weekEndDate = null,
            items = listOf(ScheduleItem(1, 1, "國文")),
        )

        val current = resolve("2026-09-21T00:30:00Z", semester)
        assertEquals("國文", current.current?.item?.subjectName)
        assertEquals(Instant.parse("2026-09-21T01:00:00Z"), current.nextBoundary)

        val afterCurrentWeek = resolve("2026-09-25T01:05:00Z", semester)
        assertTrue(afterCurrentWeek.valid)
        assertEquals(Instant.parse("2026-09-28T00:10:00Z"), afterCurrentWeek.nextBoundary)
        val advance = resolveCourses(semester, clock("2026-09-25T01:05:00Z"), true)
        assertEquals(Instant.parse("2026-09-28T00:05:00Z"), advance.nextBoundary)
        assertEquals("國文", resolveCourses(semester, clock("2026-09-28T00:05:00Z"), true)
            .upcoming?.item?.subjectName)
    }

    @Test fun emptyUnknownAndOverlappingSemesterSchedulesFailClosed() {
        val semester = report.copy(scope = ScheduleScope.SEMESTER, weekStartDate = null, weekEndDate = null)
        listOf(
            semester.copy(items = emptyList()),
            semester.copy(items = listOf(ScheduleItem(1, 999, "未知時間"))),
            semester.copy(items = listOf(ScheduleItem(1, 1, "國文"), ScheduleItem(1, 1, "數學"))),
        ).forEach { source ->
            val result = resolve("2026-09-21T00:30:00Z", source)
            assertFalse(result.valid)
            assertNull(result.current)
        }
    }

    @Test fun confirmedWeekendAndEmptyHolidayUseOnlyDatedItems() {
        val saturday = report.copy(items = listOf(ScheduleItem(6, 1, "補課")))
        assertEquals("補課", resolve("2026-09-26T00:10:00Z", saturday).current?.item?.subjectName)
        assertNull(resolve("2026-09-25T00:10:00Z", saturday).current)
        assertEquals(Instant.parse("2026-09-26T00:10:00Z"), resolve("2026-09-25T00:10:00Z", saturday).nextBoundary)
    }

    @Test fun emptyCurrentWeekWakesAtItsExpiryWithoutInventingSemesterCourses() {
        val emptyWeek = report.copy(items = emptyList())
        val result = resolve("2026-09-25T00:30:00Z", emptyWeek)
        assertNull(result.current)
        assertFalse(result.valid)
        assertEquals(Instant.parse("2026-09-27T16:00:00Z"), result.nextBoundary)
    }

    @Test fun displayOverridesApplyWithoutChangingOriginalSnapshot() {
        val source = report.copy(
            scope = ScheduleScope.SEMESTER,
            weekStartDate = null,
            weekEndDate = null,
            subjectOverrides = listOf(ScheduleSubjectOverride("國文", "自訂科目", customClassroom = "教室")),
        )
        assertEquals("自訂科目", resolve("2026-09-21T00:30:00Z", source).current?.item?.subjectName)
        assertEquals("國文", source.items.first().subjectName)
    }
}
