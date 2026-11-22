package com.clhs.score.shared.app

import com.clhs.score.data.SchoolAnnouncement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PocketAnnouncementFeedTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)
    private val feed = PocketAnnouncementFeed

    @Test
    fun theDemoArchiveSpansSeveralPagesThatJoinWithoutDuplicates() {
        var page = feed.demoPage(clock, 0, "", "-1", "-1")
        assertTrue(page.totalPages >= 4, "infinite scrolling needs several demo pages")
        var all = page.announcements
        while (feed.hasMore(page)) {
            page = feed.demoPage(clock, page.pageIndex + 1, "", "-1", "-1")
            all = feed.append(all, page.announcements + all.take(1))
        }
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(page.totalPages - 1, page.pageIndex)
        assertTrue(all.first().isPinned, "pinned notices lead the first page")
        assertNotNull(PocketCampusStore.announcementDetail(clock, all.last().id), "a later page opens its detail")
    }

    @Test
    fun keywordUnitAndCategoryFiltersNarrowThePages() {
        val unit = feed.demoFilterOptions().units.first { it.name == "教學組" }
        val byUnit = feed.demoPage(clock, 0, "", unit.id, "-1").announcements
        assertTrue(byUnit.isNotEmpty() && byUnit.all { it.unit == "教學組" })
        val category = feed.demoFilterOptions().categories.first { it.name == "考試" }
        assertTrue(feed.demoPage(clock, 0, "", "-1", category.id).announcements.all { it.category == "考試" })
        val searched = feed.demoPage(clock, 0, "  段考 ", "-1", "-1")
        assertTrue(searched.announcements.isNotEmpty() && searched.announcements.all { "段考" in it.title })
        assertEquals(0, feed.demoPage(clock, 0, "不存在的字", "-1", "-1").announcements.size)
        assertEquals(100, feed.normalizedQuery("字".repeat(150)).length)
        assertTrue(feed.isValidFilterId("-1") && feed.isValidFilterId("101"))
        assertFalse(feed.isValidFilterId("1;drop") || feed.isValidFilterId(""))
    }

    @Test
    fun bookmarksFromLaterLivePagesAreKeptForSyncing() {
        val later = SchoolAnnouncement("p3-1", "第三頁公告", "2026/09/01", "公告", "教務處", "", false, "")
        PocketData.useLive()
        try {
            PocketLive.putAnnouncements(DemoCampus.demoAnnouncements(clock))
            PocketLive.putPagedAnnouncements(listOf(later))
            assertTrue(PocketLive.listedAnnouncements.any { it.id == "p3-1" })
            val saved = feed.bookmarks(clock, listOf(later), emptySet(), setOf("p3-1"))
            assertEquals(listOf("p3-1"), saved.map { it.id })
            PocketLive.clearAll()
            assertTrue(PocketLive.listedAnnouncements.isEmpty())
        } finally {
            PocketLive.clearAll()
            PocketData.useDemo()
        }
    }

    @Test
    fun calendarSearchMatchesUpcomingEventsAndDetailsDescribeThem() {
        val results = feed.searchEvents(clock, "段考")
        assertEquals(listOf("第二次段考"), results.map { it.title })
        assertTrue(feed.searchEvents(clock, "").size > 3)
        val timed = assertNotNull(feed.eventDetail(clock, "demo-e8"))
        assertEquals("10:10–11:00", timed.timeText)
        assertFalse(timed.isAllDay)
        assertEquals("全天", feed.eventDetail(clock, "demo-e3")?.timeText)
    }

    @Test
    fun anEventDetailAddsItsOwnHomeCountdown() {
        val layout = PocketDashboard.defaultLayout()
        val added = feed.addEventCountdown(layout, "demo-e4", clock)
        val before = PocketDashboard.instances(layout)
        val after = PocketDashboard.instances(added)
        assertEquals(before.size + 1, after.size)
        assertEquals("countdown", after.last().type)
        assertTrue("demo-e4" in added, "the new countdown follows the chosen event")
        assertEquals(before, after.dropLast(1), "existing widgets are untouched")
    }
}
