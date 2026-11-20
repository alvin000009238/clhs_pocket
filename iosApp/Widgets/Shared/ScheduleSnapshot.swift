import Foundation

/// Identifiers shared by the app and its WidgetKit extension.
enum PocketPlatformID {
    private static let declaredAppGroup = "group.com.clhs.pocket.ios"

    /// The App Group the app and the widget actually share. Sideloading tools that re-sign with a
    /// personal team (AltStore) rename the group and list the new names under `ALTAppGroups` in the
    /// app's Info.plist; the first group this process can open wins, the declared one otherwise.
    static let appGroup: String = {
        var bundles = [Bundle.main]
        // From the widget extension, the containing app is two levels up (…/App.app/PlugIns/X.appex).
        let host = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        if Bundle.main.bundleURL.pathExtension == "appex", let app = Bundle(url: host) { bundles.append(app) }
        let renamed = bundles.flatMap { $0.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? [] }
            .filter { $0.hasPrefix(declaredAppGroup) }
        return (renamed + [declaredAppGroup]).first {
            FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil
        } ?? declaredAppGroup
    }()
    static let scheduleWidgetKind = "PocketScheduleWidget"
    static let snapshotFile = "schedule-snapshot.json"
    static let refreshTask = "com.clhs.pocket.ios.refresh"
    static let scheduleLink = URL(string: "clhspocket://schedule")!
}

/// Swift mirror of the shared `WidgetScheduleSnapshot` JSON. The extension
/// only decodes and renders it: every boundary and phase was computed by the
/// shared Kotlin resolver when the app published the file.
struct ScheduleSnapshot: Codable, Equatable, Sendable {
    static let schemaVersion = 1

    struct Lesson: Codable, Equatable, Hashable, Identifiable, Sendable {
        let id: Int
        let epochDay: Int64
        let dayOfWeek: Int
        let period: Int
        let subject: String
        let classroom: String
        let startMillis: Int64
        let endMillis: Int64
        let startText: String
        let endText: String
        let colorLight: Int64
        let colorDark: Int64

        var start: Date { Date(timeIntervalSince1970: Double(startMillis) / 1000) }
        var end: Date { Date(timeIntervalSince1970: Double(endMillis) / 1000) }
        var timeText: String { "\(startText)–\(endText)" }
    }

    enum Phase: String, Codable, Sendable {
        case inClass = "IN_CLASS"
        case startingSoon = "STARTING_SOON"
        case beforeClass = "BEFORE_CLASS"
        case dayDone = "DAY_DONE"
        case freeDay = "FREE_DAY"
    }

    struct Entry: Codable, Equatable, Sendable {
        let atMillis: Int64
        let epochDay: Int64
        let phase: Phase
        let currentLessonId: Int?
        let nextLessonId: Int?

        var date: Date { Date(timeIntervalSince1970: Double(atMillis) / 1000) }
    }

    let schemaVersion: Int
    let generatedAtMillis: Int64
    let validUntilMillis: Int64
    let weekLabel: String
    let lessons: [Lesson]
    let entries: [Entry]

    var validUntil: Date { Date(timeIntervalSince1970: Double(validUntilMillis) / 1000) }

    static func decode(_ data: Data) -> ScheduleSnapshot? {
        guard let snapshot = try? JSONDecoder().decode(ScheduleSnapshot.self, from: data),
              snapshot.schemaVersion == schemaVersion else { return nil }
        return snapshot
    }
}

/// What a widget or Live Activity shows at one instant.
struct ScheduleGlance: Equatable, Sendable {
    enum State: Equatable, Sendable {
        /// No snapshot: signed out, or the app has not published one yet.
        case empty
        /// The snapshot's week has ended and the app has not refreshed it.
        case expired
        case inClass, startingSoon, beforeClass, dayDone, freeDay
    }

    var state: State
    var date: Date
    var weekLabel = ""
    var current: ScheduleSnapshot.Lesson?
    var next: ScheduleSnapshot.Lesson?
    /// All of today's lessons, in order.
    var today: [ScheduleSnapshot.Lesson] = []
    var nextIsToday = false

    /// The lesson the layout leads with.
    var focus: ScheduleSnapshot.Lesson? { current ?? next }

    /// Every lesson on the focused lesson's day (today while lessons remain).
    var agenda: [ScheduleSnapshot.Lesson] = []

    /// Lessons after the focused one on the same day.
    var following: [ScheduleSnapshot.Lesson] { agenda.filter { lesson in focus.map { lesson.id > $0.id } ?? false } }

    static let weekdayLabels = ["週一", "週二", "週三", "週四", "週五", "週六", "週日"]

    /// "明天" or the weekday when the next lesson is not today.
    var nextDayLabel: String {
        guard let next, !nextIsToday, let entryDay else { return "" }
        if next.epochDay == entryDay + 1 { return "明天" }
        return Self.weekdayLabels[max(0, min(6, next.dayOfWeek - 1))]
    }

    var entryDay: Int64?
}

extension ScheduleSnapshot {
    func glance(at date: Date) -> ScheduleGlance {
        let millis = Int64((date.timeIntervalSince1970 * 1000).rounded())
        guard millis < validUntilMillis else { return ScheduleGlance(state: .expired, date: date) }
        guard let entry = entries.last(where: { $0.atMillis <= millis }) else {
            return ScheduleGlance(state: .empty, date: date)
        }
        let byId = Dictionary(uniqueKeysWithValues: lessons.map { ($0.id, $0) })
        let current = entry.currentLessonId.flatMap { byId[$0] }
        let next = entry.nextLessonId.flatMap { byId[$0] }
        let state: ScheduleGlance.State = switch entry.phase {
        case .inClass: .inClass
        case .startingSoon: .startingSoon
        case .beforeClass: .beforeClass
        case .dayDone: .dayDone
        case .freeDay: .freeDay
        }
        let agendaDay = (current ?? next)?.epochDay ?? entry.epochDay
        return ScheduleGlance(
            state: state,
            date: date,
            weekLabel: weekLabel,
            current: current,
            next: next,
            today: lessons.filter { $0.epochDay == entry.epochDay },
            nextIsToday: next?.epochDay == entry.epochDay,
            agenda: lessons.filter { $0.epochDay == agendaDay },
            entryDay: entry.epochDay
        )
    }

    /// Reload instants from `now` on: every shared boundary, then expiry.
    func timelineDates(from now: Date, limit: Int = 48) -> [Date] {
        guard now < validUntil else { return [now] }
        let upcoming = entries.map(\.date).filter { $0 > now }.prefix(limit)
        let reachesExpiry = upcoming.count < limit
        return [now] + upcoming + (reachesExpiry ? [validUntil] : [])
    }

    /// Whether WidgetKit should ask again once the dates from `timelineDates` are used up. Only a
    /// timeline cut short by `limit` has more to show. One that ends at expiry, or starts after it,
    /// would be rebuilt identically straight away, over and over, until the day's budget is gone;
    /// the app reloads the timelines when it publishes a new snapshot.
    func timelineContinues(from now: Date, limit: Int = 48) -> Bool {
        now < validUntil && timelineDates(from: now, limit: limit).last != validUntil
    }
}

/// The dedicated App Group file. It is the only data shared with the
/// extension; credentials, cookies and grades never leave the app sandbox.
struct ScheduleSnapshotStore: Sendable {
    let directory: URL?

    static let shared = ScheduleSnapshotStore(
        directory: FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: PocketPlatformID.appGroup)
    )

    private var fileURL: URL? { directory?.appendingPathComponent(PocketPlatformID.snapshotFile) }

    func loadData() -> Data? {
        fileURL.flatMap { try? Data(contentsOf: $0) }
    }

    func load() -> ScheduleSnapshot? {
        loadData().flatMap(ScheduleSnapshot.decode)
    }

    func save(json: String) throws {
        guard let fileURL else { throw CocoaError(.fileNoSuchFile) }
        // Readable by the widget while locked, but only after the first unlock.
        try Data(json.utf8).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Throws when the file exists but cannot be removed, so privacy cleanup
    /// is never reported as successful by mistake.
    func clear() throws {
        guard let fileURL, FileManager.default.fileExists(atPath: fileURL.path) else { return }
        try FileManager.default.removeItem(at: fileURL)
    }
}
