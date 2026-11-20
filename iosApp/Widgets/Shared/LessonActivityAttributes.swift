import ActivityKit
import Foundation

/// Live Activity payload for the current or next lesson. Like the widget
/// snapshot it carries timetable facts only.
struct LessonActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        /// False while waiting for the lesson to start.
        var isInClass: Bool
        var period: Int
        var subject: String
        var classroom: String
        var start: Date
        var end: Date
        var startText: String
        var endText: String
        var nextSubject: String?
        var nextStartText: String?
        var colorLight: Int64
        var colorDark: Int64
    }

    /// Activities are keyed by school day so a stale one is never updated in place.
    var epochDay: Int64
}

extension LessonActivityAttributes.ContentState {
    /// The state for `glance`, or nil when nothing is worth pinning to the Lock Screen.
    init?(glance: ScheduleGlance) {
        let lesson: ScheduleSnapshot.Lesson
        switch glance.state {
        case .inClass:
            guard let current = glance.current else { return nil }
            lesson = current
        case .startingSoon, .beforeClass:
            guard let next = glance.next, glance.nextIsToday else { return nil }
            lesson = next
        case .empty, .expired, .dayDone, .freeDay:
            return nil
        }
        let after = glance.state == .inClass ? (glance.nextIsToday ? glance.next : nil) : glance.following.first
        self.init(
            isInClass: glance.state == .inClass,
            period: lesson.period,
            subject: lesson.subject,
            classroom: lesson.classroom,
            start: lesson.start,
            end: lesson.end,
            startText: lesson.startText,
            endText: lesson.endText,
            nextSubject: after?.subject,
            nextStartText: after?.startText,
            colorLight: lesson.colorLight,
            colorDark: lesson.colorDark
        )
    }
}

/// What the activity shows, derived from the state and the system's stale flag.
///
/// The app cannot push updates, so the stale date is the only moment the
/// system re-renders on its own: for a waiting lesson it is the start (the
/// activity turns into "上課中" by itself), for a running lesson the end.
enum LessonActivityPhase {
    case upcoming, inClass, ended
}

extension LessonActivityAttributes.ContentState {
    /// The interval a countdown timer runs over, or nil when there is nothing left to count.
    /// The view can be drawn again later than its phase was computed (for example when the
    /// Lock Screen dims), so the bounds are checked here: a range must never run backwards.
    func countdownRange(_ phase: LessonActivityPhase, now: Date = .now) -> ClosedRange<Date>? {
        switch phase {
        case .upcoming: now < start ? now...start : nil
        case .inClass: now < end ? min(now, start)...end : nil
        case .ended: nil
        }
    }

    func phase(isStale: Bool, now: Date = .now) -> LessonActivityPhase {
        if now >= end { return .ended }
        if isInClass { return isStale ? .ended : .inClass }
        return isStale || now >= start ? .inClass : .upcoming
    }
}
