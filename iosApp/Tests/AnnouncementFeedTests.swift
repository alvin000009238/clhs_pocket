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

@MainActor
final class AnnouncementFeedTests: XCTestCase {
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
