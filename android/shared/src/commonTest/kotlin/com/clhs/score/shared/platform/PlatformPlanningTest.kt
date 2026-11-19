package com.clhs.score.shared.platform

import com.clhs.score.data.BackgroundPause
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.schoolEpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformPlanningTest {
    private val taipei = 8 * 3600

    /** School time on Wednesday 2026-11-18 unless [day] says otherwise. */
    private fun at(hour: Int, minute: Int, day: Int = 18): Long =
        localDateOf(2026, 11, day).at(localTimeOf(hour, minute)).schoolEpochMillis()

    private fun week(vararg items: ScheduleItem) = ScheduleReport(
        yearTermValue = "115_1",
        classNo = "230",
        scope = ScheduleScope.CURRENT_WEEK,
        weekNo = "12",
        weekStartDate = "2026-11-16",
        weekEndDate = "2026-11-22",
        items = items.toList(),
    )

    private val report = week(
        ScheduleItem(3, 3, "數學", "教師甲", "高二30"),
        ScheduleItem(3, 4, "體育", "教師乙", "操場"),
        ScheduleItem(4, 1, "英語文", "教師丙", "高二30"),
    )

    private fun settings(enabled: Boolean = true, pause: Boolean = false, start: Int = 23 * 60, end: Int = 7 * 60) =
        PlatformNotificationSettings(enabled, true, true, true, pause, start, end, taipei)

    @Test
    fun timelineReloadsAtEveryLessonBoundaryAndExpiresWithTheWeek() {
        val snapshot = assertNotNull(buildWidgetScheduleSnapshot(report, at(9, 0)))
        val today = snapshot.entries.filter { it.atMillis < at(0, 0, day = 19) }
        assertEquals(
            listOf(
                at(9, 0) to WidgetPhase.BEFORE_CLASS,
                at(10, 5) to WidgetPhase.STARTING_SOON,
                at(10, 10) to WidgetPhase.IN_CLASS,
                at(11, 0) to WidgetPhase.BEFORE_CLASS,
                at(11, 5) to WidgetPhase.STARTING_SOON,
                at(11, 10) to WidgetPhase.IN_CLASS,
                at(12, 0) to WidgetPhase.DAY_DONE,
            ),
            today.map { it.atMillis to it.phase },
        )
        val evening = assertNotNull(snapshot.entryAt(at(20, 0)))
        assertEquals("英語文", snapshot.lessons.first { it.id == evening.nextLessonId }.subject)

        assertEquals(WidgetPhase.FREE_DAY, snapshot.entryAt(at(12, 0, day = 20))?.phase)
        assertEquals(at(0, 0, day = 23), snapshot.validUntilMillis)
        assertNotNull(snapshot.entryAt(at(23, 59, day = 22)))
        assertNull(snapshot.entryAt(at(0, 0, day = 23)))
    }

    @Test
    fun snapshotIsRefusedWhenTheSharedResolverRejectsTheReport() {
        assertNull(buildWidgetScheduleSnapshot(null, at(9, 0)))
        assertNull(buildWidgetScheduleSnapshot(report, at(0, 0, day = 23)), "expired week")
        val overlapping = week(ScheduleItem(3, 3, "數學"), ScheduleItem(3, 3, "物理"))
        assertNull(buildWidgetScheduleSnapshot(overlapping, at(9, 0)), "ambiguous slot")
    }

    @Test
    fun encodedSnapshotCarriesNoIdentityOrTeacherAndRoundTrips() {
        val snapshot = assertNotNull(buildWidgetScheduleSnapshot(report, at(9, 0)))
        val json = snapshot.encode()
        listOf("教師", "230", "115_1", "token", "cookie", "studentNo").forEach {
            assertFalse(json.contains(it), "snapshot leaked $it")
        }
        assertEquals(snapshot, decodeWidgetScheduleSnapshot(json))
        assertNull(decodeWidgetScheduleSnapshot(json.replace("\"schemaVersion\":1", "\"schemaVersion\":99")))
        assertNull(decodeWidgetScheduleSnapshot("not json"))
    }

    @Test
    fun pinnedDemoClockIsReplayedAgainstTheRealClock() {
        val real = at(22, 0, day = 19)
        val snapshot = assertNotNull(PocketPlatform.demoScheduleSnapshot(at(10, 35), real))
        val entry = assertNotNull(snapshot.entryAt(real))
        assertEquals(WidgetPhase.IN_CLASS, entry.phase)
        val lesson = snapshot.lessons.first { it.id == entry.currentLessonId }
        assertEquals("10:10", lesson.startText)
        assertEquals(real + 25 * 60_000L, lesson.endMillis)
    }

    @Test
    fun courseRemindersFireFiveMinutesEarlyAndSkipPastOrDisabledOnes() {
        val snapshot = buildWidgetScheduleSnapshot(report, at(9, 0))
        val planned = planCourseReminders(snapshot, at(10, 6), true, BackgroundPause(), taipei)
        assertEquals(listOf(at(11, 5), at(8, 5, day = 19)), planned.map { it.fireAtMillis })
        assertEquals("即將上課 · 第 4 節", planned.first().title)
        assertEquals("體育 · 11:10–12:00 · 操場", planned.first().body)
        assertEquals(PocketLinks.SCHEDULE, planned.first().link)
        assertEquals(planned.size, planned.map { it.identifier }.toSet().size)

        assertTrue(planCourseReminders(snapshot, at(9, 0), false, BackgroundPause(), taipei).isEmpty())
        assertTrue(planCourseReminders(snapshot, at(0, 0, day = 23), true, BackgroundPause(), taipei).isEmpty())
        assertEquals(1, planCourseReminders(snapshot, at(9, 0), true, BackgroundPause(), taipei, limit = 1).size)
    }

    @Test
    fun quietWindowDropsRemindersOnBothSidesOfItsBoundaries() {
        val snapshot = buildWidgetScheduleSnapshot(report, at(9, 0))
        // 10:05 is inside [10:00, 11:05); 11:05 is the exclusive end and stays.
        val pause = BackgroundPause(enabled = true, startMinute = 10 * 60, endMinute = 11 * 60 + 5)
        val planned = planCourseReminders(snapshot, at(9, 0), true, pause, taipei)
        assertEquals(listOf(at(11, 5), at(8, 5, day = 19)), planned.map { it.fireAtMillis })

        val overnight = BackgroundPause(enabled = true, startMinute = 23 * 60, endMinute = 8 * 60 + 6)
        assertEquals(
            listOf(at(10, 5), at(11, 5)),
            planCourseReminders(snapshot, at(9, 0), true, overnight, taipei).map { it.fireAtMillis },
        )
        // The window follows the device offset, not the school zone.
        assertEquals(9 * 60, minuteOfDayAt(at(9, 0), taipei))
        assertEquals(60, minuteOfDayAt(at(9, 0), 0))
        assertEquals(15 * 60 + 30, minuteOfDayAt(at(9, 0), -(9 * 3600 + 1800)))
    }

    @Test
    fun gradeAlertNamesSubjectsWithoutScoresAndRespectsSettings() {
        val planned = assertNotNull(PocketPlatform.demoGradeNotification(at(12, 0), settings()))
        assertEquals("第二次段考資訊更新", planned.title)
        assertEquals("數學、英語文、整體成績有新資訊", planned.body)
        assertFalse(planned.body.any { it.isDigit() })
        assertEquals(PocketLinks.GRADES, planned.link)
        assertNull(PocketPlatform.demoGradeNotification(at(12, 0), settings(enabled = false)))
        assertNull(PocketPlatform.demoGradeNotification(at(23, 30), settings(pause = true)))
        assertNotNull(PocketPlatform.demoGradeNotification(at(7, 0), settings(pause = true)))
    }

    @Test
    fun announcementsSeedSilentlyThenAnnounceOnlyNewItemsOnce() {
        val a = AnnouncementHeadline("a", "圖書館閉館時間異動公告")
        val b = AnnouncementHeadline("b", "校慶運動會各項競賽報名開始")
        val seeded = PocketPlatform.announcementPlan(null, listOf(a), at(12, 0), settings())
        assertNull(seeded.notification)
        assertEquals(listOf("a"), seeded.seenIds)

        val quiet = PocketPlatform.announcementPlan(seeded.seenIds, listOf(b, a), at(23, 30), settings(pause = true))
        assertNull(quiet.notification)
        assertEquals(listOf("a"), quiet.seenIds, "paused items must still be announced later")

        val fresh = PocketPlatform.announcementPlan(quiet.seenIds, listOf(b, a, b), at(12, 0), settings())
        assertEquals("1 則新學校公告", fresh.notification?.title)
        assertEquals(b.title, fresh.notification?.body)
        assertEquals(PocketLinks.ANNOUNCEMENTS, fresh.notification?.link)

        assertNull(PocketPlatform.announcementPlan(fresh.seenIds, listOf(b, a), at(12, 5), settings()).notification)
    }

    @Test
    fun invalidPauseMinutesDoNotCrashBackgroundPlanning() {
        val broken = settings(pause = true, start = 600, end = 600)
        assertTrue(PocketPlatform.isBackgroundPaused(at(23, 30), broken))
        assertFalse(PocketPlatform.isBackgroundPaused(at(12, 0), broken))
    }
}
