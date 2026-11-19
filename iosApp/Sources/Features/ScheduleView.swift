import PocketShared
import SwiftUI

struct ScheduleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.colorScheme) private var colorScheme
    @State private var showsSemester = false
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var classValue = ""
    @State private var loadingClass = false
    @State private var weekOffset = 0
    @State private var selectedCell: PocketScheduleCell?

    var body: some View {
        let store = PocketScheduleStore.shared
        let schedule = store.schedule(
            scope: showsSemester ? .semester : .currentWeek, clock: model.clock,
            classValue: classValue, weekOffset: Int32(weekOffset), overrides: model.scheduleOverrides
        )
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Picker("課表範圍", selection: $showsSemester) {
                    Text("本週").tag(false)
                    Text("學期").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 360)
                .accessibilityIdentifier("schedule-scope")

                header(schedule)

                if schedule.isAvailable {
                    grid(schedule)
                        .padding(sizeClass == .regular ? 16 : 8)
                        .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))
                } else {
                    PocketPendingView(
                        isLoading: model.live?.isLoadingPrivate ?? false || loadingClass,
                        title: weekOffset == 0 ? "課表尚未載入" : "只提供本週課表",
                        message: weekOffset == 0 ? model.live?.lastError : "其他週次請改看學期課表。",
                        retry: weekOffset == 0 ? { Task { await model.refreshLiveData(force: true) } } : nil
                    )
                }

                Text("點選課程可查看教師與教室，也可以自訂顯示名稱。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            .pocketPage()
        }
        .refreshable { await model.refreshLiveData(force: true) }
        .task(id: classValue) {
            guard !classValue.isEmpty, let live = model.live, model.usesLiveData else { return }
            loadingClass = true
            await live.loadSchedule(classValue: classValue)
            loadingClass = false
        }
        .navigationTitle(PocketTab.schedule.title)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("班級", selection: $classValue) {
                        ForEach(store.classes()) { item in
                            Text(item.title).tag(item.id == store.classes().first?.id ? "" : item.id)
                        }
                    }
                    if let image = exportImage(schedule) {
                        ShareLink(
                            item: image,
                            preview: SharePreview("\(schedule.className) \(schedule.scopeLabel)", image: image)
                        ) {
                            Label("分享課表圖片", systemImage: "square.and.arrow.up")
                        }
                    }
                } label: {
                    Label("班級與分享", systemImage: "ellipsis")
                }
                .accessibilityIdentifier("schedule-menu")
            }
        }
        .sheet(item: $selectedCell) { cell in
            ScheduleCellSheet(cell: cell, days: schedule.days)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }

    /// Renders the grid off screen so the shared image does not depend on scroll position.
    @MainActor
    private func exportImage(_ schedule: PocketSchedule) -> Image? {
        let content = VStack(alignment: .leading, spacing: 12) {
            Text("\(schedule.className) · \(schedule.scopeLabel)")
                .font(PocketFont.sectionTitle)
                .foregroundStyle(PocketColor.onSurface)
            Text(showsSemester ? schedule.termText : "\(schedule.rangeText) · \(schedule.weekText)")
                .font(.subheadline)
                .foregroundStyle(PocketColor.onSurfaceVariant)
            ScheduleGrid(schedule: schedule) { _ in }
        }
        .padding(20)
        .frame(width: 640)
        .background(PocketColor.background)
        .environment(\.colorScheme, colorScheme)
        .environment(\.horizontalSizeClass, .regular)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        return renderer.uiImage.map { Image(uiImage: $0) }
    }

    /// With accessibility text five columns cannot hold a subject name, so the grid keeps a
    /// readable column width and scrolls sideways instead.
    @ViewBuilder
    private func grid(_ schedule: PocketSchedule) -> some View {
        if typeSize.isAccessibilitySize, sizeClass != .regular {
            ScrollView(.horizontal) {
                ScheduleGrid(schedule: schedule) { selectedCell = $0 }
                    .frame(width: 44 + CGFloat(schedule.days.count) * 112)
            }
            .scrollIndicators(.visible)
            .accessibilityIdentifier("schedule-grid-scroll")
        } else {
            ScheduleGrid(schedule: schedule) { selectedCell = $0 }
        }
    }

    private func header(_ schedule: PocketSchedule) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 12) {
                Group {
                    if showsSemester {
                        Text(schedule.termText)
                    } else if typeSize.isAccessibilitySize {
                        // Range and week on their own lines; one string wraps into an orphan separator.
                        VStack(alignment: .leading, spacing: 2) {
                            Text(schedule.rangeText)
                            Text(schedule.weekText)
                                .font(.headline)
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                        .accessibilityElement(children: .combine)
                    } else {
                        Text("\(schedule.rangeText) · \(schedule.weekText)")
                    }
                }
                    .font(PocketFont.sectionTitle)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurface)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
                    .accessibilityIdentifier("schedule-range")
                Spacer(minLength: 8)
                if !showsSemester {
                    HStack(spacing: 6) {
                        Button("上一週", systemImage: "chevron.left") { weekOffset -= 1 }
                            .disabled(weekOffset <= Int(PocketScheduleStore.shared.MIN_WEEK_OFFSET))
                            .accessibilityIdentifier("schedule-previous-week")
                        Button("下一週", systemImage: "chevron.right") { weekOffset += 1 }
                            .disabled(weekOffset >= Int(PocketScheduleStore.shared.MAX_WEEK_OFFSET))
                            .accessibilityIdentifier("schedule-next-week")
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { subtitle(schedule) }
                VStack(alignment: .leading, spacing: 6) { subtitle(schedule) }
            }
        }
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private func subtitle(_ schedule: PocketSchedule) -> some View {
                Text(showsSemester ? "\(schedule.className) · 固定課表" : schedule.className)
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                if schedule.changeCount > 0 {
                    PocketChip(
                        text: "\(schedule.changeCount) 項異動",
                        systemImage: "arrow.left.arrow.right",
                        foreground: PocketColor.onTertiaryContainer,
                        background: PocketColor.tertiaryContainer
                    )
                }
                if !showsSemester, weekOffset != 0 {
                    Button("回到本週") { weekOffset = 0 }
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 32)
                        .accessibilityIdentifier("schedule-this-week")
                }
    }
}

private struct ScheduleGrid: View {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @ScaledMetric(relativeTo: .caption) private var compactRowHeight: CGFloat = 58
    let schedule: PocketSchedule
    let onSelect: (PocketScheduleCell) -> Void

    private let gap: CGFloat = 4
    private let lunchGap: CGFloat = 18
    private let lastMorningPeriod = 4

    private var rowHeight: CGFloat { sizeClass == .regular ? compactRowHeight + 14 : compactRowHeight }
    private var timeColumnWidth: CGFloat { sizeClass == .regular ? 56 : 30 }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: gap) {
                Color.clear.frame(width: timeColumnWidth, height: 1)
                ForEach(schedule.days, id: \.dayOfWeek) { day in
                    VStack(spacing: 1) {
                        Text(day.label)
                            .font(.subheadline.weight(.semibold))
                        if schedule.scope == .currentWeek {
                            Text(day.dateText)
                                .font(.caption2)
                                .monospacedDigit()
                                .lineLimit(1)
                                .minimumScaleFactor(0.5)
                                .dynamicTypeSize(...DynamicTypeSize.xLarge)
                        }
                    }
                    .foregroundStyle(day.isToday ? PocketColor.onPrimary : PocketColor.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
                    .background(day.isToday ? PocketColor.primary : .clear, in: .rect(cornerRadius: 12, style: .continuous))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("週\(day.label) \(day.dateText)\(day.isToday ? "，今天" : "")")
                }
            }

            HStack(alignment: .top, spacing: gap) {
                VStack(spacing: 0) {
                    ForEach(schedule.periods, id: \.number) { period in
                        VStack(spacing: 1) {
                            Text("\(period.number)")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(PocketColor.onSurface)
                            Text(period.start)
                                .font(.system(size: 9))
                                .monospacedDigit()
                                .foregroundStyle(PocketColor.onSurfaceVariant)
                                .fixedSize()
                        }
                        .frame(width: timeColumnWidth, height: rowHeight)
                        .padding(.bottom, bottomGap(afterPeriod: Int(period.number)))
                        .accessibilityHidden(true)
                    }
                }
                ForEach(schedule.days, id: \.dayOfWeek) { day in
                    VStack(spacing: 0) {
                        ForEach(schedule.cells.filter { $0.dayOfWeek == day.dayOfWeek }) { cell in
                            let span = CGFloat(cell.span)
                            Button {
                                onSelect(cell)
                            } label: {
                                ScheduleCellView(cell: cell)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: rowHeight * span + gap * (span - 1))
                            }
                            .buttonStyle(.plain)
                            .padding(.bottom, bottomGap(afterPeriod: Int(cell.period + cell.span) - 1))
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .overlay(alignment: .top) {
                HStack(spacing: 8) {
                    line
                    Text("午休")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                    line
                }
                .frame(height: lunchGap)
                .offset(y: CGFloat(lastMorningPeriod) * rowHeight + CGFloat(lastMorningPeriod - 1) * gap)
                .accessibilityHidden(true)
            }
        }
    }

    private var line: some View {
        Rectangle().fill(PocketColor.outline.opacity(0.5)).frame(height: 1)
    }

    private func bottomGap(afterPeriod period: Int) -> CGFloat {
        if period == lastMorningPeriod { return lunchGap }
        return period == schedule.periods.count ? 0 : gap
    }
}

private struct ScheduleCellView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.horizontalSizeClass) private var sizeClass
    let cell: PocketScheduleCell

    var body: some View {
        let color = PocketColor.subject(light: cell.colorLight, dark: cell.colorDark)
        let isCurrent = cell.status == .current
        let isCancelled = cell.change == .cancelled
        let foreground: Color = isCurrent ? (colorScheme == .dark ? Color.black.opacity(0.85) : .white) : color
        VStack(spacing: 2) {
            Text(cell.subject)
                .font(sizeClass == .regular ? .callout.weight(.semibold) : .footnote.weight(.semibold))
                .strikethrough(isCancelled)
                .lineLimit(2)
                .minimumScaleFactor(0.75)
                .multilineTextAlignment(.center)
                // The grid scrolls sideways at accessibility sizes; past this the row height cannot keep up.
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            if sizeClass == .regular || cell.span > 1 {
                Text(cell.classroom)
                    .font(.caption2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .opacity(0.8)
            }
        }
        .foregroundStyle(foreground)
        .padding(.horizontal, 3)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            isCurrent ? color : color.opacity(colorScheme == .dark ? 0.2 : 0.14),
            in: .rect(cornerRadius: 12, style: .continuous)
        )
        .overlay(alignment: .topTrailing) {
            if cell.change != .none {
                Circle()
                    .fill(isCancelled ? PocketColor.negative : PocketColor.warning)
                    .frame(width: 7, height: 7)
                    .padding(5)
            }
        }
        .opacity(cell.status == .ended || isCancelled ? 0.5 : 1)
        .contentShape(.rect)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
        .accessibilityAddTraits(.isButton)
    }

    private var accessibilityText: String {
        var parts = ["第 \(cell.period) 節", cell.subject, cell.classroom]
        if isCurrentText { parts.append("上課中") }
        if !cell.changeNote.isEmpty { parts.append(cell.changeNote) }
        return parts.joined(separator: "，")
    }

    private var isCurrentText: Bool { cell.status == .current }
}

private struct ScheduleCellSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model
    @State private var editing = false
    @State private var subject = ""
    @State private var teacher = ""
    @State private var classroom = ""
    let cell: PocketScheduleCell
    let days: [PocketScheduleDay]

    var body: some View {
        let color = PocketColor.subject(light: cell.colorLight, dark: cell.colorDark)
        let day = days.first { $0.dayOfWeek == cell.dayOfWeek }
        ScrollView {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Circle().fill(color).frame(width: 10, height: 10)
                        Text("週\(day?.label ?? "") · 第 \(cell.period)\(cell.span > 1 ? "–\(cell.period + cell.span - 1)" : "") 節")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                    Text(cell.subject)
                        .font(PocketFont.display(32, relativeTo: .largeTitle))
                        .foregroundStyle(PocketColor.onSurface)
                }
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .frame(width: 32, height: 32)
                        .background(PocketColor.surfaceHigh, in: .circle)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("關閉")
            }
            if !cell.changeNote.isEmpty {
                PocketChip(
                    text: cell.changeNote,
                    systemImage: "arrow.left.arrow.right",
                    foreground: PocketColor.onTertiaryContainer,
                    background: PocketColor.tertiaryContainer
                )
                .padding(.top, 10)
            }
            VStack(spacing: 0) {
                detail("clock", "時間", cell.timeText)
                PocketRowDivider().padding(.leading, 44)
                detail("mappin.and.ellipse", "教室", cell.classroom)
                if !cell.teacher.isEmpty {
                    PocketRowDivider().padding(.leading, 44)
                    detail("person", "教師", cell.teacher)
                }
            }
            .padding(.horizontal, 4)
            .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 20, style: .continuous))
            .padding(.top, 18)
            overrideSection
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 24)
        .frame(maxWidth: .infinity, alignment: .top)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationBackground(PocketColor.background)
    }

    private static func clamped(_ text: String) -> String {
        String(text.prefix(Int(PocketScheduleStore.shared.maxOverrideLength())))
    }

    @ViewBuilder private var overrideSection: some View {
        if editing {
            VStack(alignment: .leading, spacing: 10) {
                Text("自訂「\(cell.originalSubject)」的顯示")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                Group {
                    TextField("科目名稱", text: $subject).accessibilityIdentifier("override-subject")
                    TextField("教師（留空使用原本的）", text: $teacher)
                    TextField("教室（留空使用原本的）", text: $classroom)
                }
                .textFieldStyle(.roundedBorder)
                .onChange(of: subject) { _, value in subject = Self.clamped(value) }
                .onChange(of: teacher) { _, value in teacher = Self.clamped(value) }
                .onChange(of: classroom) { _, value in classroom = Self.clamped(value) }
                Text("套用到這個科目的每一堂課，只存在這台裝置。每欄最多 \(PocketScheduleStore.shared.maxOverrideLength()) 個字。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                HStack(spacing: 12) {
                    Button("儲存") {
                        model.scheduleOverrides = PocketScheduleStore.shared.setOverride(
                            overrides: model.scheduleOverrides, original: cell.originalSubject,
                            subject: subject, teacher: teacher, classroom: classroom
                        )
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("override-save")
                    if cell.isOverridden {
                        Button("還原", role: .destructive) {
                            model.scheduleOverrides = PocketScheduleStore.shared.clearOverride(
                                overrides: model.scheduleOverrides, original: cell.originalSubject
                            )
                            dismiss()
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("override-clear")
                    }
                }
            }
            .padding(.top, 20)
        } else {
            Button {
                let saved = PocketScheduleStore.shared.overrides(overrides: model.scheduleOverrides)
                    .first { $0.original == cell.originalSubject }
                subject = saved?.subject.isEmpty == false ? saved!.subject : cell.subject
                teacher = saved?.teacher ?? ""
                classroom = saved?.classroom ?? ""
                editing = true
            } label: {
                Label(cell.isOverridden ? "編輯自訂名稱（原「\(cell.originalSubject)」）" : "自訂科目名稱", systemImage: "pencil")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background(PocketColor.surfaceLow, in: .rect(cornerRadius: 16, style: .continuous))
            }
            .padding(.top, 14)
            .accessibilityIdentifier("override-edit")
        }
    }

    private func detail(_ icon: String, _ label: String, _ value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(PocketColor.primary)
                .frame(width: 28)
            Text(label)
                .foregroundStyle(PocketColor.onSurfaceVariant)
            Spacer()
            Text(value)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
                .foregroundStyle(PocketColor.onSurface)
        }
        .font(.body)
        .padding(.vertical, 6)
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }
}
