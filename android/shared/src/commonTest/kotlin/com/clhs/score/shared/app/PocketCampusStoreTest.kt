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
