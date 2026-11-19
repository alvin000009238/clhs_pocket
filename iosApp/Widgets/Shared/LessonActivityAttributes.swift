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
