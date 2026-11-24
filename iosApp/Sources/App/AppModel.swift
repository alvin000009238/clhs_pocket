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
    case subjectTrend
    case campusSearch
    case announcementReminders
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
        static let scheduleClass = "scheduleClass"
        static let scheduleYear = "scheduleYear"
        static let unread = "unreadAnnouncementIds"
        static let bookmarks = "bookmarkedAnnouncementIds"
        static let lessonAlerts = "notifyLessons"
        static let gradeAlerts = "notifyGrades"
        static let announcementAlerts = "notifyAnnouncements"
        static let pauseEnabled = "backgroundPauseEnabled"
        static let pauseStart = "backgroundPauseStart"
        static let pauseEnd = "backgroundPauseEnd"
        static let weatherSource = "weatherSource"
        static let startOnSchedule = "startOnSchedule"
        static let developer = "developerEnabled"
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

    /// Android's「啟動時開啟課表」: a cold launch opens the schedule instead of 首頁.
    var startOnSchedule: Bool {
        didSet { defaults.set(startOnSchedule, forKey: Key.startOnSchedule) }
    }

    /// Unlocked by tapping the version ten times on the About page, as on Android.
    var developerEnabled: Bool {
        didSet { defaults.set(developerEnabled, forKey: Key.developer) }
    }

    var usage: UsageStatisticsStore { UsageStatisticsStore(defaults: defaults) }

    var appearance: PocketAppearance {
        didSet { defaults.set(appearance.rawValue, forKey: Key.appearance) }
    }

    var session: PocketSession {
        didSet {
            defaults.set(session.rawValue, forKey: Key.session)
            if oldValue != session { demoScheduleClass = ""; demoScheduleYear = "" }
            syncSource()
            syncWidgetLayout()
            guard live != nil, oldValue != session else { return }
            // The demo account and real data never share read state.
            syncingUnread = true
            unreadAnnouncementIds = session == .demo ? PocketCampusStore.shared.defaultUnreadIds() : []
            syncingUnread = false
            // Leaving the demo: pick the school account's data up again.
            if session != .demo, !resettingPreferences { Task { await refreshLiveData() } }
        }
    }

    private func syncSource() {
        if usesLiveData { PocketData.shared.useLive() } else { PocketData.shared.useDemo() }
        PocketData.shared.setDemoDate(clock: storedClock)
    }

    /// Home widget layout in the shared `OverviewWidgetInstance` JSON format.
    var dashboardLayout: String {
        didSet {
            defaults.set(dashboardLayout, forKey: Key.dashboard)
            syncWidgetLayout()
        }
    }

    /// The editor's unsaved layout. Home renders it while the editor is open; 完成 saves it.
    private(set) var dashboardDraft: String? {
        didSet { syncWidgetLayout() }
    }
    @ObservationIgnored private var draftHistory: [String] = []
    var dashboardHasChanges: Bool { dashboardDraft.map { $0 != dashboardLayout } ?? false }
    var canUndoDashboard: Bool { dashboardDraft != nil && !draftHistory.isEmpty }

    func beginDashboardEdit() {
        dashboardDraft = dashboardLayout
        draftHistory = []
    }

    func saveDashboardEdit() {
        if let dashboardDraft, dashboardDraft != dashboardLayout { dashboardLayout = dashboardDraft }
        dashboardDraft = nil
        draftHistory = []
    }

    func discardDashboardEdit() {
        dashboardDraft = nil
        draftHistory = []
    }

    func undoDashboardEdit() {
        guard dashboardDraft != nil, let previous = draftHistory.popLast() else { return }
        dashboardDraft = previous
    }

    /// Weather and YouBike widgets load what the visible layout asks for, including a draft.
    private func syncWidgetLayout() {
        guard usesLiveData, let live else { return }
        live.setWidgetLayout(dashboardDraft ?? dashboardLayout)
    }

    /// Offline township search; the bundled catalogue is read on first use.
    func searchWeatherLocations(_ query: String) -> [PocketChoice] {
        if !PocketWeatherCatalog.shared.isLoaded,
           let url = Bundle.main.url(forResource: "weather_locations", withExtension: "json"),
           let json = try? String(contentsOf: url, encoding: .utf8) {
            _ = PocketWeatherCatalog.shared.load(json: json)
        }
        return PocketDashboard.shared.searchWeatherLocations(query: query)
    }

    /// Public YouBike search in live mode, the demo stations otherwise.
    func searchYouBikeStations(_ query: String) async throws(PocketError) -> [YouBikeStation] {
        guard usesLiveData, let live else { return PocketDashboard.shared.searchDemoYouBikeStations(query: query) }
        return try await live.backend.call { try await $0.searchYouBikeStations(query: query, token: $1) }
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
        didSet {
            defaults.set(Array(bookmarkedAnnouncementIds), forKey: Key.bookmarks)
            guard oldValue != bookmarkedAnnouncementIds, !resettingPreferences, usesLiveData else { return }
            live?.syncBookmarks(bookmarkedAnnouncementIds)
        }
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
            // Nothing is fetched while the demo shows or while delete-all resets the preferences.
            guard oldValue != weatherSource, !resettingPreferences, usesLiveData, let live else { return }
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
    /// Mirrors the lock for the settings screens. `lock` is the authority: it stores the setting
    /// itself (see `AppLockSetting`) and changes only when that succeeds, so a value assigned here
    /// that the lock did not accept is put back.
    var appLockEnabled: Bool {
        didSet {
            if appLockEnabled != lock.isEnabled { appLockEnabled = lock.isEnabled }
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
        let opensSchedule = defaults.bool(forKey: Key.startOnSchedule)
        startOnSchedule = opensSchedule
        developerEnabled = defaults.bool(forKey: Key.developer)
        selectedTab = PocketLaunch.initialTab.flatMap(PocketTab.init) ?? (opensSchedule ? .schedule : .home)
        // `-demoClock 2026-11-18T10:35` pins time for previews, tests and screenshots (Debug only).
        fixedClock = PocketLaunch.demoClock.flatMap(AppModel.parseClock)
        storedClock = fixedClock ?? AppModel.systemClock()
        lock = AppLockController(
            isEnabled: lockEnabled, authenticator: PocketLaunch.authenticator(), persist: { lockSetting.set($0) }
        )
        PocketPlatformCoordinator.shared.unlockGate = AppLockRouteGate(lock: lock).pass
        PocketPlatformCoordinator.shared.isLocked = { [lock] in lock.isLocked }
        var storageFailure: String?
        if PocketLaunch.usesDemoData {
            live = nil
        } else {
            do {
                live = LiveDataController(backend: try PocketBackend.live())
            } catch {
                // Not a reason to show the demo as if it were the student's data without a word.
                live = nil
                storageFailure = "無法開啟 App 的儲存空間，目前只能顯示示範資料。請重新啟動 App；若仍發生，請確認裝置儲存空間。"
            }
        }
        syncSource()
        if defaults.string(forKey: Key.dashboard) == nil { dashboardLayout = PocketDashboard.shared.defaultLayout() }
        syncWidgetLayout()
        if usesLiveData { unreadAnnouncementIds = [] }
        if let live {
            live.scheduleChoice = .init(
                yearValue: defaults.string(forKey: Key.scheduleYear) ?? "", classValue: defaults.string(forKey: Key.scheduleClass) ?? ""
            )
            PocketPlatformCoordinator.shared.data = LivePlatformData(live: live) { [weak self] in self?.usesLiveData ?? false }
        } else if storageFailure == nil {
            PocketPlatformCoordinator.shared.data = DemoPlatformData()
        }
        // With storage unavailable the widget and reminders keep what they have; no demo lessons.
        accountError = storageFailure
        usage.record(.appOpen)
        usage.record(UsageMetric(tab: selectedTab))
    }

    var sharedWeatherSource: WeatherSource { weatherSource == "cwa" ? .cwa : .openMeteo }

    /// Launch and every return to the foreground: restore the session once, then refresh.
    func refreshLiveData(force: Bool = false) async {
        // Nothing is fetched for the school account while the demo is showing.
        guard usesLiveData, let live else { return }
        if restored {
            await live.refreshPrivate(force: force)
        } else {
            await live.restoreOnce()
            // Not yet readable (device locked, storage error): the next refresh restores again.
            restored = live.restoreSettled
        }
        live.syncBookmarks(bookmarkedAnnouncementIds)
        await live.refreshPublic(weatherSource: sharedWeatherSource, force: force)
        await syncUnread()
    }

    /// Retry for public widgets (weather, YouBike); the school session is left untouched.
    func refreshPublicData() async {
        guard usesLiveData, let live else { return }
        await live.refreshPublic(weatherSource: sharedWeatherSource, force: true)
        // The public refresh also reloads announcements, so their unread state follows.
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
            forgetScheduleChoice()
            try await live.login(studentNo: studentNo, cookies: cookies)
            session = .guest
            hasCompletedOnboarding = true
            return nil
        } catch {
            return error.message
        }
    }

    var profile: PocketProfile { dataSource.profile() }

    /// The photo on the personal page. A school account's is in its private store and goes with
    /// logout and delete-all; the demo's stays in memory, so neither ever shows the other's.
    var profilePhoto: Data? {
        if session == .demo { return demoProfilePhoto }
        return usesLiveData ? live?.profilePhoto : nil
    }

    private var demoProfilePhoto: Data?

    func setProfilePhoto(_ photo: Data?) async throws(PocketError) {
        if session == .demo {
            demoProfilePhoto = photo
        } else if usesLiveData, let live {
            try await live.setProfilePhoto(photo)
        }
    }

    /// Clears the chosen caches on this device and stays signed in.
    func clearLocalData(_ areas: [LocalDataArea]) async throws(PocketError) {
        guard !areas.isEmpty else { return }
        if let live {
            try await live.clearLocalData(areas)
        } else if let backend = transferBackend {
            try await backend.call { try await $0.clearLocalData(areas: areas, token: $1) }
        } else {
            throw PocketError(kind: .storage, message: "無法存取資料儲存空間")
        }
    }
    var isSignedIn: Bool { session == .demo || live?.session != nil }
    @ObservationIgnored private var restored = false
    /// True while delete-all puts the preferences back; their side effects stay off.
    @ObservationIgnored private var resettingPreferences = false

    func refreshClock() {
        let next = fixedClock ?? AppModel.systemClock()
        if next != storedClock { storedClock = next }
    }

    func widgets(columns: Int) -> [PocketWidget] {
        PocketDashboard.shared.widgets(
            layout: dashboardDraft ?? dashboardLayout, columns: Int32(columns), clock: clock, signedIn: isSignedIn,
            unreadIds: unreadAnnouncementIds, bookmarkedIds: bookmarkedAnnouncementIds
        )
    }

    func editDashboard(_ change: (PocketDashboard, String) -> String) {
        if let draft = dashboardDraft {
            let next = change(PocketDashboard.shared, draft)
            guard next != draft else { return }
            draftHistory.append(draft)
            dashboardDraft = next
        } else {
            dashboardLayout = change(PocketDashboard.shared, dashboardLayout)
        }
    }

    /// Back to the default arrangement; inside the editor this only changes the draft, so it can be undone.
    func resetDashboard() {
        editDashboard { dashboard, _ in dashboard.defaultLayout() }
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
            usage.record(UsageMetric(tab: tab))
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
        Task { try? await clearSession() }
    }

    /// Signs out like 登出 and waits for the account cleanup, so a failure reaches the caller too.
    func clearSession() async throws(PocketError) {
        let wasDemo = session == .demo
        forgetScheduleChoice()
        demoProfilePhoto = nil
        session = .guest
        paths = [:]
        guard !wasDemo, let live else { return }
        do {
            try await live.logout()
        } catch {
            let failure = PocketError(error)
            accountError = Self.cleanupMessage(failure)
            throw failure
        }
    }

    private static func cleanupMessage(_ error: PocketError) -> String {
        error.kind == .cleanupIncomplete ? "\(error.message)。請再試一次，完成前無法重新登入。" : error.message
    }

    /// The class the timetable screen shows: empty for the student's own timetable. Kept across
    /// launches; signing out or switching account returns to the student's own.
    /// The demo keeps its choice in memory, so it never replaces the school account's.
    var scheduleClass: String {
        get {
            access(keyPath: \.scheduleClass)
            if session == .demo { return demoScheduleClass }
            return usesLiveData && live?.session != nil ? defaults.string(forKey: Key.scheduleClass) ?? "" : ""
        }
        set {
            withMutation(keyPath: \.scheduleClass) {
                if session == .demo {
                    demoScheduleClass = newValue
                } else {
                    defaults.set(newValue, forKey: Key.scheduleClass)
                    live?.scheduleChoice.classValue = newValue
                }
            }
        }
    }

    /// The term the timetable screen shows, a school year option such as `115_1`; empty for the
    /// newest term. Kept and cleared together with `scheduleClass`.
    var scheduleYear: String {
        get {
            access(keyPath: \.scheduleYear)
            if session == .demo { return demoScheduleYear }
            return usesLiveData && live?.session != nil ? defaults.string(forKey: Key.scheduleYear) ?? "" : ""
        }
        set {
            withMutation(keyPath: \.scheduleYear) {
                if session == .demo {
                    demoScheduleYear = newValue
                } else {
                    defaults.set(newValue, forKey: Key.scheduleYear)
                    live?.scheduleChoice.yearValue = newValue
                }
            }
        }
    }

    /// Whether the signed-in student still has to choose a term and class, as Android asks before
    /// the first timetable. The demo shows its sample timetable straight away.
    var needsScheduleChoice: Bool {
        access(keyPath: \.scheduleYear)
        return session != .demo && usesLiveData && live?.session != nil && defaults.string(forKey: Key.scheduleYear) == nil
    }

    /// Stores the student's choice. A different term or class drops the timetables loaded for
    /// the previous one, so its lessons never show under the new title.
    func chooseSchedule(yearValue: String, classValue: String) {
        let changed = yearValue != scheduleYear || classValue != scheduleClass
        if changed, session != .demo, usesLiveData { PocketLive.shared.clearSchedules() }
        scheduleYear = yearValue
        scheduleClass = classValue
    }

    private func forgetScheduleChoice() {
        defaults.removeObject(forKey: Key.scheduleClass)
        defaults.removeObject(forKey: Key.scheduleYear)
        live?.scheduleChoice = .init()
    }

    @ObservationIgnored private var demoScheduleClass = ""
    @ObservationIgnored private var demoScheduleYear = ""

    /// Deletes a settings file that can no longer be read (「設定檔已損壞，請重設設定後再試一次」),
    /// so settings and backups work again with the defaults.
    func resetDamagedSettings() async throws(PocketError) {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        try await backend.call { try await $0.resetSettings(token: $1) }
    }

    /// The shared core that reads and writes backups. Demo mode has no live controller but still
    /// uses the same protected settings file, so a backup is Android-compatible in both modes.
    @ObservationIgnored private lazy var transferBackend: PocketBackend? = live?.backend ?? (try? PocketBackend.live())

    func hasCwaKey() async -> Bool {
        guard let backend = transferBackend else { return false }
        return (try? await backend.call { try await $0.hasCwaKey(token: $1) })?.boolValue ?? false
    }

    /// Checks the key against the live API; only a valid key is stored, then weather reloads.
    func saveCwaKey(_ key: String) async throws(PocketError) -> CwaKeyValidation {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        let result = try await backend.call { try await $0.saveCwaKey(rawKey: key, token: $1) }
        if result == .valid, let live { await live.refreshPublic(weatherSource: sharedWeatherSource, force: true) }
        return result
    }

    func clearCwaKey() async throws(PocketError) {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        try await backend.call { try await $0.clearCwaKey(token: $1) }
    }

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
            developerEnabled: developerEnabled, appLockEnabled: appLockEnabled, weatherSource: sharedWeatherSource,
            startOnSchedule: startOnSchedule, scheduleDisplayMode: stored.scheduleDisplayMode,
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
    /// Validates a backup without applying it, for the import confirmation.
    func inspectSettings(_ text: String) async throws(PocketError) -> SettingsBackupDocument {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        return try await backend.call { try await $0.inspectSettingsJson(text: text, token: $1) }
    }

    /// Custom subject names can only be restored into a signed-in school account.
    var canImportSubjects: Bool { usesLiveData && live?.session != nil }

    func importSettings(_ text: String, includeSubjects: Bool) async throws(PocketError) {
        guard let backend = transferBackend else { throw PocketError(kind: .storage, message: "無法存取設定儲存空間") }
        let document = try await backend.call { try await $0.inspectSettingsJson(text: text, token: $1) }
        let session = usesLiveData ? live?.session : nil
        // A guest backup has no subject list; restoring it into an account keeps the account's names.
        let subjects = includeSubjects && session != nil && document.subjects != nil
        try await backend.call { try await $0.restoreSettingsJson(text: text, includeSubjects: subjects, session: session, token: $1) }
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
        startOnSchedule = restored.startOnSchedule
        developerEnabled = restored.developerEnabled
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
        // Returning the preferences to their defaults must not write anything back: no read-state
        // file, no refetched public data, no republished widget.
        resettingPreferences = true
        session = .guest
        demoProfilePhoto = nil
        syncingUnread = true
        paths = [:]
        appearance = .system
        dashboardLayout = PocketDashboard.shared.defaultLayout()
        localScheduleOverrides = "[]"
        forgetScheduleChoice()
        // The demo's unread markers belong to the demo's own announcements only.
        unreadAnnouncementIds = live == nil ? PocketCampusStore.shared.defaultUnreadIds() : []
        bookmarkedAnnouncementIds = []
        weatherSource = "open-meteo"
        startOnSchedule = false
        developerEnabled = false
        usage.clear()
        syncingUnread = false
        resettingPreferences = false
        // Pending reminders, the widget snapshot and Live Activities go with the data, and the
        // notification preferences return to off so a later sign-in schedules nothing by itself.
        PlatformSettings.shared.reset()
        do {
            try await PocketPlatformCoordinator.shared.endSessionAndClear()
        } catch {
            accountError = "小工具的課表資料未能刪除。請再試一次。"
            succeeded = false
        }
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
