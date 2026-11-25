import SwiftUI
import WidgetKit

struct ScheduleWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let glance: ScheduleGlance

    var body: some View {
        Group {
            switch family {
            case .systemSmall: SmallScheduleView(glance: glance)
            case .systemMedium: MediumScheduleView(glance: glance)
            case .systemLarge: LargeScheduleView(glance: glance)
            case .accessoryRectangular: RectangularAccessoryView(glance: glance)
            case .accessoryCircular: CircularAccessoryView(glance: glance)
            case .accessoryInline: InlineAccessoryView(glance: glance)
            default: SmallScheduleView(glance: glance)
            }
        }
        // Accessibility sizes would clip a fixed-size widget; the layouts scale below that.
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    /// The summary, plus the lessons the medium and large layouts list after it.
    private var accessibilityText: String {
        let listed: [ScheduleSnapshot.Lesson] = switch family {
        case .systemMedium: Array(glance.following.prefix(3))
        case .systemLarge: Array(glance.agenda.prefix(8))
        default: []
        }
        return ([glance.summary] + listed.map { "第 \($0.period) 節 \($0.subject) \($0.startText)" })
            .joined(separator: "，")
    }
}

/// In tinted and clear Home Screens the system recolours content, so colour
/// roles collapse to opacity steps of the primary style there.
private struct Ink {
    let fullColor: Bool

    var strong: Color { fullColor ? PocketColor.onPrimaryContainer : .primary }
    var soft: Color { fullColor ? PocketColor.onPrimaryContainer.opacity(0.78) : .secondary }
    var accent: Color { fullColor ? PocketColor.primary : .primary }
    var inset: Color { fullColor ? PocketColor.background.opacity(0.55) : Color.primary.opacity(0.12) }
    var body: Color { fullColor ? PocketColor.onSurface : .primary }
    var bodySoft: Color { fullColor ? PocketColor.onSurfaceVariant : .secondary }

    func subject(_ lesson: ScheduleSnapshot.Lesson) -> Color {
        fullColor ? PocketColor.subject(light: lesson.colorLight, dark: lesson.colorDark) : .primary
    }
}

private struct InkReader<Content: View>: View {
    @Environment(\.widgetRenderingMode) private var mode
    @ViewBuilder var content: (Ink) -> Content

    var body: some View {
        content(Ink(fullColor: mode == .fullColor))
    }
}

// MARK: - Shared pieces

private struct StatusLine: View {
    let glance: ScheduleGlance
    let ink: Ink

    var body: some View {
        HStack(spacing: 5) {
            if glance.state == .inClass {
                Circle().fill(ink.accent).frame(width: 7, height: 7)
            }
            Text(glance.headline)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .foregroundStyle(ink.soft)
        .widgetAccentable()
    }
}

/// A timer text takes all the width it is offered, so it gets a fixed slot
/// that still grows with Dynamic Type.
private struct Countdown: View {
    let from: Date
    let until: Date
    @ScaledMetric(relativeTo: .caption) private var width: CGFloat = 38

    var body: some View {
        HStack(spacing: 3) {
            Text("剩")
            Text(timerInterval: from...until, countsDown: true)
                .monospacedDigit()
                .frame(width: width, alignment: .leading)
        }
        .lineLimit(1)
    }
}

/// Self-updating countdown; WidgetKit animates it without extra entries.
private struct LessonClock: View {
    let glance: ScheduleGlance
    let lesson: ScheduleSnapshot.Lesson
    let ink: Ink

    var body: some View {
        HStack(spacing: 4) {
            if glance.state == .inClass, glance.date < lesson.end {
                Countdown(from: glance.date, until: lesson.end)
                Spacer(minLength: 0)
                Text(lesson.endText).monospacedDigit()
            } else {
                Text(lesson.timeText).monospacedDigit()
            }
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(ink.soft)
        .lineLimit(1)
    }
}

private struct LessonProgress: View {
    let lesson: ScheduleSnapshot.Lesson
    let ink: Ink

    var body: some View {
        ProgressView(timerInterval: lesson.start...lesson.end, countsDown: false) { EmptyView() } currentValueLabel: { EmptyView() }
            .progressViewStyle(.linear)
            .tint(ink.accent)
            .widgetAccentable()
            .accessibilityHidden(true)
    }
}

/// The lesson the widget leads with: status, name, room and time.
private struct FocusBlock: View {
    let glance: ScheduleGlance
    let ink: Ink
    var titleSize: CGFloat = 30

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            StatusLine(glance: glance, ink: ink)
            if let lesson = glance.focus {
                Text(lesson.subject)
                    .font(PocketFont.display(titleSize, relativeTo: .title))
                    .foregroundStyle(ink.strong)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.top, 3)
                    .privacySensitive()
                if !lesson.classroom.isEmpty {
                    Label(lesson.classroom, systemImage: "mappin.and.ellipse")
                        .labelStyle(TightLabelStyle())
                        .font(.footnote)
                        .foregroundStyle(ink.soft)
                        .lineLimit(1)
                        .padding(.top, 2)
                        .privacySensitive()
                }
                Spacer(minLength: 6)
                if glance.state == .inClass {
                    LessonProgress(lesson: lesson, ink: ink)
                        .padding(.bottom, 6)
                } else if glance.state == .dayDone || glance.state == .freeDay {
                    Text(glance.restText)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(ink.soft)
                        .lineLimit(1)
                        .padding(.bottom, 3)
                }
                LessonClock(glance: glance, lesson: lesson, ink: ink)
            } else {
                Spacer(minLength: 6)
                Image(systemName: glance.state == .empty || glance.state == .expired ? "calendar.badge.exclamationmark" : "checkmark.circle")
                    .font(.title2)
                    .foregroundStyle(ink.accent)
                    .widgetAccentable()
                Text(glance.restText)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(ink.strong)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct TightLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 3) {
            configuration.icon.imageScale(.small)
            configuration.title
        }
    }
}

private struct AgendaRow: View {
    let lesson: ScheduleSnapshot.Lesson
    let ink: Ink
    var showsRoom = false

    var body: some View {
        HStack(spacing: 8) {
            Text(lesson.startText)
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(ink.soft)
            Capsule()
                .fill(ink.subject(lesson))
                .frame(width: 4, height: 18)
                .widgetAccentable()
            Text(lesson.subject)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(ink.strong)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .privacySensitive()
            Spacer(minLength: 0)
            if showsRoom, !lesson.classroom.isEmpty {
                Text(lesson.classroom)
                    .font(.caption)
                    .foregroundStyle(ink.soft)
                    .lineLimit(1)
                    .privacySensitive()
            }
        }
    }
}

private extension View {
    func pocketWidgetBackground(_ color: Color) -> some View {
        containerBackground(for: .widget) {
            // The same soft corner tint as the 首頁 cards; the system drops it in tinted and clear modes.
            ZStack {
                color
                LinearGradient(
                    colors: [PocketColor.primary.opacity(0.14), PocketColor.primary.opacity(0)],
                    startPoint: .topLeading, endPoint: .center
                )
            }
        }
    }
}

// MARK: - Home Screen

private struct SmallScheduleView: View {
    let glance: ScheduleGlance

    var body: some View {
        InkReader { ink in
            FocusBlock(glance: glance, ink: ink)
        }
        .pocketWidgetBackground(PocketColor.primaryContainer)
    }
}

private struct MediumScheduleView: View {
    let glance: ScheduleGlance

    var body: some View {
        InkReader { ink in
            HStack(spacing: 14) {
                FocusBlock(glance: glance, ink: ink)
                if glance.focus != nil {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("接下來")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(ink.soft)
                        let rows = Array(glance.following.prefix(3))
                        if rows.isEmpty {
                            Spacer(minLength: 0)
                            Text(glance.state == .inClass ? "這是今天最後一堂" : "之後沒有課程")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(ink.strong)
                            Spacer(minLength: 0)
                        } else {
                            ForEach(rows) { lesson in
                                Spacer(minLength: 4)
                                AgendaRow(lesson: lesson, ink: ink)
                            }
                            Spacer(minLength: 0)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .background(ink.inset, in: .rect(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .pocketWidgetBackground(PocketColor.primaryContainer)
    }
}

private struct LargeScheduleView: View {
    let glance: ScheduleGlance

    private var title: String {
        guard glance.focus != nil else { return "課表" }
        let today = glance.state == .inClass || glance.nextIsToday
        return today ? "今天的課表" : "\(glance.nextDayLabel)的課表"
    }

    var body: some View {
        InkReader { ink in
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text(title)
                        .font(PocketFont.display(22, relativeTo: .title2))
                        .foregroundStyle(ink.body)
                    Spacer(minLength: 8)
                    if !glance.weekLabel.isEmpty {
                        Text(glance.weekLabel)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(ink.bodySoft)
                    }
                }
                if glance.agenda.isEmpty {
                    FocusBlock(glance: glance, ink: ink)
                } else {
                    VStack(spacing: 4) {
                        ForEach(glance.agenda.prefix(8)) { lesson in
                            LargeRow(lesson: lesson, glance: glance, ink: ink)
                        }
                    }
                    .frame(maxHeight: .infinity, alignment: .top)
                    if glance.state == .dayDone || glance.state == .freeDay {
                        Text(glance.restText)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(ink.bodySoft)
                    }
                }
            }
        }
        .pocketWidgetBackground(PocketColor.background)
    }
}

private struct LargeRow: View {
    let lesson: ScheduleSnapshot.Lesson
    let glance: ScheduleGlance
    let ink: Ink

    private var isCurrent: Bool { glance.current?.id == lesson.id }
    private var isNext: Bool { glance.current == nil && glance.next?.id == lesson.id }
    private var isOver: Bool { lesson.end <= glance.date }

    var body: some View {
        HStack(spacing: 10) {
            Text(lesson.startText)
                .font(.caption.weight(.medium))
                .monospacedDigit()
                .foregroundStyle(isCurrent ? ink.strong : ink.bodySoft)
                .frame(width: 40, alignment: .leading)
            Capsule()
                .fill(ink.subject(lesson))
                .frame(width: 4)
                .padding(.vertical, 7)
                .widgetAccentable()
            Text(lesson.subject)
                .font(.subheadline.weight(isCurrent || isNext ? .semibold : .regular))
                .foregroundStyle(isCurrent ? ink.strong : ink.body)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .privacySensitive()
            Spacer(minLength: 4)
            if isCurrent, glance.date < lesson.end {
                Countdown(from: glance.date, until: lesson.end)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(ink.strong)
            } else if !lesson.classroom.isEmpty {
                Text(lesson.classroom)
                    .font(.caption)
                    .foregroundStyle(ink.bodySoft)
                    .lineLimit(1)
                    .privacySensitive()
            }
        }
        .padding(.horizontal, 12)
        .frame(maxWidth: .infinity, maxHeight: 40)
        .background {
            if isCurrent {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(ink.fullColor ? PocketColor.primaryContainer : Color.primary.opacity(0.16))
            } else if isNext {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(ink.fullColor ? PocketColor.surface : Color.primary.opacity(0.1))
            }
        }
        .opacity(isOver ? 0.45 : 1)
    }
}

// MARK: - Lock Screen

private struct RectangularAccessoryView: View {
    let glance: ScheduleGlance

    private func detail(for lesson: ScheduleSnapshot.Lesson) -> String {
        let time = glance.state == .inClass ? "\(lesson.endText) 下課" : lesson.timeText
        return [time, lesson.classroom].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(glance.headline)
                .font(.caption.weight(.semibold))
                .widgetAccentable()
            if let lesson = glance.focus {
                Text(lesson.subject)
                    .font(.headline)
                    .privacySensitive()
                // Vibrant rendering flattens a progress bar's fill and track, so the third line is text.
                Text(detail(for: lesson))
                    .font(.caption)
                    .monospacedDigit()
                    .privacySensitive()
            } else {
                Text(glance.restText).font(.caption)
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .containerBackground(for: .widget) { Color.clear }
    }
}

private struct CircularAccessoryView: View {
    let glance: ScheduleGlance

    var body: some View {
        Group {
            if glance.state == .inClass, let lesson = glance.current, glance.date < lesson.end {
                ProgressView(timerInterval: lesson.start...lesson.end, countsDown: false) {
                    EmptyView()
                } currentValueLabel: {
                    Text("\(lesson.period)").font(.title2.weight(.semibold))
                }
                .progressViewStyle(.circular)
            } else {
                ZStack {
                    AccessoryWidgetBackground()
                    if let lesson = glance.next {
                        VStack(spacing: 0) {
                            Image(systemName: "book.closed.fill").font(.caption2)
                            Text(lesson.startText)
                                .font(.caption.weight(.semibold))
                                .monospacedDigit()
                                .minimumScaleFactor(0.7)
                        }
                        .padding(.horizontal, 3)
                    } else {
                        Image(systemName: glance.state == .empty || glance.state == .expired ? "calendar.badge.exclamationmark" : "checkmark")
                            .font(.title3)
                    }
                }
            }
        }
        .widgetAccentable()
        .containerBackground(for: .widget) { Color.clear }
    }
}

private struct InlineAccessoryView: View {
    let glance: ScheduleGlance

    var body: some View {
        Label(glance.summary, systemImage: "book.closed.fill")
            .privacySensitive()
            .containerBackground(for: .widget) { Color.clear }
    }
}
