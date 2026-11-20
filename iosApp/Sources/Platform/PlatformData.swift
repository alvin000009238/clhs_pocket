import Foundation
import PocketShared

/// Where widgets, reminders and background refresh get their data.
///
/// `DemoPlatformData` reads the shared source that is active (synthetic fixtures in demo mode,
/// the signed-in student's data in production); `LivePlatformData` adds the grade check.
/// Every provider keeps the same contract: timetable facts only, never credentials.
@MainActor
protocol PlatformDataProviding {
    /// Shared-encoded widget snapshot, or nil when no valid timetable exists.
    func scheduleSnapshotJSON(scheduleNow: Date, deviceNow: Date) -> String?
    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]
    /// A grade-change alert when a check found differences. May fetch from the school.
    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification?
}

struct DemoPlatformData: PlatformDataProviding {
    func scheduleSnapshotJSON(scheduleNow: Date, deviceNow: Date) -> String? {
        PocketPlatform.shared.demoScheduleSnapshotJson(
            scheduleNowMillis: scheduleNow.epochMillis,
            deviceNowMillis: deviceNow.epochMillis
        )
    }

    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline] {
        PocketPlatform.shared.demoAnnouncementHeadlines(clock: PlatformClock.pocketClock(at: date))
    }

    /// The demo timetable never changes on its own; alerts are sent from the demo menu.
    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        nil
    }
}

extension Date {
    var epochMillis: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }

    init(epochMillis: Int64) {
        self.init(timeIntervalSince1970: Double(epochMillis) / 1000)
    }
}

/// Resolves "now" for schedule logic, honouring the `-demoClock` launch argument.
enum PlatformClock {
    private static let offsetKey = "platform.demoClockOffset"
    private static let offsetSourceKey = "platform.demoClockSource"

    static var schoolCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Taipei") ?? .current
        return calendar
    }

    static func parseDemoClock(_ text: String) -> Date? {
        let formatter = DateFormatter()
        formatter.calendar = schoolCalendar
        formatter.timeZone = schoolCalendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return formatter.date(from: text)
    }

    /// With a pinned demo clock, time keeps flowing from the first launch that
    /// pinned it, so widgets and the Live Activity count down in real time.
    static func scheduleNow(deviceNow: Date, defaults: UserDefaults = .standard) -> Date {
        #if !DEBUG
        return deviceNow
        #else
        guard let text = defaults.string(forKey: "demoClock"), let pinned = parseDemoClock(text) else {
            return deviceNow
        }
        if defaults.string(forKey: offsetSourceKey) != text {
            defaults.set(text, forKey: offsetSourceKey)
            defaults.set(deviceNow.timeIntervalSince(pinned), forKey: offsetKey)
        }
        return deviceNow.addingTimeInterval(-defaults.double(forKey: offsetKey))
        #endif
    }

    static func pocketClock(at date: Date) -> PocketClock {
        let parts = schoolCalendar.dateComponents([.year, .month, .day, .weekday, .hour, .minute], from: date)
        let weekday = parts.weekday ?? 1
        return PocketClock(
            year: Int32(parts.year ?? 2026),
            month: Int32(parts.month ?? 1),
            day: Int32(parts.day ?? 1),
            dayOfWeek: Int32(weekday == 1 ? 7 : weekday - 1),
            minuteOfDay: Int32((parts.hour ?? 0) * 60 + (parts.minute ?? 0))
        )
    }
}
