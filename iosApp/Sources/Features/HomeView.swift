import PocketShared
import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var width: CGFloat = 0
    @State private var showingEditor = false
    @State private var editing: PocketWidget?
    @State private var dropTarget: String?
    @State private var refreshes = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// Column count comes from the shared layout engine so breakpoints match Android.
    private var columns: Int {
        guard width > 0 else { return 4 }
        return Int(PocketDashboard.shared.columns(widthPoints: Double(width), fontScale: typeSize.pocketFontScale))
    }

    var body: some View {
        let widgets = model.widgets(columns: columns)
        ScrollView {
            VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                HomeGreeting(clock: model.clock, greeting: greetingLine, isRefreshing: isRefreshing)
                DashboardGrid(columns: columns) {
                    ForEach(widgets) { widget in
                        WidgetView(widget: widget, columns: columns)
                            .overlay {
                                if dropTarget == widget.id {
                                    RoundedRectangle(cornerRadius: HomeShape.radius, style: .continuous)
                                        .strokeBorder(PocketColor.primary, lineWidth: 3)
                                }
                            }
                            .contentShape(.contextMenuPreview, RoundedRectangle(cornerRadius: HomeShape.radius, style: .continuous))
                            .dashboardPlacement(widget)
                            .contextMenu { menu(for: widget) }
                            // Press and drag a widget onto another to take its place; the editor
                            // offers the same reordering with buttons for VoiceOver and Switch Control.
                            .draggable(widget.id)
                            .dropDestination(for: String.self) { ids, _ in
                                // The drop ends the drag even if no exit callback follows it.
                                dropTarget = nil
                                guard let id = ids.first, id != widget.id,
                                      widgets.contains(where: { $0.id == id }),
                                      let target = widgets.firstIndex(where: { $0.id == widget.id }) else { return false }
                                withAnimation(reduceMotion ? nil : .snappy) {
                                    model.editDashboard { $0.move(layout: $1, id: id, index: Int32(target)) }
                                }
                                return true
                            } isTargeted: { targeted in
                                if targeted { dropTarget = widget.id } else if dropTarget == widget.id { dropTarget = nil }
                            }
                    }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                .sensoryFeedback(.selection, trigger: widgets.map(\.id))
            }
            .pocketPage()
        }
        .refreshable {
            await model.refreshLiveData(force: true)
            refreshes += 1
        }
        .sensoryFeedback(.success, trigger: refreshes)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            brandItem
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showingEditor = true
                } label: {
                    Image(systemName: "square.grid.2x2")
                }
                .accessibilityLabel("編輯首頁")
                .accessibilityIdentifier("home-edit")
            }
        }
        .sheet(isPresented: $showingEditor) { DashboardEditorView() }
        .sheet(item: $editing) { widget in
            NavigationStack { WidgetSettingsView(widgetID: widget.id, showsDone: true) }
        }
    }

    @ViewBuilder
    private func menu(for widget: PocketWidget) -> some View {
        if widget.sizes.count > 1 {
            Menu("尺寸", systemImage: "arrow.up.left.and.arrow.down.right") {
                ForEach(widget.sizes, id: \.self) { size in
                    Button {
                        model.editDashboard { $0.resize(layout: $1, id: widget.id, columns: size.columns, rows: size.rows) }
                    } label: {
                        if size.isSelected { Label(size.label, systemImage: "checkmark") } else { Text(size.label) }
                    }
                }
            }
        }
        if widget.settings != .none {
            Button("小工具設定", systemImage: "slider.horizontal.3") { editing = widget }
        }
        Button("編輯首頁", systemImage: "square.grid.2x2") { showingEditor = true }
        Button("移除", systemImage: "minus.circle", role: .destructive) {
            model.editDashboard { $0.remove(layout: $1, id: widget.id) }
        }
    }

    @ToolbarContentBuilder
    private var brandItem: some ToolbarContent {
        if #available(iOS 26.0, *) {
            ToolbarItem(placement: .topBarLeading) { PocketBrandMark() }
                .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem(placement: .topBarLeading) { PocketBrandMark() }
        }
    }

    private var isRefreshing: Bool {
        model.live?.isLoadingPublic == true || model.live?.isLoadingPrivate == true
    }

    private var greetingLine: String {
        let hour = Int(model.clock.minuteOfDay) / 60
        let salutation = switch hour {
        case 5..<11: "早安"
        case 11..<14: "午安"
        case 14..<18: "下午好"
        default: "晚安"
        }
        let studentName = model.isSignedIn ? model.profile.studentName : ""
        return studentName.isEmpty ? salutation : "\(salutation)，\(studentName)"
    }
}

private struct CourseHeroCard: View {
    let hero: PocketCourseHero
    var compact = false

    var body: some View {
        HomeSurface(shape: HomeShape.hero, fill: PocketColor.primaryContainer, padding: compact ? 16 : 24) {
            VStack(alignment: .leading, spacing: 0) {
                Text(hero.headline)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))

                Text(hero.courseName)
                    .font(compact ? PocketFont.display(26, relativeTo: .title) : PocketFont.hero)
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .padding(.top, 6)

                if !compact {
                Text(hero.supportingText)
                    .font(.body)
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, 4)
                }

                if hero.progress >= 0, !compact {
                    HeroProgress(value: hero.progress)
                        .padding(.top, 20)
                }

                Text(hero.timeText)
                    .font(.footnote.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, compact ? 4 : hero.progress >= 0 ? 10 : 18)

                if let next = hero.nextLine, !compact {
                    Text(next)
                    .monospacedDigit()
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PocketColor.background.opacity(0.55), in: .rect(cornerRadius: 16, style: .continuous))
                    .padding(.top, 16)
                }
            }
            // On iPad the hero shares a row with taller widgets; fill the row instead of floating.
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct GuestHeroCard: View {
    @Environment(AppModel.self) private var model
    var compact = false

    var body: some View {
        HomeSurface(shape: HomeShape.hero, fill: PocketColor.primaryContainer, padding: compact ? 16 : 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text("登入後顯示今日課程")
                    .font(PocketFont.display(compact ? 20 : 24, relativeTo: .title2))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                if !compact {
                    Text("課表與成績需要登入。校園公告、行事曆與天氣可直接瀏覽。")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                }
                Button {
                    model.requestLogin()
                } label: {
                    Text(model.loginActionTitle)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18)
                        .frame(minHeight: 44)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                }
                .padding(.top, 8)
            }
        }
    }
}

/// Signed in, but no lesson to show: still loading, or a day or holiday without classes.
private struct NoLessonCard: View {
    @Environment(AppModel.self) private var model
    var compact = false

    var body: some View {
        HomeSurface(shape: HomeShape.hero, fill: PocketColor.primaryContainer, padding: compact ? 16 : 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text(model.live?.isLoadingPrivate == true ? "正在載入課表…" : "近期沒有課程")
                    .font(PocketFont.display(compact ? 20 : 24, relativeTo: .title2))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                if !compact {
                    Text("開學或課表公布後，這裡會顯示目前與下一堂課。")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

extension DynamicTypeSize {
    /// Approximate body-text scale relative to the default size, for the shared layout breakpoints.
    var pocketFontScale: Double {
        switch self {
        case .xSmall: 0.82
        case .small: 0.88
        case .medium: 0.94
        case .large: 1.0
        case .xLarge: 1.12
        case .xxLarge: 1.24
        case .xxxLarge: 1.35
        case .accessibility1: 1.65
        case .accessibility2: 1.94
        case .accessibility3: 2.35
        case .accessibility4: 2.76
        case .accessibility5: 3.12
        @unknown default: 1.0
        }
    }
}

// MARK: - Grid

private struct PlacementKey: LayoutValueKey {
    static let defaultValue = CGRect(x: 0, y: 0, width: 4, height: 1)
}

extension View {
    func dashboardPlacement(_ widget: PocketWidget) -> some View {
        layoutValue(key: PlacementKey.self, value: CGRect(
            x: Int(widget.column), y: Int(widget.row), width: Int(widget.columns), height: Int(widget.rows)
        ))
    }
}

/// Places widgets on the cells chosen by the shared layout engine. Rows grow to
/// fit their tallest widget, so large text never clips.
struct DashboardGrid: Layout {
    let columns: Int
    var spacing: CGFloat = PocketMetrics.cardSpacing
    var rowUnit: CGFloat = 78

    private func cellWidth(_ total: CGFloat) -> CGFloat {
        (total - spacing * CGFloat(columns - 1)) / CGFloat(columns)
    }

    private func rowHeights(_ subviews: Subviews, width: CGFloat) -> [CGFloat] {
        let cell = cellWidth(width)
        let places = subviews.map { $0[PlacementKey.self] }
        let count = places.map { Int($0.maxY) }.max() ?? 0
        var heights = [CGFloat](repeating: rowUnit, count: count)
        let order = places.indices.sorted { places[$0].height < places[$1].height }
        for index in order {
            let place = places[index]
            let span = Int(place.height)
            let itemWidth = cell * place.width + spacing * (place.width - 1)
            let needed = subviews[index].sizeThatFits(ProposedViewSize(width: itemWidth, height: nil)).height
            let rows = Int(place.minY)..<Int(place.minY) + span
            let current = rows.reduce(0) { $0 + heights[$1] } + spacing * CGFloat(span - 1)
            if needed > current {
                let extra = (needed - current) / CGFloat(span)
                for row in rows { heights[row] += extra }
            }
        }
        return heights
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 360
        let heights = rowHeights(subviews, width: width)
        let total = heights.reduce(0, +) + spacing * CGFloat(max(heights.count - 1, 0))
        return CGSize(width: width, height: total)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let heights = rowHeights(subviews, width: bounds.width)
        let cell = cellWidth(bounds.width)
        var offsets: [CGFloat] = []
        var y: CGFloat = 0
        for height in heights {
            offsets.append(y)
            y += height + spacing
        }
        for subview in subviews {
            let place = subview[PlacementKey.self]
            let rows = Int(place.minY)..<Int(place.maxY)
            let size = CGSize(
                width: cell * place.width + spacing * (place.width - 1),
                height: rows.reduce(0) { $0 + heights[$1] } + spacing * (place.height - 1)
            )
            subview.place(
                at: CGPoint(x: bounds.minX + place.minX * (cell + spacing), y: bounds.minY + offsets[Int(place.minY)]),
                proposal: ProposedViewSize(size)
            )
        }
    }
}

// MARK: - Widgets

private struct WidgetView: View {
    @Environment(AppModel.self) private var model
    let widget: PocketWidget
    let columns: Int

    private var isWide: Bool { widget.columns >= 4 }
    private var isShort: Bool { widget.rows == 1 }

    var body: some View {
        Group {
            switch widget.type {
            case "schedule":
                if let hero = widget.hero {
                    Button { model.select(.schedule) } label: { CourseHeroCard(hero: hero, compact: isShort) }
                        .buttonStyle(.plain)
                } else if widget.needsSignIn {
                    GuestHeroCard(compact: isShort)
                } else {
                    NoLessonCard(compact: isShort)
                }
            case "weather":
                if let weather = widget.weather {
                    WeatherCard(weather: weather, wide: isWide, short: isShort)
                } else {
                    // Offline or not loaded yet: keep the widget's place instead of leaving a hole.
                    MetricPlaceholder(title: widget.title, failure: "暫時無法取得天氣", short: isShort)
                }
            case "youbike":
                if let station = widget.youBike {
                    YouBikeCard(station: station, wide: isWide, short: isShort)
                } else {
                    MetricPlaceholder(title: widget.title, failure: "暫時無法取得車位資訊", short: isShort)
                }
            case "countdown":
                CountdownCard(countdown: widget.countdown, wide: isWide, short: isShort)
            case "alerts":
                HomeSection(title: widget.title) {
                    if widget.needsSignIn {
                        EmptyLine(text: "登入後顯示調課與成績更新")
                    } else if widget.alerts.isEmpty {
                        EmptyLine(text: "目前沒有異動")
                    } else {
                        ForEach(Array(widget.alerts.enumerated()), id: \.element.id) { index, alert in
                            AlertRow(alert: alert)
                                .background(PocketColor.surfaceLow, in: HomeShape.segment(index, of: widget.alerts.count))
                        }
                    }
                } trailing: { EmptyView() }
            case "calendar":
                HomeSection(title: widget.title) {
                    if widget.events.isEmpty {
                        EmptyLine(text: "近期沒有行程")
                    } else {
                        ForEach(Array(widget.events.enumerated()), id: \.element.id) { index, event in
                            EventTile(event: event)
                                .background(PocketColor.surfaceLow, in: HomeShape.segment(index, of: widget.events.count))
                        }
                    }
                } trailing: { PocketMoreLink(value: PocketRoute.calendar) }
            case "announcements":
                HomeSection(title: widget.title) {
                    if widget.announcements.isEmpty {
                        EmptyLine(text: "沒有未讀、置頂或收藏的公告")
                    } else {
                        ForEach(Array(widget.announcements.enumerated()), id: \.element.id) { index, item in
                            AnnouncementTile(item: item)
                                .background(PocketColor.surfaceLow, in: HomeShape.segment(index, of: widget.announcements.count))
                        }
                    }
                } trailing: { PocketMoreLink(value: PocketRoute.announcements) }
            default:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityIdentifier("widget-\(widget.type)")
    }
}

// MARK: - Home surfaces

/// Corner sets for the home page: the schedule hero, metric widgets and stacked list rows.
enum HomeShape {
    static let radius: CGFloat = 28
    static let hero = UnevenRoundedRectangle(topLeadingRadius: 32, bottomLeadingRadius: 12, bottomTrailingRadius: 32, topTrailingRadius: 32, style: .continuous)
    static let metric = UnevenRoundedRectangle(topLeadingRadius: 24, bottomLeadingRadius: 24, bottomTrailingRadius: 12, topTrailingRadius: 24, style: .continuous)

    /// One row of a stack of separate rows: the outer corners of the stack are round, the inner ones tight.
    static func segment(_ index: Int, of count: Int) -> UnevenRoundedRectangle {
        let outer: CGFloat = 22, inner: CGFloat = 6
        let top = index == 0 ? outer : inner
        let bottom = index == count - 1 ? outer : inner
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom, bottomTrailingRadius: bottom, topTrailingRadius: top, style: .continuous)
    }
}

/// A flat tonal home card.
private struct HomeSurface<S: Shape, Content: View>: View {
    let shape: S
    var fill: Color
    var padding: CGFloat = PocketMetrics.cardPadding
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: shape)
            .contentShape(shape)
    }
}

enum HomeClock {
    static func weekday(_ clock: PocketClock) -> String {
        ["一", "二", "三", "四", "五", "六", "日"][(Int(clock.dayOfWeek) + 6) % 7]
    }
}

/// The date and greeting at the top of 首頁.
private struct HomeGreeting: View {
    let clock: PocketClock
    let greeting: String
    let isRefreshing: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(clock.month) 月 \(clock.day) 日 星期\(HomeClock.weekday(clock))")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                Text(greeting)
                    .font(PocketFont.display(30, relativeTo: .largeTitle))
                    .foregroundStyle(PocketColor.onSurface)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            if isRefreshing {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("更新中")
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
    }
}

private struct HeroProgress: View {
    let value: Double

    var body: some View {
        Capsule()
            .fill(PocketColor.onPrimaryContainer.opacity(0.14))
            .overlay(alignment: .leading) {
                GeometryReader { proxy in
                    Capsule()
                        .fill(PocketColor.primary)
                        .frame(width: max(8, proxy.size.width * min(max(value, 0), 1)))
                }
            }
            .frame(height: 8)
            .accessibilityElement()
            .accessibilityLabel("本節進度")
            .accessibilityValue("\(Int((value * 100).rounded()))%")
    }
}

/// A list widget: the title sits above a stack of separate rows.
private struct HomeSection<Content: View, Trailing: View>: View {
    let title: String
    @ViewBuilder var content: Content
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(title)
                    .font(PocketFont.display(21, relativeTo: .title3))
                    .foregroundStyle(PocketColor.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                trailing
            }
            .padding(.leading, 4)
            .frame(minHeight: 44)
            VStack(spacing: 3) { content }
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }
}

private struct EmptyLine: View {
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(PocketColor.onSurfaceVariant)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minHeight: 64)
        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct RowChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(PocketColor.onSurfaceVariant)
            .accessibilityHidden(true)
    }
}

/// The date at the start of an event or announcement row.
private struct DateBadge: View {
    var top: String?
    let bottom: String
    var highlighted = false

    var body: some View {
        VStack(spacing: 0) {
            if let top {
                Text(top).font(.caption2.weight(.semibold))
            }
            Text(bottom)
                .font(top == nil ? .footnote.weight(.semibold) : PocketFont.display(18, relativeTo: .title3))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .foregroundStyle(highlighted ? PocketColor.onPrimary : PocketColor.onSurface)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .padding(.horizontal, 4)
        .frame(width: 54, height: 48)
        .background(highlighted ? PocketColor.primary : PocketColor.surfaceHigh, in: .rect(cornerRadius: 14, style: .continuous))
    }
}

private struct EventTile: View {
    @Environment(AppModel.self) private var model
    @State private var selectedEvent: PocketEventDetail?
    let event: PocketEventRow

    var body: some View {
        Group {
            // Open the tapped event itself; fall back to the calendar when its detail is unavailable.
            if let detail = PocketAnnouncementFeed.shared.eventDetail(clock: model.clock, id: event.id) {
                Button { selectedEvent = detail } label: { row }
            } else {
                NavigationLink(value: PocketRoute.calendar) { row }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .sheet(item: $selectedEvent) { CalendarEventSheet(detail: $0) }
    }

    private var row: some View {
        HStack(spacing: 14) {
            DateBadge(top: event.monthText, bottom: event.dayText, highlighted: event.isOngoing)
            VStack(alignment: .leading, spacing: 3) {
                Text(event.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(event.subtitle)
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(event.isOngoing ? PocketColor.primary : PocketColor.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            RowChevron()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(minHeight: 72)
        .contentShape(.rect)
    }
}

private struct AnnouncementTile: View {
    let item: PocketAnnouncement

    var body: some View {
        NavigationLink(value: PocketRoute.announcement(item.id)) {
            HStack(spacing: 14) {
                DateBadge(bottom: item.shortDateText, highlighted: item.isUnread)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(.body.weight(item.isUnread ? .semibold : .regular))
                        .foregroundStyle(PocketColor.onSurface)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    HStack(spacing: 6) {
                        Text(item.unit)
                            .font(.footnote)
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                            .lineLimit(1)
                        if item.isUnread {
                            PocketChip(text: "未讀", foreground: PocketColor.negative, background: PocketColor.negative.opacity(0.14))
                                .accessibilityHidden(true)
                        }
                        if item.isPinned {
                            PocketChip(text: "置頂", foreground: PocketColor.onTertiaryContainer, background: PocketColor.tertiaryContainer)
                        }
                    }
                }
                Spacer(minLength: 8)
                if item.isBookmarked {
                    Image(systemName: "bookmark.fill")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.primary)
                        .accessibilityLabel("已收藏")
                }
                RowChevron()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 72)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityValue(item.isUnread ? "未讀" : "")
        .accessibilityIdentifier("announcement-\(item.id)")
    }
}

private struct AlertRow: View {
    let alert: PocketAlertRow

    var body: some View {
        HStack(spacing: 14) {
            PocketIconTile(
                systemImage: alert.symbol, foreground: PocketColor.onTertiaryContainer,
                background: PocketColor.tertiaryContainer, size: 40
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(alert.title)
                    .font(.body.weight(.medium))
                    .foregroundStyle(PocketColor.onSurface)
                Text(alert.detail)
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(minHeight: 72)
        .accessibilityElement(children: .combine)
    }
}

/// Weather or YouBike before its data arrives: placeholder blocks while loading, then a retry.
private struct MetricPlaceholder: View {
    @Environment(AppModel.self) private var model
    let title: String
    let failure: String
    let short: Bool

    var body: some View {
        HomeSurface(shape: HomeShape.metric, fill: PocketColor.surfaceLow, padding: short ? 16 : PocketMetrics.cardPadding) {
            if model.live?.isLoadingPublic == true {
                VStack(alignment: .leading, spacing: 10) {
                    SkeletonBar(width: 88, height: 14)
                    SkeletonBar(width: 120, height: short ? 20 : 40)
                    if !short {
                        Spacer(minLength: 0)
                        SkeletonBar(width: 150, height: 12)
                    }
                }
                .frame(maxHeight: .infinity, alignment: .top)
                .accessibilityElement()
                .accessibilityLabel("\(title)載入中")
            } else {
                let layout = short
                    ? AnyLayout(HStackLayout(alignment: .center, spacing: 8))
                    : AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                layout {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                        Text(failure)
                            .font(.subheadline)
                            .foregroundStyle(PocketColor.onSurface)
                    }
                    .accessibilityElement(children: .combine)
                    Spacer(minLength: 0)
                    Button {
                        Task { await model.refreshPublicData() }
                    } label: {
                        Label("重試", systemImage: "arrow.clockwise")
                            .font(.subheadline.weight(.semibold))
                            .frame(minHeight: 44)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(PocketColor.primary)
                }
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
    }
}

private struct SkeletonBar: View {
    let width: CGFloat
    let height: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: min(height / 2, 10), style: .continuous)
            .fill(PocketColor.onSurface.opacity(0.08))
            .frame(width: width, height: height)
    }
}

private struct WeatherFacts: View {
    let weather: PocketWeather

    var body: some View {
        ViewThatFits(in: .horizontal) {
            Text("\(weather.highText) / \(weather.lowText) · 降雨 \(weather.rainText)")
            VStack(alignment: .leading, spacing: 2) {
                Text("\(weather.highText) / \(weather.lowText)")
                Text("降雨 \(weather.rainText)")
            }
        }
        .font(.caption.weight(.medium))
        .monospacedDigit()
        .foregroundStyle(PocketColor.onSurfaceVariant)
        .lineLimit(1)
    }
}

private struct WeatherCard: View {
    let weather: PocketWeather
    let wide: Bool
    let short: Bool

    private var symbol: String {
        switch weather.kind {
        case .sunny: "sun.max.fill"
        case .partlyCloudy: "cloud.sun.fill"
        case .cloudy: "cloud.fill"
        case .rain: "cloud.rain.fill"
        default: "cloud.bolt.rain.fill"
        }
    }

    private var icon: some View {
        Image(systemName: symbol)
            .symbolRenderingMode(.multicolor)
            .font(.title2)
            .accessibilityHidden(true)
    }

    private var range: String { "\(weather.highText) / \(weather.lowText) · 降雨 \(weather.rainText)" }

    var body: some View {
        HomeSurface(shape: HomeShape.metric, fill: PocketColor.surfaceLow, padding: short ? 16 : PocketMetrics.cardPadding) {
            if short {
                HStack(spacing: 12) {
                    Text(weather.temperatureText)
                        .font(PocketFont.display(30, relativeTo: .title))
                        .foregroundStyle(PocketColor.onSurface)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(wide ? "\(weather.location) · \(weather.condition)" : weather.location)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurface)
                            .lineLimit(1)
                        Text(wide ? "\(range) · 體感 \(weather.apparentText)" : weather.condition)
                            .font(.caption)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if wide { icon }
                }
                .frame(maxHeight: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(weather.location)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                        Spacer()
                        icon
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text(weather.temperatureText)
                            .font(PocketFont.display(44, relativeTo: .largeTitle))
                            .foregroundStyle(PocketColor.onSurface)
                        if wide {
                            Text("\(weather.condition) · 體感 \(weather.apparentText)")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(PocketColor.onSurface)
                        }
                    }
                    .padding(.top, 8)
                    if !wide {
                        Text(weather.condition)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(PocketColor.onSurface)
                    }
                    Spacer(minLength: 12)
                    WeatherFacts(weather: weather)
                }
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(weather.location)天氣，\(weather.temperatureText)，\(weather.condition)，降雨機率 \(weather.rainText)")
    }
}

private struct CountdownCard: View {
    let countdown: PocketCountdown?
    let wide: Bool
    let short: Bool

    var body: some View {
        HomeSurface(shape: HomeShape.metric, fill: PocketColor.tertiaryContainer, padding: short ? 16 : PocketMetrics.cardPadding) {
            if let countdown {
                if short {
                    HStack(spacing: 12) {
                        Text(countdown.label)
                            .font(PocketFont.display(wide ? 30 : 26, relativeTo: .title))
                            .foregroundStyle(PocketColor.onTertiaryContainer)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                            .layoutPriority(1)
                        details(countdown)
                        Spacer(minLength: 0)
                    }
                    .frame(maxHeight: .infinity)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("倒數")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onTertiaryContainer.opacity(0.85))
                        Text(countdown.label)
                            .font(PocketFont.display(44, relativeTo: .largeTitle))
                            .foregroundStyle(PocketColor.onTertiaryContainer)
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                            .padding(.top, 8)
                        Spacer(minLength: 12)
                        details(countdown)
                    }
                    .frame(maxHeight: .infinity, alignment: .top)
                }
            } else {
                Text("行程已不在行事曆中")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onTertiaryContainer)
                    .frame(maxHeight: .infinity)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(countdown.map {
            // The label is a day count, or a word such as 進行中／今天 that cannot follow 還有.
            Int($0.label) != nil ? "距離\($0.title)還有 \($0.label) 天，\($0.dateText)" : "\($0.title)\($0.label)，\($0.dateText)"
        } ?? "倒數行程已不存在")
    }

    private func details(_ countdown: PocketCountdown) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(countdown.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PocketColor.onTertiaryContainer)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
            Text(countdown.dateText)
                .font(.caption)
                .monospacedDigit()
                .foregroundStyle(PocketColor.onTertiaryContainer.opacity(0.8))
                .lineLimit(1)
        }
    }
}

private struct YouBikeCard: View {
    let station: PocketYouBikeStation
    let wide: Bool
    let short: Bool

    var body: some View {
        HomeSurface(shape: HomeShape.metric, fill: PocketColor.surfaceLow, padding: short ? 16 : PocketMetrics.cardPadding) {
            if short {
                HStack(spacing: 12) {
                    PocketIconTile(systemImage: "bicycle", size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(station.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurface)
                            .lineLimit(1)
                        // The narrow layout drops the detail line, except a suspension notice that changes what the numbers mean.
                        Text(wide ? "可借 \(station.bikesText) · 可還 \(station.docksText) · \(station.detailText)"
                            : station.detailText.contains("暫停") ? station.detailText
                            : "可借 \(station.bikesText) · 可還 \(station.docksText)")
                            .font(.caption)
                            .monospacedDigit()
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .frame(maxHeight: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    Label(station.name, systemImage: "bicycle")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .lineLimit(1)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(station.bikesText)
                            .font(PocketFont.display(44, relativeTo: .largeTitle))
                            .foregroundStyle(PocketColor.onSurface)
                        Text("可借")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(PocketColor.onSurface)
                    }
                    .padding(.top, 8)
                    Spacer(minLength: 12)
                    // One fact per line so a narrow card never breaks in the middle of an item.
                    VStack(alignment: .leading, spacing: 1) {
                        Text("可還 \(station.docksText)")
                        Text(station.detailText)
                    }
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                }
                .frame(maxHeight: .infinity, alignment: .top)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("YouBike \(station.name)，可借 \(station.bikesText) 輛，可還 \(station.docksText) 格")
    }
}
