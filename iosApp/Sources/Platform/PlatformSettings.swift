import Foundation
import Observation
import PocketShared

/// Notification and background preferences, mirroring the related Android
/// `AppSettings` fields. Nothing here is personal data.
@MainActor
@Observable
final class PlatformSettings {
    static let shared = PlatformSettings()

    private enum Key {
        static let notifications = "platform.notificationsEnabled"
        static let course = "platform.courseReminders"
        static let grades = "platform.gradeUpdates"
        static let announcements = "platform.announcements"
        static let liveActivity = "platform.liveActivity"
        static let pauseEnabled = "platform.pauseEnabled"
        static let pauseStart = "platform.pauseStartMinute"
        static let pauseEnd = "platform.pauseEndMinute"
    }

    private let defaults: UserDefaults

    var notificationsEnabled: Bool { didSet { defaults.set(notificationsEnabled, forKey: Key.notifications) } }
    var courseReminders: Bool { didSet { defaults.set(courseReminders, forKey: Key.course) } }
    var gradeUpdates: Bool { didSet { defaults.set(gradeUpdates, forKey: Key.grades) } }
    var announcements: Bool { didSet { defaults.set(announcements, forKey: Key.announcements) } }
    var liveActivity: Bool { didSet { defaults.set(liveActivity, forKey: Key.liveActivity) } }
    var pauseEnabled: Bool { didSet { defaults.set(pauseEnabled, forKey: Key.pauseEnabled) } }
    var pauseStartMinute: Int { didSet { defaults.set(pauseStartMinute, forKey: Key.pauseStart) } }
    var pauseEndMinute: Int { didSet { defaults.set(pauseEndMinute, forKey: Key.pauseEnd) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        func flag(_ key: String, _ fallback: Bool) -> Bool { defaults.object(forKey: key) as? Bool ?? fallback }
        notificationsEnabled = flag(Key.notifications, false)
        courseReminders = flag(Key.course, true)
        gradeUpdates = flag(Key.grades, true)
        announcements = flag(Key.announcements, true)
        liveActivity = flag(Key.liveActivity, true)
        pauseEnabled = flag(Key.pauseEnabled, false)
        pauseStartMinute = defaults.object(forKey: Key.pauseStart) as? Int ?? 23 * 60
        pauseEndMinute = defaults.object(forKey: Key.pauseEnd) as? Int ?? 7 * 60
    }

    /// Back to first-launch values; used when the user deletes all data.
    func reset() {
        notificationsEnabled = false
        courseReminders = true
        gradeUpdates = true
        announcements = true
        liveActivity = true
        pauseEnabled = false
        pauseStartMinute = 23 * 60
        pauseEndMinute = 7 * 60
    }

    /// The quiet window follows the device's wall clock, as on Android.
    func shared(at date: Date = Date(), timeZone: TimeZone = .current) -> PlatformNotificationSettings {
        PlatformNotificationSettings(
            notificationsEnabled: notificationsEnabled,
            courseReminders: courseReminders,
            gradeUpdates: gradeUpdates,
            announcements: announcements,
            pauseEnabled: pauseEnabled,
            pauseStartMinute: Int32(pauseStartMinute),
            pauseEndMinute: Int32(pauseEndMinute),
            utcOffsetSeconds: Int32(timeZone.secondsFromGMT(for: date))
        )
    }
}
