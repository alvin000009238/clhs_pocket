package com.clhs.score.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketCampusStoreTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)

    private fun list(
        category: String = "全部", query: String = "",
        filter: PocketAnnouncementFilter = PocketAnnouncementFilter.ALL,
        unread: Set<String> = PocketCampusStore.defaultUnreadIds(), bookmarks: Set<String> = emptySet(),
    ) = PocketCampusStore.announcements(clock, category, query, filter, unread, bookmarks)

    @Test
    fun aBookmarkThatLeftTheFirstPageIsStillListedAndIsNeverPartOfTheFeed() {
        val old = com.clhs.score.data.SchoolAnnouncement("old-1", "舊公告", "2026-09-01", "一般", "教務處", "", false, "")
        PocketData.useLive()
        try {
            PocketLive.putAnnouncements(DemoCampus.demoAnnouncements(clock))
            PocketLive.savedAnnouncements = listOf(old)
            val saved = list(filter = PocketAnnouncementFilter.BOOKMARKED, bookmarks = setOf("old-1"))
            assertEquals(listOf("old-1"), saved.map { it.id })
            assertTrue(saved.single().isBookmarked)
            assertEquals("9/1", saved.single().shortDateText, "ISO dates are shortened too")
            // No longer bookmarked: the kept copy is not shown.
            assertEquals(emptyList(), list(filter = PocketAnnouncementFilter.BOOKMARKED))
            // Old items never join the list the reminder check compares against.
            assertTrue(list().none { it.id == "old-1" })
            assertTrue(DemoCampus.sharedAnnouncements(clock).none { it.id == "old-1" })
        } finally {
            PocketLive.clearAll()
            PocketData.useDemo()
        }
    }

    @Test
    fun homeAnnouncementsCarryTheirBookmark() {
        val id = DemoCampus.sharedAnnouncements(clock).last().id
        val rows = DemoCampus.importantAnnouncements(
            clock, com.clhs.score.domain.overview.OverviewWidgetConfig.ListItems(3), unreadIds = emptySet(), bookmarkedIds = setOf(id),
        )
        assertTrue(rows.first { it.id == id }.isBookmarked)
    }

    @Test
    fun announcementsFilterByCategorySearchAndState() {
        val all = list()
        val category = PocketCampusStore.categories(clock)[1]
        assertTrue(list(category = category).all { it.category == category })
        assertTrue(list(category = category).size < all.size)
        assertTrue(list(query = "運動會").all { "運動會" in it.title })
        assertEquals(emptyList(), list(query = "不存在的關鍵字"))
        assertTrue(list(filter = PocketAnnouncementFilter.UNREAD).all { it.isUnread })
        val saved = all.last().id
        assertEquals(listOf(saved), list(filter = PocketAnnouncementFilter.BOOKMARKED, bookmarks = setOf(saved)).map { it.id })
        assertEquals(all.sortedByDescending { it.isPinned }.map { it.isPinned }, all.map { it.isPinned })
    }

    @Test
    fun monthGridIsWholeWeeksStartingMondayAndMarksToday() {
        val month = PocketCampusStore.month(clock, 2026, 11)
        assertEquals(0, month.days.size % 7)
        assertEquals("26", month.days.first().dayText) // Monday 26 October 2026
        assertEquals(30, month.days.count { it.inMonth })
        assertEquals("18", month.days.single { it.isToday }.dayText)
        assertTrue(PocketCampusStore.eventsOn(clock, "2026-11-18").isNotEmpty())
        assertEquals(31, PocketCampusStore.month(clock, 2026, 12).days.count { it.inMonth })
    }

    @Test
    fun recordsSummariseTheSelectedSemester() {
        val attendance = PocketCampusStore.attendance(0)
        assertEquals(3, attendance.rows.size)
        assertTrue(PocketCampusStore.attendance(1).rows.isEmpty())
        val merits = PocketCampusStore.merits(0)
        assertTrue(merits.rows.single { it.category == "警告" }.note.isNotEmpty())
    }
}

class PocketScheduleStoreTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)

    private fun week(offset: Int = 0, overrides: String = "[]") =
        PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, clock, "", offset, overrides)

    @Test
    fun semesterTimetableHasNoToday() {
        assertTrue(week().days.any { it.isToday })
        assertTrue(PocketScheduleStore.schedule(PocketScheduleScope.SEMESTER, clock, "", 0, "[]").days.none { it.isToday })
    }

    @Test
    fun aLessonWithOnlyItsTeacherChangedCanBeRestored() {
        val subject = week().cells.first { it.subject.isNotEmpty() }.originalSubject
        val edited = PocketScheduleStore.overridesJson(listOf(
            com.clhs.score.data.ScheduleSubjectOverride(subject, customTeacherName = "代課老師"),
        ))
        val cells = week(overrides = edited).cells.filter { it.originalSubject == subject }
        assertTrue(cells.isNotEmpty() && cells.all { it.isOverridden && it.subject == subject })
    }

    @Test
    fun overrideRenamesEveryLessonOfTheSubjectAndCanBeCleared() {
        val subject = week().cells.first { it.change == PocketCellChange.NONE }.subject
        val saved = PocketScheduleStore.setOverride("[]", subject, "自訂科目", "", "B101")
        val cells = week(overrides = saved).cells.filter { it.originalSubject == subject }
        assertTrue(cells.isNotEmpty() && cells.all { it.subject == "自訂科目" && it.classroom == "B101" && it.isOverridden })
        assertEquals(1, PocketScheduleStore.overrides(saved).size)
        assertEquals(emptyList(), PocketScheduleStore.overrides(PocketScheduleStore.clearOverride(saved, subject)))
        assertEquals(emptyList(), PocketScheduleStore.overrides(PocketScheduleStore.setOverride("[]", subject, subject, " ", "")))
    }

    @Test
    fun otherWeeksShiftDatesAndNeverShowACurrentLesson() {
        assertTrue(week().cells.any { it.status == PocketCellStatus.CURRENT })
        val next = week(offset = 1)
        assertEquals("11/23", next.days.first().dateText)
        assertTrue(next.cells.none { it.status != PocketCellStatus.NORMAL } && next.days.none { it.isToday })
    }

    @Test
    fun overLongOverridesAreClampedOnSaveAndDroppedOnReadInsteadOfThrowing() {
        val long = "課".repeat(200)
        val saved = PocketScheduleStore.setOverride("[]", "國語文", long, long, long)
        val entry = PocketScheduleStore.overrides(saved).single()
        assertEquals(PocketScheduleStore.maxOverrideLength(), entry.subject.length)
        assertEquals(PocketScheduleStore.maxOverrideLength(), entry.teacher.length)

        // A backup written elsewhere with an over-long value must not crash the schedule.
        val imported = """[{"originalSubjectName":"國語文","customSubjectName":"$long"},{"originalSubjectName":"數學","customSubjectName":"數甲"}]"""
        assertEquals(listOf("數學"), PocketScheduleStore.overrides(imported).map { it.original })
        PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, PocketClock(2026, 11, 18, 3, 635), "", 0, imported)
    }
}
