package com.clhs.score.ui

import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.notifications.NotificationAction
import com.clhs.score.reminders.announcementNotificationAction
import com.clhs.score.ui.announcements.orderAnnouncements
import com.clhs.score.ui.announcements.shouldPrefetchAnnouncements
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementPresentationTest {
    private val ordinary = SchoolAnnouncement("1", "一般", "2026/09/09", "公告", "", "", false, "")
    private val pinned = ordinary.copy(id = "2", isPinned = true)
    private val unread = ordinary.copy(id = "3")

    @Test
    fun unreadPrecedesPinnedAndReadRestoresOrderIncludingAppendedPages() {
        val laterUnread = ordinary.copy(id = "4")
        val announcements = listOf(pinned, ordinary, unread, laterUnread)
        assertEquals(
            listOf(unread, laterUnread, pinned, ordinary),
            orderAnnouncements(announcements, setOf("3", "4")),
        )
        assertEquals(announcements, orderAnnouncements(announcements, emptySet()))
        assertEquals(listOf(unread, ordinary), orderAnnouncements(listOf(ordinary, unread), setOf("3")))
    }

    @Test
    fun singleDistinctNotificationOpensDetailAndMultipleOpenList() {
        val detail = NotificationAction.OpenAnnouncement(ordinary.id, ordinary.category)
        assertEquals(detail, announcementNotificationAction(listOf(ordinary)))
        assertEquals(detail, announcementNotificationAction(listOf(ordinary, ordinary)))
        assertEquals(NotificationAction.OpenAnnouncements, announcementNotificationAction(listOf(ordinary, unread)))
        assertEquals(NotificationAction.OpenAnnouncements, announcementNotificationAction(emptyList()))
    }

    @Test
    fun announcementPrefetchStartsNearTheEndAndHonorsLoadState() {
        val state = SchoolAnnouncementsUiState(
            isInitialLoading = false,
            hasMore = true,
        )

        assertFalse(shouldPrefetchAnnouncements(state, lastVisibleItemIndex = 17, totalItemsCount = 22))
        assertTrue(shouldPrefetchAnnouncements(state, lastVisibleItemIndex = 18, totalItemsCount = 22))
        assertFalse(shouldPrefetchAnnouncements(state.copy(hasMore = false), 21, 22))
        assertFalse(shouldPrefetchAnnouncements(state.copy(isLoadingMore = true), 21, 22))
        assertFalse(shouldPrefetchAnnouncements(state.copy(isRefreshing = true), 21, 22))
        assertFalse(shouldPrefetchAnnouncements(state.copy(loadMoreError = "offline"), 21, 22))
    }
}
