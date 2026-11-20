import Foundation
import PocketShared
@preconcurrency import UserNotifications

/// A local notification in platform-neutral form, so scheduling can be tested
/// without the system notification centre.
struct PocketNotificationRequest: Equatable, Sendable {
    var identifier: String
    var title: String
    var body: String
    /// Nil delivers immediately.
    var fireDate: Date?
    /// One-use capability redeemed on tap; the destination is not in the payload.
    var capability: String
    /// Course reminders carry the "暫停一日" action.
    var isCourseReminder = false

    static let capabilityKey = "capability"
    static let courseCategory = "course-reminder"
    static let pauseDayAction = "pause-day"
}

protocol NotificationScheduling: Sendable {
    func isAuthorized() async -> Bool
    func requestAuthorization() async -> Bool
    func add(_ request: PocketNotificationRequest) async throws
    func pendingIdentifiers() async -> [String]
    /// Pending course reminders with their fire dates, for "暫停一日".
    func pendingCourseReminders() async -> [(identifier: String, fireDate: Date)]
    func removePending(_ identifiers: [String]) async
    func removeAll() async
}

struct SystemNotificationScheduler: NotificationScheduling {
    private var center: UNUserNotificationCenter { .current() }

    func isAuthorized() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }

    func requestAuthorization() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    func add(_ request: PocketNotificationRequest) async throws {
        let content = UNMutableNotificationContent()
        content.title = request.title
        content.body = request.body
        content.sound = .default
        content.threadIdentifier = request.identifier.hasPrefix(ReminderPlanningKt.COURSE_REMINDER_PREFIX) ? "course" : request.identifier
        content.userInfo = [PocketNotificationRequest.capabilityKey: request.capability]
        if request.isCourseReminder { content.categoryIdentifier = PocketNotificationRequest.courseCategory }
        let trigger = request.fireDate.map { date in
            UNTimeIntervalNotificationTrigger(timeInterval: max(1, date.timeIntervalSinceNow), repeats: false)
        }
        try await center.add(UNNotificationRequest(identifier: request.identifier, content: content, trigger: trigger))
    }

    func pendingIdentifiers() async -> [String] {
        await center.pendingNotificationRequests().map(\.identifier)
    }

    func pendingCourseReminders() async -> [(identifier: String, fireDate: Date)] {
        await center.pendingNotificationRequests().compactMap { request in
            guard request.identifier.hasPrefix(ReminderPlanningKt.COURSE_REMINDER_PREFIX),
                  let date = (request.trigger as? UNTimeIntervalNotificationTrigger)?.nextTriggerDate() else { return nil }
            return (request.identifier, date)
        }
    }

    func removePending(_ identifiers: [String]) async {
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    /// Registers the course category so reminders show "暫停一日".
    static func registerCategories() {
        let pause = UNNotificationAction(identifier: PocketNotificationRequest.pauseDayAction, title: "暫停一日", options: [.authenticationRequired])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: PocketNotificationRequest.courseCategory, actions: [pause], intentIdentifiers: []),
        ])
    }

    func removeAll() async {
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }
}

/// One-use tokens that map a notification tap to its destination.
///
/// The notification payload holds only the random token, so a replayed or
/// forged payload cannot navigate anywhere, and sign-out invalidates every
/// outstanding notification at once.
@MainActor
final class NotificationCapabilityStore {
    private static let key = "platform.notificationCapabilities"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private var entries: [String: [String: String]] {
        get { defaults.dictionary(forKey: Self.key) as? [String: [String: String]] ?? [:] }
        set { defaults.set(newValue, forKey: Self.key) }
    }

    /// Issues a token for the notification `identifier`, replacing any earlier one.
    func issue(for identifier: String, link: PocketDeepLink) -> String {
        let token = UUID().uuidString
        var all = entries.filter { $0.value["id"] != identifier }
        all[token] = ["id": identifier, "link": link.rawValue]
        entries = all
        return token
    }

    func consume(_ token: String) -> PocketDeepLink? {
        var all = entries
        guard let entry = all.removeValue(forKey: token) else { return nil }
        entries = all
        return entry["link"].flatMap(PocketDeepLink.init(rawValue:))
    }

    /// Where `token` leads, without redeeming it.
    func link(for token: String) -> PocketDeepLink? {
        entries[token]?["link"].flatMap(PocketDeepLink.init(rawValue:))
    }

    /// Whether `token` is still outstanding, without redeeming it.
    func isValid(_ token: String) -> Bool { entries[token] != nil }

    func revoke(identifiers: [String]) {
        let ids = Set(identifiers)
        entries = entries.filter { !ids.contains($0.value["id"] ?? "") }
    }

    func revokeAll() {
        defaults.removeObject(forKey: Self.key)
    }

    var count: Int { entries.count }
}

/// Turns shared plans into scheduled notifications.
@MainActor
final class PocketNotificationCenter {
    private static let seenAnnouncementsKey = "platform.seenAnnouncementIds"

    let scheduler: NotificationScheduling
    let capabilities: NotificationCapabilityStore
    private let defaults: UserDefaults

    init(
        scheduler: NotificationScheduling = SystemNotificationScheduler(),
        capabilities: NotificationCapabilityStore = NotificationCapabilityStore(),
        defaults: UserDefaults = .standard
    ) {
        self.scheduler = scheduler
        self.capabilities = capabilities
        self.defaults = defaults
    }

    private func request(for planned: PlannedNotification) -> PocketNotificationRequest? {
        guard let url = URL(string: planned.link), let link = PocketDeepLink(url: url) else { return nil }
        return PocketNotificationRequest(
            identifier: planned.identifier,
            title: planned.title,
            body: planned.body,
            fireDate: planned.fireAtMillis.map { Date(epochMillis: $0.int64Value) },
            capability: capabilities.issue(for: planned.identifier, link: link),
            isCourseReminder: planned.identifier.hasPrefix(ReminderPlanningKt.COURSE_REMINDER_PREFIX)
                || planned.identifier == Self.demoCourseIdentifier
        )
    }

    private static let coursePausedUntilKey = "platform.coursePausedUntilMillis"

    /// The demo course alert has its own identifier so it never replaces a real reminder.
    static let demoCourseIdentifier = "demo-course"

    /// End of the "暫停一日" pause, or 0 when course reminders are not paused.
    var coursePausedUntilMillis: Int64 {
        get { Int64(defaults.double(forKey: Self.coursePausedUntilKey)) }
        set { defaults.set(Double(newValue), forKey: Self.coursePausedUntilKey) }
    }

    func isCoursePaused(at date: Date) -> Bool { date.epochMillis < coursePausedUntilMillis }

    /// "暫停一日": silences course reminders until the next school day, as on Android.
    func pauseCourseRemindersForToday(now: Date) async {
        coursePausedUntilMillis = PocketPlatform.shared.coursePauseEndMillis(nowMillis: now.epochMillis)
        let until = Date(epochMillis: coursePausedUntilMillis)
        let paused = await scheduler.pendingCourseReminders().filter { $0.fireDate < until }.map(\.identifier)
        await scheduler.removePending(paused)
        capabilities.revoke(identifiers: paused)
    }

    /// Replaces every pending course reminder with the current plan.
    ///
    /// `isCurrent` is asked again after every suspension point: a sign-out
    /// that lands in the middle must not leave reminders behind.
    func syncCourseReminders(
        snapshotJSON: String?,
        now: Date,
        settings: PlatformNotificationSettings,
        isCurrent: @MainActor () -> Bool = { true }
    ) async {
        let stale = await scheduler.pendingIdentifiers().filter { $0.hasPrefix(ReminderPlanningKt.COURSE_REMINDER_PREFIX) }
        await scheduler.removePending(stale)
        capabilities.revoke(identifiers: stale)
        guard await scheduler.isAuthorized(), isCurrent() else { return }
        let planned = PocketPlatform.shared.courseReminders(
            snapshotJson: snapshotJSON, nowMillis: now.epochMillis, settings: settings,
            pausedUntilMillis: coursePausedUntilMillis
        )
        var added: [String] = []
        var addedBefore: [(id: String, fireMillis: Int64)] = []
        for item in planned {
            guard isCurrent() else { break }
            // "暫停一日" can arrive between two adds; the plan predates it.
            let fireMillis = item.fireAtMillis?.int64Value ?? 0
            guard fireMillis >= coursePausedUntilMillis, let request = request(for: item) else { continue }
            try? await scheduler.add(request)
            added.append(request.identifier)
            addedBefore.append((request.identifier, fireMillis))
        }
        // The last add may have raced a sign-out or a pause; withdraw what no longer belongs.
        let withdrawn = isCurrent()
            ? addedBefore.filter { $0.fireMillis < coursePausedUntilMillis }.map(\.id)
            : added
        if !withdrawn.isEmpty {
            await scheduler.removePending(withdrawn)
            capabilities.revoke(identifiers: withdrawn)
        }
    }

    @discardableResult
    func deliver(_ planned: PlannedNotification?) async -> Bool {
        guard let planned, await scheduler.isAuthorized(), let request = request(for: planned) else { return false }
        return (try? await scheduler.add(request)) != nil
    }

    /// Compares the public announcement list with the stored baseline.
    func checkAnnouncements(_ latest: [AnnouncementHeadline], now: Date, settings: PlatformNotificationSettings) async {
        let seen = defaults.stringArray(forKey: Self.seenAnnouncementsKey)
        let plan = PocketPlatform.shared.announcementPlan(
            seenIds: seen, latest: latest, nowMillis: now.epochMillis, settings: settings
        )
        // The baseline only advances once the alert was actually handed to the system.
        if plan.notification == nil {
            defaults.set(plan.seenIds, forKey: Self.seenAnnouncementsKey)
        } else if await deliver(plan.notification) {
            defaults.set(plan.seenIds, forKey: Self.seenAnnouncementsKey)
        }
    }

    /// Sign-out and account changes: nothing scheduled or delivered may outlive the session.
    func removeEverything() async {
        await scheduler.removeAll()
        capabilities.revokeAll()
        defaults.removeObject(forKey: Self.seenAnnouncementsKey)
        defaults.removeObject(forKey: Self.coursePausedUntilKey)
    }
}
