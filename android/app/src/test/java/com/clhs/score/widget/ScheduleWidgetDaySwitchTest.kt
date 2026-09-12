package com.clhs.score.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScheduleWidgetDaySwitchTest {
    private val monday = LocalDate.parse("2026-07-20")
    private val items = listOf(
        ScheduleItem(1, 4, "國文", "", ""),
        ScheduleItem(2, 1, "英文", "", ""),
    )

    @Test
    fun previewRefreshWaitsForCooldownAndSkipsCurrentPreview() {
        val hour = 60 * 60 * 1_000L

        assertFalse(shouldRefreshScheduleWidgetPreview(true, "current", "current", 0L, hour))
        assertFalse(shouldRefreshScheduleWidgetPreview(true, "old", "current", hour, hour * 2 - 1))
        assertTrue(shouldRefreshScheduleWidgetPreview(true, "old", "current", hour, hour * 2))
        assertTrue(shouldRefreshScheduleWidgetPreview(false, "current", "current", hour, hour * 2))
    }

    @Test
    fun nextUpdateUsesActualCourseBoundary() {
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = monday.toString(),
            weekEndDate = monday.plusDays(6).toString(),
            items = listOf(lesson(period = 1), lesson(period = 4)),
        )

        assertEquals(
            LocalDateTime.of(2026, 7, 20, 11, 10),
            nextScheduleWidgetUpdateAt(report, LocalDateTime.of(2026, 7, 20, 9, 5)),
        )
    }

    @Test
    fun nextUpdateUsesMidnightWhenThereIsNoCourseBoundaryToday() {
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = monday.toString(),
            weekEndDate = monday.plusDays(6).toString(),
            items = listOf(lesson(period = 1)),
        )

        assertEquals(
            LocalDateTime.of(2026, 7, 21, 0, 0),
            nextScheduleWidgetUpdateAt(report, LocalDateTime.of(2026, 7, 20, 9, 5)),
        )
    }

    @Test
    fun nextUpdateUsesTheNextBoundaryFromSemesterSchedule() {
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.SEMESTER,
            items = listOf(lesson(period = 4)),
        )

        assertEquals(
            LocalDateTime.of(2026, 7, 20, 11, 10),
            nextScheduleWidgetUpdateAt(report, LocalDateTime.of(2026, 7, 20, 10, 0)),
        )
    }

    @Test
    fun nextUpdateIgnoresPeriodsWithoutKnownTimes() {
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = monday.toString(),
            weekEndDate = monday.plusDays(6).toString(),
            items = listOf(lesson(period = 9)),
        )

        assertEquals(
            LocalDateTime.of(2026, 7, 21, 0, 0),
            nextScheduleWidgetUpdateAt(report, LocalDateTime.of(2026, 7, 20, 9, 5)),
        )
    }

    @Test
    fun switchesAfterLastClass() {
        assertEquals(
            monday,
            selectWidgetSchedule(items, monday, 11 * 60 + 59, afterLastClass = true).date,
        )
        assertEquals(
            monday.plusDays(1),
            selectWidgetSchedule(items, monday, 12 * 60, afterLastClass = true).date,
        )
        assertEquals(
            monday,
            selectWidgetSchedule(items, monday, 12 * 60, afterLastClass = false).date,
        )
    }

    @Test
    fun classifiesLessonsBeforeFirstClass() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(2), lesson(1)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 8 * 60,
        )

        assertNull(sections.current)
        assertEquals(listOf(1, 2), sections.upcoming.map { it.period })
        assertEquals(emptyList<ScheduleItem>(), sections.completed)
    }

    @Test
    fun classifiesCurrentLessonFirst() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(2), lesson(1)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 8 * 60 + 30,
        )

        assertEquals(1, sections.current?.period)
        assertEquals(listOf(2), sections.upcoming.map { it.period })
        assertEquals(listOf(1, 2), sections.prioritized.map { it.period })
        assertEquals(emptyList<ScheduleItem>(), sections.completed)
    }

    @Test
    fun classifiesBreakBetweenLessons() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(1), lesson(2)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 9 * 60 + 5,
        )

        assertNull(sections.current)
        assertEquals(listOf(2), sections.upcoming.map { it.period })
        assertEquals(listOf(1), sections.completed.map { it.period })
    }

    @Test
    fun classifiesLessonAsCompletedAtItsEndBoundary() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(1)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 9 * 60,
        )

        assertNull(sections.current)
        assertEquals(emptyList<ScheduleItem>(), sections.upcoming)
        assertEquals(listOf(1), sections.completed.map { it.period })
    }

    @Test
    fun classifiesAllLessonsAsCompletedAfterSchool() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(1), lesson(2)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 10 * 60 + 1,
        )

        assertNull(sections.current)
        assertEquals(emptyList<ScheduleItem>(), sections.upcoming)
        assertEquals(listOf(1, 2), sections.completed.map { it.period })
    }

    @Test
    fun keepsUnknownPeriodAsUpcoming() {
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(1), lesson(9)),
            selectionDate = monday,
            today = monday,
            currentMinutes = 18 * 60,
        )

        assertEquals(listOf(9), sections.upcoming.map { it.period })
        assertEquals(listOf(1), sections.completed.map { it.period })
    }

    @Test
    fun treatsNextSchoolDayLessonsAsUpcoming() {
        val tuesday = monday.plusDays(1)
        val sections = classifyWidgetScheduleItems(
            items = listOf(lesson(2, dayOfWeek = 2), lesson(1, dayOfWeek = 2)),
            selectionDate = tuesday,
            today = monday,
            currentMinutes = 18 * 60,
        )

        assertNull(sections.current)
        assertEquals(listOf(1, 2), sections.upcoming.map { it.period })
        assertEquals(emptyList<ScheduleItem>(), sections.completed)
    }

    @Test
    fun formatsDayAndDateHeader() {
        assertEquals("今天", widgetScheduleDayLabel(monday, monday))
        assertEquals("明天", widgetScheduleDayLabel(monday.plusDays(1), monday))
        assertEquals("週三", widgetScheduleDayLabel(monday.plusDays(2), monday))
        assertEquals("7 月 20 日", widgetScheduleDateLabel(monday))
        assertEquals("7/20", widgetScheduleShortDateLabel(monday))
    }

    @Test
    fun nextSchoolDayOutsideCurrentWeekRemainsExpired() {
        val friday = LocalDate.parse("2026-07-24")
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekNo = "21",
            weekStartDate = "2026-07-20",
            weekEndDate = "2026-07-26",
            items = listOf(
                lesson(period = 8, dayOfWeek = 5),
                lesson(period = 1, dayOfWeek = 1),
            ),
        )

        val selection = selectWidgetSchedule(
            items = report.items,
            today = friday,
            currentMinutes = 16 * 60 + 55,
            afterLastClass = true,
        )

        assertEquals(LocalDate.parse("2026-07-27"), selection.date)
        assertFalse(report.isValidOn(selection.date))
    }

    @Test
    fun futureWeekDoesNotReuseSameWeekdayBeforeStartDate() {
        val friday = LocalDate.parse("2026-07-24")
        val report = ScheduleReport(
            yearTermValue = "115_4",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekNo = "5",
            weekStartDate = "2026-07-26",
            weekEndDate = "2026-08-01",
            items = listOf(
                lesson(period = 8, dayOfWeek = 5),
                lesson(period = 1, dayOfWeek = 1),
            ),
        )

        val selection = selectWidgetSchedule(
            items = report.items,
            today = friday,
            currentMinutes = 17 * 60,
            afterLastClass = false,
            validFrom = LocalDate.parse(requireNotNull(report.weekStartDate)),
        )

        assertEquals(LocalDate.parse("2026-07-27"), selection.date)
        assertTrue(report.isValidOn(selection.date))
    }

    @Test
    fun legacyPreferencesOnlyFillMissingPerWidgetValues() {
        val state = mutablePreferencesOf(WidgetShowTeacherKey to true)

        state.syncScheduleWidgetState(
            reportStr = null,
            settings = AppSettings(),
            legacyPreferences = Triple(false, false, false),
        )

        assertEquals(true, state[WidgetShowTeacherKey])
        assertEquals(false, state[WidgetShowClassroomKey])
        assertEquals(false, state[WidgetShowTimeKey])
    }

    @Test
    fun selectedPreferencesOverrideTheExistingWidgetStateDuringRefresh() {
        val state = mutablePreferencesOf(
            WidgetShowTeacherKey to true,
            WidgetShowClassroomKey to true,
            WidgetShowTimeKey to true,
            WidgetAfterLastClassKey to false,
        )

        state.syncScheduleWidgetState(
            reportStr = null,
            settings = AppSettings(),
            legacyPreferences = Triple(true, true, false),
            preferences = ScheduleWidgetPreferences(
                showTeacher = false,
                showClassroom = false,
                showTime = false,
                afterLastClass = true,
            ),
        )

        assertEquals(false, state[WidgetShowTeacherKey])
        assertEquals(false, state[WidgetShowClassroomKey])
        assertEquals(false, state[WidgetShowTimeKey])
        assertEquals(true, state[WidgetAfterLastClassKey])
    }

    private fun lesson(period: Int, dayOfWeek: Int = 1) =
        ScheduleItem(dayOfWeek, period, "科目 $period", "", "")
}
