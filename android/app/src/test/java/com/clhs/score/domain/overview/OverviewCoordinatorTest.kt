package com.clhs.score.domain.overview

import com.clhs.score.data.GradeChange
import com.clhs.score.data.GradeChangeField
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolCalendarFeed
import com.clhs.score.data.SchoolCalendarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class OverviewCoordinatorTest {
    private val monday = LocalDateTime.of(2026, 8, 24, 8, 30)

    @Test
    fun multipleCountdownsStayIndependentAndEndedEventsSortLast() {
        val events = listOf(
            SchoolCalendarEvent("later", "較晚活動", monday.plusDays(10), monday.plusDays(11), true),
            SchoolCalendarEvent("ended", "已結束活動", monday.minusDays(2), monday.minusDays(1), true),
            SchoolCalendarEvent("soon", "即將開始", monday.plusSeconds(20), monday.plusHours(1), false),
        )
        val result = OverviewState(now = monday, calendarEvents = events).withPreferences(
            com.clhs.score.data.OverviewPreferences(countdownEventIds = setOf("ended", "later", "soon", "missing")),
        )
        assertEquals(listOf("即將開始", "較晚活動", "已結束活動"), result.countdowns.map { it.title })
        assertEquals(listOf("1 分鐘", "10 天", "已結束"), result.countdowns.map { it.label })
        assertEquals(listOf("soon", "later", "ended").map { OverviewDestination.CalendarEvent(it) }, result.countdowns.map { it.destination })
        assertTrue(result.countdowns.all { it.supportingText.isEmpty() })
    }

    @Test
    fun countdownTracksCalendarDaysStartAndExclusiveEnd() {
        val event = SchoolCalendarEvent("exam", "段考", monday.plusDays(30).toLocalDate().atStartOfDay(),
            monday.plusDays(32).toLocalDate().atStartOfDay(), true)
        val preferences = com.clhs.score.data.OverviewPreferences(countdownEventIds = setOf(event.id))
        val state = OverviewState(now = monday, calendarEvents = listOf(event))
        assertEquals("30 天", state.withPreferences(preferences).countdowns.singleOrNull()?.label)
        assertEquals("29 天", state.copy(now = monday.plusDays(1)).withPreferences(preferences).countdowns.singleOrNull()?.label)
        assertEquals("進行中", state.copy(now = event.start).withPreferences(preferences).countdowns.singleOrNull()?.label)
        assertEquals("已結束", state.copy(now = event.endExclusive).withPreferences(preferences).countdowns.singleOrNull()?.label)
        assertTrue(state.withPreferences(preferences.copy(countdownEventIds = setOf("missing"))).countdowns.isEmpty())
        assertTrue(state.withPreferences(preferences.copy(countdownEventIds = emptySet())).countdowns.isEmpty())
        val timed = event.copy(start = monday.plusHours(2).plusMinutes(15), isAllDay = false)
        assertEquals("2 小時",
            state.copy(calendarEvents = listOf(timed)).withPreferences(preferences).countdowns.singleOrNull()?.label)
    }

    @Test
    fun sourcesRetainItemsBeyondOldGlobalAndPerSourceLimits() {
        val events = (1..12).map {
            SchoolCalendarEvent("event-$it", "活動 $it", monday.plusDays(it.toLong()),
                monday.plusDays(it.toLong()).plusHours(1), false)
        }
        val calendar = calendarOverviewItems(SchoolCalendarSnapshot(SchoolCalendarFeed(events), Instant.EPOCH), monday)
        assertEquals(12, calendar.size)
        assertEquals(events.map { OverviewDestination.CalendarEvent(it.id) }, calendar.map { it.destination })
        val announcements = announcementOverviewItems(SchoolAnnouncementPage(
            announcements = (1..10).map { announcement("pinned-$it", pinned = true) },
            pageIndex = 0, totalPages = 1, fetchedAt = Instant.EPOCH,
        ))
        assertEquals(10, announcements.size)
        val preferences = com.clhs.score.data.OverviewPreferences(calendarLimit = 8, announcementLimit = 6)
        val state = OverviewState(items = calendar + announcements).withPreferences(preferences)
        assertEquals(8, state.items.count { it.kind == OverviewItemKind.CalendarEvent })
        assertEquals(6, state.items.count { it.kind == OverviewItemKind.Announcement })
        assertEquals(calendar.take(8).map { it.id }, state.items.filter { it.kind == OverviewItemKind.CalendarEvent }.map { it.id })
    }

    @Test
    fun contextTracksClassBreakAfterSchoolEveningAndNonSchoolDay() {
        val report = weekReport()

        assertEquals(OverviewContext.InClass, overviewContext(report, monday))
        assertEquals(OverviewContext.BetweenClasses, overviewContext(report, monday.withHour(9).withMinute(5)))
        assertEquals(OverviewContext.AfterSchool, overviewContext(report, monday.withHour(17)))
        assertEquals(OverviewContext.Evening, overviewContext(report, monday.withHour(20)))
        assertEquals(OverviewContext.NonSchoolDay, overviewContext(report, monday.plusDays(6)))
    }

    @Test
    fun scheduleChangeAndCurrentClassAreP0BeforeUpcomingClasses() {
        val items = scheduleOverviewItems(weekReport(withChange = true), monday)

        assertEquals(OverviewItemKind.ScheduleChange, items.first().kind)
        assertEquals(OverviewPriority.P0, items.first().priority)
        assertTrue(items.any { it.kind == OverviewItemKind.CurrentClass && it.title.contains("國文") })
        assertTrue(items.any { it.kind == OverviewItemKind.UpcomingClass && it.title.contains("數學") })
    }

    @Test
    fun heroExposesCurrentCourseWithoutUiStringParsing() {
        val hero = scheduleOverviewHero(weekReport(), monday)!!

        assertEquals("正在上課", hero.headline)
        assertEquals("第 1 節", hero.courseLabel)
        assertEquals("國文", hero.courseName)
        assertEquals("201 · 王老師", hero.supportingText)
    }

    @Test
    fun overviewUsesLocalSubjectOverrides() {
        val report = weekReport().copy(
            subjectOverrides = listOf(
                ScheduleSubjectOverride(
                    originalSubjectName = "國文",
                    customSubjectName = "國語文",
                    customTeacherName = "自訂教師",
                    customClassroom = "自訂教室",
                ),
            ),
        )

        val hero = scheduleOverviewHero(report, monday)!!

        assertEquals("國語文", hero.courseName)
        assertEquals("自訂教室 · 自訂教師", hero.supportingText)
    }

    @Test
    fun heroUsesNextSchoolDayFirstCourseAndTotalCountAfterClasses() {
        val hero = scheduleOverviewHero(weekReport(), monday.withHour(17))!!

        assertEquals("明天", hero.headline)
        assertEquals("第一節", hero.courseLabel)
        assertEquals("英文", hero.courseName)
        assertEquals("1 節課", hero.trailingText)
        assertEquals(25, hero.date.dayOfMonth)
    }

    @Test
    fun courseProgressTracksPeriodAndDisappearsAtClassEnd() {
        val report = weekReport()
        assertEquals(0f, scheduleOverviewHero(report, monday.withMinute(10))!!.courseProgress!!, 0.001f)
        assertEquals(0.4f, scheduleOverviewHero(report, monday)!!.courseProgress!!, 0.001f)
        assertEquals(0.5f, scheduleOverviewHero(report, monday.withMinute(35))!!.courseProgress!!, 0.001f)
        assertNull(scheduleOverviewHero(report, monday.withHour(9).withMinute(0))!!.courseProgress)
        assertNull(scheduleOverviewHero(report, monday.withHour(17))!!.courseProgress)
    }

    @Test
    fun nextSchoolDayUsesRelativeDatesAcrossWeekAndYearBoundaries() {
        val report = weekReport().copy(scope = ScheduleScope.SEMESTER)
        for ((now, expected) in listOf(
            LocalDateTime.parse("2026-08-29T17:00") to "後天",
            LocalDateTime.parse("2026-08-28T17:00") to "3 天後",
            LocalDateTime.parse("2026-12-31T17:00") to "4 天後",
        )) {
            assertEquals(expected, scheduleOverviewHero(report, now)!!.headline)
            assertTrue(scheduleOverviewItems(report, now).single().title.startsWith(expected))
        }
    }

    @Test
    fun announcementOverviewIncludesPinnedItemsOnly() {
        val page = SchoolAnnouncementPage(
            announcements = listOf(
                announcement("pinned", pinned = true),
                announcement("normal", pinned = false),
            ),
            pageIndex = 0,
            totalPages = 1,
            fetchedAt = Instant.parse("2026-08-24T00:00:00Z"),
        )

        val items = announcementOverviewItems(page)

        assertEquals(listOf("置頂公告"), items.map(OverviewItem::label))
        assertTrue(items.single().title.contains("pinned"))
    }

    @Test
    fun multiDayCalendarEventUsesOngoingRelativeTimeInsteadOfOldStartDate() {
        val snapshot = SchoolCalendarSnapshot(
            feed = SchoolCalendarFeed(
                events = listOf(
                    SchoolCalendarEvent(
                        id = "ongoing",
                        title = "圖書館志工招募",
                        start = monday.minusDays(6).toLocalDate().atStartOfDay(),
                        endExclusive = monday.plusDays(1).toLocalDate().atStartOfDay(),
                        isAllDay = true,
                    ),
                ),
            ),
            fetchedAt = Instant.parse("2026-08-24T00:00:00Z"),
        )

        val item = calendarOverviewItems(snapshot, monday).single()

        assertEquals("進行中", item.label)
        assertEquals(monday.toLocalDate(), item.eventTime?.toLocalDate())
        assertTrue(item.supportingText.contains("至 8/24"))
    }

    @Test
    fun gradeOverviewUsesExistingReminderChangeSetWithoutPolling() {
        val state = GradeReminderState(
            latestChangeSet = GradeChangeSet(
                studentNo = "student",
                yearValue = "114_2",
                examValue = "E1",
                examName = "第一次段考",
                checkedAtMillis = 1L,
                changes = listOf(
                    GradeChange(
                        targetName = "國文",
                        subjectName = "國文",
                        field = GradeChangeField.SUBJECT_SCORE,
                        oldValue = "80",
                        newValue = "90",
                    ),
                ),
            ),
        )

        val item = gradeOverviewItems(state).single()

        assertEquals(OverviewPriority.P2, item.priority)
        assertEquals(OverviewDestination.GradeExam("114_2", "E1"), item.destination)
    }

    @Test
    fun currentWeekUsesSemanticBoundaryAndSemesterUsesAge() {
        val currentWeek = weekReport()
        val nowMillis = monday.atZone(ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli()
        val semester = currentWeek.copy(
            scope = ScheduleScope.SEMESTER,
            weekStartDate = null,
            weekEndDate = null,
        )

        assertFalse(scheduleNeedsRefresh(currentWeek, null, monday))
        assertFalse(scheduleNeedsRefresh(semester, nowMillis, monday))
        assertTrue(scheduleNeedsRefresh(semester, nowMillis - 7 * 60 * 60 * 1_000L, monday))
        assertTrue(scheduleNeedsRefresh(currentWeek, nowMillis, monday.plusDays(7)))
    }

    @Test
    fun currentWeekRefreshDelayUsesActualFridayClassEndWithSundayWeekStart() {
        val report = ScheduleReport(
            yearTermValue = "114_2",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-08-30",
            weekEndDate = "2026-09-05",
            items = listOf(ScheduleItem(dayOfWeek = 5, period = 8, subjectName = "國文")),
        )

        assertEquals(
            55 * 60 * 1_000L,
            scheduleRefreshDelayMillis(report, LocalDateTime.parse("2026-09-04T16:00")),
        )
        assertEquals(
            0L,
            scheduleRefreshDelayMillis(report, LocalDateTime.parse("2026-09-04T17:00")),
        )
    }

    @Test
    fun nextWeekReportPreparedOnFridayIsNotRefetchedAsThePreviousWeek() {
        val friday = LocalDateTime.parse("2026-09-04T17:00")
        val report = ScheduleReport(
            yearTermValue = "114_2",
            classNo = "230",
            scope = ScheduleScope.CURRENT_WEEK,
            weekStartDate = "2026-09-06",
            weekEndDate = "2026-09-12",
            items = listOf(ScheduleItem(dayOfWeek = 1, period = 1, subjectName = "國文")),
        )

        assertFalse(scheduleNeedsRefresh(report, null, friday))
        assertEquals("國文", scheduleOverviewHero(report, friday)?.courseName)
    }

    @Test
    fun missingScheduleCacheProducesOneActionableStatusInsteadOfFalseCalmState() {
        val item = scheduleSetupOverviewItem()

        assertEquals(OverviewItemKind.ScheduleSetup, item.kind)
        assertEquals(OverviewDestination.Schedule, item.destination)
        assertTrue(item.title.contains("尚未有可用的課表"))
    }

    private fun weekReport(withChange: Boolean = false) = ScheduleReport(
        yearTermValue = "114_2",
        classNo = "230",
        scope = ScheduleScope.CURRENT_WEEK,
        weekStartDate = "2026-08-24",
        weekEndDate = "2026-08-30",
        items = listOf(
            ScheduleItem(1, 1, "國文", "王老師", "201"),
            ScheduleItem(1, 2, "數學", "李老師", "202"),
            ScheduleItem(2, 1, "英文", "陳老師", "203"),
        ),
        changes = if (withChange) {
            listOf(
                ScheduleChange(
                    type = ScheduleChangeType.MODIFIED,
                    dayOfWeek = 1,
                    period = 2,
                    semesterItem = ScheduleItem(1, 2, "數學", "李老師", "202"),
                    weekItem = ScheduleItem(1, 2, "數學", "李老師", "305"),
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun announcement(id: String, pinned: Boolean) = SchoolAnnouncement(
        id = id,
        title = "公告 $id",
        date = "2026-08-24",
        category = "行政",
        unit = "教務處",
        issuer = "",
        isPinned = pinned,
        contentType = "html",
    )
}
