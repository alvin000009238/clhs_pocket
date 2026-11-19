package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun heroTracksTheLessonInProgressThenBreaksThenTheNextSchoolDay() {
        val inClass = assertNotNull(source.home(wednesday(10, 35)).hero)
        assertTrue(inClass.isInClass)
        assertEquals(0.5, inClass.progress)
        assertTrue(inClass.timeText.endsWith("還有 25 分鐘下課"))

        val lunch = assertNotNull(source.home(wednesday(12, 30)).hero)
        assertEquals(false, lunch.isInClass)
        assertTrue(lunch.headline.startsWith("下一堂 · 第 5 節"))

        val fridayEvening = assertNotNull(source.home(PocketClock(2026, 11, 20, 5, 18 * 60)).hero)
        assertTrue(fridayEvening.courseName.startsWith("週一 "))
        assertNull(fridayEvening.nextLine)
    }

    @Test
    fun weekScheduleCoversEachSlotOnceMarksChangesAndOnlyAgesToday() {
        val week = source.schedule(PocketScheduleScope.CURRENT_WEEK, wednesday(10, 35))
        // FakeScheduleData removes Mon P4, modifies Tue P3 and adds Wed P8.
        assertEquals(3, week.changeCount)
        assertEquals(PocketCellChange.CANCELLED, week.cells.first { it.id == "1-4" }.change)
        assertEquals(PocketCellChange.CHANGED, week.cells.first { it.id == "2-3" }.change)
        assertEquals(PocketCellChange.ADDED, week.cells.single { it.dayOfWeek == 3 && it.period + it.span - 1 == 8 }.change)
        week.cells.groupBy { it.dayOfWeek }.values.forEach { day ->
            val covered = day.flatMap { cell -> (cell.period until cell.period + cell.span) }
            assertEquals(covered.distinct(), covered, "a period is rendered twice")
            assertTrue(day.none { it.period <= 4 && it.period + it.span - 1 > 4 }, "block crosses lunch")
        }
        assertEquals(1, week.cells.count { it.status == PocketCellStatus.CURRENT })
        assertTrue(week.cells.filter { it.dayOfWeek != 3 }.all { it.status == PocketCellStatus.NORMAL })
        assertEquals("11/16 – 11/20", week.rangeText)

        val semester = source.schedule(PocketScheduleScope.SEMESTER, wednesday(10, 35))
        assertEquals(0, semester.changeCount)
        assertTrue(semester.cells.all { it.change == PocketCellChange.NONE && it.status == PocketCellStatus.NORMAL })
    }

    @Test
    fun gradeReportMirrorsTheSharedReportAndAnalysis() {
        val report = source.grades(source.defaultExamValue())
        val shared = FakeData.latestReport()
        assertEquals(shared.examSummary?.averageScoreDisplay, report.averageText)
        assertEquals(shared.subjects.size, report.subjects.size)
        assertEquals(shared.subjects.count { it.flunk }, report.subjects.count { it.isFlunk })
        assertEquals(3, report.trend.size)
        assertTrue(report.deltaText.isNotEmpty())
        // The first exam of a term has no earlier exam to compare with.
        assertEquals(0, source.grades("114_1_E1").deltaSign)
        assertEquals("", source.grades("114_1_E1").deltaText)
        // Unknown values fall back instead of crashing the UI.
        assertEquals(report.examValue, source.grades("nope").examValue)
    }
}
