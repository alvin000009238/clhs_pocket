import Foundation
import PocketShared

/// Where widgets, reminders and background refresh get their data.
///
/// `DemoPlatformData` reads the shared source that is active (synthetic fixtures in demo mode,
/// the signed-in student's data in production); `LivePlatformData` adds the grade check.
/// Every provider keeps the same contract: timetable facts only, never credentials.
@MainActor
protocol PlatformDataProviding {
    /// Loads what a refresh needs when the app was started in the background and nothing is in memory.
    func prepare() async
    /// Brings the public announcement list up to date when it is missing or old. A background
    /// launch has none in memory, and nobody else fetches it there.
    func loadAnnouncements() async
    func scheduleSnapshot(scheduleNow: Date, deviceNow: Date) -> PlatformSnapshot
    /// Nil when the list is not known right now (not loaded, or the demo is showing).
    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]?
    /// A grade-change alert when a check found differences. May fetch from the school.
    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification?
}

extension PlatformDataProviding {
    func prepare() async {}
    func loadAnnouncements() async {}
}

/// What a provider knows about the student's timetable.
enum PlatformSnapshot: Equatable {
    /// Shared-encoded widget snapshot.
    case available(String)
    /// The timetable was loaded and holds nothing to publish.
    case none
    /// The timetable could not be loaded right now (cold background launch, locked device, offline,
    /// demo showing). What was published before stays.
    case unknown

    enum StoreChange: Equatable { case save(String), clear, keep }

    /// What to do with the stored snapshot, and the snapshot reminders are planned from.
    /// An unknown timetable never deletes a snapshot that is still valid.
    func resolved(stored: Data?, now: Date) -> (change: StoreChange, json: String?) {
        switch self {
        case let .available(json):
            return (.save(json), json)
        case .none:
            return (.clear, nil)
        case .unknown:
            guard let stored, let snapshot = ScheduleSnapshot.decode(stored), snapshot.validUntil > now,
                  let json = String(data: stored, encoding: .utf8) else { return (.keep, nil) }
            return (.keep, json)
        }
    }
}

/// Until the app shell installs the real provider nothing is known, so nothing is published or removed.
struct UnavailablePlatformData: PlatformDataProviding {
    func scheduleSnapshot(scheduleNow: Date, deviceNow: Date) -> PlatformSnapshot { .unknown }
    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]? { nil }
    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? { nil }
}

/// Reads whichever shared source is active. Installed as the provider only for `-dataMode demo`.
struct DemoPlatformData: PlatformDataProviding {
    func scheduleSnapshot(scheduleNow: Date, deviceNow: Date) -> PlatformSnapshot {
        PocketPlatform.shared.demoScheduleSnapshotJson(
            scheduleNowMillis: scheduleNow.epochMillis,
            deviceNowMillis: deviceNow.epochMillis
        ).map(PlatformSnapshot.available) ?? .none
    }

    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]? {
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
