import PocketShared
import SwiftUI

// MARK: - Announcements

private struct AnnouncementImageView: View {
    @Environment(\.openURL) private var openURL
    let image: PocketAnnouncementImage

    private var url: URL? {
        URL(string: image.url).flatMap { $0.scheme == "https" ? $0 : nil }
    }

    var body: some View {
        PocketCard(padding: 0) {
            if let url, image.canPreview {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case let .success(loaded):
                        Button { openURL(url) } label: {
                            loaded.resizable().scaledToFit().frame(maxWidth: .infinity, maxHeight: 480)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(image.description.isEmpty ? "公告圖片" : image.description)
                        .accessibilityHint("開啟原圖")
                    case .failure:
                        note("圖片暫時無法載入")
                    default:
                        ProgressView().frame(maxWidth: .infinity, minHeight: 120)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    note("外部圖片未自動載入")
                    if let link = URL(string: image.url), link.scheme == "https" || link.scheme == "http" {
                        Button("開啟圖片") { openURL(link) }
                            .buttonStyle(.bordered)
                            .padding([.horizontal, .bottom], 20)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(PocketColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
    }
}

struct AnnouncementDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    let id: String
    @State private var loadingDetail = true
    @State private var attachmentNotice = false
    @State private var richBody: AttributedString?

    private func loadDetail() async {
        guard model.usesLiveData, let live = model.live else { return }
        loadingDetail = true
        await live.loadAnnouncementDetail(id: id, category: "")
        loadingDetail = false
    }

    var body: some View {
        let detail = PocketCampusStore.shared.announcementDetail(clock: model.clock, id: id)
        content(detail)
            .task(id: detail?.html ?? "") {
                richBody = detail.flatMap { AnnouncementMarkup.attributed(html: $0.html) }
            }
            // Only a body the user can actually read counts as read; a failed live load keeps it unread.
            .onChange(of: detail != nil, initial: true) { _, loaded in
                if loaded { model.unreadAnnouncementIds.remove(id) }
            }
    }

    @ViewBuilder
    private func content(_ detail: PocketAnnouncementDetail?) -> some View {
        ScrollView {
            if let detail {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 8) {
                            if detail.isPinned {
                                PocketChip(text: "置頂", systemImage: "pin.fill", foreground: PocketColor.onTertiaryContainer, background: PocketColor.tertiaryContainer)
                            }
                            PocketChip(text: detail.category)
                        }
                        Text(detail.title)
                            .font(PocketFont.display(26, relativeTo: .title))
                            .foregroundStyle(PocketColor.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityAddTraits(.isHeader)
                        Text("\(detail.unit) · \(detail.dateText)")
                            .font(.subheadline)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    if let rich = richBody {
                        Text(rich)
                            .lineSpacing(5)
                            .tint(PocketColor.primary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("announcement-body")
                    } else {
                        VStack(alignment: .leading, spacing: 14) {
                            ForEach(Array(detail.paragraphs.enumerated()), id: \.offset) { _, paragraph in
                                Text(paragraph)
                                    .font(.body)
                                    .lineSpacing(5)
                                    .foregroundStyle(PocketColor.onSurface)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                    ForEach(Array(detail.images.enumerated()), id: \.offset) { _, image in
                        AnnouncementImageView(image: image)
                    }
                    if !detail.attachments.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            PocketSectionHeader(title: "附件") { EmptyView() }
                            PocketCard(padding: 8) {
                                VStack(spacing: 0) {
                                    ForEach(Array(detail.attachments.enumerated()), id: \.offset) { index, file in
                                        if index > 0 { PocketRowDivider().padding(.leading, 60) }
                                        Button {
                                            if let url = URL(string: file.url), url.scheme == "https" || url.scheme == "http" {
                                                openURL(url)
                                            } else {
                                                attachmentNotice = true
                                            }
                                        } label: {
                                            HStack(spacing: 12) {
                                                PocketIconTile(systemImage: "doc.fill", size: 36)
                                                VStack(alignment: .leading, spacing: 2) {
                                                    Text(file.name).font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                                                        .multilineTextAlignment(.leading)
                                                    if !file.sizeText.isEmpty {
                                                        Text(file.sizeText).font(.footnote).monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                                                    }
                                                }
                                                Spacer(minLength: 0)
                                                Image(systemName: "arrow.up.right.square")
                                                    .foregroundStyle(PocketColor.primary)
                                                    .accessibilityHidden(true)
                                            }
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 8)
                                            .frame(minHeight: 44)
                                            .contentShape(.rect)
                                        }
                                        .buttonStyle(.plain)
                                        .accessibilityElement(children: .combine)
                                        .accessibilityHint("開啟附件")
                                        .accessibilityIdentifier("attachment-\(index)")
                                    }
                                }
                            }
                            if !model.usesLiveData {
                                Text("示範資料不含實際檔案。")
                                    .font(.footnote)
                                    .foregroundStyle(PocketColor.onSurfaceVariant)
                                    .padding(.horizontal, 4)
                            }
                        }
                    }
                }
                .frame(maxWidth: 720, alignment: .leading)
                .pocketPage()
            } else if model.usesLiveData {
                PocketPendingView(isLoading: loadingDetail, title: "無法載入公告內容", message: model.live?.lastError) {
                    Task { await loadDetail() }
                }
                .pocketPage()
            } else {
                ContentUnavailableView("找不到這則公告", systemImage: "questionmark.circle")
            }
        }
        .task { await loadDetail() }
        .alert(model.usesLiveData ? "無法開啟附件" : "示範附件", isPresented: $attachmentNotice) {
            Button("好") {}
        } message: {
            Text(model.usesLiveData ? "這個附件沒有有效的網址，請到學校網站查看這則公告。"
                : "示範資料不含實際檔案；使用真實資料時會開啟學校網站上的附件。")
        }
        .background(PocketColor.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let detail {
                let saved = model.bookmarkedAnnouncementIds.contains(id)
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button(saved ? "取消收藏" : "收藏", systemImage: saved ? "bookmark.fill" : "bookmark") {
                        if saved { model.bookmarkedAnnouncementIds.remove(id) } else { model.bookmarkedAnnouncementIds.insert(id) }
                    }
                    .accessibilityIdentifier("announcement-bookmark")
                    if let url = URL(string: detail.officialUrl) {
                        ShareLink(item: url, subject: Text(detail.title)) {
                            Label("分享", systemImage: "square.and.arrow.up")
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Calendar

struct CalendarListView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var mode = 0
    @State private var year = 0
    @State private var month = 0
    @State private var selectedDay: String?
    @State private var query = ""
    @State private var selectedEvent: PocketEventDetail?
    @Environment(\.openURL) private var openURL

    private func open(_ id: String) {
        selectedEvent = PocketAnnouncementFeed.shared.eventDetail(clock: model.clock, id: id)
    }

    var body: some View {
        let store = PocketCampusStore.shared
        let searchText = PocketAnnouncementFeed.shared.normalizedQuery(query: query)
        let shownYear = year == 0 ? Int(model.clock.year) : year
        let shownMonth = month == 0 ? Int(model.clock.month) : month
        let data = store.month(clock: model.clock, year: Int32(shownYear), month: Int32(shownMonth))
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("檢視方式", selection: $mode) {
                    Text("月曆").tag(0)
                    Text("列表").tag(1)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("calendar-mode")

                if !searchText.isEmpty {
                    let results = PocketAnnouncementFeed.shared.searchEvents(clock: model.clock, query: searchText)
                    Text("搜尋：\(searchText)")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.horizontal, 4)
                    if results.isEmpty {
                        ContentUnavailableView {
                            Label("找不到符合「\(searchText)」的活動", systemImage: "calendar.badge.exclamationmark")
                        } description: {
                            Text("請嘗試其他關鍵字，或清除搜尋查看接下來的活動。")
                        }
                    } else {
                        PocketCard(padding: 8) { EventRows(events: results, onSelect: open) }
                    }
                } else if mode == 0 {
                    if sizeClass == .regular {
                        HStack(alignment: .top, spacing: 20) {
                            monthCard(data).frame(maxWidth: .infinity)
                            eventList(data, store: store).frame(maxWidth: .infinity)
                        }
                    } else {
                        monthCard(data)
                        eventList(data, store: store)
                    }
                } else {
                    let events = model.dataSource.campus(clock: model.clock).events
                    Text("共 \(events.count) 個即將到來的行程")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.horizontal, 4)
                    PocketCard(padding: 8) { EventRows(events: events, onSelect: open) }
                }
                Text("部分重複活動請前往 Google 行事曆查看。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            .frame(maxWidth: mode == 0 && sizeClass == .regular ? PocketMetrics.regularContentWidth : 720)
            .pocketPage()
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜尋行事曆")
        .navigationTitle("行事曆")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Google 行事曆", systemImage: "safari") {
                    if let url = URL(string: PocketAnnouncementFeed.shared.SCHOOL_CALENDAR_URL) { openURL(url) }
                }
                .accessibilityIdentifier("calendar-google")
            }
        }
        .sheet(item: $selectedEvent) { CalendarEventSheet(detail: $0) }
    }

    private func step(_ delta: Int, from data: PocketCalendarMonth) {
        var newMonth = Int(data.month) + delta
        var newYear = Int(data.year)
        if newMonth < 1 { newMonth = 12; newYear -= 1 }
        if newMonth > 12 { newMonth = 1; newYear += 1 }
        year = newYear
        month = newMonth
        selectedDay = nil
    }

    private func monthCard(_ data: PocketCalendarMonth) -> some View {
        PocketCard(padding: 16) {
            VStack(spacing: 12) {
                HStack {
                    Text(data.title)
                        .font(PocketFont.cardTitle)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(PocketColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("calendar-title")
                    Spacer()
                    Button("上個月", systemImage: "chevron.left") { step(-1, from: data) }
                        .accessibilityIdentifier("calendar-previous")
                    Button("下個月", systemImage: "chevron.right") { step(1, from: data) }
                        .accessibilityIdentifier("calendar-next")
                }
                .labelStyle(.iconOnly)
                .buttonStyle(.bordered)
                .buttonBorderShape(.circle)

                Grid(horizontalSpacing: 0, verticalSpacing: 4) {
                    GridRow {
                        ForEach(data.weekdays, id: \.self) { label in
                            Text(label)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .accessibilityHidden(true)
                    ForEach(0..<data.days.count / 7, id: \.self) { week in
                        GridRow {
                            ForEach(data.days[week * 7..<week * 7 + 7], id: \.key) { day in
                                dayCell(day)
                            }
                        }
                    }
                }
            }
        }
    }

    private func dayCell(_ day: PocketCalendarDay) -> some View {
        let selected = selectedDay == day.key
        return Button {
            selectedDay = selected ? nil : day.key
        } label: {
            VStack(spacing: 3) {
                Text(day.dayText)
                    .font(.body.weight(day.isToday ? .bold : .regular))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .dynamicTypeSize(...DynamicTypeSize.accessibility1)
                    .foregroundStyle(
                        selected ? PocketColor.onPrimary
                            : day.isToday ? PocketColor.primary
                            : day.inMonth ? PocketColor.onSurface : PocketColor.outline
                    )
                Circle()
                    .fill(day.eventCount > 0 ? (selected ? PocketColor.onPrimary : PocketColor.primary) : .clear)
                    .frame(width: 5, height: 5)
            }
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(
                selected ? PocketColor.primary : day.isToday ? PocketColor.primaryContainer : .clear,
                in: .rect(cornerRadius: 12, style: .continuous)
            )
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(day.accessibilityLabel)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("day-\(day.key)")
    }

    @ViewBuilder
    private func eventList(_ data: PocketCalendarMonth, store: PocketCampusStore) -> some View {
        let events = selectedDay.map { store.eventsOn(clock: model.clock, dayKey: $0) } ?? data.events
        VStack(alignment: .leading, spacing: 8) {
            PocketSectionHeader(title: selectedDay == nil ? "本月行程" : "當日行程") {
                if selectedDay != nil {
                    Button("顯示整月") { selectedDay = nil }
                        .font(.subheadline.weight(.medium))
                }
            }
            PocketCard(padding: 8) {
                if events.isEmpty {
                    Text(selectedDay == nil ? "這個月沒有行程" : "這一天沒有行程")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                } else {
                    EventRows(events: events, onSelect: open)
                }
            }
        }
    }
}

// MARK: - Attendance and merit records

struct StudentRecordsView: View {
    enum Kind { case attendance, merits }

    @Environment(AppModel.self) private var model
    let kind: Kind
    @State private var semester = 0
    @State private var loadingRecords = false
    @State private var recordsError: String?
    @State private var filter = "全部"
    @State private var showsCategories = false
    @State private var historyExpanded = false
    @State private var loadingHistory = false
    @State private var historyError: String?

    private var title: String { kind == .attendance ? "出缺勤" : "獎懲紀錄" }

    var body: some View {
        Group {
            if model.isSignedIn {
                content
            } else {
                ContentUnavailableView {
                    Label("登入後查看\(title)", systemImage: "lock.fill")
                } description: {
                    Text("在校紀錄屬於個人資料，需要先登入校務帳號。")
                } actions: {
                    Button(model.loginActionTitle) { model.requestLogin() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("records-demo")
                }
            }
        }
        .background(PocketColor.background.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
    }

    private var content: some View {
        let store = PocketCampusStore.shared
        let records = kind == .attendance
            ? store.attendance(semesterIndex: Int32(semester)) : store.merits(semesterIndex: Int32(semester), filter: filter)
        return ScrollView {
            if records.semesters.isEmpty {
                PocketPendingView(
                    isLoading: model.live?.isLoadingPrivate ?? false, title: "紀錄尚未載入", message: model.live?.lastError
                ) { Task { await model.refreshLiveData(force: true) } }
                .pocketPage()
            } else {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                semesterMenu(records)

                if !records.isLoaded {
                    PocketPendingView(isLoading: loadingRecords, title: "這個學期的紀錄尚未載入", message: recordsError ?? model.live?.lastError) {
                        Task { await loadRecords() }
                    }
                } else {
                if let recordsError {
                    // Android: a failed update is never shown as if the old records were current.
                    PocketCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Label("更新未完成，以下顯示上次成功資料", systemImage: "exclamationmark.triangle")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(PocketColor.negative)
                            Text(recordsError).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                            Button("重試") { Task { await loadRecords() } }
                                .font(.subheadline.weight(.semibold))
                                .accessibilityIdentifier("records-retry")
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                PocketColumns {
                    VStack(alignment: .leading, spacing: PocketMetrics.cardSpacing) {
                        summary(records)
                        historySection
                    }
                } trailing: {
                    VStack(alignment: .leading, spacing: 8) {
                        PocketSectionHeader(title: "近期紀錄") { EmptyView() }
                        if records.rows.isEmpty {
                            PocketCard {
                                Label(records.emptyText, systemImage: "checkmark.circle")
                                    .font(.subheadline)
                                    .foregroundStyle(PocketColor.onSurfaceVariant)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        } else {
                            PocketCard(padding: 8) {
                                VStack(spacing: 0) {
                                    ForEach(Array(records.rows.enumerated()), id: \.element.id) { index, row in
                                        if index > 0 { PocketRowDivider().padding(.leading, 12) }
                                        RecordRow(row: row)
                                    }
                                }
                            }
                        }
                    }
                }
                }
                Text("校務資料非即時更新，請以學校正式紀錄為準。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
            .pocketPage()
            }
        }
        .refreshable {
            await model.refreshLiveData(force: true)
            await loadRecords()
            if historyExpanded { await loadHistory(force: true) }
        }
        .task(id: "\(semester)-\(records.semesters.count)") { await loadRecords() }
        .onChange(of: semester) { filter = "全部" }
        .sheet(isPresented: $showsCategories) {
            NavigationStack {
                List {
                    Section {
                        ForEach(records.summary, id: \.category) { item in
                            LabeledContent(item.category) {
                                Text("\(item.count)").monospacedDigit()
                            }
                        }
                    } footer: {
                        Text("校方統計數量")
                    }
                }
                .navigationTitle("全部分類")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("關閉") { showsCategories = false } }
                }
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func semesterMenu(_ records: PocketRecords) -> some View {
        Menu {
            ForEach(Array(records.semesters.enumerated()), id: \.offset) { index, label in
                Button {
                    semester = index
                } label: {
                    if index == Int(records.semesterIndex) { Label(label, systemImage: "checkmark") } else { Text(label) }
                }
            }
        } label: {
            HStack(spacing: 8) {
                Text(records.semesters[Int(records.semesterIndex)])
                    .font(PocketFont.cardTitle)
                    .foregroundStyle(PocketColor.onSurface)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.primary)
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 48)
            .pocketGlass(in: .capsule, interactive: true)
        }
        .accessibilityLabel("選擇學期，目前為\(records.semesters[Int(records.semesterIndex)])")
        .accessibilityIdentifier("records-semester")
    }

    @ViewBuilder
    private func summary(_ records: PocketRecords) -> some View {
        PocketCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("學期摘要")
                        .font(PocketFont.cardTitle)
                        .foregroundStyle(PocketColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    if kind == .attendance {
                        Button {
                            showsCategories = true
                        } label: {
                            Label("全部分類", systemImage: "chevron.right")
                                .labelStyle(TrailingIconLabelStyle())
                                .font(.subheadline.weight(.semibold))
                        }
                        .disabled(records.summary.isEmpty)
                        .accessibilityIdentifier("records-categories")
                    }
                }
                if kind == .merits {
                    Text("依校方分類查看獎懲紀錄")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    Picker("分類", selection: $filter) {
                        ForEach(PocketCampusStore.shared.meritFilters(), id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("records-filter")
                }
                let counts = kind == .attendance ? records.highlights : records.summary
                if counts.isEmpty {
                    Text(kind == .attendance ? "所選學期沒有缺曠統計" : "所選學期沒有獎懲紀錄")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: PocketMetrics.cardSpacing)], spacing: PocketMetrics.cardSpacing) {
                        ForEach(counts, id: \.category) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.category)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(PocketColor.onSurfaceVariant)
                                Text("\(item.count)")
                                    .font(PocketFont.display(30, relativeTo: .title))
                                    .monospacedDigit()
                                    .foregroundStyle(item.isConcern ? PocketColor.negative : PocketColor.onSurface)
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(PocketColor.background.opacity(0.6), in: .rect(cornerRadius: PocketMetrics.innerRadius, style: .continuous))
                            .accessibilityElement(children: .combine)
                        }
                    }
                }
            }
        }
    }

    /// Android's 查看歷年統計: collapsed at first, loaded on first open.
    private var historySection: some View {
        let history = kind == .attendance ? PocketCampusStore.shared.attendanceHistory() : PocketCampusStore.shared.meritHistory()
        return PocketCard {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    historyExpanded.toggle()
                    if historyExpanded, history == nil { Task { await loadHistory(force: false) } }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "chart.bar")
                            .foregroundStyle(PocketColor.primary)
                            .accessibilityHidden(true)
                        Text("查看歷年統計")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurface)
                        Spacer()
                        if historyError != nil {
                            Text("待重試").font(.caption.weight(.semibold)).foregroundStyle(PocketColor.negative)
                        } else if loadingHistory {
                            ProgressView()
                        }
                        Image(systemName: historyExpanded ? "chevron.up" : "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                            .accessibilityHidden(true)
                    }
                    .frame(minHeight: 44)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityValue(historyExpanded ? "已展開" : "已收合")
                .accessibilityIdentifier("records-history")

                if historyExpanded {
                    if let historyError {
                        if history != nil {
                            Text("更新未完成，以下顯示上次成功資料").font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        Text(historyError).font(.footnote).foregroundStyle(PocketColor.negative)
                        Button("重試") { Task { await loadHistory(force: true) } }.font(.subheadline.weight(.semibold))
                    }
                    if let history {
                        if history.rows.isEmpty {
                            Text("沒有歷年統計資料").font(.subheadline).foregroundStyle(PocketColor.onSurfaceVariant)
                        } else {
                            Text(history.note).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                            RecordHistoryTable(history: history)
                        }
                    } else if historyError == nil {
                        ProgressView("正在查詢").font(.footnote)
                    }
                }
            }
        }
    }

    private func loadRecords() async {
        guard model.usesLiveData, let live = model.live else { return }
        loadingRecords = true
        recordsError = await live.loadRecords(semesterIndex: semester)
        loadingRecords = false
    }

    private func loadHistory(force: Bool) async {
        guard let live = model.account else { return }
        loadingHistory = true
        historyError = await live.loadRecordHistory(merits: kind == .merits)
        loadingHistory = false
    }
}

private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.title
            configuration.icon.imageScale(.small)
        }
    }
}

/// Category column fixed, six halves scrolling sideways, as Android's table.
private struct RecordHistoryTable: View {
    let history: PocketRecordHistory

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                cell("類別", header: true).frame(width: 72, alignment: .leading)
                ForEach(history.rows, id: \.category) { row in
                    Divider()
                    cell(row.category, header: false).frame(width: 72, alignment: .leading)
                }
            }
            .accessibilityHidden(true)
            ScrollView(.horizontal) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 0) {
                        ForEach(history.terms, id: \.self) { cell($0, header: true).frame(width: 56) }
                    }
                    .accessibilityHidden(true)
                    ForEach(history.rows, id: \.category) { row in
                        Divider()
                        HStack(spacing: 0) {
                            ForEach(Array(row.cells.enumerated()), id: \.offset) { index, value in
                                cell(value, header: false)
                                    .frame(width: 56)
                                    .monospacedDigit()
                                    .accessibilityLabel(row.descriptions[index])
                            }
                        }
                    }
                }
            }
            .scrollIndicators(.visible)
        }
        .accessibilityIdentifier("records-history-table")
    }

    private func cell(_ text: String, header: Bool) -> some View {
        Text(text)
            .font(header ? .caption.weight(.semibold) : .subheadline)
            .foregroundStyle(header ? PocketColor.onSurfaceVariant : PocketColor.onSurface)
            .frame(minHeight: header ? 36 : 44)
    }
}

private struct RecordRow: View {
    let row: PocketRecordRow
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    PocketChip(
                        text: row.category,
                        foreground: row.isConcern ? PocketColor.negative : PocketColor.onPrimaryContainer,
                        background: row.isConcern ? PocketColor.negative.opacity(0.12) : PocketColor.primaryContainer
                    )
                    Text(row.countText)
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onSurface)
                    Spacer(minLength: 8)
                    Text(row.dateText)
                        .font(.footnote)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .layoutPriority(1)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                }
                Text(row.detail)
                    .font(.body)
                    .foregroundStyle(PocketColor.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                if !row.note.isEmpty {
                    Label(row.note, systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.positive)
                }
            }
            .accessibilityElement(children: .combine)
            if !row.group.isEmpty {
                Button {
                    withAnimation(.snappy) { expanded.toggle() }
                } label: {
                    Label(expanded ? "收合核定資訊" : "查看核定資訊", systemImage: expanded ? "chevron.up" : "chevron.down")
                        .labelStyle(TrailingIconLabelStyle())
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 44)
                }
                .accessibilityValue(expanded ? "已展開" : "已收合")
                .accessibilityIdentifier("record-approval-\(row.id)")
                if expanded {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("校方分類：\(row.group)")
                        if !row.yearTerm.isEmpty { Text("學期：\(row.yearTerm)") }
                        if !row.checkDate.isEmpty { Text("核定日期：\(row.checkDate)") }
                        if !row.cleanDate.isEmpty { Text("銷過日期：\(row.cleanDate)") }
                    }
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
    }
}
