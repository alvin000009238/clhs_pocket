import BackgroundTasks
import Foundation
import PocketShared
import UIKit
@preconcurrency import UserNotifications
import WidgetKit

/// Owns everything that leaves the app process: the widget snapshot, local
/// notifications, the Live Activity and best-effort background refresh.
@MainActor
final class PocketPlatformCoordinator: NSObject {
    static let shared = PocketPlatformCoordinator()

    var data: PlatformDataProviding = UnavailablePlatformData()
    let settings = PlatformSettings.shared
    let notifications = PocketNotificationCenter()
    let store = ScheduleSnapshotStore.shared
    private let defaults = UserDefaults.standard

    /// Set by the app shell; taps that arrive before it exists are held here.
    var openLink: ((PocketDeepLink) -> Void)? {
        didSet { flushPendingLink() }
    }

    private var pendingLink: PocketDeepLink?

    /// Installed by the app shell: true while the app lock covers the app. Links wait until it opens.
    var isLocked: (@MainActor () -> Bool)?

    /// Mirrors `AppModel.isSignedIn` for background launches, which have no
    /// model. It is the coordinator's own key because `AppModel`'s `session`
    /// default can be shadowed by a launch argument.
    private(set) var isSignedIn: Bool {
        get { defaults.bool(forKey: Self.signedInKey) }
        set { defaults.set(newValue, forKey: Self.signedInKey) }
    }

    private static let signedInKey = "platform.sessionActive"

    /// Bumped on every sign-in or sign-out so an in-flight refresh can tell it is stale.
    private var sessionGeneration = 0

    /// Asked before a notification tap reveals a protected destination. The
    /// shell installs it (see `AppLockRouteGate`); with no gate installed the
    /// destination's own sign-in gate is the only protection.
    var unlockGate: (@MainActor () async -> Bool)?

    /// Called by the app shell whenever the session changes or the app becomes active.
    func sessionChanged(signedIn: Bool) async {
        if signedIn != isSignedIn { sessionGeneration += 1 }
        isSignedIn = signedIn
        await refresh()
    }

    // MARK: Launch

    /// Must run before the app finishes launching (BGTaskScheduler requirement).
    func didFinishLaunching() {
        UNUserNotificationCenter.current().delegate = self
        SystemNotificationScheduler.registerCategories()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: PocketPlatformID.refreshTask, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            let work = Task { @MainActor in
                await PocketPlatformCoordinator.shared.refresh()
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    /// Asks iOS for another refresh. The system decides if and when it runs.
    func scheduleBackgroundRefresh() {
        guard isSignedIn || settings.notificationsEnabled else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: PocketPlatformID.refreshTask)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: PocketPlatformID.refreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    // MARK: Refresh

    /// Republishes the snapshot and reschedules everything derived from it.
    func refresh(deviceNow: Date = Date()) async {
        guard isSignedIn else {
            await clearSessionData()
            return
        }
        let generation = sessionGeneration
        let isCurrent: @MainActor () -> Bool = { [unowned self] in generation == sessionGeneration && isSignedIn }
        await data.prepare()
        guard isCurrent() else { return }
        let shared = settings.shared(at: deviceNow)
        // As on Android, the quiet window only stops background grade and
        // announcement checks; the widget snapshot and course reminders continue.
        let paused = PocketPlatform.shared.isBackgroundPaused(nowMillis: deviceNow.epochMillis, settings: shared)
        let scheduleNow = PlatformClock.scheduleNow(deviceNow: deviceNow)
        let snapshot = data.scheduleSnapshot(scheduleNow: scheduleNow, deviceNow: deviceNow)
        let (change, json) = snapshot.resolved(stored: store.loadData(), now: deviceNow)
        switch change {
        case let .save(json):
            try? store.save(json: json)
            WidgetCenter.shared.reloadAllTimelines()
        case .clear:
            try? store.clear()
            WidgetCenter.shared.reloadAllTimelines()
        case .keep:
            break
        }
        // With the timetable unknown and nothing valid stored, the reminders already scheduled stay.
        let plansFromSnapshot = snapshot != .unknown || json != nil
        if plansFromSnapshot {
            await notifications.syncCourseReminders(snapshotJSON: json, now: deviceNow, settings: shared, isCurrent: isCurrent)
        }
        if !paused, isCurrent() {
            let gradeAlert = await data.gradeNotification(at: deviceNow, settings: shared)
            if isCurrent() { await notifications.deliver(gradeAlert) }
            if isCurrent(), let headlines = data.announcementHeadlines(at: scheduleNow) {
                await notifications.checkAnnouncements(headlines, now: deviceNow, settings: shared)
            }
        }
        // A sign-out during the awaits above already ran its own cleanup; run it
        // again so nothing this pass scheduled or published survives.
        guard isCurrent() else {
            if !isSignedIn { await clearSessionData() }
            return
        }
        if plansFromSnapshot {
            let glance = json.flatMap { ScheduleSnapshot.decode(Data($0.utf8)) }?.glance(at: deviceNow)
            await LessonActivityController.sync(
                glance: glance, enabled: settings.liveActivity && !notifications.isCoursePaused(at: deviceNow)
            )
        }
        scheduleBackgroundRefresh()
    }

    /// Sign-out, revocation and account switches. A failed snapshot deletion
    /// is retried on the next refresh because `isSignedIn` stays false.
    func clearSessionData() async {
        try? store.clear()
        WidgetCenter.shared.reloadAllTimelines()
        await notifications.removeEverything()
        await LessonActivityController.endAll()
        pendingLink = nil
        scheduleBackgroundRefresh()
    }

    // MARK: Permission

    /// Turns notifications on only if iOS grants permission.
    func enableNotifications() async -> Bool {
        let granted = await notifications.scheduler.requestAuthorization()
        settings.notificationsEnabled = granted
        await refresh()
        return granted
    }

    // MARK: Demo alerts

    enum DemoAlert: String, CaseIterable, Identifiable {
        case course, grade, announcement

        var id: String { rawValue }

        var title: String {
            switch self {
            case .course: "上課前提醒"
            case .grade: "段考更新"
            case .announcement: "學校公告"
            }
        }
    }

    /// Sends a synthetic alert through the same planning and delivery path.
    func sendDemoAlert(_ alert: DemoAlert, after delay: TimeInterval = 5) async {
        let now = Date()
        let shared = settings.shared(at: now)
        var planned: PlannedNotification?
        switch alert {
        case .course:
            let reminders = PocketPlatform.shared.courseReminders(
                snapshotJson: store.loadData().flatMap { String(data: $0, encoding: .utf8) },
                nowMillis: now.epochMillis, settings: shared, pausedUntilMillis: 0
            )
            planned = reminders.first
        case .grade:
            planned = PocketPlatform.shared.demoGradeNotification(nowMillis: now.epochMillis, settings: shared)
        case .announcement:
            let latest = data.announcementHeadlines(at: PlatformClock.scheduleNow(deviceNow: now)) ?? []
            planned = PocketPlatform.shared.announcementPlan(
                seenIds: Array(latest.dropFirst().map(\.id)), latest: latest, nowMillis: now.epochMillis, settings: shared
            ).notification
        }
        guard let planned else { return }
        await notifications.deliver(PlannedNotification(
            identifier: "demo-\(alert.rawValue)",
            fireAtMillis: KotlinLong(value: now.addingTimeInterval(delay).epochMillis),
            title: planned.title,
            body: planned.body,
            link: planned.link
        ))
    }

    // MARK: Links

    func handle(url: URL) {
        guard let link = PocketDeepLink(url: url) else { return }
        route(link)
    }

    private func route(_ link: PocketDeepLink) {
        pendingLink = link
        flushPendingLink()
    }

    /// Opens the held link once the shell exists and the app lock is open.
    func flushPendingLink() {
        guard let openLink, let link = pendingLink, isLocked?() != true else { return }
        pendingLink = nil
        openLink(link)
    }

    /// Redeems a notification's one-use capability. An unknown, reused or
    /// revoked token opens the app without navigating anywhere, and a
    /// protected destination is only opened after the unlock gate passes.
    func handleNotificationTap(capability: String?) async {
        // The capability is redeemed only after the gate passes, so a cancelled Face ID prompt
        // leaves the notification usable.
        guard let capability, let destination = notifications.capabilities.link(for: capability) else { return }
        if destination.tab.requiresSession, let unlockGate, !(await unlockGate()) { return }
        guard let link = notifications.capabilities.consume(capability) else { return }
        route(link)
    }

    /// The "暫停一日" action; runs without opening the app.
    ///
    /// Android guards its pause action with the session generation; here the
    /// notification's capability plays that role (it is revoked on sign-out
    /// and is not consumed, so the notification can still be opened), and the
    /// action itself requires the device to be unlocked.
    func pauseCourseRemindersForToday(capability: String?, now: Date = Date()) async {
        guard isSignedIn, let capability, notifications.capabilities.isValid(capability) else { return }
        await notifications.pauseCourseRemindersForToday(now: now)
        await LessonActivityController.endAll()
    }
}

extension PocketPlatformCoordinator: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    // The completion-handler form is used on purpose: UIKit aborts the app when
    // this completion runs off the main thread, which is where the `async`
    // form of this method would resume.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let capability = response.notification.request.content.userInfo[PocketNotificationRequest.capabilityKey] as? String
        let action = response.actionIdentifier
        let done = MainThreadCompletion(run: completionHandler)
        Task { @MainActor in
            // A tap may wait for Face ID; iOS must not be kept waiting for that.
            if action == UNNotificationDefaultActionIdentifier { done.run() }
            await PocketPlatformCoordinator.shared.handleNotificationResponse(action: action, capability: capability)
            if action != UNNotificationDefaultActionIdentifier { done.run() }
        }
    }

    func handleNotificationResponse(action: String, capability: String?) async {
        switch action {
        case PocketNotificationRequest.pauseDayAction:
            await pauseCourseRemindersForToday(capability: capability)
        case UNNotificationDefaultActionIdentifier:
            await handleNotificationTap(capability: capability)
        default:
            break
        }
    }
}

/// Carries a system completion handler to the main actor, where it must be called.
private struct MainThreadCompletion: @unchecked Sendable {
    let run: () -> Void
}

final class PocketAppDelegate: NSObject, UIApplicationDelegate {
    override init() {
        PocketLaunch.seal()
        super.init()
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        PocketPlatformCoordinator.shared.didFinishLaunching()
        return true
    }
}
