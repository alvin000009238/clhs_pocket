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
    fun homeListsComeFromTheSharedOverviewSelection() {
        val home = source.home(wednesday(10, 35))
        // Unread first, then pinned: the shared announcement priority order.
        assertEquals(listOf("45072", "45068", "45055"), home.announcements.map { it.id })
        assertEquals(listOf("demo-e1", "demo-e2", "demo-e3"), home.events.map { it.id })
        assertTrue(home.events.first().isOngoing)
        assertEquals(listOf("8", "51"), home.countdowns.map { it.label })
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
    fun cancelledLessonsAndBreaksInsideADoublePeriodAreNeverInProgress() {
        // Monday period 4 (11:10-12:00) is cancelled this week.
        val monday = source.schedule(PocketScheduleScope.CURRENT_WEEK, PocketClock(2026, 11, 16, 1, 11 * 60 + 30))
        assertEquals(PocketCellStatus.NORMAL, monday.cells.first { it.id == "1-4" }.status)
        assertTrue(monday.cells.none { it.status == PocketCellStatus.CURRENT })

        // Tuesday periods 5-6 are one merged block with a break from 13:50 to 14:00.
        fun tuesdayBlock(hour: Int, minute: Int) =
            source.schedule(PocketScheduleScope.CURRENT_WEEK, PocketClock(2026, 11, 17, 2, hour * 60 + minute))
                .cells.first { it.id == "2-5" }
        assertEquals(2, tuesdayBlock(13, 30).span)
        assertEquals(PocketCellStatus.CURRENT, tuesdayBlock(13, 30).status)
        assertEquals(PocketCellStatus.NORMAL, tuesdayBlock(13, 55).status)
        assertEquals(PocketCellStatus.CURRENT, tuesdayBlock(14, 0).status)
        assertEquals(PocketCellStatus.ENDED, tuesdayBlock(14, 50).status)
    }

    @Test
    fun heroNamesTheVeryNextPeriodEvenWhenItIsTheSameSubject() {
        val hero = assertNotNull(source.home(PocketClock(2026, 11, 17, 2, 13 * 60 + 30)).hero)
        assertEquals("現在上課 · 第 5 節", hero.headline)
        assertEquals("下一堂 美術 · 14:00", hero.nextLine)
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
