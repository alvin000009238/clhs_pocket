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
    /// The school account's data, or nil while the demo is showing. The demo can be entered from
    /// the welcome screen with a real session still stored; it must never read or write that
    /// account's store, so account-scoped state goes through this instead of `live`.
    var account: LiveDataController? { usesLiveData ? live : nil }
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
            // Leaving the demo: pick the school account's data up again.
            if session != .demo { Task { await refreshLiveData() } }
        }
    }

    private func syncSource() {
        if usesLiveData { PocketData.shared.useLive() } else { PocketData.shared.useDemo() }
        PocketData.shared.setDemoDate(clock: storedClock)
    }

    /// Home widget layout in the shared `OverviewWidgetInstance` JSON format.
    var dashboardLayout: String {
        didSet { defaults.set(dashboardLayout, forKey: Key.dashboard) }
    }

    /// Personal subject overrides as the shared `ScheduleSubjectOverride` JSON list.
    /// Custom subject names. Signed in, they belong to the account (and its settings backup);
    /// otherwise they are a device preference.
    var scheduleOverrides: String {
        get { account?.subjectOverrides ?? localScheduleOverrides }
        set {
            if let account, account.session != nil { account.setSubjectOverrides(newValue) } else { localScheduleOverrides = newValue }
        }
    }

    private var localScheduleOverrides: String {
        didSet { defaults.set(localScheduleOverrides, forKey: Key.overrides) }
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

    // Notification and quiet-hours preferences live in `PlatformSettings`, which the platform
    // coordinator reads; these forward so every screen edits the same values.
    private var platform: PlatformSettings { .shared }
    var notifyLessons: Bool { get { platform.courseReminders } set { platform.courseReminders = newValue } }
    var notifyGrades: Bool { get { platform.gradeUpdates } set { platform.gradeUpdates = newValue } }
    var notifyAnnouncements: Bool { get { platform.announcements } set { platform.announcements = newValue } }
    var pauseEnabled: Bool { get { platform.pauseEnabled } set { platform.pauseEnabled = newValue } }
    /// Minutes after midnight.
    var pauseStart: Int { get { platform.pauseStartMinute } set { platform.pauseStartMinute = newValue } }
    var pauseEnd: Int { get { platform.pauseEndMinute } set { platform.pauseEndMinute = newValue } }
    var weatherSource: String {
        didSet {
            defaults.set(weatherSource, forKey: Key.weatherSource)
            guard oldValue != weatherSource, let live else { return }
            Task { await live.refreshPublic(weatherSource: sharedWeatherSource, force: true) }
        }
    }

    /// Turns notifications on, asking iOS for permission outside demo mode. Returns whether they are on.
    func enableNotifications() async -> Bool {
        if live == nil {
            notificationsEnabled = await services.notifications.requestAuthorization()
            await PocketPlatformCoordinator.shared.refresh()
            return notificationsEnabled
        }
        return await PocketPlatformCoordinator.shared.enableNotifications()
    }

    /// Call after changing any notification, Live Activity or quiet-hours preference.
    func platformSettingsChanged() {
        Task { await PocketPlatformCoordinator.shared.refresh() }
    }
    /// Stored in the Keychain and the preferences; see `AppLockSetting`.
    var appLockEnabled: Bool {
        didSet {
            guard oldValue != appLockEnabled, !AppLockSetting().set(appLockEnabled) else { return }
            appLockEnabled = oldValue
        }
    }

    var notificationsEnabled: Bool { get { platform.notificationsEnabled } set { platform.notificationsEnabled = newValue } }

    init() {
        // UI tests start every run from a clean install without touching launch-argument overrides.
        PocketLaunch.seal()
        let lockSetting = AppLockSetting()
        if PocketLaunch.resetsLocalState, let domain = Bundle.main.bundleIdentifier {
            defaults.removePersistentDomain(forName: domain)
            lockSetting.set(false)
        }
        hasCompletedOnboarding = defaults.bool(forKey: Key.onboarding)
        appearance = defaults.string(forKey: Key.appearance).flatMap(PocketAppearance.init) ?? .system
        session = defaults.string(forKey: Key.session).flatMap(PocketSession.init) ?? .guest
        dashboardLayout = defaults.string(forKey: Key.dashboard) ?? PocketDashboard.shared.defaultLayout()
        unreadAnnouncementIds = (defaults.stringArray(forKey: Key.unread)).map(Set.init)
            ?? PocketCampusStore.shared.defaultUnreadIds()
        localScheduleOverrides = defaults.string(forKey: Key.overrides) ?? "[]"
        weatherSource = defaults.string(forKey: Key.weatherSource) ?? "open-meteo"
        let lockEnabled = lockSetting.isEnabled
        appLockEnabled = lockEnabled
        bookmarkedAnnouncementIds = Set(defaults.stringArray(forKey: Key.bookmarks) ?? [])
        selectedTab = PocketLaunch.initialTab.flatMap(PocketTab.init) ?? .home
        // `-demoClock 2026-11-18T10:35` pins time for previews, tests and screenshots (Debug only).
        fixedClock = PocketLaunch.demoClock.flatMap(AppModel.parseClock)
        storedClock = fixedClock ?? AppModel.systemClock()
        lock = AppLockController(isEnabled: lockEnabled, authenticator: PocketLaunch.authenticator())
        PocketPlatformCoordinator.shared.unlockGate = AppLockRouteGate(lock: lock).pass
        PocketPlatformCoordinator.shared.isLocked = { [lock] in lock.isLocked }
        live = PocketLaunch.usesDemoData ? nil : (try? PocketBackend.live()).map(LiveDataController.init)
        syncSource()
        if defaults.string(forKey: Key.dashboard) == nil { dashboardLayout = PocketDashboard.shared.defaultLayout() }
        if usesLiveData { unreadAnnouncementIds = [] }
        if let live {
            PocketPlatformCoordinator.shared.data = LivePlatformData(live: live) { [weak self] in self?.usesLiveData ?? false }
        }
    }

    var sharedWeatherSource: WeatherSource { weatherSource == "cwa" ? .cwa : .openMeteo }

    /// Launch and every return to the foreground: restore the session once, then refresh.
    func refreshLiveData(force: Bool = false) async {
        // Nothing is fetched for the school account while the demo is showing.
        guard usesLiveData, let live else { return }
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

    /// The shared core that reads and writes backups. Demo mode has no live controller but still
    /// uses the same protected settings file, so a backup is Android-compatible in both modes.
    @ObservationIgnored private lazy var transferBackend: PocketBackend? = live?.backend ?? (try? PocketBackend.live())

    /// Copies this device's preferences into the shared settings, keeping fields iOS has no screen for.
    private func storeSharedSettings(_ backend: PocketBackend) async throws(PocketError) {
        let stored = try await backend.call { try await $0.loadSettings(token: $1) }
        let theme: ThemeMode = switch appearance {
        case .system: .system
        case .light: .light
        case .dark: .dark
        }
        let next = stored.doCopy(
            themeMode: theme, dynamicColor: stored.dynamicColor, amoledBlack: stored.amoledBlack,
            notificationsEnabled: notificationsEnabled, preferExactCourseAlarms: stored.preferExactCourseAlarms,
            developerEnabled: stored.developerEnabled, appLockEnabled: appLockEnabled, weatherSource: sharedWeatherSource,
            startOnSchedule: stored.startOnSchedule, scheduleDisplayMode: stored.scheduleDisplayMode,
            profileAvatarShapeIndex: stored.profileAvatarShapeIndex, hasCompletedOnboarding: hasCompletedOnboarding,
            backgroundPauseEnabled: pauseEnabled, backgroundPauseStartMinute: Int32(pauseStart),
            backgroundPauseEndMinute: Int32(pauseEnd), overview: PocketDashboard.shared.instances(layout: dashboardLayout),
            overviewLegacyOrder: false, announcementReminderEnabled: notifyAnnouncements,
            announcementIntervalMinutes: stored.announcementIntervalMinutes, announcementUnitIds: stored.announcementUnitIds,
            remindBeforeClass: notifyLessons
        )
        try await backend.call { try await $0.saveSettings(settings: next, token: $1) }
    }

    /// A backup in the JSON format shared with Android. Credentials and school data are never included.
    func exportSettings() async throws(PocketError) -> String {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        try await storeSharedSettings(backend)
        let session = usesLiveData ? live?.session : nil
        return try await backend.call { try await $0.exportSettingsJson(session: session, token: $1) }
    }

    /// Validates and applies a backup. The app lock and notification permission are never turned on by an import.
    func importSettings(_ text: String) async throws(PocketError) {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        _ = try await backend.call { try await $0.inspectSettingsJson(text: text, token: $1) }
        let session = usesLiveData ? live?.session : nil
        try await backend.call { try await $0.restoreSettingsJson(text: text, includeSubjects: session != nil, session: session, token: $1) }
        await account?.reloadSubjectOverrides()
        let restored = try await backend.call { try await $0.loadSettings(token: $1) }
        appearance = switch restored.themeMode {
        case .light: .light
        case .dark: .dark
        default: .system
        }
        dashboardLayout = PocketDashboard.shared.layout(instances: restored.overview)
        weatherSource = restored.weatherSource == .cwa ? "cwa" : "open-meteo"
        pauseStart = Int(restored.backgroundPauseStartMinute)
        pauseEnd = Int(restored.backgroundPauseEndMinute)
        pauseEnabled = restored.backgroundPauseEnabled
        notifyAnnouncements = restored.announcementReminderEnabled
        notifyLessons = restored.remindBeforeClass
        if !restored.notificationsEnabled { notificationsEnabled = false }
        platformSettingsChanged()
    }

    /// Signs out and restores every device-local preference to its default.
    /// Returns false when part of the stored data could not be removed; the failure is also
    /// surfaced through `accountError`. Preferences and scheduled alerts are reset either way.
    @discardableResult
    func deleteAllData() async -> Bool {
        var succeeded = true
        do {
            if let live {
                try await live.deleteAllData()
            } else if let backend = transferBackend {
                try await backend.deleteAllData()
            }
        } catch {
            accountError = Self.cleanupMessage(PocketError(error))
            succeeded = false
        }
        session = .guest
        paths = [:]
        appearance = .system
        dashboardLayout = PocketDashboard.shared.defaultLayout()
        localScheduleOverrides = "[]"
        unreadAnnouncementIds = PocketCampusStore.shared.defaultUnreadIds()
        bookmarkedAnnouncementIds = []
        weatherSource = "open-meteo"
        // Pending reminders, the widget snapshot and Live Activities go with the data, and the
        // notification preferences return to off so a later sign-in schedules nothing by itself.
        PlatformSettings.shared.reset()
        await PocketPlatformCoordinator.shared.clearSessionData()
        await PocketPlatformCoordinator.shared.refresh()
        return succeeded
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
