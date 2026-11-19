import ActivityKit
import SwiftUI
import WidgetKit

struct LessonLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LessonActivityAttributes.self) { context in
            LessonLockScreenView(state: context.state, isStale: context.isStale)
                .activityBackgroundTint(PocketColor.primaryContainer)
                .activitySystemActionForegroundColor(PocketColor.onPrimaryContainer)
                .widgetURL(PocketPlatformID.scheduleLink)
        } dynamicIsland: { context in
            let state = context.state
            let tint = PocketColor.subject(light: state.colorDark, dark: state.colorDark)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    PeriodBadge(period: state.period, tint: tint)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    LessonCountdown(state: state, isStale: context.isStale)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(tint)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(spacing: 0) {
                        Text(state.statusText(isStale: context.isStale))
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
                            if let next = state.nextText { Text(next) }
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
                LessonCountdown(state: state, isStale: context.isStale)
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
    func statusText(isStale: Bool) -> String {
        if isStale { return "已下課 · 第 \(period) 節" }
        return isInClass ? "上課中 · 第 \(period) 節" : "下一堂 · 第 \(period) 節"
    }

    var placeText: String {
        [classroom, "\(startText)–\(endText)"].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    var nextText: String? {
        guard let nextSubject, let nextStartText else { return nil }
        return "下一堂 \(nextSubject) \(nextStartText)"
    }
}

/// Counts down to the bell while in class, or to the start beforehand.
private struct LessonCountdown: View {
    let state: LessonActivityAttributes.ContentState
    let isStale: Bool

    var body: some View {
        let target = state.isInClass ? state.end : state.start
        if isStale || target <= .now {
            Text(state.isInClass ? state.endText : state.startText).monospacedDigit()
        } else {
            Text(timerInterval: Date.now...target, countsDown: true)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
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
    let isStale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 6) {
                    if state.isInClass, !isStale {
                        Circle().fill(PocketColor.primary).frame(width: 8, height: 8)
                    }
                    Text(state.statusText(isStale: isStale))
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                Spacer(minLength: 8)
                HStack(spacing: 4) {
                    if !isStale { Text(state.isInClass ? "剩" : "還有") }
                    LessonCountdown(state: state, isStale: isStale)
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
                if let next = state.nextText { Text(next) }
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
