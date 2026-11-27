import PocketShared
import SwiftUI

// MARK: - Loaders

/// The school's list through the shared repository: its cached first page, the filter options
/// from the home page, and the epoch guards of `LiveDataController`.
@MainActor
struct LiveAnnouncementLoader: AnnouncementPageLoading {
    let live: LiveDataController

    func cachedFirstPage() async -> SchoolAnnouncementPage? { await live.cachedAnnouncementPage() }

    func page(_ index: Int, keyword: String, unitId: String, categoryId: String) async throws -> SchoolAnnouncementPage? {
        do {
            return try await live.announcementPage(index, keyword: keyword, unitId: unitId, categoryId: categoryId)
        } catch let error as PocketError where error.kind == .cancelled {
            throw CancellationError()
        }
    }

    func cachedFilters() async -> AnnouncementFilterOptions? { try? await live.announcementFilters(cached: true) }
    func filters() async throws -> AnnouncementFilterOptions { try await live.announcementFilters(cached: false) }
    func remember(_ items: [SchoolAnnouncement]) { live.rememberPagedAnnouncements(items) }
}

extension AppModel {
    func makeAnnouncementLoader() -> AnnouncementPageLoading {
        if usesLiveData, let live { return LiveAnnouncementLoader(live: live) }
        let defaults = UserDefaults.standard
        let delay = defaults.integer(forKey: "demoAnnouncementDelayMs")
        return DemoAnnouncementLoader(
            clock: { [weak self] in self?.clock ?? PocketClock(year: 2026, month: 1, day: 1, dayOfWeek: 1, minuteOfDay: 0) },
            failNextLoadMore: defaults.bool(forKey: "demoAnnouncementLoadMoreFailure"),
            failsFirstPage: defaults.bool(forKey: "demoAnnouncementFailure"),
            delay: .milliseconds(delay > 0 ? delay : 250)
        )
    }

    /// Opens [route] on top of the tab that is showing.
    func push(_ route: PocketRoute) {
        path(for: selectedTab).wrappedValue.append(route)
    }
}

private let fetchedTimeFormat: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_Hant_TW")
    formatter.dateFormat = "M月d日 HH:mm"
    return formatter
}()

// MARK: - Announcements

struct AnnouncementListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var feed: AnnouncementFeed?
    @State private var query = ""
    @State private var filter: PocketAnnouncementFilter = .all
    @State private var showingFilters = false
    @State private var scrollRequest = 0
    @State private var animatesScroll = false
    @State private var rowInset = PocketMetrics.screenPadding
    @State private var browserTick = 0
    @State private var isAtTop = true
    @State private var isScrolling = false
    @State private var showsBackToTop = false
    @State private var listedIds: Set<String> = []
    /// Unread set the order was taken from; refreshed when the list changes, not when a row is read.
    @State private var orderingUnread: Set<String>?
    @State private var bookmarkTick = 0
    @State private var readAllTick = 0

    private static let topId = "announcements-top"

    var body: some View {
        let feed = feed ?? AnnouncementFeed(loader: model.makeAnnouncementLoader(), unreadIds: { [model] in
            await model.syncUnread()
            return model.unreadAnnouncementIds
        })
        let firstPage = PocketCampusStore.shared.announcements(
            clock: model.clock, category: "全部", query: "", filter: .all,
            unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
        )
        // With a unit or category filter the unread list holds only the filtered results; so does the count.
        let unread = AnnouncementFeed.unreadCount(
            (feed.hasFilters ? [] : firstPage.map(\.id)) + feed.items.map(\.id), unreadIds: model.unreadAnnouncementIds
        )
        let rows = rows(feed)
        ScrollViewReader { proxy in
            List {
                Group {
                    Picker("顯示", selection: $filter) {
                        Text("全部").tag(PocketAnnouncementFilter.all)
                        Text(unread > 0 ? "未讀 \(unread)" : "未讀").tag(PocketAnnouncementFilter.unread)
                        Text("收藏").tag(PocketAnnouncementFilter.bookmarked)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("announcement-filter")
                    .id(Self.topId)

                    statusChips(feed)
                    if let notice = feed.noticeMessage {
                        noticeBanner(notice, feed: feed)
                    }
                    content(feed, rows: rows)
                }
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: rowInset, bottom: 4, trailing: rowInset))
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 1)
            .onChange(of: scrollRequest) {
                withAnimation(reduceMotion || !animatesScroll ? nil : .smooth) { proxy.scrollTo(Self.topId, anchor: .top) }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width in
            rowInset = max(PocketMetrics.screenPadding, (width - PocketMetrics.regularContentWidth) / 2)
        }
        .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y + $0.contentInsets.top } action: { _, offset in
            isAtTop = offset < 8
            let shows = offset > 600
            if shows != showsBackToTop {
                withAnimation(reduceMotion ? nil : .snappy) { showsBackToTop = shows }
            }
        }
        .onScrollPhaseChange { _, phase in isScrolling = phase.isScrolling }
        // An unread announcement found on a later page moves to the top. Keep it in view only when the
        // reader is resting at the top; otherwise the scroll position keeps the row they were reading.
        // Every load or refresh re-sorts with the current unread set; reading a row alone does not.
        .onChange(of: feed.listRevision, initial: true) {
            let unread = model.unreadAnnouncementIds
            orderingUnread = unread
            let ids = Set(feed.items.map(\.id))
            defer { listedIds = ids }
            let listed = PocketAnnouncementFeed.shared.rows(items: feed.items, unreadIds: unread, bookmarkedIds: [])
            let first = AnnouncementFeed.ordered(listed, unreadIds: unread, bookmarkedIds: model.bookmarkedAnnouncementIds).first?.id
            guard filter == .all, let first, unread.contains(first),
                  !listedIds.isEmpty, !listedIds.contains(first), listedIds.isSubset(of: ids),
                  isAtTop, !isScrolling else { return }
            scrollToTop()
        }
        .overlay(alignment: .bottomTrailing) {
            if filter == .all, showsBackToTop {
                Button {
                    scrollToTop(animated: true)
                } label: {
                    Label("回到頂端", systemImage: "chevron.up")
                        .labelStyle(.iconOnly)
                        .font(.title3.weight(.semibold))
                        .frame(width: 52, height: 52)
                }
                .buttonStyle(.plain)
                .foregroundStyle(PocketColor.onSecondaryContainer)
                .background(PocketColor.secondaryContainer, in: .circle)
                .pocketGlass(in: Circle(), interactive: true)
                .shadow(color: .black.opacity(0.16), radius: 8, y: 2)
                .padding(20)
                .transition(.scale(scale: 0.6).combined(with: .opacity))
                .accessibilityIdentifier("announcements-top")
            }
        }
        .refreshable { await feed.refresh() }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜尋公告")
        .onChange(of: query) { _, text in feed.search(text) }
        .onChange(of: feed.searchQuery) { scrollToTop() }
        .onChange(of: feed.unitId) { scrollToTop() }
        .onChange(of: feed.categoryId) { scrollToTop() }
        .onChange(of: filter) { scrollToTop() }
        .sensoryFeedback(.selection, trigger: filter)
        .sensoryFeedback(.impact(weight: .light), trigger: bookmarkTick)
        .sensoryFeedback(.success, trigger: readAllTick)
        .sensoryFeedback(.impact(weight: .light), trigger: browserTick)
        .navigationTitle("公告")
        .navigationBarTitleDisplayMode(.large)
        .toolbar { ToolbarItem(placement: .topBarTrailing) { menu(feed, unread: unread) } }
        .sheet(isPresented: $showingFilters) {
            AnnouncementFilterSheet(feed: feed)
        }
        .task {
            if self.feed == nil { self.feed = feed }
            await feed.start()
        }
    }

    private func scrollToTop(animated: Bool = false) {
        animatesScroll = animated
        scrollRequest += 1
    }

    private func openInBrowser(_ item: PocketAnnouncement, feed: AnnouncementFeed) {
        let external = feed.items.first { $0.id == item.id }?.externalUrl
        guard let url = AnnouncementFeed.browserURL(id: item.id, externalUrl: external) else { return }
        browserTick += 1
        openURL(url)
    }

    private func toggleBookmark(_ item: PocketAnnouncement) {
        if item.isBookmarked { model.bookmarkedAnnouncementIds.remove(item.id) } else { model.bookmarkedAnnouncementIds.insert(item.id) }
        bookmarkTick += 1
    }

    private func menu(_ feed: AnnouncementFeed, unread: Int) -> some View {
        let bookmarks = model.bookmarkedAnnouncementIds.count
        return Menu {
            Button("全部已讀", systemImage: "checkmark.circle") {
                model.unreadAnnouncementIds = []
                orderingUnread = []
                readAllTick += 1
                scrollToTop()
            }
            .disabled(model.unreadAnnouncementIds.isEmpty)
            .accessibilityIdentifier("announcements-read-all")
            Button(bookmarks > 0 ? "已收藏公告（\(bookmarks)）" : "已收藏公告", systemImage: "bookmark") { filter = .bookmarked }
                .accessibilityIdentifier("announcements-bookmarks")
            Button(feed.hasFilters ? "篩選公告（已套用）" : "篩選公告", systemImage: "line.3.horizontal.decrease.circle") {
                showingFilters = true
            }
            .accessibilityIdentifier("announcements-filters")
            Button("公告更新提醒", systemImage: "bell.badge") { model.push(.announcementReminders) }
                .accessibilityIdentifier("announcements-reminders")
        } label: {
            Label("更多選項", systemImage: "ellipsis.circle")
        }
        .accessibilityIdentifier("announcements-menu")
    }

    private func openSchoolWebsite() {
        if let url = URL(string: PocketAnnouncementFeed.shared.SCHOOL_ANNOUNCEMENTS_URL) { openURL(url) }
    }

    /// Search and filters stay visible and clearable while the toolbar search field is collapsed.
    @ViewBuilder
    private func statusChips(_ feed: AnnouncementFeed) -> some View {
        let searching = filter == .all && !feed.searchQuery.isEmpty
        let filtered = filter == .all && feed.hasFilters
        if searching || filtered {
            BadgeFlowLayout(spacing: 8) {
                if searching {
                    clearChip("搜尋：\(feed.searchQuery)", systemImage: "magnifyingglass") { query = "" }
                        .accessibilityLabel("清除搜尋：\(feed.searchQuery)")
                        .accessibilityIdentifier("announcements-clear-search")
                }
                if filtered {
                    clearChip("已套用公告篩選 · 清除", systemImage: "line.3.horizontal.decrease") {
                        Task { await feed.clearFilters() }
                    }
                    .accessibilityIdentifier("announcements-clear-filters")
                }
            }
        }
    }

    private func clearChip(_ text: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Image(systemName: systemImage).accessibilityHidden(true)
                Text(text).lineLimit(2)
                Image(systemName: "xmark.circle.fill").accessibilityHidden(true)
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(PocketColor.onPrimaryContainer)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 40)
            .background(PocketColor.primaryContainer, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
    }

    private func noticeBanner(_ notice: String, feed: AnnouncementFeed) -> some View {
        let time = feed.lastUpdatedAt.map { "（上次取得：\(fetchedTimeFormat.string(from: $0))）" } ?? ""
        return HStack(alignment: .top, spacing: 10) {
            Image(systemName: "wifi.exclamationmark").accessibilityHidden(true)
            Text(notice + time).frame(maxWidth: .infinity, alignment: .leading)
            Button("關閉", systemImage: "xmark") { feed.noticeMessage = nil }
                .labelStyle(.iconOnly)
        }
        .font(.footnote)
        .foregroundStyle(PocketColor.onSurface)
        .padding(12)
        .background(PocketColor.surfaceHigh, in: .rect(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func content(_ feed: AnnouncementFeed, rows: [PocketAnnouncement]) -> some View {
        if filter == .all, feed.isInitialLoading, feed.items.isEmpty, rows.isEmpty {
            AnnouncementSkeleton()
        } else if filter == .all, let error = feed.errorMessage {
            ContentUnavailableView {
                Label(feed.searchQuery.isEmpty ? "暫時無法取得學校消息" : "暫時無法搜尋公告", systemImage: "wifi.exclamationmark")
            } description: {
                Text(error == AnnouncementFeed.initialFailure ? "請檢查網路連線後重新整理，或前往學校網站查看。" : error)
            } actions: {
                Button("重新整理", systemImage: "arrow.clockwise") { Task { await feed.refresh() } }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("announcements-retry")
                Button("前往學校網站", systemImage: "safari") { openSchoolWebsite() }
                    .buttonStyle(.bordered)
            }
            .padding(.vertical, 24)
            .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
            .padding(.top, 8)
            // Saved bookmarks stay readable while the school cannot be reached.
            announcementRows(rows, feed: feed)
        } else if rows.isEmpty {
            emptyState(feed)
        } else {
            announcementRows(rows, feed: feed)
            if filter == .all { footer(feed) }
        }
    }

    private func announcementRows(_ rows: [PocketAnnouncement], feed: AnnouncementFeed) -> some View {
        ForEach(Array(rows.enumerated()), id: \.element.id) { index, item in
            let bookmarkLabel = item.isBookmarked ? "取消收藏" : "收藏公告"
            let bookmarkSymbol = item.isBookmarked ? "bookmark.slash" : "bookmark"
            Button {
                model.push(.announcement(item.id))
            } label: {
                AnnouncementCardRow(item: item, showsCategory: filter != .all || feed.searchQuery.isEmpty)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityValue(item.isUnread ? "未讀" : "")
            .accessibilityIdentifier("announcement-\(item.id)")
            .accessibilityAction(named: Text(bookmarkLabel)) { toggleBookmark(item) }
            .accessibilityAction(named: Text("在瀏覽器開啟")) { openInBrowser(item, feed: feed) }
            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                Button("在瀏覽器開啟", systemImage: "arrow.up.right") { openInBrowser(item, feed: feed) }
                    .tint(PocketColor.tertiary)
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(bookmarkLabel, systemImage: bookmarkSymbol) { toggleBookmark(item) }
                    .tint(PocketColor.primary)
            }
            .contextMenu {
                Button(bookmarkLabel, systemImage: bookmarkSymbol) { toggleBookmark(item) }
                Button("在瀏覽器開啟", systemImage: "safari") { openInBrowser(item, feed: feed) }
                if item.isUnread {
                    Button("標為已讀", systemImage: "checkmark.circle") { model.unreadAnnouncementIds.remove(item.id) }
                }
            }
            .onAppear {
                if filter == .all, index >= rows.count - 3 { Task { await feed.loadMore() } }
            }
        }
    }

    private func emptyState(_ feed: AnnouncementFeed) -> some View {
        ContentUnavailableView {
            Label(emptyTitle(feed), systemImage: emptySymbol(feed))
        } description: {
            Text(emptyDetail(feed))
        } actions: {
            switch filter {
            case .unread, .bookmarked:
                if query.isEmpty {
                    Button("查看全部公告") { filter = .all }.buttonStyle(.bordered)
                }
            default:
                if feed.hasFilters {
                    Button("清除篩選") { Task { await feed.clearFilters() } }.buttonStyle(.borderedProminent)
                } else if !feed.searchQuery.isEmpty {
                    Button("清除搜尋") { query = "" }.buttonStyle(.borderedProminent)
                } else {
                    Button("重新整理", systemImage: "arrow.clockwise") { Task { await feed.refresh() } }.buttonStyle(.borderedProminent)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 32)
    }

    private func emptySymbol(_ feed: AnnouncementFeed) -> String {
        switch filter {
        case .bookmarked: return query.isEmpty ? "bookmark" : "magnifyingglass"
        case .unread: return query.isEmpty ? "checkmark.circle" : "magnifyingglass"
        default: return feed.searchQuery.isEmpty && !feed.hasFilters ? "tray" : "magnifyingglass"
        }
    }

    @ViewBuilder
    private func footer(_ feed: AnnouncementFeed) -> some View {
        Group {
            if feed.isInitialLoading {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在載入學校消息")
                }
                .accessibilityElement(children: .combine)
            } else if feed.isLoadingMore {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在載入更多消息")
                }
                .accessibilityElement(children: .combine)
            } else if let error = feed.loadMoreError {
                VStack(spacing: 10) {
                    Text(error)
                    Button("再試一次", systemImage: "arrow.clockwise") { Task { await feed.loadMore(retrying: true) } }
                        .buttonStyle(.bordered)
                        .tint(PocketColor.primary)
                        .accessibilityIdentifier("announcements-load-more-retry")
                }
            } else if !feed.hasMore {
                Text("\(Image(systemName: "checkmark")) 已載入全部消息")
                    .accessibilityLabel("已載入全部消息")
                    .accessibilityIdentifier("announcements-end")
            } else {
                Color.clear.frame(height: 1)
            }
        }
        .font(.footnote)
        .foregroundStyle(PocketColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, minHeight: 56)
        // A short first page never scrolls, so the footer itself asks for the next one.
        .task(id: feed.items.count) { await feed.loadMore() }
    }

    private func rows(_ feed: AnnouncementFeed) -> [PocketAnnouncement] {
        let unread = model.unreadAnnouncementIds
        let bookmarks = model.bookmarkedAnnouncementIds
        let store = PocketAnnouncementFeed.shared
        switch filter {
        case .bookmarked:
            return store.bookmarks(clock: model.clock, loaded: feed.items, unreadIds: unread, bookmarkedIds: bookmarks)
                .filter { matches($0) }
        case .unread:
            let known = PocketDataSourceItems.known(feed.items, clock: model.clock)
            return AnnouncementFeed.ordered(store.rows(items: known, unreadIds: unread, bookmarkedIds: bookmarks), unreadIds: unread)
                .filter { $0.isUnread && matches($0) }
        default:
            var rows = store.rows(items: feed.items, unreadIds: unread, bookmarkedIds: bookmarks)
            // Like Android, the unfiltered list also lists saved bookmarks its loaded pages do not reach.
            if feed.searchQuery.isEmpty, !feed.hasFilters {
                let saved = store.bookmarks(clock: model.clock, loaded: feed.items, unreadIds: unread, bookmarkedIds: bookmarks)
                rows = AnnouncementFeed.withBookmarks(rows, fallbacks: saved)
            }
            return AnnouncementFeed.ordered(rows, unreadIds: orderingUnread ?? unread, bookmarkedIds: bookmarks)
        }
    }

    /// Unread and bookmarked are local views of what is loaded, so the search narrows them locally.
    private func matches(_ item: PocketAnnouncement) -> Bool {
        let words = query.lowercased().split(whereSeparator: \.isWhitespace)
        let text = "\(item.title) \(item.unit) \(item.category)".lowercased()
        return words.allSatisfy { text.contains($0) }
    }

    private func emptyTitle(_ feed: AnnouncementFeed) -> String {
        switch filter {
        case .unread: return query.isEmpty ? "沒有未讀公告" : "找不到符合的公告"
        case .bookmarked: return query.isEmpty ? "還沒有收藏" : "找不到符合的公告"
        default:
            if !feed.searchQuery.isEmpty { return "找不到符合「\(feed.searchQuery)」的公告" }
            if feed.hasFilters { return "找不到符合篩選條件的公告" }
            return "目前沒有最新消息"
        }
    }

    private func emptyDetail(_ feed: AnnouncementFeed) -> String {
        switch filter {
        case .bookmarked: return query.isEmpty ? "目前沒有收藏。可在公告詳情按書籤，稍後再回來查看。" : "試試其他關鍵字。"
        case .unread: return query.isEmpty ? "" : "試試其他關鍵字。"
        default: return feed.searchQuery.isEmpty && !feed.hasFilters ? "可以稍後重新整理，或前往學校網站查看。" : "請嘗試其他關鍵字或篩選條件。"
        }
    }
}

/// One announcement in the list: status badges and the date above an emphasised title, with a bookmark
/// mark when saved, the iOS counterpart of Android's `AnnouncementCard`. Actions live in swipe actions,
/// the context menu and the VoiceOver actions those provide.
struct AnnouncementCardRow: View {
    let item: PocketAnnouncement
    var showsCategory = true

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                BadgeFlowLayout(spacing: 6) {
                    if item.isUnread { AnnouncementBadge(text: "未讀", style: .unread) }
                    if item.isPinned { AnnouncementBadge(text: "置頂", style: .emphasized) }
                    if showsCategory, !item.category.isEmpty { AnnouncementBadge(text: item.category) }
                    Text(item.dateText)
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.vertical, 3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if item.isBookmarked {
                    Image(systemName: "bookmark.fill")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.primary)
                        .padding(.top, 3)
                        .accessibilityLabel("已收藏")
                }
            }
            Text(item.title)
                .font(.headline)
                .foregroundStyle(PocketColor.onSurface)
                .lineLimit(3)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !item.unit.isEmpty {
                Text(item.unit)
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 20, style: .continuous))
        .contentShape(.rect(cornerRadius: 20, style: .continuous))
    }
}

struct AnnouncementBadge: View {
    enum Style { case plain, emphasized, unread }
    let text: String
    var systemImage: String?
    var style: Style = .plain

    var body: some View {
        HStack(spacing: 3) {
            if let systemImage { Image(systemName: systemImage).imageScale(.small).accessibilityHidden(true) }
            Text(text)
        }
        .font(.caption.weight(.semibold))
        .lineLimit(1)
        .foregroundStyle(foreground)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(background, in: .rect(cornerRadius: 8, style: .continuous))
    }

    private var foreground: Color {
        switch style {
        case .unread: PocketColor.negative
        case .emphasized: PocketColor.onSecondaryContainer
        case .plain: PocketColor.onSurfaceVariant
        }
    }

    private var background: Color {
        switch style {
        case .unread: PocketColor.negative.opacity(0.14)
        case .emphasized: PocketColor.secondaryContainer
        case .plain: PocketColor.surfaceHigh
        }
    }
}

/// Lays badges out in rows, wrapping when the width (or a large text size) runs out.
struct BadgeFlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map { $0.width }.max() ?? 0
        let height = rows.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, rows.count - 1))
        return CGSize(width: proposal.width.map { min($0, width) } ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
                subviews[index].place(at: CGPoint(x: x, y: y + (row.height - size.height) / 2), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
            let needed = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            if !row.indices.isEmpty, needed > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}

/// Placeholder cards shaped like announcement rows while the first page loads.
private struct AnnouncementSkeleton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dimmed = false

    var body: some View {
        VStack(spacing: 10) {
            ForEach(0..<6, id: \.self) { index in
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 6).frame(width: 36, height: 18)
                        RoundedRectangle(cornerRadius: 6).frame(width: 72, height: 18)
                    }
                    RoundedRectangle(cornerRadius: 6).frame(height: 18)
                    RoundedRectangle(cornerRadius: 6).frame(width: index.isMultiple(of: 2) ? 180 : 240, height: 18)
                    RoundedRectangle(cornerRadius: 6).frame(width: 64, height: 14)
                }
                .foregroundStyle(PocketColor.surfaceHigh)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 20, style: .continuous))
            }
        }
        .opacity(dimmed ? 0.55 : 1)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { dimmed = true }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("正在載入學校消息")
        .accessibilityIdentifier("announcements-loading")
    }
}

/// The first page the rest of the app knows, plus the pages this list has loaded.
enum PocketDataSourceItems {
    @MainActor
    static func known(_ loaded: [SchoolAnnouncement], clock: PocketClock) -> [SchoolAnnouncement] {
        var seen = Set<String>()
        return loaded.filter { seen.insert($0.id).inserted }
    }
}

struct AnnouncementFilterSheet: View {
    @Environment(\.dismiss) private var dismiss
    let feed: AnnouncementFeed
    @State private var unitId = ""
    @State private var categoryId = ""

    var body: some View {
        let all = PocketAnnouncementFeed.shared.ALL_ID
        NavigationStack {
            Form {
                if feed.isLoadingFilters {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在載入篩選選項").foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    .accessibilityElement(children: .combine)
                }
                if feed.filterLoadFailed {
                    Section {
                        Text("無法更新篩選選項，請稍後再試。").foregroundStyle(PocketColor.negative)
                        Button("重試") { Task { await feed.loadFilters() } }
                    }
                }
                Section {
                    Picker("單位", selection: $unitId) {
                        ForEach(options(feed.filterOptions.units.map { ($0.id, $0.name) }), id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .accessibilityIdentifier("filter-unit")
                    Picker("類別", selection: $categoryId) {
                        ForEach(options(feed.filterOptions.categories.map { ($0.id, $0.name) }), id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .accessibilityIdentifier("filter-category")
                } footer: {
                    Text("依學校網站的單位與類別篩選，可與搜尋同時使用。")
                }
                Section {
                    Button("重設") { unitId = all; categoryId = all }
                        .disabled(unitId == all && categoryId == all)
                }
            }
            .navigationTitle("篩選公告")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("套用") {
                        let unit = unitId, category = categoryId
                        dismiss()
                        Task { await feed.setFilters(unitId: unit, categoryId: category) }
                    }
                    .accessibilityIdentifier("filter-apply")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear {
            unitId = feed.unitId
            categoryId = feed.categoryId
        }
    }

    /// Always offers 全部, even before the school's options arrive.
    private func options(_ list: [(String, String)]) -> [(String, String)] {
        let all = PocketAnnouncementFeed.shared.ALL_ID
        return list.contains { $0.0 == all } ? list : [(all, "全部")] + list
    }
}

// MARK: - Campus search

struct CampusSearchView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var announcements: [PocketAnnouncement] = []
    @State private var searching = false
    @State private var failed = false
    @State private var selectedEvent: PocketEventDetail?
    @State private var found: [SchoolAnnouncement] = []
    @State private var nextPage: Int?
    @State private var loadingMore = false

    private static let quickTerms = ["期中考", "期末考", "校慶", "補考"]

    var body: some View {
        let trimmed = PocketAnnouncementFeed.shared.normalizedQuery(query: query)
        let events = trimmed.isEmpty ? [] : PocketAnnouncementFeed.shared.searchEvents(clock: model.clock, query: trimmed)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("快速搜尋")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Self.quickTerms, id: \.self) { term in
                                Button(term) { query = term }
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(PocketColor.onSurface)
                                    .padding(.horizontal, 16)
                                    .frame(minHeight: 40)
                                    .background(PocketColor.surfaceLow, in: .capsule)
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("quick-\(term)")
                            }
                        }
                    }
                    .scrollClipDisabled()
                }
                if trimmed.isEmpty {
                    ContentUnavailableView("搜尋公告與行事曆", systemImage: "magnifyingglass", description: Text("搜尋學校公告與尚未結束的行程"))
                        .padding(.top, 24)
                } else {
                    PocketSectionHeader(title: "學校公告") { EmptyView() }
                    PocketCard(padding: 8) {
                        if searching {
                            HStack(spacing: 10) { ProgressView(); Text("正在搜尋") }
                                .frame(maxWidth: .infinity, alignment: .leading).padding(12)
                        } else if failed {
                            HStack {
                                Text("暫時無法搜尋公告").foregroundStyle(PocketColor.onSurfaceVariant)
                                Spacer()
                                Button("再試一次") { Task { await search(trimmed) } }
                            }
                            .padding(12)
                        } else if announcements.isEmpty {
                            emptyRow("沒有符合的公告")
                        } else {
                            AnnouncementRows(announcements: announcements)
                            if nextPage != nil {
                                Button(loadingMore ? "載入中…" : "載入更多公告") { Task { await loadMore(trimmed) } }
                                    .disabled(loadingMore)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .accessibilityIdentifier("search-load-more")
                            }
                        }
                    }
                    PocketSectionHeader(title: "行事曆") { EmptyView() }
                    PocketCard(padding: 8) {
                        if events.isEmpty {
                            emptyRow("沒有符合的行程")
                        } else {
                            EventRows(events: events) { id in
                                selectedEvent = PocketAnnouncementFeed.shared.eventDetail(clock: model.clock, id: id)
                            }
                        }
                    }
                    Text("重複活動請至 Google 行事曆查看。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .pocketPage()
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜尋公告、行事曆…")
        .navigationTitle("校園搜尋")
        .navigationBarTitleDisplayMode(.large)
        .task(id: trimmed) {
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            await search(trimmed)
        }
        .sheet(item: $selectedEvent) { CalendarEventSheet(detail: $0) }
    }

    private func emptyRow(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(PocketColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
    }

    private func search(_ text: String) async {
        nextPage = nil
        found = []
        guard !text.isEmpty else { announcements = []; return }
        searching = true
        failed = false
        defer { searching = false }
        do {
            let loader = model.makeAnnouncementLoader()
            let page = try await loader.page(0, keyword: text, unitId: "-1", categoryId: "-1")
            guard !Task.isCancelled, let page else { return }
            // Known announcements are what a bookmark can be saved from.
            loader.remember(page.announcements)
            show(page)
        } catch {
            if !(error is CancellationError) { failed = true }
        }
    }

    /// The school pages its search results; later pages are loaded on request, as in the list.
    private func loadMore(_ text: String) async {
        guard let index = nextPage, !loadingMore else { return }
        loadingMore = true
        defer { loadingMore = false }
        let loader = model.makeAnnouncementLoader()
        // A failed page keeps the button, so it can be tried again.
        guard let page = try? await loader.page(index, keyword: text, unitId: "-1", categoryId: "-1"),
              !Task.isCancelled, text == PocketAnnouncementFeed.shared.normalizedQuery(query: query) else { return }
        loader.remember(page.announcements)
        show(page)
    }

    private func show(_ page: SchoolAnnouncementPage) {
        let known = Set(found.map(\.id))
        found += page.announcements.filter { !known.contains($0.id) }
        nextPage = page.pageIndex + 1 < page.totalPages ? Int(page.pageIndex) + 1 : nil
        announcements = PocketAnnouncementFeed.shared.rows(
            items: found, unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
        )
    }
}

extension PocketEventDetail: @retroactive Identifiable {}

// MARK: - Calendar event

struct CalendarEventSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let detail: PocketEventDetail
    @State private var added = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(detail.title)
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("event-title")
                    LabeledContent("日期", value: detail.dateText)
                    LabeledContent("時間", value: detail.timeText)
                    if let location = detail.location { LabeledContent("地點", value: location) }
                    LabeledContent("狀態", value: detail.relativeText)
                }
                Section {
                    Button(added ? "已加入首頁倒數" : "加入首頁倒數", systemImage: added ? "checkmark.circle.fill" : "timer") {
                        model.editDashboard { _, layout in
                            PocketAnnouncementFeed.shared.addEventCountdown(layout: layout, eventId: detail.id, clock: model.clock)
                        }
                        added = true
                    }
                    .disabled(added)
                    .accessibilityLabel(added ? "已將「\(detail.title)」加入首頁倒數元件" : "將「\(detail.title)」加入首頁倒數元件")
                    .accessibilityIdentifier("event-add-countdown")
                    Button("在 Google 行事曆查看", systemImage: "safari") {
                        if let url = URL(string: PocketAnnouncementFeed.shared.SCHOOL_CALENDAR_URL) { openURL(url) }
                    }
                }
            }
            .navigationTitle("行程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Announcement reminders

struct AnnouncementReminderView: View {
    @Environment(AppModel.self) private var model
    @State private var options: [AnnouncementUnit] = []
    @State private var loadFailed = false
    @State private var unitQuery = ""
    @State private var permissionDenied = false

    private static let recommendedNames = ["學務處", "實驗研究組", "實研組", "教學組"]

    var body: some View {
        @Bindable var settings = PlatformSettings.shared
        let all = PocketAnnouncementFeed.shared.ALL_ID
        let selected = Set(settings.announcementUnitIds)
        let units = options.filter { $0.id != all }
        let shown = unitQuery.isEmpty ? units : units.filter { $0.name.localizedCaseInsensitiveContains(unitQuery) }
        let recommended = units.filter { unit in Self.recommendedNames.contains { unit.name.hasPrefix($0) } }
        Form {
            Section {
                Toggle(isOn: Binding(
                    get: { settings.notificationsEnabled && settings.announcements },
                    set: { on in
                        settings.announcements = on
                        if on, !settings.notificationsEnabled {
                            Task { permissionDenied = !(await model.enableNotifications()) }
                        }
                    }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("公告更新提醒")
                        Text(statusText(settings)).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
                .accessibilityIdentifier("reminder-toggle")
                if permissionDenied {
                    Button("請在系統設定中開啟通知", systemImage: "exclamationmark.triangle") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .foregroundStyle(PocketColor.warning)
                }
                LabeledContent("最近檢查時間", value: settings.announcementLastCheck.map { fetchedTimeFormat.string(from: $0) } ?? "尚未檢查")
            } footer: {
                Text("有新公告時在這台裝置通知你。iOS 由系統決定背景執行的時機，實際檢查可能較設定晚。")
            }
            Section {
                Picker("檢查頻率", selection: $settings.announcementIntervalMinutes) {
                    ForEach(PocketAnnouncementFeed.shared.reminderIntervalsMinutes.map(\.intValue), id: \.self) {
                        Text(Self.intervalLabel($0)).tag($0)
                    }
                }
                .accessibilityIdentifier("reminder-interval")
            } footer: {
                Text("建議選擇 1–3 小時，兼顧即時性與耗電量")
            }
            Section {
                unitRow(id: all, name: "全部單位", selected: selected.contains(all))
                if !recommended.isEmpty, unitQuery.isEmpty {
                    ForEach(recommended, id: \.id) { unitRow(id: $0.id, name: $0.name, selected: selected.contains($0.id), badge: "推薦") }
                }
                if options.isEmpty, !loadFailed {
                    HStack(spacing: 10) { ProgressView(); Text("正在讀取設定") }
                }
                if loadFailed {
                    Text("暫時無法更新單位清單，已顯示上次成功取得的內容。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                ForEach(shown.filter { unit in !(unitQuery.isEmpty && recommended.contains { $0.id == unit.id }) }, id: \.id) {
                    unitRow(id: $0.id, name: $0.name, selected: selected.contains($0.id))
                }
                if !unitQuery.isEmpty, shown.isEmpty {
                    Text("找不到符合的單位").foregroundStyle(PocketColor.onSurfaceVariant)
                }
            } header: {
                Text("接收單位")
            } footer: {
                Text(selected.contains(all) ? "已選擇全部" : "已選擇 \(selected.count) 個")
            }
        }
        .searchable(text: $unitQuery, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "搜尋單位")
        .navigationTitle("公告更新提醒")
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadUnits() }
    }

    private func unitRow(id: String, name: String, selected: Bool, badge: String? = nil) -> some View {
        Button {
            toggle(id, name: name)
        } label: {
            HStack {
                Text(name).foregroundStyle(PocketColor.onSurface)
                if let badge {
                    Text(badge)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(PocketColor.onPrimaryContainer)
                        .padding(.horizontal, 8).padding(.vertical, 2)
                        .background(PocketColor.primaryContainer, in: .capsule)
                }
                Spacer()
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? PocketColor.primary : PocketColor.outline)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityValue(selected ? "已選取" : "未選取")
        .accessibilityIdentifier("reminder-unit-\(id)")
    }

    private func toggle(_ id: String, name: String) {
        let settings = PlatformSettings.shared
        let all = PocketAnnouncementFeed.shared.ALL_ID
        var ids = Set(settings.announcementUnitIds)
        if id == all {
            ids = [all]
        } else {
            ids.remove(all)
            if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
            if ids.isEmpty { ids = [all] }
        }
        settings.announcementUnitIds = ids.sorted()
    }

    private func statusText(_ settings: PlatformSettings) -> String {
        guard settings.notificationsEnabled else { return "通知已關閉，背景檢查暫停" }
        guard settings.announcements else { return "已關閉" }
        let all = PocketAnnouncementFeed.shared.ALL_ID
        let units = settings.announcementUnitIds.contains(all) ? "全部單位"
            : "\(settings.announcementUnitIds.count) 個來源"
        return "每 \(Self.intervalLabel(settings.announcementIntervalMinutes)) · \(units)"
    }

    static func intervalLabel(_ minutes: Int) -> String {
        minutes < 60 ? "\(minutes) 分鐘" : "\(minutes / 60) 小時"
    }

    private func loadUnits() async {
        let loader = model.makeAnnouncementLoader()
        if let cached = await loader.cachedFilters(), !cached.units.isEmpty { options = cached.units }
        do {
            options = try await loader.filters().units
            loadFailed = false
        } catch {
            loadFailed = !(error is CancellationError)
        }
    }
}
