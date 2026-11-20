package com.clhs.score.shared.app

import com.clhs.score.shared.time.shiftDays
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.FakeScheduleData
import kotlin.test.assertFalse
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.ExamOption
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

    /** The school reuses exam codes across terms; the picker value must still name one report. */
    @Test
    fun examSelectionIsKeyedByTermAndExam() {
        val terms = listOf(
            YearTermOption("114 學年度 第 1 學期", "114_1", listOf(ExamOption("第一次段考", "1"), ExamOption("第二次段考", "2"))),
            YearTermOption("114 學年度 第 2 學期", "114_2", listOf(ExamOption("第一次段考", "1"))),
        )
        PocketData.useLive()
        try {
            PocketLive.clearAll()
            PocketLive.putStructure(terms)
            val old = FakeData.reportFor("114_1", "114_1_E1")
            val new = FakeData.reportFor("114_1", "114_1_E2")
            PocketLive.putReport("114_1", "1", old)
            PocketLive.putReport("114_2", "1", new)

            val options = DemoGrades.terms.associate { term -> term.value to term.exams.map { it.value } }
            val newKey = options.getValue("114_2").single()
            val oldKey = options.getValue("114_1").first()
            assertTrue(newKey != oldKey)
            assertEquals("114_2", PocketLive.yearValueOf(newKey))
            assertEquals("1", PocketLive.examCodeOf(newKey))
            assertEquals("114_1", PocketLive.yearValueOf(oldKey))

            assertEquals(new.examSummary?.averageScoreDisplay, DemoGrades.report(newKey).averageText)
            assertEquals(old.examSummary?.averageScoreDisplay, DemoGrades.report(oldKey).averageText)
            assertEquals("114 學年度 第 2 學期", DemoGrades.report(newKey).termTitle)
            assertEquals(newKey, DemoGrades.report(newKey).examValue)
            assertEquals(listOf(newKey), PocketLive.currentTermExamValues())
        } finally {
            PocketLive.clearAll()
            PocketData.useDemo()
        }
    }

    @Test
    fun firstExamAfterAConfirmedEmptyListIsNewButTheFirstCheckNeverIs() {
        // First check after sign-in: whatever is listed is only recorded.
        assertFalse(PocketLive.isNewExam(null, "114_1|1"))
        // Already known at the next check.
        assertFalse(PocketLive.isNewExam(listOf("114_1|1"), "114_1|1"))
        assertTrue(PocketLive.isNewExam(listOf("114_1|1"), "114_1|2"))

        // A student without exams: the empty list is confirmed, then the first exam appears.
        assertFalse(PocketLive.isNewExam(null, null))
        assertFalse(PocketLive.isNewExam(emptyList(), null))
        assertTrue(PocketLive.isNewExam(emptyList(), "114_1|1"))

        // A check that failed after listing the new exam did not move the baseline: still new.
        assertTrue(PocketLive.isNewExam(listOf("114_1|1"), "114_1|2"))
    }

    /** Browsing another week in production keeps the current week and finds each by its dates. */
    @Test
    fun liveTimetableKeepsOneReportPerWeek() {
        val clock = PocketClock(2026, 11, 18, 3, 635)
        val today = clock.date()
        PocketData.useLive()
        try {
            PocketLive.clearAll()
            PocketLive.putSchedule("", FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, today))
            PocketLive.putSchedule("", FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, today.shiftDays(7)))
            fun week(offset: Int) = PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, clock, "", offset, "[]")
            assertTrue(week(0).isAvailable)
            assertTrue(week(1).isAvailable)
            assertEquals("11/23", week(1).days.first().dateText)
            assertFalse(week(2).isAvailable)
            assertFalse(week(-1).isAvailable)
            // The widget and reminders use the semester timetable while the week is missing.
            assertEquals(ScheduleScope.CURRENT_WEEK, PocketSchedules.currentWeek(today)?.scope)
            PocketLive.clearAll()
            assertEquals(null, PocketSchedules.currentWeek(today))
            PocketLive.putSchedule("", FakeScheduleData.report("115_1", "230", ScheduleScope.SEMESTER, today))
            assertEquals(ScheduleScope.SEMESTER, PocketSchedules.currentWeek(today)?.scope)
            PocketLive.putSchedule("", FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, today))
            // A class that has not loaded yet is empty, not the student's own timetable.
            assertFalse(PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, clock, "101", 0, "[]").isAvailable)
            PocketLive.putSchedule("101", FakeScheduleData.report("115_1", "101", ScheduleScope.CURRENT_WEEK, today))
            assertTrue(PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, clock, "101", 0, "[]").isAvailable)
        } finally {
            PocketLive.clearAll()
            PocketData.useDemo()
        }
    }

    @Test
    fun announcementBodyKeepsFormattingAndDropsAnythingThatRunsOrLoads() {
        val html = PocketCampusStore.formattingHtml(
            "<style>p{color:red}</style><script>alert(1)</script><p style=\"color:red\" onclick=\"x()\">請<b>注意</b></p>" +
                "<img src=\"https://example.com/a.png\"><iframe src=\"https://example.com\"></iframe>" +
                "<a href=\"https://www.clhs.tyc.edu.tw/a\">連結</a><a href=\"javascript:alert(1)\">壞</a><!-- note -->",
        )
        assertEquals(
            "<p>請<b>注意</b></p><a href=\"https://www.clhs.tyc.edu.tw/a\">連結</a><a>壞</a>",
            html,
        )
    }
}
