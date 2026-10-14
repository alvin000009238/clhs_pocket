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
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

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
            Instant.parse("2026-07-20T03:10:00Z"),
            nextScheduleWidgetUpdateAt(report, Instant.parse("2026-07-20T01:05:00Z")),
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
            Instant.parse("2026-07-20T16:00:00Z"),
            nextScheduleWidgetUpdateAt(report, Instant.parse("2026-07-20T01:05:00Z")),
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
            Instant.parse("2026-07-20T03:10:00Z"),
            nextScheduleWidgetUpdateAt(report, Instant.parse("2026-07-20T02:00:00Z")),
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
            Instant.parse("2026-07-20T16:00:00Z"),
            nextScheduleWidgetUpdateAt(report, Instant.parse("2026-07-20T01:05:00Z")),
        )
    }

    @Test
    fun widgetDateCourseAndNextBoundaryUseSchoolTimezone() {
        val report = ScheduleReport(
            yearTermValue = "1142",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = monday.toString(),
            weekEndDate = monday.plusDays(6).toString(),
            items = listOf(lesson(period = 1), lesson(period = 2), lesson(period = 1, dayOfWeek = 2)),
        )
        val instant = Instant.parse("2026-07-20T01:15:00Z")
        val deviceZones = listOf("Asia/Taipei", "UTC", "America/Los_Angeles").map(ZoneId::of)
        val previousDefault = TimeZone.getDefault()
        val results = try {
            deviceZones.map { deviceZone ->
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                val view = resolveWidgetSchedule(report, instant, afterLastClass = false)
                Triple(view.selection.date to view.sections.current?.period,
                    nextScheduleWidgetUpdateAt(report, instant), deviceZone)
            }
        } finally {
            TimeZone.setDefault(previousDefault)
        }

        results.drop(1).forEach { result ->
            assertEquals(results.first().first, result.first)
            assertEquals(results.first().second, result.second)
        }
        assertEquals(monday, results.first().first.first)
        assertEquals(2, results.first().first.second)
    }

    @Test
    fun widgetAlarmOwnershipHandlesOneManyAndNoActiveInstances() {
        assertTrue(shouldScheduleWidgetAlarm(1))
        assertTrue(shouldScheduleWidgetAlarm(2))
        assertTrue(shouldScheduleWidgetAlarm(8))
        assertFalse(shouldScheduleWidgetAlarm(0))
    }

    @Test
    fun supportedSystemEventsRearmWidgetButMidnightAlarmOwnsDateRollover() {
        listOf(
            android.content.Intent.ACTION_BOOT_COMPLETED,
            android.content.Intent.ACTION_MY_PACKAGE_REPLACED,
            android.content.Intent.ACTION_TIME_CHANGED,
            android.content.Intent.ACTION_TIMEZONE_CHANGED,
        ).forEach { assertTrue(isWidgetRearmAction(it)) }
        assertFalse(shouldScheduleWidgetAlarm(0))
        assertFalse(isWidgetRearmAction(android.content.Intent.ACTION_DATE_CHANGED))
        assertFalse(isWidgetRearmAction("com.example.UNKNOWN"))
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
    fun fullRefreshPreservesPreferencesAlreadySavedForEachWidget() {
        val state = mutablePreferencesOf(
            WidgetShowTeacherKey to true,
            WidgetShowClassroomKey to true,
            WidgetShowTimeKey to true,
            WidgetAfterLastClassKey to false,
        )

        state.syncScheduleWidgetState(
            reportStr = null,
            settings = AppSettings(),
            legacyPreferences = Triple(false, false, false),
        )

        assertEquals(true, state[WidgetShowTeacherKey])
        assertEquals(true, state[WidgetShowClassroomKey])
        assertEquals(true, state[WidgetShowTimeKey])
        assertEquals(false, state[WidgetAfterLastClassKey])
    }

    private fun lesson(period: Int, dayOfWeek: Int = 1) =
        ScheduleItem(dayOfWeek, period, "科目 $period", "", "")
}
