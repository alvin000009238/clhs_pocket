import PocketShared
import SwiftUI

struct ScheduleView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showsSemester = false
    @State private var selectedCell: PocketScheduleCell?

    var body: some View {
        let schedule = model.dataSource.schedule(
            scope: showsSemester ? .semester : .currentWeek,
            clock: model.clock
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

                ScheduleGrid(schedule: schedule) { selectedCell = $0 }
                    .padding(sizeClass == .regular ? 16 : 8)
                    .background(PocketColor.surfaceLow, in: .rect(cornerRadius: PocketMetrics.cardRadius, style: .continuous))

                Text("點選課程可查看教師與教室。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.horizontal, 4)
            }
            .pocketPage()
        }
        .navigationTitle(PocketTab.schedule.title)
        .sheet(item: $selectedCell) { cell in
            ScheduleCellSheet(cell: cell, days: schedule.days)
                .presentationDetents([.height(320)])
                .presentationDragIndicator(.visible)
        }
    }

    private func header(_ schedule: PocketSchedule) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(showsSemester ? schedule.termText : "\(schedule.rangeText) · \(schedule.weekText)")
                    .font(PocketFont.sectionTitle)
                    .monospacedDigit()
                    .foregroundStyle(PocketColor.onSurface)
                Text(showsSemester ? "\(schedule.className) · 固定課表" : "\(schedule.className) · \(schedule.termText)")
                    .font(.subheadline)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            if schedule.changeCount > 0 {
                PocketChip(
                    text: "\(schedule.changeCount) 項異動",
                    systemImage: "arrow.left.arrow.right",
                    foreground: PocketColor.onTertiaryContainer,
                    background: PocketColor.tertiaryContainer
                )
            }
        }
        .padding(.horizontal, 4)
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
    let cell: PocketScheduleCell
    let days: [PocketScheduleDay]

    var body: some View {
        let color = PocketColor.subject(light: cell.colorLight, dark: cell.colorDark)
        let day = days.first { $0.dayOfWeek == cell.dayOfWeek }
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
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationBackground(PocketColor.background)
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
                .foregroundStyle(PocketColor.onSurface)
        }
        .font(.body)
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }
}
