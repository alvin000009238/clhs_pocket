import ActivityKit
import SwiftUI
import WidgetKit

struct LessonLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LessonActivityAttributes.self) { context in
            LessonLockScreenView(state: context.state, phase: context.state.phase(isStale: context.isStale))
                .activityBackgroundTint(PocketColor.primaryContainer)
                .activitySystemActionForegroundColor(PocketColor.onPrimaryContainer)
                .widgetURL(PocketPlatformID.scheduleLink)
        } dynamicIsland: { context in
            let state = context.state
            let phase = state.phase(isStale: context.isStale)
            let tint = PocketColor.subject(light: state.colorDark, dark: state.colorDark)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    PeriodBadge(period: state.period, tint: tint)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    LessonCountdown(state: state, phase: phase)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(tint)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 0) {
                        Text(state.statusText(phase))
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text(state.subject)
                            .font(PocketFont.display(22, relativeTo: .title2))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        ProgressView(timerInterval: state.start...state.end, countsDown: false) { EmptyView() } currentValueLabel: { EmptyView() }
                            .progressViewStyle(.linear)
                            .tint(tint)
                        HStack {
                            Label(state.placeText, systemImage: "mappin.and.ellipse")
                            Spacer(minLength: 8)
                            if let next = state.nextText(phase) { Text(next) }
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    }
                    .padding(.horizontal, 6)
                    .padding(.top, 4)
                }
            } compactLeading: {
                PeriodBadge(period: state.period, tint: tint, compact: true)
            } compactTrailing: {
                LessonCountdown(state: state, phase: phase)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(tint)
                    .frame(maxWidth: 46)
            } minimal: {
                ProgressView(timerInterval: state.start...state.end, countsDown: false) { EmptyView() } currentValueLabel: {
                    Text("\(state.period)").font(.caption2.weight(.bold))
                }
                .progressViewStyle(.circular)
                .tint(tint)
            }
            .widgetURL(PocketPlatformID.scheduleLink)
            .keylineTint(tint)
        }
    }
}

extension LessonActivityAttributes.ContentState {
    func statusText(_ phase: LessonActivityPhase) -> String {
        switch phase {
        case .upcoming: "下一堂 · 第 \(period) 節"
        case .inClass: "上課中 · 第 \(period) 節"
        case .ended: "已下課 · 第 \(period) 節"
        }
    }

    var placeText: String {
        [classroom, "\(startText)–\(endText)"].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    func nextText(_ phase: LessonActivityPhase) -> String? {
        guard let nextSubject, let nextStartText else { return nil }
        // Before class the headline already says "下一堂" for the pinned lesson.
        return "\(phase == .upcoming ? "接著" : "下一堂") \(nextSubject) \(nextStartText)"
    }
}

/// Counts down to the bell while in class, or to the start beforehand.
private struct LessonCountdown: View {
    let state: LessonActivityAttributes.ContentState
    let phase: LessonActivityPhase
    /// On a dimmed Lock Screen iOS hides a timer's seconds ("15:--"), so the
    /// fixed clock time is shown there instead.
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced

    var body: some View {
        if isLuminanceReduced, phase != .ended {
            Text(phase == .inClass ? state.endText : state.startText).monospacedDigit()
        } else {
            timer
        }
    }

    @ViewBuilder
    private var timer: some View {
        if let range = state.countdownRange(phase) {
            Text(timerInterval: range, countsDown: true)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        } else {
            Text(phase == .upcoming ? state.startText : state.endText).monospacedDigit()
        }
    }
}

private struct PeriodBadge: View {
    let period: Int
    let tint: Color
    var compact = false

    var body: some View {
        Text("\(period)")
            .font(.system(size: compact ? 13 : 20, weight: .bold, design: .rounded))
            .foregroundStyle(.black.opacity(0.85))
            .frame(width: compact ? 22 : 40, height: compact ? 22 : 40)
            .background(tint, in: .rect(cornerRadius: compact ? 7 : 12, style: .continuous))
            .accessibilityLabel("第 \(period) 節")
    }
}

private struct LessonLockScreenView: View {
    let state: LessonActivityAttributes.ContentState
    let phase: LessonActivityPhase
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced
    @ScaledMetric(relativeTo: .subheadline) private var timerWidth: CGFloat = 46

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 6) {
                    if phase == .inClass {
                        Circle().fill(PocketColor.primary).frame(width: 8, height: 8)
                    }
                    Text(state.statusText(phase))
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                Spacer(minLength: 8)
                HStack(spacing: 4) {
                    if phase != .ended, !isLuminanceReduced { Text(phase == .inClass ? "剩" : "還有") }
                    if isLuminanceReduced, phase != .ended { Text(phase == .inClass ? "下課" : "上課") }
                    // A timer text takes all offered width, so it gets a fixed slot.
                    LessonCountdown(state: state, phase: phase)
                        .frame(width: timerWidth, alignment: .trailing)
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(PocketColor.onPrimaryContainer)
            }
            Text(state.subject)
                .font(PocketFont.display(30, relativeTo: .title))
                .foregroundStyle(PocketColor.onPrimaryContainer)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.top, 4)
            ProgressView(timerInterval: state.start...state.end, countsDown: false) { EmptyView() } currentValueLabel: { EmptyView() }
                .progressViewStyle(.linear)
                .tint(PocketColor.primary)
                .padding(.top, 10)
            HStack {
                Label(state.placeText, systemImage: "mappin.and.ellipse")
                Spacer(minLength: 8)
                if let next = state.nextText(phase) { Text(next) }
            }
            .font(.footnote)
            .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
            .lineLimit(1)
            .padding(.top, 8)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
    }
}
