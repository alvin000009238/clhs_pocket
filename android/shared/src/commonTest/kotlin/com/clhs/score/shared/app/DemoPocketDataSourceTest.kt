package com.clhs.score.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoPocketDataSourceTest {
    private val source = PocketEnvironment.demo()

    /** Wednesday 2026-11-18. */
    private fun wednesday(hour: Int, minute: Int) = PocketClock(2026, 11, 18, 3, hour * 60 + minute)

    @Test
    fun civilDatesRoundTripAcrossMonthAndYearBoundaries() {
        assertEquals(CivilDate(2026, 11, 18), civilDateOf(epochDayOf(2026, 11, 18)))
        assertEquals(CivilDate(2027, 1, 1), civilDateOf(epochDayOf(2026, 12, 31) + 1))
        assertEquals(CivilDate(2028, 2, 29), civilDateOf(epochDayOf(2028, 3, 1) - 1))
        assertEquals(3, dayOfWeekOf(epochDayOf(2026, 11, 18)))
    }

    @Test
    fun heroReportsTheLessonInProgressWithRemainingMinutes() {
        val hero = source.home(wednesday(10, 35)).hero!!
        assertTrue(hero.isInClass)
        assertEquals("化學", hero.courseName)
        assertEquals(0.5, hero.progress)
        assertTrue(hero.timeText.endsWith("還有 25 分鐘下課"))
        // Period 4 is the same double lesson, so the next distinct lesson is after lunch.
        assertEquals("下一堂 數學 · 13:00", hero.nextLine)
    }

    @Test
    fun heroPointsToTheNextLessonDuringABreakAndToMondayOnFridayEvening() {
        val lunch = source.home(wednesday(12, 30)).hero!!
        assertEquals(false, lunch.isInClass)
        assertEquals("數學", lunch.courseName)

        val friday = source.home(PocketClock(2026, 11, 20, 5, 18 * 60)).hero!!
        assertEquals("週一 國語文", friday.courseName)
        assertNull(friday.nextLine)
    }

    @Test
    fun weekScheduleMergesBlocksButNeverAcrossLunchAndMarksChanges() {
        val week = source.schedule(PocketScheduleScope.CURRENT_WEEK, wednesday(10, 35))
        val monday = week.cells.filter { it.dayOfWeek == 1 }
        assertEquals(listOf(2, 1, 1, 2, 1, 1), monday.map { it.span })
        assertEquals(8, monday.sumOf { it.span })
        assertEquals(2, week.changeCount)
        assertEquals(PocketCellStatus.CURRENT, week.cells.first { it.id == "3-3" }.status)
        assertEquals(PocketCellStatus.ENDED, week.cells.first { it.id == "3-1" }.status)
        assertEquals(PocketCellStatus.NORMAL, week.cells.first { it.id == "1-1" }.status)
        assertEquals("11/16 – 11/20", week.rangeText)

        val semester = source.schedule(PocketScheduleScope.SEMESTER, wednesday(10, 35))
        assertEquals(0, semester.changeCount)
        assertTrue(semester.cells.all { it.status == PocketCellStatus.NORMAL })
    }

    @Test
    fun gradeReportUsesCreditWeightedAverageAndFlagsFailingSubjects() {
        val report = source.grades(source.defaultExamValue())
        // (83+88+78)*4 + (76+58+90+85)*2 = 1614 over 20 credits.
        assertEquals("80.70", report.averageText)
        assertEquals(1, report.deltaSign)
        assertEquals(listOf("化學"), report.subjects.filter { it.isFlunk }.map { it.name })
        assertEquals("化學", report.weaknesses.first().name)
        assertEquals(3, report.trend.size)
        assertEquals("", source.grades("113_2_E1").deltaText)
    }
}
