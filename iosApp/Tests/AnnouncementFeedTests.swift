import XCTest
@testable import CLHSPocket
@preconcurrency import PocketShared

/// The demo archive behind a switch that makes the next request fail, like a dropped connection.
@MainActor
private final class FlakyLoader: AnnouncementPageLoading {
    var failNext = false
    var cached: SchoolAnnouncementPage?
    private(set) var requests: [(page: Int, keyword: String, unit: String, category: String)] = []
    private(set) var remembered: [SchoolAnnouncement] = []
    let clock = PocketClock(year: 2026, month: 11, day: 18, dayOfWeek: 3, minuteOfDay: 10 * 60 + 35)

    func cachedFirstPage() async -> SchoolAnnouncementPage? { cached }

    func page(_ index: Int, keyword: String, unitId: String, categoryId: String) async throws -> SchoolAnnouncementPage? {
        requests.append((index, keyword, unitId, categoryId))
        if failNext {
            failNext = false
            throw URLError(.notConnectedToInternet)
        }
        return PocketAnnouncementFeed.shared.demoPage(clock: clock, pageIndex: Int32(index), keyword: keyword, unitId: unitId, categoryId: categoryId)
    }

    func cachedFilters() async -> AnnouncementFilterOptions? { nil }
    func filters() async throws -> AnnouncementFilterOptions { PocketAnnouncementFeed.shared.demoFilterOptions() }
    func remember(_ items: [SchoolAnnouncement]) { remembered = items }
}

/// A long archive of generated pages, with an optional failing page.
@MainActor
private final class EndlessLoader: AnnouncementPageLoading {
    var failAt: Int?
    let totalPages: Int
    private(set) var requests: [Int] = []
    private let fetchedAt = PocketAnnouncementFeed.shared.demoPage(
        clock: PocketClock(year: 2026, month: 11, day: 18, dayOfWeek: 3, minuteOfDay: 600), pageIndex: 0, keyword: "", unitId: "-1", categoryId: "-1"
    ).fetchedAt

    init(totalPages: Int = 100) { self.totalPages = totalPages }

    func cachedFirstPage() async -> SchoolAnnouncementPage? { nil }

    func page(_ index: Int, keyword: String, unitId: String, categoryId: String) async throws -> SchoolAnnouncementPage? {
        requests.append(index)
        if index == failAt {
            failAt = nil
            throw URLError(.notConnectedToInternet)
        }
        let items = (0..<10).map { row in
            SchoolAnnouncement(
                id: "p\(index)-\(row)", title: "公告 \(index)-\(row)", date: "2026/11/18", category: "公告",
                unit: "教務處", issuer: "", isPinned: false, contentType: "", externalUrl: nil
            )
        }
        return SchoolAnnouncementPage(announcements: items, pageIndex: Int32(index), totalPages: Int32(totalPages), fetchedAt: fetchedAt)
    }

    func cachedFilters() async -> AnnouncementFilterOptions? { nil }
    func filters() async throws -> AnnouncementFilterOptions { PocketAnnouncementFeed.shared.demoFilterOptions() }
    func remember(_ items: [SchoolAnnouncement]) {}
}

@MainActor
final class AnnouncementFeedTests: XCTestCase {
    func testUnfilteredStartLoadsPagesUntilKnownUnreadAnnouncementsAreListed() async {
        let loader = EndlessLoader()
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["p0-1", "p3-4"] })
        await feed.start()
        XCTAssertTrue(feed.items.contains { $0.id == "p3-4" }, "an unread announcement on a later page is listed")
        XCTAssertEqual(loader.requests, [0, 1, 2, 3], "the lookup stops once every unread announcement is listed")
        XCTAssertFalse(feed.isLoadingMore)
        XCTAssertNil(feed.loadMoreError)
        XCTAssertTrue(feed.canLoadMore, "scrolling still loads the pages after the lookup")
    }

    func testUnreadRegisteredAfterStartIsRevealedWhenTheUnfilteredListReturns() async {
        let loader = EndlessLoader()
        var registered: Set<String> = []
        var reads = 0
        let feed = AnnouncementFeed(loader: loader, unreadIds: {
            reads += 1
            return registered
        })
        await feed.start()
        await feed.setFilters(unitId: "101", categoryId: PocketAnnouncementFeed.shared.ALL_ID)
        // Browsing the unit registered an unread announcement that the unfiltered list has on page 4.
        registered = ["p4-2"]
        await feed.clearFilters()
        XCTAssertEqual(reads, 2, "the unread set is read again for each unfiltered first page")
        XCTAssertTrue(feed.items.contains { $0.id == "p4-2" })
    }

    func testUnreadLookupReadsAtMostTwentyPagesThenScrollingContinues() async {
        let loader = EndlessLoader()
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["not-on-any-page"] })
        await feed.start()
        XCTAssertEqual(loader.requests, Array(0..<AnnouncementFeed.unreadLookupMaxPages), "no unbounded archive crawl")
        XCTAssertTrue(feed.canLoadMore)
        await feed.loadMore()
        XCTAssertEqual(loader.requests.last, AnnouncementFeed.unreadLookupMaxPages, "later pages still load by scrolling")
    }

    func testUnreadLookupStopsAtTheEndOfTheArchive() async {
        let loader = EndlessLoader(totalPages: 3)
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["not-on-any-page"] })
        await feed.start()
        XCTAssertEqual(loader.requests, [0, 1, 2])
        XCTAssertFalse(feed.hasMore)
    }

    func testUnreadLookupFailureKeepsTheListAndOffersTheRetry() async {
        let loader = EndlessLoader()
        loader.failAt = 2
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["p5-0"] })
        await feed.start()
        XCTAssertEqual(feed.items.count, 20, "the pages read before the failure stay listed")
        XCTAssertEqual(feed.loadMoreError, AnnouncementFeed.loadMoreFailure)
        XCTAssertFalse(feed.isLoadingMore)
        XCTAssertNil(feed.errorMessage)
        await feed.loadMore(retrying: true)
        XCTAssertEqual(loader.requests.last, 2, "the footer retry asks for the failed page again")
        XCTAssertEqual(feed.items.count, 30)
    }

    func testFilteredAndSearchedListsSkipTheUnreadLookup() async {
        let loader = EndlessLoader()
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["p0-0"] })
        await feed.start()
        let before = loader.requests.count
        await feed.setFilters(unitId: "101", categoryId: PocketAnnouncementFeed.shared.ALL_ID)
        XCTAssertEqual(Array(loader.requests.dropFirst(before)), [0], "a filtered list reads only its first page")
        let unreadLater = AnnouncementFeed(loader: EndlessLoader(), unreadIds: { ["p4-0"] })
        await unreadLater.setFilters(unitId: "101", categoryId: PocketAnnouncementFeed.shared.ALL_ID)
        XCTAssertFalse(unreadLater.items.contains { $0.id == "p4-0" })
    }

    func testRefreshReportsANewLoadEvenWhenTheListIsUnchanged() async {
        let feed = AnnouncementFeed(loader: EndlessLoader())
        await feed.start()
        let loads = feed.listRevision
        let count = feed.items.count
        await feed.refresh()
        XCTAssertEqual(feed.items.count, count)
        XCTAssertGreaterThan(feed.listRevision, loads, "the list re-sorts after a refresh with the same items")
    }

    func testListReSortsAfterTheUnreadSyncThatFollowsTheFirstPage() async throws {
        final class Box { var feed: AnnouncementFeed?; var revisionAtSync: Int? }
        let box = Box()
        let feed = AnnouncementFeed(loader: EndlessLoader(), unreadIds: { @MainActor in
            box.revisionAtSync = box.feed?.listRevision
            return ["p0-0"]
        })
        box.feed = feed
        await feed.start()
        let atSync = try XCTUnwrap(box.revisionAtSync)
        XCTAssertGreaterThan(feed.listRevision, atSync, "the first page is re-sorted once the unread set is synced")
    }

    func testUnreadCountIncludesLaterPagesOnce() async {
        let loader = EndlessLoader()
        let feed = AnnouncementFeed(loader: loader, unreadIds: { ["p2-0"] })
        await feed.start()
        let listed = ["first-page"] + feed.items.map(\.id) + feed.items.prefix(3).map(\.id)
        XCTAssertEqual(AnnouncementFeed.unreadCount(listed, unreadIds: ["first-page", "p2-0", "elsewhere"]), 2,
                       "a revealed later-page item counts, duplicates and unlisted ids do not")
    }

    func testListShowsUnreadThenPinnedThenTheSchoolOrder() {
        func row(_ id: String, pinned: Bool = false, unread: Bool = false) -> PocketAnnouncement {
            PocketAnnouncement(id: id, title: id, dateText: "", shortDateText: "", unit: "", category: "",
                               isPinned: pinned, isUnread: unread, isBookmarked: false)
        }
        let rows = [row("a"), row("b", pinned: true), row("c", unread: true), row("d"), row("e", pinned: true, unread: true)]
        XCTAssertEqual(AnnouncementFeed.ordered(rows, unreadIds: ["c", "e"]).map(\.id), ["e", "c", "b", "a", "d"])
        XCTAssertEqual(AnnouncementFeed.ordered(rows, unreadIds: ["e"]).map(\.id), ["e", "b", "a", "c", "d"],
                       "the order follows the unread set it is given, not the rows' live read state")
    }

    func testScrollingLoadsEveryPageOnceAndStopsAtTheEnd() async {
        let loader = FlakyLoader()
        let feed = AnnouncementFeed(loader: loader)
        await feed.start()
        XCTAssertEqual(feed.pageIndex, 0)
        XCTAssertTrue(feed.canLoadMore)
        var pages = 1
        while feed.canLoadMore, pages < 20 {
            await feed.loadMore()
            pages += 1
        }
        XCTAssertFalse(feed.hasMore)
        XCTAssertGreaterThanOrEqual(pages, 4, "the demo archive must exercise several pages")
        XCTAssertEqual(feed.items.count, Set(feed.items.map(\.id)).count)
        XCTAssertEqual(loader.requests.map(\.page), Array(0..<pages))
        XCTAssertEqual(loader.remembered.count, feed.items.count, "later pages stay known so their bookmarks can be saved")
        await feed.loadMore()
        XCTAssertEqual(loader.requests.count, pages, "the end of the list asks for nothing more")
    }

    func testFailedNextPageWaitsForRetryInsteadOfLooping() async {
        let loader = FlakyLoader()
        let feed = AnnouncementFeed(loader: loader)
        await feed.start()
        let firstPage = feed.items
        loader.failNext = true
        await feed.loadMore()
        XCTAssertEqual(feed.loadMoreError, AnnouncementFeed.loadMoreFailure)
        XCTAssertEqual(feed.items.map(\.id), firstPage.map(\.id), "a failed page keeps what is shown")
        XCTAssertFalse(feed.canLoadMore)
        await feed.loadMore()
        XCTAssertEqual(loader.requests.count, 2, "prefetching stops after a failure")
        await feed.loadMore(retrying: true)
        XCTAssertNil(feed.loadMoreError)
        XCTAssertEqual(feed.pageIndex, 1)
        XCTAssertGreaterThan(feed.items.count, firstPage.count)
    }

    func testRefreshFailureKeepsTheListButAFirstFailureShowsAnError() async {
        let loader = FlakyLoader()
        loader.failNext = true
        let feed = AnnouncementFeed(loader: loader)
        await feed.start()
        XCTAssertEqual(feed.errorMessage, AnnouncementFeed.initialFailure)
        XCTAssertTrue(feed.items.isEmpty)
        XCTAssertFalse(feed.canLoadMore)

        await feed.refresh()
        XCTAssertNil(feed.errorMessage)
        let shown = feed.items
        XCTAssertFalse(shown.isEmpty)
        loader.failNext = true
        await feed.refresh()
        XCTAssertEqual(feed.noticeMessage, AnnouncementFeed.refreshFailure)
        XCTAssertNil(feed.errorMessage)
        XCTAssertEqual(feed.items.map(\.id), shown.map(\.id))
    }

    func testFiltersAndSearchQueryTheSchoolFromTheFirstPage() async throws {
        let loader = FlakyLoader()
        let feed = AnnouncementFeed(loader: loader)
        await feed.start()
        await feed.setFilters(unitId: "101", categoryId: "-1")
        XCTAssertTrue(feed.hasFilters)
        XCTAssertEqual(loader.requests.last?.page, 0)
        XCTAssertEqual(loader.requests.last?.unit, "101")
        XCTAssertFalse(feed.items.isEmpty)
        XCTAssertTrue(feed.items.allSatisfy { $0.unit == "教學組" })

        await feed.setFilters(unitId: "<script>", categoryId: "-1")
        XCTAssertEqual(loader.requests.last?.unit, "101", "ids that are not numbers are refused, as on Android")

        feed.search("  " + String(repeating: "考", count: 150) + "  ", debounce: .zero)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(loader.requests.last?.keyword.count, 100, "the keyword is trimmed and capped at 100 characters")

        await feed.clearFilters()
        feed.search("", debounce: .zero)
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertFalse(feed.hasFilters)
        XCTAssertEqual(feed.searchQuery, "")
    }

    func testCachedFirstPageShowsBeforeTheSchoolAnswers() async {
        let loader = FlakyLoader()
        loader.cached = PocketAnnouncementFeed.shared.demoPage(clock: loader.clock, pageIndex: 0, keyword: "", unitId: "-1", categoryId: "-1")
        loader.failNext = true
        let feed = AnnouncementFeed(loader: loader)
        await feed.start()
        XCTAssertFalse(feed.items.isEmpty, "the saved page stays when the school does not answer")
        XCTAssertNil(feed.errorMessage)
        XCTAssertEqual(feed.noticeMessage, AnnouncementFeed.refreshFailure)
    }
}

@MainActor
final class AnnouncementReminderSettingsTests: XCTestCase {
    func testCheckIsDueOnlyAfterTheChosenInterval() {
        let settings = PlatformSettings(defaults: UserDefaults(suiteName: "reminder-\(UUID().uuidString)")!)
        XCTAssertEqual(settings.announcementIntervalMinutes, 60)
        XCTAssertEqual(settings.announcementUnitIds, ["-1"])
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(settings.announcementCheckDue(at: now), "never checked")
        settings.announcementLastCheck = now
        settings.announcementIntervalMinutes = 180
        XCTAssertFalse(settings.announcementCheckDue(at: now.addingTimeInterval(60 * 60)))
        XCTAssertTrue(settings.announcementCheckDue(at: now.addingTimeInterval(3 * 60 * 60)))
        XCTAssertTrue(settings.announcementCheckDue(at: now.addingTimeInterval(-60)), "a clock set back does not stop checks")
    }
}
