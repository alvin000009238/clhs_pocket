package com.clhs.score.domain.overview

import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolCalendarEvent
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class OverviewListSelectionTest {
    @Test fun eachPriorityCanLeadAndRemainingPrioritiesBreakTies() {
        val unread = announcement("unread")
        val pinned = announcement("pinned", pinned = true)
        val saved = announcement("saved")
        val both = announcement("both", pinned = true)
        val state = OverviewState(announcements = listOf(pinned, saved, unread, both, announcement("ordinary")))
        val bookmarks = listOf(AnnouncementBookmark.from(saved))
        val expected = mapOf(
            listOf(AnnouncementPriority.Unread, AnnouncementPriority.Pinned, AnnouncementPriority.Bookmarked) to listOf("both", "unread", "pinned", "saved"),
            listOf(AnnouncementPriority.Unread, AnnouncementPriority.Bookmarked, AnnouncementPriority.Pinned) to listOf("both", "unread", "saved", "pinned"),
            listOf(AnnouncementPriority.Pinned, AnnouncementPriority.Unread, AnnouncementPriority.Bookmarked) to listOf("both", "pinned", "unread", "saved"),
            listOf(AnnouncementPriority.Pinned, AnnouncementPriority.Bookmarked, AnnouncementPriority.Unread) to listOf("both", "pinned", "saved", "unread"),
            listOf(AnnouncementPriority.Bookmarked, AnnouncementPriority.Unread, AnnouncementPriority.Pinned) to listOf("saved", "both", "unread", "pinned"),
            listOf(AnnouncementPriority.Bookmarked, AnnouncementPriority.Pinned, AnnouncementPriority.Unread) to listOf("saved", "both", "pinned", "unread"),
        )
        expected.forEach { (order, ids) ->
            assertEquals(ids, state.importantAnnouncements(OverviewWidgetConfig.ListItems(20, order), setOf("unread", "both"), bookmarks).map { (it.destination as OverviewDestination.Announcement).id })
        }
    }

    @Test fun bookmarksOutsideLatestPageAreIncludedAndLiveDuplicatesWin() {
        val live = announcement("shared", pinned = true).copy(title = "最新標題", date = "2026-10-09")
        val saved = listOf(AnnouncementBookmark.from(announcement("shared").copy(title = "舊標題")),
            AnnouncementBookmark.from(announcement("older")))
        val state = OverviewState(announcements = listOf(live, announcement("newer").copy(date = "2026/10/10")))
        val items = state.importantAnnouncements(OverviewWidgetConfig.ListItems(3), setOf("newer"), saved)
        assertEquals(listOf("newer", "shared", "older"), items.map { (it.destination as OverviewDestination.Announcement).id })
        assertEquals("最新標題", items[1].title)
        assertEquals(1, state.importantAnnouncements(OverviewWidgetConfig.ListItems(1), setOf("newer"), saved).size)
    }

    @Test fun readOrUnbookmarkedAnnouncementsLeaveCandidateListUnlessPinned() {
        val state = OverviewState(announcements = listOf(announcement("read"), announcement("pinned", true)))
        assertEquals(2, state.importantAnnouncements(OverviewWidgetConfig.ListItems(), setOf("read"), emptyList()).size)
        assertEquals(listOf("pinned"), state.importantAnnouncements(OverviewWidgetConfig.ListItems(), emptySet(), emptyList()).map { it.title })
    }

    @Test fun durationFilterUsesFullDurationExclusiveEndAndRunsBeforeLimit() {
        val start = LocalDateTime.parse("2026-10-01T00:00:00")
        val events = listOf(
            SchoolCalendarEvent("month", "整月活動", start, start.plusDays(31), true),
            SchoolCalendarEvent("week", "一週活動", start, start.plusDays(7), true),
            SchoolCalendarEvent("over", "超過一週", start, start.plusDays(7).plusMinutes(1), false),
            SchoolCalendarEvent("day", "一天活動", start.plusDays(7), start.plusDays(8), true),
        )
        val state = OverviewState(calendarEvents = events, items = calendarOverviewItems(
            com.clhs.score.data.SchoolCalendarSnapshot(com.clhs.score.data.SchoolCalendarFeed(events), java.time.Instant.EPOCH), start.plusDays(5)))
        assertEquals(listOf("一週活動", "一天活動"), state.recentEvents(OverviewWidgetConfig.ListItems(2, maxEventDurationDays = 7)).map { it.title })
        assertEquals(listOf("一天活動"), state.recentEvents(OverviewWidgetConfig.ListItems(2, maxEventDurationDays = 1)).map { it.title })
        assertEquals(4, state.recentEvents(OverviewWidgetConfig.ListItems(20)).size)
    }

    @Test fun historicalListConfigKeepsDefaultsAndRejectsInvalidNewSettings() {
        val widget = OverviewWidgetInstance.create("announcements").copy(configuration = """{"type":"list","limit":2}""")
        assertEquals(OverviewWidgetConfig.ListItems(2), widget.config())
        val config = OverviewWidgetConfig.ListItems(5, AnnouncementPriority.entries.reversed(), 3)
        assertEquals(config, widget.withConfig(config).config())
        assertEquals(config, OverviewLayoutJson.decodeFromString<OverviewWidgetConfig>(OverviewLayoutJson.encodeToString<OverviewWidgetConfig>(config)))
        assertNull(widget.withConfig(config.copy(announcementPriority = listOf(AnnouncementPriority.Unread))).config())
        assertNull(widget.withConfig(config.copy(announcementPriority = List(3) { AnnouncementPriority.Unread })).config())
        assertNull(widget.withConfig(config.copy(maxEventDurationDays = 0)).config())
    }

    private fun announcement(id: String, pinned: Boolean = false) =
        SchoolAnnouncement(id, id, "2026-10-08", "general", "教務處", "", pinned, "content")
}
