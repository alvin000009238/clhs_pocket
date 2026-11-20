import ActivityKit
import Foundation

/// Starts, updates and ends the lesson Live Activity locally.
///
/// There is no push channel, so the activity changes only while the app runs
/// (foreground or a granted background refresh). Its timers and progress bar
/// advance on their own, and `staleDate` gives one unattended transition: a
/// waiting lesson becomes "上課中" at its start, a running one "已下課" at the bell.
@MainActor
enum LessonActivityController {
    static func sync(glance: ScheduleGlance?, enabled: Bool) async {
        let state = enabled ? glance.flatMap(LessonActivityAttributes.ContentState.init(glance:)) : nil
        guard let state, let day = glance?.entryDay, ActivityAuthorizationInfo().areActivitiesEnabled else {
            await endAll()
            return
        }
        let content = ActivityContent(state: state, staleDate: state.isInClass ? state.end : state.start)
        var current: Activity<LessonActivityAttributes>?
        for activity in Activity<LessonActivityAttributes>.activities {
            if current == nil, activity.attributes.epochDay == day, activity.activityState == .active || activity.activityState == .stale {
                current = activity
            } else {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
        if let current {
            // Also when only the stale date moved, or the system already marked it stale.
            if current.content.state != state || current.content.staleDate != content.staleDate
                || current.activityState == .stale {
                await current.update(content)
            }
        } else {
            _ = try? Activity.request(attributes: LessonActivityAttributes(epochDay: day), content: content, pushType: nil)
        }
    }

    static func endAll() async {
        for activity in Activity<LessonActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}
