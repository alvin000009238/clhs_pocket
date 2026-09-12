package com.clhs.score.ui.schedule

import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleGridTest {
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

    private fun lesson(period: Int, classroom: String = "101") =
        ScheduleItem(
            dayOfWeek = 1,
            period = period,
            subjectName = "國文",
            teacherName = "王老師",
            classroom = classroom,
        )
}
