import Observation
import PocketShared
import SwiftUI

enum PocketTab: String, CaseIterable, Hashable {
    case home, schedule, grades, campus

    /// Labels come from the shared root catalogue so both platforms stay in step.
    var title: String {
        PocketRootCatalog.shared.titleAt(index: Int32(PocketTab.allCases.firstIndex(of: self) ?? 0))
    }

    var requiresSession: Bool {
        PocketRootCatalog.shared.requiresSessionAt(index: Int32(PocketTab.allCases.firstIndex(of: self) ?? 0))
    }

    var systemImage: String {
        switch self {
        case .home: "house.fill"
        case .schedule: "calendar"
        case .grades: "chart.bar.fill"
        case .campus: "building.columns.fill"
        }
    }
}

enum PocketRoute: Hashable {
    case announcements
    case calendar
}

enum PocketAppearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "跟隨系統"
        case .light: "淺色"
        case .dark: "深色"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum PocketSession: String {
    case guest, demo
}

/// App-level presentation state. Every figure shown on screen is produced by
/// the shared Kotlin core through `PocketDataSource`.
@MainActor
@Observable
final class AppModel {
    private enum Key {
        static let onboarding = "hasCompletedOnboarding"
        static let appearance = "appearance"
        static let session = "session"
        static let demoClock = "demoClock"
        static let initialTab = "initialTab"
        static let notifications = "notificationsEnabled"
        static let dashboard = "dashboardLayout"
        static let unread = "unreadAnnouncementIds"
        static let bookmarks = "bookmarkedAnnouncementIds"
    }

    let dataSource: PocketDataSource = PocketEnvironment.shared.demo()
    let services = PocketServices()
    private let defaults = UserDefaults.standard
    private let fixedClock: PocketClock?

    var clock: PocketClock
    var selectedTab: PocketTab
    var paths: [PocketTab: [PocketRoute]] = [:]
    var showingPersonal = false

    var hasCompletedOnboarding: Bool {
        didSet { defaults.set(hasCompletedOnboarding, forKey: Key.onboarding) }
    }

    var appearance: PocketAppearance {
        didSet { defaults.set(appearance.rawValue, forKey: Key.appearance) }
    }

    var session: PocketSession {
        didSet { defaults.set(session.rawValue, forKey: Key.session) }
    }

    /// Home widget layout in the shared `OverviewWidgetInstance` JSON format.
    var dashboardLayout: String {
        didSet { defaults.set(dashboardLayout, forKey: Key.dashboard) }
    }

    var unreadAnnouncementIds: Set<String> {
        didSet { defaults.set(Array(unreadAnnouncementIds), forKey: Key.unread) }
    }

    var bookmarkedAnnouncementIds: Set<String> {
        didSet { defaults.set(Array(bookmarkedAnnouncementIds), forKey: Key.bookmarks) }
    }

    var notificationsEnabled: Bool {
        didSet { defaults.set(notificationsEnabled, forKey: Key.notifications) }
    }

    init() {
        hasCompletedOnboarding = defaults.bool(forKey: Key.onboarding)
        appearance = defaults.string(forKey: Key.appearance).flatMap(PocketAppearance.init) ?? .system
        session = defaults.string(forKey: Key.session).flatMap(PocketSession.init) ?? .guest
        notificationsEnabled = defaults.bool(forKey: Key.notifications)
        dashboardLayout = defaults.string(forKey: Key.dashboard) ?? PocketDashboard.shared.defaultLayout()
        unreadAnnouncementIds = (defaults.stringArray(forKey: Key.unread)).map(Set.init)
            ?? PocketCampusStore.shared.defaultUnreadIds()
        bookmarkedAnnouncementIds = Set(defaults.stringArray(forKey: Key.bookmarks) ?? [])
        selectedTab = defaults.string(forKey: Key.initialTab).flatMap(PocketTab.init) ?? .home
        // `-demoClock 2026-11-18T10:35` pins time for previews, tests and screenshots.
        fixedClock = defaults.string(forKey: Key.demoClock).flatMap(AppModel.parseClock)
        clock = fixedClock ?? AppModel.systemClock()
    }

    var profile: PocketProfile { dataSource.profile() }
    var isSignedIn: Bool { session == .demo }

    func refreshClock() {
        let next = fixedClock ?? AppModel.systemClock()
        if next != clock { clock = next }
    }

    func widgets(columns: Int) -> [PocketWidget] {
        PocketDashboard.shared.widgets(
            layout: dashboardLayout, columns: Int32(columns), clock: clock, signedIn: isSignedIn,
            unreadIds: unreadAnnouncementIds, bookmarkedIds: bookmarkedAnnouncementIds
        )
    }

    func editDashboard(_ change: (PocketDashboard, String) -> String) {
        dashboardLayout = change(PocketDashboard.shared, dashboardLayout)
    }

    func path(for tab: PocketTab) -> Binding<[PocketRoute]> {
        Binding(
            get: { self.paths[tab] ?? [] },
            set: { self.paths[tab] = $0 }
        )
    }

    /// Selecting the active tab again returns its own stack to the root.
    func select(_ tab: PocketTab) {
        if tab == selectedTab {
            paths[tab] = []
        } else {
            selectedTab = tab
        }
    }

    func enterDemo() {
        session = .demo
        hasCompletedOnboarding = true
    }

    func continueAsGuest() {
        session = .guest
        hasCompletedOnboarding = true
    }

    /// Returns nil on success, otherwise the message to show under the form.
    func signIn(account: String, password: String) async -> String? {
        let failure = await services.account.signIn(account: account, password: password)
        if failure == nil { session = .demo }
        return failure
    }

    func signOut() {
        Task { await services.account.signOut() }
        session = .guest
        paths = [:]
    }

    func replayOnboarding() {
        showingPersonal = false
        paths = [:]
        selectedTab = .home
        hasCompletedOnboarding = false
    }

    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Taipei") ?? .current
        return calendar
    }

    private static func systemClock() -> PocketClock {
        clock(from: Date())
    }

    private static func clock(from date: Date) -> PocketClock {
        let parts = calendar.dateComponents([.year, .month, .day, .weekday, .hour, .minute], from: date)
        let weekday = parts.weekday ?? 1
        return PocketClock(
            year: Int32(parts.year ?? 2026),
            month: Int32(parts.month ?? 1),
            day: Int32(parts.day ?? 1),
            dayOfWeek: Int32(weekday == 1 ? 7 : weekday - 1),
            minuteOfDay: Int32((parts.hour ?? 0) * 60 + (parts.minute ?? 0))
        )
    }

    private static func parseClock(_ text: String) -> PocketClock? {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return formatter.date(from: text).map(clock(from:))
    }
}

extension PocketAnnouncement: @retroactive Identifiable {}
extension PocketEventRow: @retroactive Identifiable {}
extension PocketScheduleCell: @retroactive Identifiable {}
extension PocketYouBikeStation: @retroactive Identifiable {}
extension PocketCountdown: @retroactive Identifiable {}
extension PocketWidget: @retroactive Identifiable {}
extension PocketAlertRow: @retroactive Identifiable {}
extension PocketChoice: @retroactive Identifiable {}
extension PocketWidgetType: @retroactive Identifiable {
    public var id: String { type }
}
