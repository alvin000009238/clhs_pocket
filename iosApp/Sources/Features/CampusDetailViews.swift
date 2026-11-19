import PocketShared
import SwiftUI

// MARK: - Announcements

struct AnnouncementListView: View {
    @Environment(AppModel.self) private var model
    @State private var category = "全部"
    @State private var query = ""
    @State private var filter: PocketAnnouncementFilter = .all

    var body: some View {
        let store = PocketCampusStore.shared
        let items = store.announcements(
            clock: model.clock, category: category, query: query, filter: filter,
            unreadIds: model.unreadAnnouncementIds, bookmarkedIds: model.bookmarkedAnnouncementIds
        )
        let unread = Int(store.unreadCount(clock: model.clock, unreadIds: model.unreadAnnouncementIds))
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Picker("顯示", selection: $filter) {
                    Text("全部").tag(PocketAnnouncementFilter.all)
                    Text(unread > 0 ? "未讀 \(unread)" : "未讀").tag(PocketAnnouncementFilter.unread)
                    Text("收藏").tag(PocketAnnouncementFilter.bookmarked)
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("announcement-filter")

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(store.categories(clock: model.clock), id: \.self) { name in
                            let selected = name == category
                            Button {
                                category = name
                            } label: {
                                Text(name)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(selected ? PocketColor.onPrimary : PocketColor.onSurface)
                                    .padding(.horizontal, 16)
                                    .frame(minHeight: 40)
                                    .background(selected ? PocketColor.primary : PocketColor.surfaceLow, in: .capsule)
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                            .accessibilityIdentifier("category-\(name)")
                        }
                    }
                }
                .scrollClipDisabled()

                if items.isEmpty {
                    ContentUnavailableView {
                        Label(emptyTitle, systemImage: filter == .bookmarked ? "bookmark" : "tray")
                    } description: {
                        Text(emptyDetail)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 32)
                } else {
                    PocketCard(padding: 8) {
                        AnnouncementRows(announcements: items)
                    }
                }
            }
            .frame(maxWidth: 720)
            .pocketPage()
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜尋標題或處室")
        .navigationTitle("公告")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("全部標為已讀", systemImage: "envelope.open") {
                    model.unreadAnnouncementIds = []
                }
                .disabled(unread == 0)
                .accessibilityIdentifier("announcements-read-all")
            }
        }
    }

    private var emptyTitle: String {
        if !query.isEmpty { return "找不到符合的公告" }
        switch filter {
        case .unread: return "沒有未讀公告"
        case .bookmarked: return "還沒有收藏"
        default: return "這個分類沒有公告"
        }
    }

    private var emptyDetail: String {
        if !query.isEmpty { return "試試其他關鍵字，或切換分類。" }
        return filter == .bookmarked ? "在公告內文點選書籤，就能在這裡找到。" : ""
    }
}

struct AnnouncementDetailView: View {
    @Environment(AppModel.self) private var model
    let id: String

    var body: some View {
        let detail = PocketCampusStore.shared.announcementDetail(clock: model.clock, id: id)
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
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(detail.paragraphs, id: \.self) { paragraph in
                            Text(paragraph)
                                .font(.body)
                                .lineSpacing(5)
                                .foregroundStyle(PocketColor.onSurface)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if !detail.attachments.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            PocketSectionHeader(title: "附件") { EmptyView() }
                            PocketCard(padding: 8) {
                                VStack(spacing: 0) {
                                    ForEach(Array(detail.attachments.enumerated()), id: \.element.name) { index, file in
                                        if index > 0 { PocketRowDivider().padding(.leading, 60) }
                                        HStack(spacing: 12) {
                                            PocketIconTile(systemImage: "doc.fill", size: 36)
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(file.name).font(.body.weight(.medium)).foregroundStyle(PocketColor.onSurface)
                                                Text(file.sizeText).font(.footnote).monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                                            }
                                            Spacer(minLength: 0)
                                        }
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 8)
                                        .accessibilityElement(children: .combine)
                                    }
                                }
                            }
                            Text("示範資料不含實際檔案。")
                                .font(.footnote)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                .padding(.horizontal, 4)
                        }
                    }
                }
                .frame(maxWidth: 720, alignment: .leading)
                .pocketPage()
            } else {
                ContentUnavailableView("找不到這則公告", systemImage: "questionmark.circle")
            }
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
        .onAppear { model.unreadAnnouncementIds.remove(id) }
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

    var body: some View {
        let store = PocketCampusStore.shared
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

                if mode == 0 {
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
                    PocketCard(padding: 8) { EventRows(events: events) }
                }
            }
            .frame(maxWidth: mode == 0 && sizeClass == .regular ? PocketMetrics.regularContentWidth : 720)
            .pocketPage()
        }
        .navigationTitle("行事曆")
        .navigationBarTitleDisplayMode(.large)
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
                    EventRows(events: events)
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
            ? store.attendance(semesterIndex: Int32(semester)) : store.merits(semesterIndex: Int32(semester))
        return ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
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

                PocketColumns {
                    if !records.summary.isEmpty {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: PocketMetrics.cardSpacing)], spacing: PocketMetrics.cardSpacing) {
                            ForEach(records.summary, id: \.category) { item in
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
                                .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.innerRadius, style: .continuous))
                                .accessibilityElement(children: .combine)
                            }
                        }
                    }
                } trailing: {
                    if records.rows.isEmpty {
                        PocketCard {
                            Label(records.emptyText, systemImage: "checkmark.circle")
                                .font(.subheadline)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            PocketSectionHeader(title: "明細") { EmptyView() }
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
            .pocketPage()
        }
    }
}

private struct RecordRow: View {
    let row: PocketRecordRow

    var body: some View {
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
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }
}
