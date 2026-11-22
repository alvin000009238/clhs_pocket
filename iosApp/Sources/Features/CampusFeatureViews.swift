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
        let failOnce = UserDefaults.standard.bool(forKey: "demoAnnouncementLoadMoreFailure")
        return DemoAnnouncementLoader(clock: { [weak self] in self?.clock ?? PocketClock(year: 2026, month: 1, day: 1, dayOfWeek: 1, minuteOfDay: 0) }, failNextLoadMore: failOnce)
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
    @State private var feed: AnnouncementFeed?
    @State private var query = ""
    @State private var filter: PocketAnnouncementFilter = .all
    @State private var showingFilters = false

    var body: some View {
        let feed = feed ?? AnnouncementFeed(loader: model.makeAnnouncementLoader())
        let unread = Int(PocketCampusStore.shared.unreadCount(clock: model.clock, unreadIds: model.unreadAnnouncementIds))
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    Picker("顯示", selection: $filter) {
                        Text("全部").tag(PocketAnnouncementFilter.all)
                        Text(unread > 0 ? "未讀 \(unread)" : "未讀").tag(PocketAnnouncementFilter.unread)
                        Text("收藏").tag(PocketAnnouncementFilter.bookmarked)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("announcement-filter")
                    .id("announcements-top")

                    statusChips(feed)
                    if let notice = feed.noticeMessage {
                        noticeBanner(notice, feed: feed)
                    }
                    content(feed)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .pocketPage()
            }
            .overlay(alignment: .bottomTrailing) {
                if filter == .all, feed.pageIndex > 0 {
                    Button {
                        withAnimation { proxy.scrollTo("announcements-top", anchor: .top) }
                    } label: {
                        Label("回到頂端", systemImage: "arrow.up")
                            .labelStyle(.iconOnly)
                            .font(.headline)
                            .frame(width: 48, height: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.circle)
                    .padding(20)
                    .accessibilityIdentifier("announcements-top")
                }
            }
        }
        .refreshable { await feed.refresh() }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜尋公告")
        .onChange(of: query) { _, text in feed.search(text) }
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

    private func menu(_ feed: AnnouncementFeed, unread: Int) -> some View {
        let bookmarks = model.bookmarkedAnnouncementIds.count
        return Menu {
            Button("全部已讀", systemImage: "envelope.open") { model.unreadAnnouncementIds = [] }
                .disabled(unread == 0)
                .accessibilityIdentifier("announcements-read-all")
            Button(bookmarks > 0 ? "已收藏公告（\(bookmarks)）" : "已收藏公告", systemImage: "bookmark") { filter = .bookmarked }
                .accessibilityIdentifier("announcements-bookmarks")
            Button(feed.hasFilters ? "篩選公告（已套用）" : "篩選公告", systemImage: "line.3.horizontal.decrease.circle") {
                showingFilters = true
            }
            .accessibilityIdentifier("announcements-filters")
            Button("公告更新提醒", systemImage: "bell.badge") { model.push(.announcementReminders) }
                .accessibilityIdentifier("announcements-reminders")
            Button("重新整理", systemImage: "arrow.clockwise") { Task { await feed.refresh() } }
            Divider()
            Button("學校網站", systemImage: "safari") {
                if let url = URL(string: PocketAnnouncementFeed.shared.SCHOOL_ANNOUNCEMENTS_URL) { openURL(url) }
            }
        } label: {
            Label("更多選項", systemImage: "ellipsis.circle")
        }
        .accessibilityIdentifier("announcements-menu")
    }

    @ViewBuilder
    private func statusChips(_ feed: AnnouncementFeed) -> some View {
        if filter == .all, feed.hasFilters {
            Button {
                Task { await feed.clearFilters() }
            } label: {
                Label("已套用公告篩選 · 清除", systemImage: "xmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 40)
                    .background(PocketColor.primaryContainer, in: .capsule)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("announcements-clear-filters")
        }
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
        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func content(_ feed: AnnouncementFeed) -> some View {
        let rows = rows(feed)
        if filter == .all, feed.isInitialLoading, feed.items.isEmpty {
            HStack(spacing: 12) {
                ProgressView()
                Text("正在載入學校消息").foregroundStyle(PocketColor.onSurfaceVariant)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 40)
            .accessibilityElement(children: .combine)
        } else if filter == .all, let error = feed.errorMessage {
            ContentUnavailableView {
                Label("暫時無法取得學校消息", systemImage: "wifi.exclamationmark")
            } description: {
                Text("\(error)\n可以稍後重新整理，或前往學校網站查看。")
            } actions: {
                Button("再試一次") { Task { await feed.refresh() } }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("announcements-retry")
            }
            .padding(.top, 24)
        } else if rows.isEmpty {
            ContentUnavailableView {
                Label(emptyTitle(feed), systemImage: filter == .bookmarked ? "bookmark" : "tray")
            } description: {
                Text(emptyDetail(feed))
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 32)
        } else {
            if filter == .all, !feed.searchQuery.isEmpty {
                Text("搜尋：\(feed.searchQuery)")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            ForEach(Array(rows.enumerated()), id: \.element.id) { index, item in
                AnnouncementRows(announcements: [item])
                    .padding(.horizontal, 8)
                    .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 16, style: .continuous))
                    .contextMenu {
                        if item.isBookmarked {
                            Button("取消收藏", systemImage: "bookmark.slash") { model.bookmarkedAnnouncementIds.remove(item.id) }
                        } else {
                            Button("收藏公告", systemImage: "bookmark") { model.bookmarkedAnnouncementIds.insert(item.id) }
                        }
                    }
                    .onAppear {
                        if filter == .all, index >= rows.count - 3 { Task { await feed.loadMore() } }
                    }
            }
            if filter == .all { footer(feed) }
        }
    }

    @ViewBuilder
    private func footer(_ feed: AnnouncementFeed) -> some View {
        Group {
            if feed.isLoadingMore {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在載入更多消息")
                }
                .accessibilityElement(children: .combine)
            } else if let error = feed.loadMoreError {
                VStack(spacing: 8) {
                    Text(error).foregroundStyle(PocketColor.negative)
                    Button("重試") { Task { await feed.loadMore(retrying: true) } }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("announcements-load-more-retry")
                }
            } else if !feed.hasMore {
                Text("已載入全部消息")
                    .accessibilityIdentifier("announcements-end")
            } else {
                Color.clear.frame(height: 1)
            }
        }
        .font(.footnote)
        .foregroundStyle(PocketColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, minHeight: 44)
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
            return store.rows(items: known, unreadIds: unread, bookmarkedIds: bookmarks).filter { $0.isUnread && matches($0) }
        default:
            return store.rows(items: feed.items, unreadIds: unread, bookmarkedIds: bookmarks)
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
        guard !text.isEmpty else { announcements = []; return }
        searching = true
        failed = false
        defer { searching = false }
        do {
            let page = try await model.makeAnnouncementLoader().page(0, keyword: text, unitId: "-1", categoryId: "-1")
            guard !Task.isCancelled, let page else { return }
            announcements = PocketAnnouncementFeed.shared.rows(
                items: page.announcements, unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
            )
        } catch {
            if !(error is CancellationError) { failed = true }
        }
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
