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
    var store = ScheduleSnapshotStore.shared
    private let defaults = UserDefaults.standard

    /// Set by the app shell; taps that arrive before it exists are held here.
    var openLink: ((PocketDeepLink) -> Void)? {
        didSet { flushPendingLink() }
    }

    private var pendingLink: PocketDeepLink?

    /// Installed by the app shell: true while the app lock covers the app. Links wait until it opens.
    var isLocked: (@MainActor () -> Bool)?

    /// Whether the app lock is closed right now. A background launch that has no shell yet counts
    /// as locked whenever the lock is switched on: the app always starts locked.
    var lockIsClosed: Bool { isLocked?() ?? AppLockSetting().isEnabled }

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
    /// The tail of the refresh queue.
    private var queue: Task<Void, Never>?

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
        // The handler is main-actor code, so it is delivered on the main queue: iOS would otherwise
        // call it on a background queue, where Swift 6 aborts a main-actor closure. The expiration
        // handler runs on a queue of the system's choosing and is therefore `@Sendable`.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: PocketPlatformID.refreshTask, using: .main) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            let work = Task { @MainActor in
                // Ask for the next run first: a refresh that runs out of time must not end the chain.
                PocketPlatformCoordinator.shared.scheduleBackgroundRefresh()
                await PocketPlatformCoordinator.shared.refresh()
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            task.expirationHandler = { @Sendable in work.cancel() }
        }
    }

    /// Asks iOS for another refresh. The system decides if and when it runs.
    func scheduleBackgroundRefresh() {
        // Every background job (reminders, widget, grade and announcement checks) belongs to a
        // signed-in student; a guest would only be woken to do nothing.
        guard isSignedIn else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: PocketPlatformID.refreshTask)
            return
        }
        let request = BGAppRefreshTaskRequest(identifier: PocketPlatformID.refreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    // MARK: Refresh

    /// Republishes the snapshot and reschedules everything derived from it.
    ///
    /// Passes run one after another, in the order they were asked for. Interleaved passes would
    /// withdraw each other's reminders, and a pass still running for the previous account would
    /// remove the ones just planned for the next.
    func refresh(deviceNow: Date = Date()) async {
        await serialized { await self.performRefresh(deviceNow: deviceNow) }
    }

    private func serialized<T: Sendable>(_ work: @escaping @MainActor () async -> T) async -> T {
        let previous = queue
        // The caller's cancellation (a background task running out of time) reaches the work.
        let task = Task { @MainActor in
            await previous?.value
            return await work()
        }
        queue = Task { _ = await task.value }
        return await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }

    private func performRefresh(deviceNow: Date) async {
        guard isSignedIn else {
            try? await clearSessionData()
            return
        }
        let generation = sessionGeneration
        let isCurrent: @MainActor () -> Bool = { [unowned self] in generation == sessionGeneration && isSignedIn }
        // One rule for every caller, foreground or background task: behind the app lock nothing of
        // the student's is read, fetched or published. The device being unlocked changes nothing;
        // what is already on the widget and in the scheduled reminders stays as it is.
        guard !lockIsClosed else {
            scheduleBackgroundRefresh()
            return
        }
        await data.prepare()
        guard isCurrent(), !lockIsClosed else { return }
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
        // The checks below reach the network; a background task that ran out of time stops here.
        if !paused, isCurrent(), !Task.isCancelled {
            let gradeAlert = await data.gradeNotification(at: deviceNow, settings: shared)
            if isCurrent() { await notifications.deliver(gradeAlert) }
            if isCurrent(), !Task.isCancelled, let headlines = data.announcementHeadlines(at: scheduleNow) {
                await notifications.checkAnnouncements(headlines, now: deviceNow, settings: shared)
            }
        }
        // A sign-out during the awaits above already ran its own cleanup; run it
        // again so nothing this pass scheduled or published survives.
        guard isCurrent() else {
            if !isSignedIn { try? await clearSessionData() }
            return
        }
        if plansFromSnapshot {
            let glance = json.flatMap { ScheduleSnapshot.decode(Data($0.utf8)) }?.glance(at: deviceNow)
            await LessonActivityController.sync(
                glance: glance, enabled: settings.liveActivity && !notifications.isCoursePaused(at: deviceNow)
            )
            // A sign-out or「暫停一日」while the activity was being started must not leave it running.
            if !isCurrent() || !settings.liveActivity || notifications.isCoursePaused(at: Date()) {
                await LessonActivityController.endAll()
            }
            guard isCurrent() else { return }
        }
        scheduleBackgroundRefresh()
    }

    /// Sign-out, revocation and account switches. A failed snapshot deletion
    /// is retried on the next refresh because `isSignedIn` stays false.
    ///
    /// Everything else is still removed when the snapshot cannot be deleted, and that failure is
    /// thrown: privacy cleanup is never reported as done when the widget file is still there.
    func clearSessionData() async throws {
        let snapshot = Result { try store.clear() }
        WidgetCenter.shared.reloadAllTimelines()
        await notifications.removeEverything()
        await LessonActivityController.endAll()
        pendingLink = nil
        scheduleBackgroundRefresh()
        try snapshot.get()
    }

    /// Delete-all: the session ends for the platform first, so nothing is republished, no
    /// background refresh is requested and no baseline is written back after the cleanup.
    func endSessionAndClear() async throws {
        if isSignedIn { sessionGeneration += 1 }
        isSignedIn = false
        let result: Result<Void, Error> = await serialized {
            do { return .success(try await self.clearSessionData()) } catch { return .failure(error) }
        }
        try result.get()
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
            // Without an App Group container nothing is stored; plan from the timetable in memory.
            var snapshotJSON = store.loadData().flatMap { String(data: $0, encoding: .utf8) }
            if snapshotJSON == nil, case let .available(json) = data.scheduleSnapshot(
                scheduleNow: PlatformClock.scheduleNow(deviceNow: now), deviceNow: now
            ) { snapshotJSON = json }
            let reminders = PocketPlatform.shared.courseReminders(
                snapshotJson: snapshotJSON,
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
        guard isSignedIn, let capability, notifications.capabilities.isValid(capability, now: now) else { return }
        await notifications.pauseCourseRemindersForToday(now: now)
        await LessonActivityController.endAll()
    }
}

extension PocketPlatformCoordinator: UNUserNotificationCenterDelegate {
    // Completion-handler form, for the same reason as `didReceive` below.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
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
