import Foundation
import Observation
@preconcurrency import PocketShared

/// Where the paged announcement list gets its pages: the school (through the shared repository and
/// its cache) or the demo archive.
@MainActor
protocol AnnouncementPageLoading {
    func cachedFirstPage() async -> SchoolAnnouncementPage?
    func page(_ index: Int, keyword: String, unitId: String, categoryId: String) async throws -> SchoolAnnouncementPage?
    func cachedFilters() async -> AnnouncementFilterOptions?
    func filters() async throws -> AnnouncementFilterOptions
    /// Every announcement the list holds, so a bookmark set on a later page can be saved.
    func remember(_ items: [SchoolAnnouncement])
}

/// The demo archive. Debug launch arguments exercise the other states: a one-time failure of the second
/// page (`-demoAnnouncementLoadMoreFailure YES`), a failing first page (`-demoAnnouncementFailure YES`)
/// and a slow network (`-demoAnnouncementDelayMs 4000`).
@MainActor
final class DemoAnnouncementLoader: AnnouncementPageLoading {
    private let clock: () -> PocketClock
    private var failNextLoadMore: Bool
    private let failsFirstPage: Bool
    private let delay: Duration

    init(
        clock: @escaping () -> PocketClock,
        failNextLoadMore: Bool = false,
        failsFirstPage: Bool = false,
        delay: Duration = .milliseconds(250)
    ) {
        self.clock = clock
        self.failNextLoadMore = failNextLoadMore
        self.failsFirstPage = failsFirstPage
        self.delay = delay
    }

    func cachedFirstPage() async -> SchoolAnnouncementPage? { nil }

    func page(_ index: Int, keyword: String, unitId: String, categoryId: String) async throws -> SchoolAnnouncementPage? {
        try await Task.sleep(for: delay)
        if index == 0, failsFirstPage { throw URLError(.notConnectedToInternet) }
        if index > 0, failNextLoadMore {
            failNextLoadMore = false
            throw URLError(.notConnectedToInternet)
        }
        return PocketAnnouncementFeed.shared.demoPage(
            clock: clock(), pageIndex: Int32(index), keyword: keyword, unitId: unitId, categoryId: categoryId
        )
    }

    func cachedFilters() async -> AnnouncementFilterOptions? { PocketAnnouncementFeed.shared.demoFilterOptions() }
    func filters() async throws -> AnnouncementFilterOptions { PocketAnnouncementFeed.shared.demoFilterOptions() }
    func remember(_ items: [SchoolAnnouncement]) {}
}

/// State of the school announcement list, following Android's `SchoolAnnouncementsViewModel`:
/// a cached first page shows at once, later pages are appended as the list nears its end, and a
/// failed refresh keeps what is already on screen.
@MainActor
@Observable
final class AnnouncementFeed {
    static let initialFailure = "暫時無法取得學校消息，請檢查網路後再試一次。"
    static let refreshFailure = "無法更新，暫時顯示已儲存的學校消息"
    static let loadMoreFailure = "無法載入更多消息，請稍後再試。"
    static let searchFailure = "暫時無法搜尋公告，請稍後再試一次。"
    /// Pages the unfiltered list may read, counting the first, to show announcements already known unread.
    static let unreadLookupMaxPages = 20

    private(set) var items: [SchoolAnnouncement] = []
    private(set) var pageIndex = 0
    private(set) var hasMore = false
    private(set) var isInitialLoading = true
    private(set) var isRefreshing = false
    private(set) var isLoadingMore = false
    private(set) var lastUpdatedAt: Date?
    private(set) var errorMessage: String?
    var noticeMessage: String?
    private(set) var loadMoreError: String?
    private(set) var searchQuery = ""
    private(set) var unitId = PocketAnnouncementFeed.shared.ALL_ID
    private(set) var categoryId = PocketAnnouncementFeed.shared.ALL_ID
    private(set) var filterOptions = AnnouncementFilterOptions(units: [], categories: [])
    private(set) var isLoadingFilters = false
    private(set) var filterLoadFailed = false

    var hasFilters: Bool { unitId != PocketAnnouncementFeed.shared.ALL_ID || categoryId != PocketAnnouncementFeed.shared.ALL_ID }

    @ObservationIgnored private let loader: AnnouncementPageLoading
    /// Current unread set; live mode first syncs it from the repository that registered the loaded pages.
    @ObservationIgnored private let unreadIds: () async -> Set<String>
    /// Bumped by every new query, so a page that answers an older one is dropped.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var started = false
    @ObservationIgnored private var searchTask: Task<Void, Never>?

    init(loader: AnnouncementPageLoading, unreadIds: @escaping () async -> Set<String> = { [] }) {
        self.loader = loader
        self.unreadIds = unreadIds
    }

    /// Unread announcements among `listedIds`, each counted once, so items revealed from later pages count.
    static func unreadCount(_ listedIds: [String], unreadIds: Set<String>) -> Int {
        unreadIds.intersection(listedIds).count
    }

    /// Android's list order: unread first, then pinned, otherwise the school's order. `unreadIds` is the
    /// unread set when the list last changed, so reading an announcement does not move rows under the reader.
    static func ordered(_ rows: [PocketAnnouncement], unreadIds: Set<String>) -> [PocketAnnouncement] {
        rows.enumerated().sorted { a, b in
            let aUnread = unreadIds.contains(a.element.id), bUnread = unreadIds.contains(b.element.id)
            if aUnread != bUnread { return aUnread }
            if a.element.isPinned != b.element.isPinned { return a.element.isPinned }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// Cached content first, then the latest page and the filter options.
    func start() async {
        guard !started else { return }
        started = true
        if let cached = await loader.cachedFirstPage(), items.isEmpty {
            apply(cached, appending: false)
            isInitialLoading = false
        }
        async let filters: Void = loadFilters()
        await reload(showsRefreshing: !items.isEmpty)
        await filters
    }

    func refresh() async {
        await reload(showsRefreshing: true)
    }

    /// Clears the search and filters, then shows the newest announcements.
    func refreshLatest() async {
        searchTask?.cancel()
        searchQuery = ""
        unitId = PocketAnnouncementFeed.shared.ALL_ID
        categoryId = PocketAnnouncementFeed.shared.ALL_ID
        await reload(showsRefreshing: !items.isEmpty)
    }

    /// Debounced, like typing into the Android search field.
    func search(_ text: String, debounce: Duration = .milliseconds(400)) {
        let query = PocketAnnouncementFeed.shared.normalizedQuery(query: text)
        guard query != searchQuery else { return }
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(for: debounce)
            guard !Task.isCancelled else { return }
            searchQuery = query
            await reload(showsRefreshing: false)
        }
    }

    func setFilters(unitId: String, categoryId: String) async {
        let feed = PocketAnnouncementFeed.shared
        guard feed.isValidFilterId(id: unitId), feed.isValidFilterId(id: categoryId) else { return }
        guard unitId != self.unitId || categoryId != self.categoryId else { return }
        self.unitId = unitId
        self.categoryId = categoryId
        await reload(showsRefreshing: false)
    }

    func clearFilters() async {
        await setFilters(unitId: PocketAnnouncementFeed.shared.ALL_ID, categoryId: PocketAnnouncementFeed.shared.ALL_ID)
    }

    var canLoadMore: Bool {
        hasMore && !isInitialLoading && !isRefreshing && !isLoadingMore && errorMessage == nil && loadMoreError == nil
    }

    /// Next page; a failure leaves a retry in the footer instead of looping.
    func loadMore(retrying: Bool = false) async {
        if retrying { loadMoreError = nil }
        guard canLoadMore else { return }
        let current = generation
        isLoadingMore = true
        loadMoreError = nil
        do {
            let page = try await loader.page(pageIndex + 1, keyword: searchQuery, unitId: unitId, categoryId: categoryId)
            guard current == generation, let page else { isLoadingMore = false; return }
            apply(page, appending: true)
        } catch {
            guard current == generation, !(error is CancellationError) else { isLoadingMore = false; return }
            loadMoreError = Self.loadMoreFailure
        }
        isLoadingMore = false
    }

    func loadFilters() async {
        if let cached = await loader.cachedFilters(), filterOptions.units.isEmpty { filterOptions = cached }
        isLoadingFilters = true
        filterLoadFailed = false
        do {
            filterOptions = try await loader.filters()
        } catch {
            filterLoadFailed = !(error is CancellationError)
        }
        isLoadingFilters = false
    }

    private func reload(showsRefreshing: Bool) async {
        generation += 1
        let current = generation
        let keepsItems = showsRefreshing && !items.isEmpty
        if keepsItems { isRefreshing = true } else { isInitialLoading = true; items = [] }
        errorMessage = nil
        loadMoreError = nil
        isLoadingMore = false
        do {
            let page = try await loader.page(0, keyword: searchQuery, unitId: unitId, categoryId: categoryId)
            guard current == generation else { return }
            if let page { apply(page, appending: false) }
            if searchQuery.isEmpty, !hasFilters {
                isRefreshing = false
                isInitialLoading = false
                await revealUnread(generation: current)
            }
        } catch {
            guard current == generation, !(error is CancellationError) else { return }
            if keepsItems {
                noticeMessage = Self.refreshFailure
            } else {
                errorMessage = searchQuery.isEmpty && !hasFilters ? Self.initialFailure : Self.searchFailure
            }
        }
        if current == generation {
            isRefreshing = false
            isInitialLoading = false
        }
    }

    /// Reads later pages of the unfiltered list until every announcement that was unread when the first
    /// page arrived is listed, the archive ends, or `unreadLookupMaxPages` pages are loaded. Unread
    /// announcements found on the way do not extend the lookup; a failure keeps the list and offers the
    /// footer retry, and loading further pages by scrolling still works afterwards.
    private func revealUnread(generation current: Int) async {
        let snapshot = await unreadIds()
        guard current == generation, !snapshot.isEmpty else { return }
        isLoadingMore = true
        defer { if current == generation { isLoadingMore = false } }
        for _ in 1..<Self.unreadLookupMaxPages {
            guard current == generation, hasMore, !snapshot.isSubset(of: Set(items.map(\.id))) else { return }
            let all = PocketAnnouncementFeed.shared.ALL_ID
            do {
                guard let page = try await loader.page(pageIndex + 1, keyword: "", unitId: all, categoryId: all),
                      current == generation else { return }
                apply(page, appending: true)
                if page.announcements.isEmpty { return }
            } catch {
                if current == generation, !(error is CancellationError) { loadMoreError = Self.loadMoreFailure }
                return
            }
        }
    }

    private func apply(_ page: SchoolAnnouncementPage, appending: Bool) {
        let feed = PocketAnnouncementFeed.shared
        items = appending ? feed.append(existing: items, incoming: page.announcements) : page.announcements
        pageIndex = Int(page.pageIndex)
        hasMore = feed.hasMore(page: page)
        lastUpdatedAt = Date(timeIntervalSince1970: TimeInterval(feed.fetchedAtMillis(page: page)) / 1000)
        loader.remember(items)
    }
}
