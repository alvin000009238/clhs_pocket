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

    var data: PlatformDataProviding = DemoPlatformData()
    let settings = PlatformSettings.shared
    let notifications = PocketNotificationCenter()
    let store = ScheduleSnapshotStore.shared
    private let defaults = UserDefaults.standard

    /// Set by the app shell; taps that arrive before it exists are held here.
    var openLink: ((PocketDeepLink) -> Void)? {
        didSet { flushPendingLink() }
    }

    private var pendingLink: PocketDeepLink?

    /// The session flag persisted by `AppModel`; background launches have no model.
    private var isSignedIn: Bool { defaults.string(forKey: "session") == "demo" }

    // MARK: Launch

    /// Must run before the app finishes launching (BGTaskScheduler requirement).
    func didFinishLaunching() {
        UNUserNotificationCenter.current().delegate = self
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
        let shared = settings.shared(at: deviceNow)
        let paused = PocketPlatform.shared.isBackgroundPaused(nowMillis: deviceNow.epochMillis, settings: shared)
        let scheduleNow = PlatformClock.scheduleNow(deviceNow: deviceNow)
        let json = data.scheduleSnapshotJSON(scheduleNow: scheduleNow, deviceNow: deviceNow)
        if let json {
            try? store.save(json: json)
        } else {
            try? store.clear()
        }
        WidgetCenter.shared.reloadAllTimelines()

        await notifications.syncCourseReminders(snapshotJSON: json, now: deviceNow, settings: shared)
        if !paused {
            await notifications.deliver(data.gradeNotification(at: deviceNow, settings: shared))
            await notifications.checkAnnouncements(data.announcementHeadlines(at: scheduleNow), now: deviceNow, settings: shared)
        }
        let glance = json.flatMap { ScheduleSnapshot.decode(Data($0.utf8)) }?.glance(at: deviceNow)
        await LessonActivityController.sync(glance: glance, enabled: settings.liveActivity)
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
                nowMillis: now.epochMillis, settings: shared
            )
            planned = reminders.first
        case .grade:
            planned = PocketPlatform.shared.demoGradeNotification(nowMillis: now.epochMillis, settings: shared)
        case .announcement:
            let latest = data.announcementHeadlines(at: PlatformClock.scheduleNow(deviceNow: now))
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

    private func flushPendingLink() {
        guard let openLink, let link = pendingLink else { return }
        pendingLink = nil
        openLink(link)
    }

    /// Redeems a notification's one-use capability. An unknown, reused or
    /// revoked token opens the app without navigating anywhere.
    func handleNotificationTap(capability: String?) {
        guard let capability, let link = notifications.capabilities.consume(capability) else { return }
        route(link)
    }
}

extension PocketPlatformCoordinator: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        let capability = response.notification.request.content.userInfo[PocketNotificationRequest.capabilityKey] as? String
        await MainActor.run { PocketPlatformCoordinator.shared.handleNotificationTap(capability: capability) }
    }
}

final class PocketAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        PocketPlatformCoordinator.shared.didFinishLaunching()
        return true
    }
}
