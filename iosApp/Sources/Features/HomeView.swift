import PocketShared
import SwiftUI

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var width: CGFloat = 0
    @State private var showingEditor = false
    @State private var editing: PocketWidget?
    @State private var dropTarget: String?
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
                greeting
                DashboardGrid(columns: columns) {
                    ForEach(widgets) { widget in
                        WidgetView(widget: widget, columns: columns)
                            .overlay {
                                if dropTarget == widget.id {
                                    RoundedRectangle(cornerRadius: PocketMetrics.cardRadius, style: .continuous)
                                        .strokeBorder(PocketColor.primary, lineWidth: 3)
                                }
                            }
                            .dashboardPlacement(widget)
                            .contextMenu { menu(for: widget) }
                            // Press and drag a widget onto another to take its place; the editor
                            // offers the same reordering with buttons for VoiceOver and Switch Control.
                            .draggable(widget.id)
                            .dropDestination(for: String.self) { ids, _ in
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

                Button {
                    showingEditor = true
                } label: {
                    Label("編輯首頁", systemImage: "square.grid.2x2")
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 18)
                        .frame(minHeight: 44)
                        .background(PocketColor.surfaceLow, in: .capsule)
                }
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("home-edit")
            }
            .pocketPage()
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { brandItem }
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

    private var greeting: some View {
        let clock = model.clock
        let hour = Int(clock.minuteOfDay) / 60
        let salutation = switch hour {
        case 5..<11: "早安"
        case 11..<14: "午安"
        case 14..<18: "下午好"
        default: "晚安"
        }
        let weekday = ["一", "二", "三", "四", "五", "六", "日"][Int(clock.dayOfWeek) - 1]
        let name = model.isSignedIn ? "，\(model.profile.studentName)" : ""
        return VStack(alignment: .leading, spacing: 4) {
            Text("\(clock.month) 月 \(clock.day) 日 週\(weekday)")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(PocketColor.onSurfaceVariant)
            Text("\(salutation)\(name)")
                .font(PocketFont.display(30, relativeTo: .largeTitle))
                .foregroundStyle(PocketColor.onSurface)
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.horizontal, 4)
        .padding(.top, 4)
    }
}

private struct CourseHeroCard: View {
    let hero: PocketCourseHero
    var compact = false

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: compact ? 16 : 24) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    if hero.isInClass {
                        Circle()
                            .fill(PocketColor.primary)
                            .frame(width: 8, height: 8)
                    }
                    Text(hero.headline)
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))

                Text(hero.courseName)
                    .font(compact ? PocketFont.display(26, relativeTo: .title) : PocketFont.hero)
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .padding(.top, 6)

                if !compact {
                Label(hero.supportingText, systemImage: "mappin.and.ellipse")
                    .font(.body)
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, 4)
                }

                if hero.progress >= 0, !compact {
                    ProgressView(value: hero.progress)
                        .tint(PocketColor.primary)
                        .scaleEffect(x: 1, y: 1.6, anchor: .center)
                        .padding(.top, 20)
                        .accessibilityLabel("本節進度")
                }

                Text(hero.timeText)
                    .font(.footnote.weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    .padding(.top, compact ? 4 : hero.progress >= 0 ? 10 : 18)

                if let next = hero.nextLine, !compact {
                    HStack(spacing: 8) {
                        Image(systemName: "arrow.turn.down.right")
                            .imageScale(.small)
                        Text(next)
                            .monospacedDigit()
                    }
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(PocketColor.background.opacity(0.55), in: .rect(cornerRadius: 14, style: .continuous))
                    .padding(.top, 16)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

private struct GuestHeroCard: View {
    @Environment(AppModel.self) private var model
    var compact = false

    var body: some View {
        PocketCard(fill: PocketColor.primaryContainer, padding: compact ? 16 : 24) {
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
                } else {
                    GuestHeroCard(compact: isShort)
                }
            case "weather":
                if let weather = widget.weather {
                    WeatherCard(weather: weather, wide: isWide, short: isShort)
                } else {
                    // Offline or not loaded yet: keep the widget's place instead of leaving a hole.
                    ListCard(title: widget.title, symbol: widget.symbol) { EmptyLine(text: "暫時無法取得天氣") } trailing: { EmptyView() }
                }
            case "youbike":
                if let station = widget.youBike {
                    YouBikeCard(station: station, wide: isWide, short: isShort)
                } else {
                    ListCard(title: widget.title, symbol: widget.symbol) { EmptyLine(text: "暫時無法取得車位資訊") } trailing: { EmptyView() }
                }
            case "countdown":
                CountdownCard(countdown: widget.countdown, wide: isWide, short: isShort)
            case "alerts":
                ListCard(title: widget.title, symbol: widget.symbol) {
                    if widget.needsSignIn {
                        EmptyLine(text: "登入後顯示調課與成績更新")
                    } else if widget.alerts.isEmpty {
                        EmptyLine(text: "目前沒有異動")
                    } else {
                        ForEach(Array(widget.alerts.enumerated()), id: \.element.id) { index, alert in
                            if index > 0 { PocketRowDivider().padding(.leading, 56) }
                            AlertRow(alert: alert)
                        }
                    }
                } trailing: { EmptyView() }
            case "calendar":
                ListCard(title: widget.title, symbol: widget.symbol) {
                    if widget.events.isEmpty { EmptyLine(text: "近期沒有行程") } else { EventRows(events: widget.events) }
                } trailing: { PocketMoreLink(label: "全部", value: PocketRoute.calendar) }
            case "announcements":
                ListCard(title: widget.title, symbol: widget.symbol) {
                    if widget.announcements.isEmpty {
                        EmptyLine(text: "沒有未讀、置頂或收藏的公告")
                    } else {
                        AnnouncementRows(announcements: widget.announcements)
                    }
                } trailing: { PocketMoreLink(label: "全部", value: PocketRoute.announcements) }
            default:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityIdentifier("widget-\(widget.type)")
    }
}

private struct ListCard<Content: View, Trailing: View>: View {
    let title: String
    let symbol: String
    @ViewBuilder var content: Content
    @ViewBuilder var trailing: Trailing

    var body: some View {
        PocketCard(padding: 8) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Image(systemName: symbol)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(PocketColor.primary)
                        .accessibilityHidden(true)
                    Text(title)
                        .font(PocketFont.cardTitle)
                        .foregroundStyle(PocketColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Spacer(minLength: 8)
                    trailing
                }
                .frame(minHeight: 44)
                .padding(.horizontal, 12)
                content
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
    }
}

private struct EmptyLine: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(PocketColor.onSurfaceVariant)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
    }
}

private struct AlertRow: View {
    let alert: PocketAlertRow

    var body: some View {
        HStack(spacing: 12) {
            PocketIconTile(
                systemImage: alert.symbol, foreground: PocketColor.onTertiaryContainer,
                background: PocketColor.tertiaryContainer, size: 36
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
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
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
        PocketCard(padding: short ? 16 : PocketMetrics.cardPadding) {
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
                    Text(range)
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onSurfaceVariant)
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
        PocketCard(fill: PocketColor.tertiaryContainer, padding: short ? 16 : PocketMetrics.cardPadding) {
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
                        Label("倒數", systemImage: "hourglass")
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
                Label("行程已不在行事曆中", systemImage: "hourglass")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onTertiaryContainer)
                    .frame(maxHeight: .infinity)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(countdown.map { "距離\($0.title)還有 \($0.label)，\($0.dateText)" } ?? "倒數行程已不存在")
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
        PocketCard(padding: short ? 16 : PocketMetrics.cardPadding) {
            if short {
                HStack(spacing: 12) {
                    PocketIconTile(systemImage: "bicycle", size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(station.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurface)
                            .lineLimit(1)
                        Text(wide ? "可借 \(station.bikesText) · 可還 \(station.docksText) · \(station.detailText)"
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
