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
    case announcement(String)
    case calendar
    case attendance
    case merits
    case subject(exam: String, name: String)
    case simulator(exam: String)
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
        static let overrides = "scheduleSubjectOverrides"
        static let unread = "unreadAnnouncementIds"
        static let bookmarks = "bookmarkedAnnouncementIds"
        static let lessonAlerts = "notifyLessons"
        static let gradeAlerts = "notifyGrades"
        static let announcementAlerts = "notifyAnnouncements"
        static let pauseEnabled = "backgroundPauseEnabled"
        static let pauseStart = "backgroundPauseStart"
        static let pauseEnd = "backgroundPauseEnd"
        static let weatherSource = "weatherSource"
        static let appLock = "appLockEnabled"
    }

    private let source: PocketDataSource = PocketEnvironment.shared.demo()
    /// Real data through `PocketBackend`; nil with `-dataMode demo` (UI tests, screenshots).
    let live: LiveDataController?
    /// Face ID / Touch ID / passcode lock over the whole window.
    let lock: AppLockController
    /// Message of an account action that failed (for example an incomplete logout cleanup).
    var accountError: String?

    /// Facade over whichever source is active. Reading it subscribes the view to live updates.
    var dataSource: PocketDataSource {
        _ = live?.revision
        return source
    }

    /// True while the screens show real data rather than the synthetic fixtures.
    var usesLiveData: Bool { live != nil && session != .demo }
    let services = PocketAppServices()
    private let defaults = UserDefaults.standard
    private let fixedClock: PocketClock?

    private var storedClock: PocketClock
    /// Every screen derives its content from the clock, so it also carries the live-data revision.
    var clock: PocketClock {
        _ = live?.revision
        return storedClock
    }
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
        didSet {
            defaults.set(session.rawValue, forKey: Key.session)
            syncSource()
            guard live != nil, oldValue != session else { return }
            // The demo account and real data never share read state.
            syncingUnread = true
            unreadAnnouncementIds = session == .demo ? PocketCampusStore.shared.defaultUnreadIds() : []
            syncingUnread = false
            if session != .demo { Task { await syncUnread() } }
        }
    }

    private func syncSource() {
        if usesLiveData { PocketData.shared.useLive() } else { PocketData.shared.useDemo() }
    }

    /// Home widget layout in the shared `OverviewWidgetInstance` JSON format.
    var dashboardLayout: String {
        didSet { defaults.set(dashboardLayout, forKey: Key.dashboard) }
    }

    /// Personal subject overrides as the shared `ScheduleSubjectOverride` JSON list.
    var scheduleOverrides: String {
        didSet { defaults.set(scheduleOverrides, forKey: Key.overrides) }
    }

    var unreadAnnouncementIds: Set<String> {
        didSet {
            guard usesLiveData else {
                defaults.set(Array(unreadAnnouncementIds), forKey: Key.unread)
                return
            }
            let read = oldValue.subtracting(unreadAnnouncementIds)
            guard !syncingUnread, !read.isEmpty, let live else { return }
            Task { try? await live.backend.call { try await $0.markAnnouncementsRead(ids: read, token: $1) } }
        }
    }
    @ObservationIgnored private var syncingUnread = false

    var bookmarkedAnnouncementIds: Set<String> {
        didSet { defaults.set(Array(bookmarkedAnnouncementIds), forKey: Key.bookmarks) }
    }

    var notifyLessons: Bool { didSet { defaults.set(notifyLessons, forKey: Key.lessonAlerts) } }
    var notifyGrades: Bool { didSet { defaults.set(notifyGrades, forKey: Key.gradeAlerts) } }
    var notifyAnnouncements: Bool { didSet { defaults.set(notifyAnnouncements, forKey: Key.announcementAlerts) } }
    var pauseEnabled: Bool { didSet { defaults.set(pauseEnabled, forKey: Key.pauseEnabled) } }
    /// Minutes after midnight.
    var pauseStart: Int { didSet { defaults.set(pauseStart, forKey: Key.pauseStart) } }
    var pauseEnd: Int { didSet { defaults.set(pauseEnd, forKey: Key.pauseEnd) } }
    var weatherSource: String { didSet { defaults.set(weatherSource, forKey: Key.weatherSource) } }
    var appLockEnabled: Bool { didSet { defaults.set(appLockEnabled, forKey: Key.appLock) } }

    var notificationsEnabled: Bool {
        didSet { defaults.set(notificationsEnabled, forKey: Key.notifications) }
    }

    init() {
        // UI tests start every run from a clean install without touching launch-argument overrides.
        if defaults.bool(forKey: "resetLocalState"), let domain = Bundle.main.bundleIdentifier {
            defaults.removePersistentDomain(forName: domain)
        }
        hasCompletedOnboarding = defaults.bool(forKey: Key.onboarding)
        appearance = defaults.string(forKey: Key.appearance).flatMap(PocketAppearance.init) ?? .system
        session = defaults.string(forKey: Key.session).flatMap(PocketSession.init) ?? .guest
        notificationsEnabled = defaults.bool(forKey: Key.notifications)
        dashboardLayout = defaults.string(forKey: Key.dashboard) ?? PocketDashboard.shared.defaultLayout()
        unreadAnnouncementIds = (defaults.stringArray(forKey: Key.unread)).map(Set.init)
            ?? PocketCampusStore.shared.defaultUnreadIds()
        scheduleOverrides = defaults.string(forKey: Key.overrides) ?? "[]"
        notifyLessons = defaults.object(forKey: Key.lessonAlerts) as? Bool ?? true
        notifyGrades = defaults.object(forKey: Key.gradeAlerts) as? Bool ?? true
        notifyAnnouncements = defaults.object(forKey: Key.announcementAlerts) as? Bool ?? true
        pauseEnabled = defaults.bool(forKey: Key.pauseEnabled)
        pauseStart = defaults.object(forKey: Key.pauseStart) as? Int ?? 23 * 60
        pauseEnd = defaults.object(forKey: Key.pauseEnd) as? Int ?? 6 * 60
        weatherSource = defaults.string(forKey: Key.weatherSource) ?? "open-meteo"
        appLockEnabled = defaults.bool(forKey: Key.appLock)
        bookmarkedAnnouncementIds = Set(defaults.stringArray(forKey: Key.bookmarks) ?? [])
        selectedTab = defaults.string(forKey: Key.initialTab).flatMap(PocketTab.init) ?? .home
        // `-demoClock 2026-11-18T10:35` pins time for previews, tests and screenshots.
        fixedClock = defaults.string(forKey: Key.demoClock).flatMap(AppModel.parseClock)
        storedClock = fixedClock ?? AppModel.systemClock()
        lock = AppLockController(isEnabled: defaults.bool(forKey: Key.appLock), authenticator: AppModel.authenticator(defaults))
        PocketPlatformCoordinator.shared.unlockGate = AppLockRouteGate(lock: lock).pass
        live = defaults.string(forKey: "dataMode") == "demo" ? nil : (try? PocketBackend.live()).map(LiveDataController.init)
        syncSource()
        if defaults.string(forKey: Key.dashboard) == nil { dashboardLayout = PocketDashboard.shared.defaultLayout() }
        if usesLiveData { unreadAnnouncementIds = [] }
    }

    var sharedWeatherSource: WeatherSource { weatherSource == "cwa" ? .cwa : .openMeteo }

    /// Launch and every return to the foreground: restore the session once, then refresh.
    func refreshLiveData(force: Bool = false) async {
        guard let live else { return }
        if !restored {
            restored = true
            await live.restore()
        } else {
            await live.refreshPrivate(force: force)
        }
        await live.refreshPublic(weatherSource: sharedWeatherSource, force: force)
        await syncUnread()
    }

    /// In live mode the read state lives in the shared announcement repository.
    private func syncUnread() async {
        guard usesLiveData, let live else { return }
        if let ids = try? await live.backend.call({ try await $0.unreadAnnouncementIds(token: $1) }) {
            syncingUnread = true
            unreadAnnouncementIds = Set(ids)
            syncingUnread = false
        }
    }

    /// Completes a login from the school web view. Returns the message to show on failure.
    func signIn(studentNo: String, cookies: [String: String]) async -> String? {
        guard let live else { return "示範模式無法登入校務系統" }
        do {
            try await live.login(studentNo: studentNo, cookies: cookies)
            session = .guest
            hasCompletedOnboarding = true
            return nil
        } catch {
            return error.message
        }
    }

    var profile: PocketProfile { dataSource.profile() }
    var isSignedIn: Bool { session == .demo || live?.session != nil }
    @ObservationIgnored private var restored = false

    func refreshClock() {
        let next = fixedClock ?? AppModel.systemClock()
        if next != storedClock { storedClock = next }
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

    /// Presents the login sheet from any guest gate.
    var showingLogin = false
    var loginActionTitle: String { live != nil ? "登入校務帳號" : "使用示範帳號" }

    func requestLogin() {
        if live != nil { showingLogin = true } else { enterDemo() }
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
        let wasDemo = session == .demo
        session = .guest
        paths = [:]
        guard !wasDemo, let live else { return }
        Task {
            do { try await live.logout() } catch { accountError = Self.cleanupMessage(PocketError(error)) }
        }
    }

    private static func cleanupMessage(_ error: PocketError) -> String {
        error.kind == .cleanupIncomplete ? "\(error.message)。請再試一次，完成前無法重新登入。" : error.message
    }

    /// Values a settings backup carries. Credentials and school data are never included.
    var exportableSettings: [String: String] {
        [
            Key.appearance: appearance.rawValue,
            Key.dashboard: dashboardLayout,
            Key.overrides: scheduleOverrides,
            Key.weatherSource: weatherSource,
            Key.pauseEnabled: String(pauseEnabled),
            Key.pauseStart: String(pauseStart),
            Key.pauseEnd: String(pauseEnd),
        ]
    }

    func applyImportedSettings(_ values: [String: String]) {
        if let value = values[Key.appearance].flatMap(PocketAppearance.init(rawValue:)) { appearance = value }
        if let value = values[Key.dashboard] { dashboardLayout = value }
        if let value = values[Key.overrides] { scheduleOverrides = value }
        if let value = values[Key.weatherSource] { weatherSource = value }
        if let value = values[Key.pauseEnabled] { pauseEnabled = value == "true" }
        if let value = values[Key.pauseStart].flatMap(Int.init) { pauseStart = min(max(value, 0), 1439) }
        if let value = values[Key.pauseEnd].flatMap(Int.init) { pauseEnd = min(max(value, 0), 1439) }
    }

    /// Signs out and restores every device-local preference to its default.
    func deleteAllData() async {
        if let live {
            do { try await live.deleteAllData() } catch { accountError = Self.cleanupMessage(PocketError(error)) }
        }
        session = .guest
        paths = [:]
        appearance = .system
        dashboardLayout = PocketDashboard.shared.defaultLayout()
        scheduleOverrides = "[]"
        unreadAnnouncementIds = PocketCampusStore.shared.defaultUnreadIds()
        bookmarkedAnnouncementIds = []
        notificationsEnabled = false
        notifyLessons = true
        notifyGrades = true
        notifyAnnouncements = true
        pauseEnabled = false
        pauseStart = 23 * 60
        pauseEnd = 6 * 60
        weatherSource = "open-meteo"
    }

    func replayOnboarding() {
        showingPersonal = false
        paths = [:]
        selectedTab = .home
        hasCompletedOnboarding = false
    }

    /// `-lockAuth succeed|manual` replaces the system prompt in UI tests; `manual` dismisses the
    /// automatic prompt once so the lock screen stays visible until its button is tapped.
    private static func authenticator(_ defaults: UserDefaults) -> DeviceAuthenticating {
        switch defaults.string(forKey: "lockAuth") {
        case "succeed": ScriptedAuthenticator(cancelFirst: false)
        case "manual": ScriptedAuthenticator(cancelFirst: true)
        default: SystemDeviceAuthenticator()
        }
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
