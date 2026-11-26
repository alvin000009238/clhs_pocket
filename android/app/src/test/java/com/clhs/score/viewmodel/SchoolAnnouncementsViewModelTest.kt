package com.clhs.score.viewmodel

import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.AnnouncementFilterOptions
import com.clhs.score.data.AnnouncementCategory
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.data.SchoolAnnouncementPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SchoolAnnouncementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unreadBeyondPinnedFirstPageIsLoadedAndSortedAheadWithoutScrolling() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val pins = (1..20).map { announcement("pin-$it").copy(isPinned = true) }
        val unread = announcement("unread")
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls += index
                if (index == 0) page(*pins.toTypedArray(), totalPages = 50)
                else page(unread, pageIndex = index, totalPages = 50)
            },
            loadUnreadIds = { setOf(unread.id) },
        )
        runCurrent()

        assertEquals(listOf(0, 1), calls)
        val displayed = com.clhs.score.ui.announcements.orderAnnouncements(viewModel.uiState.value.announcements, setOf(unread.id))
        assertEquals(unread, displayed.first())
        assertTrue(viewModel.uiState.value.hasMore)
        assertFalse(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun pinnedOnlyPagesLoadThroughFirstOrdinaryAnnouncementWithNoUnread() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls += index
                page(announcement("page-$index").copy(isPinned = index < 2), pageIndex = index, totalPages = 50)
            },
        )
        runCurrent()

        assertEquals(listOf(0, 1, 2), calls)
        assertEquals(listOf("page-0", "page-1", "page-2"), viewModel.uiState.value.announcements.map { it.id })
        assertTrue(viewModel.uiState.value.hasMore)
    }

    @Test
    fun unreadLookupIsBoundedAndDoesNotChaseAnnouncementsDiscoveredDuringPaging() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        var unreadIds = setOf("missing")
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls += index
                unreadIds = unreadIds + "discovered-$index"
                page(announcement("page-$index"), pageIndex = index, totalPages = 100)
            },
            loadUnreadIds = { unreadIds },
        )
        runCurrent()
        assertEquals((0..19).toList(), calls)
        assertTrue(viewModel.uiState.value.hasMore)
        viewModel.loadMore()
        runCurrent()
        assertEquals(20, calls.last())
    }

    @Test
    fun visibleUnreadDoesNotFetchExtraPages() = runTest(dispatcher) {
        val calls = mutableListOf<Int>()
        SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls += index
                page(announcement("visible"), totalPages = 50)
            },
            loadUnreadIds = { setOf("visible") },
        )
        runCurrent()
        assertEquals(listOf(0), calls)
    }

    @Test
    fun unreadLookupStopsOnceOriginalSnapshotIsVisible() = runTest(dispatcher) {
        var unreadIds = setOf("target")
        val calls = mutableListOf<Int>()
        SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls += index
                if (index == 0) page(announcement("pin").copy(isPinned = true), totalPages = 50)
                else {
                    unreadIds = unreadIds + "not-yet-loaded"
                    page(announcement("target"), pageIndex = index, totalPages = 50)
                }
            },
            loadUnreadIds = { unreadIds },
        )
        runCurrent()
        assertEquals(listOf(0, 1), calls)
    }

    @Test
    fun filteredResultsCancelUnreadLookupAndNeverFetchUnfilteredPages() = runTest(dispatcher) {
        var cancelled = false
        val calls = mutableListOf<Pair<Int, String>>()
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, unit, _ ->
                calls += index to unit
                if (index == 1) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                page(announcement(unit), totalPages = 50)
            },
            loadUnreadIds = { setOf("missing") },
        )
        runCurrent()
        viewModel.loadMore()
        viewModel.setFilters("425", "-1")
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf(0 to "-1", 1 to "-1", 0 to "425"), calls)
        assertEquals("425", viewModel.uiState.value.announcements.single().id)
        viewModel.search("升學")
        runCurrent()
        assertEquals(0 to "425", calls.last())
        assertEquals(4, calls.size)
    }

    @Test
    fun unreadLookupFailureKeepsFirstPageAndManualPagingCanRetry() = runTest(dispatcher) {
        var failed = true
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                if (index > 0 && failed) throw IOException("offline")
                page(announcement("page-$index"), pageIndex = index, totalPages = 2)
            },
            loadUnreadIds = { setOf("page-1") },
        )
        runCurrent()
        assertEquals(listOf("page-0"), viewModel.uiState.value.announcements.map { it.id })
        assertEquals("無法載入更多消息，請稍後再試。", viewModel.uiState.value.loadMoreError)
        assertNull(viewModel.uiState.value.errorMessage)
        failed = false
        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf("page-0", "page-1"), viewModel.uiState.value.announcements.map { it.id })
        assertNull(viewModel.uiState.value.loadMoreError)
    }

    @Test
    fun filtersPreserveKeywordAcrossPagingRefreshAndClearWithoutUsingDefaultCache() = runTest(dispatcher) {
        val calls = mutableListOf<List<String>>()
        var cacheCalls = 0
        var cancelled = false
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { cacheCalls++; page(announcement("cached")) },
            loadPage = { index, keyword, unit, category ->
                calls += listOf(index.toString(), keyword, unit, category)
                if (index == 1 && unit == "425") {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                page(announcement("$keyword-$unit-$category"), pageIndex = index, totalPages = 2)
            },
        )
        runCurrent()
        viewModel.search("升學")
        runCurrent()
        viewModel.setFilters("425", "12")
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        viewModel.setFilters("50", "12")
        runCurrent()
        assertTrue(cancelled)
        viewModel.loadMore()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        assertEquals(listOf("0", "升學", "50", "12"), calls.last())
        assertTrue(calls.contains(listOf("1", "升學", "50", "12")))
        viewModel.clearSearch()
        runCurrent()
        assertEquals(listOf("0", "", "50", "12"), calls.last())
        assertEquals(1, cacheCalls)
        viewModel.setFilters("-1", "-1")
        runCurrent()
        assertEquals(2, cacheCalls)
        assertFalse(viewModel.uiState.value.hasFilters)
    }

    @Test
    fun notificationRefreshReplacesFilteredResultsWithLatestFeed() = runTest(dispatcher) {
        var latest = "initial"
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { _, keyword, unit, category ->
                if (keyword.isEmpty() && unit == "-1" && category == "-1") page(announcement(latest))
                else page(announcement("filtered"))
            },
        )
        runCurrent()
        viewModel.search("升學")
        viewModel.setFilters("425", "12")
        runCurrent()
        assertEquals("filtered", viewModel.uiState.value.announcements.single().id)

        latest = "notification"
        viewModel.refreshLatest()
        runCurrent()

        assertFalse(viewModel.uiState.value.hasFilters)
        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals("notification", viewModel.uiState.value.announcements.single().id)
    }

    @Test
    fun filterOptionsKeepCacheOnFailureAndRefreshOnRetry() = runTest(dispatcher) {
        val cached = AnnouncementFilterOptions(
            units = listOf(AnnouncementUnit("-1", "全部"), AnnouncementUnit("425", "首頁")),
            categories = listOf(AnnouncementCategory("-1", "全部"), AnnouncementCategory("12", "升學資訊")),
        )
        var fail = true
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { _, _, _, _ -> page() },
            loadCachedFilters = { cached },
            loadFilters = { if (fail) throw IOException("private response") else cached },
        )
        viewModel.refreshFilters()
        runCurrent()
        assertEquals(cached, viewModel.uiState.value.filterOptions)
        assertTrue(viewModel.uiState.value.filterLoadFailed)
        assertFalse(viewModel.uiState.value.isLoadingFilters)
        fail = false
        viewModel.refreshFilters()
        runCurrent()
        assertEquals(cached, viewModel.uiState.value.filterOptions)
        assertFalse(viewModel.uiState.value.filterLoadFailed)
    }

    @Test
    fun failedUpdateKeepsCachedMessagesAndShowsNotice() = runTest(dispatcher) {
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { page(announcement("cached"), totalPages = 2) },
            loadPage = { _, _, _, _ -> throw IOException("offline") },
        )

        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("cached"), state.announcements.map(SchoolAnnouncement::id))
        assertEquals("無法更新，暫時顯示已儲存的學校消息", state.noticeMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isInitialLoading)
    }

    @Test
    fun loadMoreAppendsInServerOrderAndRemovesDuplicateIds() = runTest(dispatcher) {
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                if (index == 0) {
                    page(announcement("first"), announcement("duplicate"), totalPages = 2)
                } else {
                    page(announcement("duplicate"), announcement("last"), pageIndex = 1, totalPages = 2)
                }
            },
        )
        runCurrent()

        viewModel.loadMore()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("first", "duplicate", "last"), state.announcements.map(SchoolAnnouncement::id))
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
    }

    @Test
    fun loadMoreIgnoresRepeatedTriggerWhileRequestIsInFlight() = runTest(dispatcher) {
        var calls = 0
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                calls++
                if (index == 0) page(announcement("first"), totalPages = 2) else awaitCancellation()
            },
        )
        runCurrent()

        viewModel.loadMore()
        runCurrent()
        viewModel.loadMore()
        runCurrent()

        assertEquals(2, calls)
        assertTrue(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun firstLoadFailureShowsFixedActionableError() = runTest(dispatcher) {
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { _, _, _, _ -> throw IOException("server response with secret details") },
        )

        runCurrent()

        assertTrue(viewModel.uiState.value.errorMessage?.contains("請檢查網路") == true)
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains("secret"))
    }

    @Test
    fun loadMoreFailureKeepsExistingMessagesAndShowsInlineRetry() = runTest(dispatcher) {
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, _, _, _ ->
                if (index == 0) page(announcement("first"), totalPages = 2)
                else throw IOException("offline")
            },
        )
        runCurrent()

        viewModel.loadMore()
        runCurrent()

        assertEquals(listOf("first"), viewModel.uiState.value.announcements.map(SchoolAnnouncement::id))
        assertEquals("無法載入更多消息，請稍後再試。", viewModel.uiState.value.loadMoreError)
        assertFalse(viewModel.uiState.value.isLoadingMore)
    }

    @Test
    fun detailFailureUsesFixedMessageWithoutLeakingException() = runTest(dispatcher) {
        val viewModel = SchoolAnnouncementDetailViewModel {
            throw IOException("private server response")
        }

        runCurrent()

        assertTrue(viewModel.uiState.value.errorMessage?.contains("請檢查網路") == true)
        assertFalse(viewModel.uiState.value.errorMessage.orEmpty().contains("private"))
    }

    @Test
    fun searchTrimsKeywordAndUsesItForRefreshAndPagination() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<Int, String>>()
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { index, keyword, _, _ ->
                calls += index to keyword
                if (keyword.isEmpty()) {
                    page(announcement("default"))
                } else {
                    page(
                        announcement("search-$index"),
                        pageIndex = index,
                        totalPages = 2,
                    )
                }
            },
        )
        runCurrent()

        viewModel.search("  模擬考  ")
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        viewModel.refresh()
        runCurrent()

        assertEquals("模擬考", viewModel.uiState.value.searchQuery)
        assertEquals(listOf(0 to "", 0 to "模擬考", 1 to "模擬考", 0 to "模擬考"), calls)
    }

    @Test
    fun clearingSearchRestoresDefaultCacheAndNetworkFeed() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { page(announcement("cached")) },
            loadPage = { _, keyword, _, _ ->
                calls += keyword
                page(announcement(if (keyword.isEmpty()) "default" else "search"))
            },
        )
        runCurrent()

        viewModel.search("模擬考")
        runCurrent()
        viewModel.clearSearch()
        runCurrent()

        assertEquals("", viewModel.uiState.value.searchQuery)
        assertEquals(listOf("", "模擬考", ""), calls)
        assertEquals(listOf("default"), viewModel.uiState.value.announcements.map(SchoolAnnouncement::id))
    }

    @Test
    fun newSearchCancelsThePreviousSearchAndLimitsTheKeyword() = runTest(dispatcher) {
        var firstSearchCancelled = false
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { _, keyword, _, _ ->
                when (keyword) {
                    "first" -> try {
                        awaitCancellation()
                    } finally {
                        firstSearchCancelled = true
                    }
                    else -> page(announcement(keyword.ifEmpty { "default" }))
                }
            },
        )
        runCurrent()

        viewModel.search("first")
        runCurrent()
        viewModel.search("x".repeat(101))
        runCurrent()

        assertTrue(firstSearchCancelled)
        assertEquals(100, viewModel.uiState.value.searchQuery.length)
        assertEquals(listOf("x".repeat(100)), viewModel.uiState.value.announcements.map(SchoolAnnouncement::id))
    }

    @Test
    fun emptySearchResultAndRefreshFailureKeepSafeState() = runTest(dispatcher) {
        var failRefresh = false
        val viewModel = SchoolAnnouncementsViewModel(
            loadCached = { null },
            loadPage = { _, keyword, _, _ ->
                if (failRefresh) throw IOException("private response")
                if (keyword == "找不到") page() else page(announcement(keyword.ifEmpty { "default" }))
            },
        )
        runCurrent()

        viewModel.search("找不到")
        runCurrent()
        assertTrue(viewModel.uiState.value.announcements.isEmpty())
        assertEquals("找不到", viewModel.uiState.value.searchQuery)

        viewModel.search("模擬考")
        runCurrent()
        failRefresh = true
        viewModel.refresh()
        runCurrent()

        assertEquals(listOf("模擬考"), viewModel.uiState.value.announcements.map(SchoolAnnouncement::id))
        assertEquals("無法更新，暫時顯示已儲存的學校消息", viewModel.uiState.value.noticeMessage)
        assertFalse(viewModel.uiState.value.noticeMessage.orEmpty().contains("private"))
    }

    private fun announcement(id: String) = SchoolAnnouncement(
        id = id,
        title = id,
        date = "2026/08/09",
        category = "公告",
        unit = "教務處",
        issuer = "承辦人",
        isPinned = false,
        contentType = "content",
    )

    private fun page(
        vararg announcements: SchoolAnnouncement,
        pageIndex: Int = 0,
        totalPages: Int = 1,
    ) = SchoolAnnouncementPage(
        announcements = announcements.toList(),
        pageIndex = pageIndex,
        totalPages = totalPages,
        fetchedAt = Instant.parse("2026-08-09T00:00:00Z"),
    )
}
