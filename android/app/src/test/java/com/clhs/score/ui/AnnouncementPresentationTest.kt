package com.clhs.score.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.notifications.NotificationAction
import com.clhs.score.reminders.announcementNotificationAction
import com.clhs.score.ui.announcements.orderAnnouncements
import com.clhs.score.ui.announcements.shouldPrefetchAnnouncements
import com.clhs.score.ui.announcements.withAnnouncementLinks
import com.clhs.score.ui.announcements.AnnouncementPrefetchViewport
import com.clhs.score.ui.announcements.AnnouncementTopAnchor
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
    fun inlineArticleLinkOpensValidatedUrlAndDropsUnsafeLinks() {
        val article = buildAnnotatedString {
            append("公告連結 外部指令")
            addLink(LinkAnnotation.Url(" /notice "), 0, 4)
            addLink(LinkAnnotation.Url("javascript:alert(1)"), 5, length)
        }
        val opened = mutableListOf<String>()
        val rendered = article.withAnnouncementLinks { opened.add(it) }
        val range = rendered.getLinkAnnotations(0, rendered.length).single()
        val link = range.item as LinkAnnotation.Url

        assertEquals("公告連結", rendered.text.substring(range.start, range.end))
        checkNotNull(link.linkInteractionListener).onClick(link)
        assertEquals(listOf("https://www.clhs.tyc.edu.tw/notice"), opened)
    }

    @Test
    fun delayedUnreadInsertionKeepsIdleTopViewportAtTopOnce() {
        val anchor = AnnouncementTopAnchor()
        assertFalse(anchor.shouldKeepAtTop(setOf("pin"), null, isAtTop = true, isScrollInProgress = false))
        assertTrue(anchor.shouldKeepAtTop(setOf("pin", "unread"), "unread", isAtTop = true, isScrollInProgress = false))
        assertFalse(anchor.shouldKeepAtTop(setOf("pin", "unread"), "unread", isAtTop = true, isScrollInProgress = false))
    }

    @Test
    fun delayedUnreadInsertionPreservesScrolledOrMovingViewport() {
        for ((isAtTop, isScrolling) in listOf(false to false, true to true)) {
            val anchor = AnnouncementTopAnchor()
            anchor.shouldKeepAtTop(setOf("pin"), null, isAtTop = true, isScrollInProgress = false)
            assertFalse(anchor.shouldKeepAtTop(setOf("pin", "unread"), "unread", isAtTop, isScrolling))
            assertFalse(anchor.shouldKeepAtTop(setOf("pin", "unread"), "unread", isAtTop = true, isScrollInProgress = false))
        }
    }

    @Test
    fun readStateChangesAndReplacementResultsDoNotRequestTopAnchor() {
        val anchor = AnnouncementTopAnchor()
        assertFalse(anchor.shouldKeepAtTop(setOf("pin", "unread"), "unread", isAtTop = true, isScrollInProgress = false))
        assertFalse(anchor.shouldKeepAtTop(setOf("pin", "unread"), "pin", isAtTop = true, isScrollInProgress = false))
        assertFalse(anchor.shouldKeepAtTop(setOf("replacement"), "replacement", isAtTop = true, isScrollInProgress = false))
    }

    @Test
    fun reorderingPagesAboveTheSameViewportDoesNotRequestAnotherPage() {
        val viewport = AnnouncementPrefetchViewport()
        assertTrue(viewport.shouldRequest(listOf("18", "19", "20"), nearEnd = true))
        assertFalse(viewport.shouldRequest(listOf("19", "20"), nearEnd = true))
        assertFalse(viewport.shouldRequest(listOf("20", "18", "19"), nearEnd = true))
        assertFalse(viewport.shouldRequest(listOf("21", "22"), nearEnd = false))
        assertTrue(viewport.shouldRequest(listOf("18", "19", "20"), nearEnd = true))
    }

    @Test
    fun shortViewportCanFillWhenNewRowsActuallyBecomeVisible() {
        val viewport = AnnouncementPrefetchViewport()
        assertTrue(viewport.shouldRequest(listOf("1"), nearEnd = true, shortPage = 0))
        assertTrue(viewport.shouldRequest(listOf("1"), nearEnd = true, shortPage = 1))
        assertFalse(viewport.shouldRequest(listOf("1"), nearEnd = true, shortPage = 1))
        assertTrue(viewport.shouldRequest(listOf("1", "2"), nearEnd = true, shortPage = 2))
        assertFalse(viewport.shouldRequest(listOf("1", "2"), nearEnd = true, shortPage = 2))
    }

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
    fun bookmarkedReadAnnouncementPrecedesUnreadAndPinnedAnnouncements() {
        val announcements = listOf(pinned, unread, ordinary)
        val unreadIds = setOf(unread.id)
        assertEquals(
            listOf(ordinary, unread, pinned),
            orderAnnouncements(announcements, unreadIds, bookmarkedIds = setOf(ordinary.id)),
        )
        assertEquals(listOf(unread, pinned, ordinary), orderAnnouncements(announcements, unreadIds))
    }

    @Test
    fun bookmarksOutsideLoadedPagesAppearFirstAndUseFreshDataWhenLoaded() {
        val saved = AnnouncementBookmark.from(ordinary)
        val updated = ordinary.copy(title = "更新後的公告標題")
        val bookmarkedIds = setOf(saved.id)

        assertEquals(
            listOf(ordinary),
            orderAnnouncements(emptyList(), emptySet(), bookmarkedIds, bookmarkFallbacks = listOf(saved)),
        )
        assertEquals(
            listOf(updated, unread, pinned),
            orderAnnouncements(listOf(pinned, unread, updated), setOf(unread.id), bookmarkedIds, listOf(saved)),
        )
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
