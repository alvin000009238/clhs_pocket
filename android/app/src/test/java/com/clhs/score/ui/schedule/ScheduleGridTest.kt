package com.clhs.score.ui.schedule

import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleGridTest {
    @Test
    fun automaticFutureWeekStartsOnMondayInsteadOfCurrentFriday() {
        val friday = LocalDate.of(2026, 11, 13)
        val week = ScheduleReport("115_1", "230", ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-11-16", weekEndDate = "2026-11-22", items = emptyList())
        assertEquals(1, scheduleInitialDay(week, (1..5).toList(), friday))
        assertEquals(5, scheduleInitialDay(week.copy(weekStartDate = "2026-11-09"), (1..5).toList(), friday))
        assertEquals(1, scheduleInitialDay(week, (1..5).toList(), friday.plusDays(1)))
    }

    @Test
    fun weekRowsFitSpareHeightButPreserveContentAndCapStretching() {
        assertEquals(56.dp, scheduleFittedRowHeight(320.dp, 56.dp, 8))
        assertEquals(56.dp, scheduleFittedRowHeight(448.dp, 56.dp, 8))
        assertEquals(70.dp, scheduleFittedRowHeight(560.dp, 56.dp, 8))
        assertEquals(80.dp, scheduleFittedRowHeight(560.dp, 56.dp, 7))
        assertEquals(80.dp, scheduleFittedRowHeight(1200.dp, 56.dp, 8))
        assertEquals(96.dp, scheduleFittedRowHeight(560.dp, 96.dp, 8))
        assertEquals(56.dp, scheduleFittedRowHeight(0.dp, 56.dp, 0))
    }

    @Test
    fun dayViewSplitsConsecutiveLessonsWithoutChangingWeekCells() {
        val week = scheduleGridCells(listOf(ScheduleItem(2, 5, "美術"), ScheduleItem(2, 6, "美術")), emptyList(), 2)
            .filter { it.item != null }
        assertEquals(2, week.single().periodCount)
        val day = scheduleSinglePeriodCells(week)
        assertEquals(listOf(5, 6), day.map { it.period })
        assertEquals(listOf(5, 6), day.map { it.item?.period })
        assertTrue(day.all { it.periodCount == 1 })
    }

    @Test
    fun regularWeekOmitsUnusedExtraPeriodButKeepsEmptySlotsWithinTheSchoolDay() {
        assertEquals((1..7).toList(), scheduleGridPeriods(listOf(ScheduleItem(1, 7, "班會")), emptyList()))
    }

    @Test
    fun extraPeriodRemainsVisibleForEitherACourseOrACancellation() {
        val extra = ScheduleItem(5, 8, "生涯規劃")
        assertEquals((1..8).toList(), scheduleGridPeriods(listOf(extra), emptyList()))
        assertEquals((1..8).toList(), scheduleGridPeriods(emptyList(), listOf(
            ScheduleChange(ScheduleChangeType.REMOVED, 5, 8, semesterItem = extra),
        )))
    }

    @Test
    fun currentWeekHighlightsOnlyTheActualWeekday() {
        val report = ScheduleReport(
            yearTermValue = "115_1",
            classNo = "101",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-09-21",
            weekEndDate = "2026-09-27",
            items = emptyList(),
        )

        assertEquals(1, scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-21")))
        assertEquals(5, scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-25")))
        assertNull(scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-20")))
        assertNull(scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-28")))
    }

    @Test
    fun semesterScheduleHighlightsWeekdayButNotWeekend() {
        val report = ScheduleReport(
            yearTermValue = "115_1",
            classNo = "101",
            scope = ScheduleScope.SEMESTER,
            items = emptyList(),
        )

        assertEquals(1, scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-21")))
        assertNull(scheduleCurrentDayOfWeek(report, LocalDate.parse("2026-09-20")))
    }

    @Test
    fun changeTitlesDescribeTheActualEvent() {
        assertEquals("加課", scheduleChangeTitle(ScheduleChangeType.ADDED))
        assertEquals("調課", scheduleChangeTitle(ScheduleChangeType.MODIFIED))
        assertEquals("停課", scheduleChangeTitle(ScheduleChangeType.REMOVED))
    }

    @Test
    fun consecutiveIdenticalCoursesShareOneCell() {
        val items = listOf(
            lesson(period = 1),
            lesson(period = 2),
            lesson(period = 3, classroom = "不同教室"),
            lesson(period = 4),
        )
        val changes = listOf(
            ScheduleChange(
                type = ScheduleChangeType.MODIFIED,
                dayOfWeek = 1,
                period = 4,
                weekItem = items.last(),
            ),
        )

        val cells = scheduleGridCells(items, changes, dayOfWeek = 1, periods = 1..4)

        assertEquals(listOf(1, 3, 4), cells.map { it.period })
        assertEquals(listOf(2, 1, 1), cells.map { it.periodCount })
    }

    @Test
    fun extraPeriodsRemainVisible() {
        val cells = scheduleGridCells(
            items = listOf(lesson(period = 9)),
            changes = emptyList(),
            dayOfWeek = 1,
        )

        assertEquals(9, cells.last().period)
        assertEquals("國文", cells.last().item?.subjectName)
    }

    @Test
    fun cellDescriptionsIncludeDayPeriodAndCourseContext() {
        assertEquals(
            "週一，第 1 至 2 節，國文，教師王老師，教室101",
            scheduleCellContentDescription(
                dayOfWeek = 1,
                cell = ScheduleGridCell(
                    period = 1,
                    periodCount = 2,
                    item = lesson(period = 1),
                    change = null,
                ),
            ),
        )
        assertEquals(
            "週三，第 3 節，停課，原課程國文，教師王老師，教室101",
            scheduleCellContentDescription(
                dayOfWeek = 3,
                cell = ScheduleGridCell(
                    period = 3,
                    periodCount = 1,
                    item = null,
                    change = ScheduleChange(
                        type = ScheduleChangeType.REMOVED,
                        dayOfWeek = 3,
                        period = 3,
                        semesterItem = lesson(period = 3),
                    ),
                ),
            ),
        )
    }

    @Test
    fun unchangedPrefilledFieldsRemainInheritedWhenSaved() {
        val draft = scheduleSubjectOverrideDraft(
            originalSubjectName = "國文",
            originalTeacherName = "王老師",
            originalClassroom = "101",
            subjectName = "國語文",
            teacherName = "王老師",
            classroom = "101",
        )

        assertEquals("國語文", draft.customSubjectName)
        assertNull(draft.customTeacherName)
        assertNull(draft.customClassroom)
    }

    @Test
    fun emptyDetailsOnlyAppearWhileEditing() {
        assertFalse(shouldShowScheduleDetailField(value = "", isEditing = false))
        assertTrue(shouldShowScheduleDetailField(value = "", isEditing = true))
        assertTrue(shouldShowScheduleDetailField(value = "王老師", isEditing = false))
    }

    @Test
    fun temporalStatusUsesTheActualDatedWeekAndExactLessonBoundaries() {
        val report = datedReport()
        val cell = ScheduleGridCell(1, 1, lesson(1), null)
        assertEquals(ScheduleTemporalStatus.UPCOMING, scheduleCellTemporalStatus(report, 1, cell, LocalDateTime.parse("2026-09-21T08:09:59")))
        assertEquals(ScheduleTemporalStatus.CURRENT, scheduleCellTemporalStatus(report, 1, cell, LocalDateTime.parse("2026-09-21T08:10:00")))
        assertEquals(ScheduleTemporalStatus.CURRENT, scheduleCellTemporalStatus(report, 1, cell, LocalDateTime.parse("2026-09-21T08:59:59")))
        assertEquals(ScheduleTemporalStatus.ENDED, scheduleCellTemporalStatus(report, 1, cell, LocalDateTime.parse("2026-09-21T09:00:00")))
        assertNull(scheduleCellTemporalStatus(report, 1, cell, LocalDateTime.parse("2026-09-28T08:30:00")))
        assertNull(scheduleCellTemporalStatus(report, 2, cell, LocalDateTime.parse("2026-09-21T08:30:00")))
        assertNull(scheduleCellTemporalStatus(report.copy(scope = ScheduleScope.SEMESTER), 1, cell, LocalDateTime.parse("2026-09-21T08:30:00")))
    }

    @Test
    fun mergedLessonDoesNotRemainCurrentDuringTheBreakAndRetainsFullRange() {
        val cell = ScheduleGridCell(1, 2, lesson(1), null)
        assertEquals("第 1 至 2 節", schedulePeriodLabel(cell))
        assertEquals("08:10–10:00", scheduleCellTimeLabel(cell))
        assertEquals(ScheduleTemporalStatus.UPCOMING, scheduleCellTemporalStatus(datedReport(), 1, cell, LocalDateTime.parse("2026-09-21T09:05:00")))
        assertEquals(ScheduleTemporalStatus.CURRENT, scheduleCellTemporalStatus(datedReport(), 1, cell, LocalDateTime.parse("2026-09-21T09:10:00")))
        assertEquals(ScheduleTemporalStatus.ENDED, scheduleCellTemporalStatus(datedReport(), 1, cell, LocalDateTime.parse("2026-09-21T10:00:00")))
    }

    @Test
    fun absentDatesUnknownTimesAndCancellationsNeverInventTemporalStatus() {
        val now = LocalDateTime.parse("2026-09-21T08:30:00")
        val cell = ScheduleGridCell(1, 1, lesson(1), null)
        assertNull(scheduleCellTemporalStatus(datedReport().copy(weekStartDate = null), 1, cell, now))
        assertNull(scheduleCellTemporalStatus(datedReport().copy(weekEndDate = "invalid"), 1, cell, now))
        assertNull(scheduleCellTemporalStatus(datedReport().copy(weekEndDate = "2026-09-20"), 1, cell, now))
        assertNull(scheduleCellTemporalStatus(datedReport(), 1, cell.copy(period = 9), now))
        val removed = ScheduleChange(ScheduleChangeType.REMOVED, 1, 1, semesterItem = lesson(1))
        assertNull(scheduleCellTemporalStatus(datedReport(), 1, cell.copy(change = removed), now))
        assertNull(scheduleCellTimeLabel(cell.copy(period = 9)))
        assertEquals(LocalDate.parse("2026-09-27"), scheduleDayDate(datedReport(), 7))
    }

    private fun datedReport() = ScheduleReport(
        yearTermValue = "115_1", classNo = "101", scope = ScheduleScope.CURRENT_WEEK,
        weekStartDate = "2026-09-21", weekEndDate = "2026-09-27", items = listOf(lesson(1)),
    )

    private fun lesson(period: Int, classroom: String = "101") =
        ScheduleItem(
            dayOfWeek = 1,
            period = period,
            subjectName = "國文",
            teacherName = "王老師",
            classroom = classroom,
        )
}
