import PocketShared
import XCTest
@testable import CLHSPocket

private actor FakeScheduler: NotificationScheduling {
    var authorized = true
    var pending: [PocketNotificationRequest] = []
    var removedAll = false

    func setAuthorized(_ value: Bool) { authorized = value }
    func isAuthorized() async -> Bool { authorized }
    func requestAuthorization() async -> Bool { authorized }
    var failsAdd = false
    func setFailsAdd(_ value: Bool) { failsAdd = value }
    func add(_ request: PocketNotificationRequest) async throws {
        if failsAdd { throw CocoaError(.featureUnsupported) }
        pending.removeAll { $0.identifier == request.identifier }
        pending.append(request)
        await afterAdd?()
    }
    /// Runs after each add, standing in for a sign-out that lands mid-refresh.
    var afterAdd: (@Sendable () async -> Void)?
    func setAfterAdd(_ hook: (@Sendable () async -> Void)?) { afterAdd = hook }
    func pendingIdentifiers() async -> [String] { pending.map(\.identifier) }
    func pendingCourseReminders() async -> [(identifier: String, fireDate: Date)] {
        pending.compactMap { request in request.fireDate.map { (request.identifier, $0) } }
    }
    func removePending(_ identifiers: [String]) async { pending.removeAll { identifiers.contains($0.identifier) } }
    func removeAll() async {
        pending = []
        removedAll = true
    }
}

@MainActor
private final class SessionFlag: Sendable {
    var signedIn = true
    func signOut() { signedIn = false }
}

private struct StubAuthenticator: DeviceAuthenticating {
    let outcome: AppLockOutcome
    func method() -> AppLockMethod { .passcode }
    func authenticate(reason: String) async -> AppLockOutcome { outcome }
}

@MainActor
/// Records what a refresh asked the data provider for.
private final class RecordingPlatformData: PlatformDataProviding {
    var snapshot: PlatformSnapshot = .unknown
    var headlines: [AnnouncementHeadline]?
    private(set) var calls: [String] = []

    func prepare() async { calls.append("prepare") }
    func loadAnnouncements() async { calls.append("loadAnnouncements") }
    func scheduleSnapshot(scheduleNow: Date, deviceNow: Date) -> PlatformSnapshot {
        calls.append("snapshot")
        return snapshot
    }
    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]? {
        calls.append("headlines")
        return headlines
    }
    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        calls.append("grades")
        return nil
    }
}

@MainActor
final class PlatformTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "PlatformTests"

    /// Wednesday 2026-11-18 10:35 in Taipei: period 3 of the demo timetable is in progress.
    private let lessonTime = PlatformClock.parseDemoClock("2026-11-18T10:35")!

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    private func settings(enabled: Bool = true, pause: Bool = false) -> PlatformNotificationSettings {
        PlatformNotificationSettings(
            notificationsEnabled: enabled, courseReminders: true, gradeUpdates: true, announcements: true,
            pauseEnabled: pause, pauseStartMinute: 23 * 60, pauseEndMinute: 7 * 60, utcOffsetSeconds: 8 * 3600
        )
    }

    private func demoJSON(at date: Date) throws -> String {
        guard case let .available(json) = DemoPlatformData().scheduleSnapshot(scheduleNow: date, deviceNow: date) else {
            return try XCTUnwrap(nil as String?, "the demo timetable has no snapshot at \(date)")
        }
        return json
    }

    // MARK: Snapshot contract between Kotlin and the widget

    func testOnlyTheDeclaredAppGroupOrItsTeamSuffixedNameIsAccepted() {
        XCTAssertTrue(PocketPlatformID.isRenamed("group.com.clhs.pocket.ios"))
        XCTAssertTrue(PocketPlatformID.isRenamed("group.com.clhs.pocket.ios.ABCDE12345"))
        XCTAssertFalse(PocketPlatformID.isRenamed("group.com.clhs.pocket.iosother"))
        XCTAssertFalse(PocketPlatformID.isRenamed("group.com.example"))
    }

    func testSharedSnapshotDecodesInSwiftAndDrivesTheWidgetTimeline() throws {
        let json = try demoJSON(at: lessonTime)
        let snapshot = try XCTUnwrap(ScheduleSnapshot.decode(Data(json.utf8)))

        let glance = snapshot.glance(at: lessonTime)
        XCTAssertEqual(glance.state, .inClass)
        XCTAssertEqual(glance.current?.period, 3)
        XCTAssertEqual(glance.current?.startText, "10:10")
        XCTAssertEqual(glance.headline, "上課中 · 第 3 節")
        XCTAssertEqual(glance.following.first?.period, 4)

        let dates = snapshot.timelineDates(from: lessonTime)
        XCTAssertEqual(dates.first, lessonTime)
        XCTAssertEqual(dates, dates.sorted())
        // The first reload is the bell at 11:00, not an arbitrary polling interval.
        XCTAssertEqual(dates[1], glance.current?.end)
        XCTAssertEqual(snapshot.glance(at: dates[1]).state, .beforeClass)

        XCTAssertEqual(snapshot.timelineDates(from: lessonTime, limit: 500).last, snapshot.validUntil)
        // Only a timeline cut short has more to load; one that reaches expiry, or an expired
        // snapshot, must not ask WidgetKit to come straight back.
        XCTAssertTrue(snapshot.timelineContinues(from: lessonTime, limit: 2))
        XCTAssertFalse(snapshot.timelineContinues(from: lessonTime, limit: 500))
        XCTAssertEqual(snapshot.timelineDates(from: snapshot.validUntil), [snapshot.validUntil])
        XCTAssertFalse(snapshot.timelineContinues(from: snapshot.validUntil))
        XCTAssertFalse(snapshot.timelineContinues(from: snapshot.validUntil.addingTimeInterval(3600)))
        XCTAssertEqual(snapshot.glance(at: snapshot.validUntil).state, .expired)
        XCTAssertNotEqual(snapshot.glance(at: snapshot.validUntil.addingTimeInterval(-1)).state, .expired)
    }

    func testSnapshotFileHoldsOnlyTimetableFacts() throws {
        let json = try demoJSON(at: lessonTime)
        for forbidden in ["DEMO-000", "token", "cookie", "張三", "李四", "studentNo", "teacher"] {
            XCTAssertFalse(json.contains(forbidden), "snapshot leaked \(forbidden)")
        }
    }

    func testUnknownSchemaIsTreatedAsNoSnapshot() throws {
        let json = try demoJSON(at: lessonTime).replacingOccurrences(of: "\"schemaVersion\":1", with: "\"schemaVersion\":2")
        XCTAssertNil(ScheduleSnapshot.decode(Data(json.utf8)))
        XCTAssertNil(ScheduleSnapshot.decode(Data("{}".utf8)))
    }

    func testStoreRoundTripsAndClearRemovesTheFile() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = ScheduleSnapshotStore(directory: directory)

        XCTAssertNil(store.load())
        try store.save(json: demoJSON(at: lessonTime), account: "a", generation: 1)
        XCTAssertNotNil(store.load())
        XCTAssertNotNil(store.loadAuthorized())
        try store.clear()
        XCTAssertNil(store.loadData())
        XCTAssertNil(store.loadAuthority(), "the authority goes with the snapshot")
        XCTAssertNoThrow(try store.clear())
        XCTAssertThrowsError(try ScheduleSnapshotStore(directory: nil).save(json: "{}", account: "a", generation: 1))
    }

    /// The widget shows lessons only while the app's authority vouches for the very snapshot on disk.
    func testWidgetShowsNoLessonsWhenTheAuthorityIsMissingRevokedOrForAnotherSnapshot() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            try? FileManager.default.removeItem(at: directory)
        }
        let store = ScheduleSnapshotStore(directory: directory)
        let json = try demoJSON(at: lessonTime)
        let snapshotURL = directory.appendingPathComponent(PocketPlatformID.snapshotFile)

        // Missing: a snapshot with no authority beside it.
        try Data(json.utf8).write(to: snapshotURL)
        XCTAssertNotNil(store.load())
        XCTAssertNil(store.loadAuthorized(), "a snapshot without authority is not shown")
        XCTAssertEqual(store.authorizedGlance(at: lessonTime).state, .empty)

        try store.save(json: json, account: "a", generation: 1)
        XCTAssertEqual(store.authorizedGlance(at: lessonTime).state, .inClass)

        // Mismatch: another session's snapshot replaced the one the authority was written for.
        let other = try demoJSON(at: lessonTime.addingTimeInterval(7 * 24 * 3600))
        XCTAssertNotEqual(other, json)
        try Data(other.utf8).write(to: snapshotURL)
        XCTAssertNil(store.loadAuthorized(), "the authority belongs to a different snapshot")
        XCTAssertEqual(store.authorizedGlance(at: lessonTime).state, .empty)

        // An authority naming no sign-in vouches for nothing either.
        let anonymous = ScheduleSnapshotAuthority(account: "", generation: 1, snapshotDigest: ScheduleSnapshotAuthority.digest(Data(other.utf8)))
        try JSONEncoder().encode(anonymous).write(to: directory.appendingPathComponent(PocketPlatformID.snapshotAuthorityFile))
        XCTAssertNil(store.loadAuthorized())

        // Revoked: sign-out marks it before deleting, even where the files cannot be removed.
        try store.save(json: json, account: "a", generation: 1)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.path)
        try store.revoke()
        XCTAssertThrowsError(try store.clear())
        XCTAssertNotNil(store.loadData())
        XCTAssertEqual(store.loadAuthority()?.revoked, true)
        XCTAssertNil(store.loadAuthorized(), "a revoked authority is not shown")
        XCTAssertEqual(store.authorizedGlance(at: lessonTime).state, .empty)
    }

    func testPinnedDemoClockKeepsFlowingAfterTheFirstLaunch() {
        defaults.set("2026-11-18T10:35", forKey: "demoClock")
        let launch = Date(timeIntervalSince1970: 2_000_000_000)
        XCTAssertEqual(PlatformClock.scheduleNow(deviceNow: launch, defaults: defaults), lessonTime)
        XCTAssertEqual(
            PlatformClock.scheduleNow(deviceNow: launch.addingTimeInterval(600), defaults: defaults),
            lessonTime.addingTimeInterval(600)
        )
        defaults.removeObject(forKey: "demoClock")
        XCTAssertEqual(PlatformClock.scheduleNow(deviceNow: launch, defaults: defaults), launch)
    }

    // MARK: Live Activity

    func testLiveActivityFollowsTheLessonAndStopsAfterSchool() throws {
        let snapshot = try XCTUnwrap(ScheduleSnapshot.decode(Data(demoJSON(at: lessonTime).utf8)))
        let inClass = try XCTUnwrap(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: lessonTime)))
        XCTAssertTrue(inClass.isInClass)
        XCTAssertEqual(inClass.period, 3)
        XCTAssertEqual(inClass.nextStartText, "11:10")

        let breakTime = try XCTUnwrap(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: inClass.end)))
        XCTAssertFalse(breakTime.isInClass)
        XCTAssertEqual(breakTime.period, 4)

        let evening = lessonTime.addingTimeInterval(9 * 3600)
        XCTAssertNil(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: evening)))
        XCTAssertNil(LessonActivityAttributes.ContentState(glance: ScheduleGlance(state: .empty, date: evening)))
    }

    /// The countdown can be redrawn after its phase was computed, past the lesson's start or end.
    func testLiveActivityCountdownRangeNeverRunsBackwards() throws {
        let snapshot = try XCTUnwrap(ScheduleSnapshot.decode(Data(demoJSON(at: lessonTime).utf8)))
        let state = try XCTUnwrap(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: lessonTime)))
        let running = try XCTUnwrap(state.countdownRange(.inClass, now: lessonTime))
        XCTAssertEqual(running.upperBound, state.end)
        XCTAssertNil(state.countdownRange(.inClass, now: state.end.addingTimeInterval(1)))
        XCTAssertNil(state.countdownRange(.upcoming, now: state.start.addingTimeInterval(1)))
        XCTAssertNil(state.countdownRange(.upcoming, now: state.start))
        XCTAssertNotNil(state.countdownRange(.upcoming, now: state.start.addingTimeInterval(-60)))
        XCTAssertNil(state.countdownRange(.ended, now: lessonTime))
    }

    func testLiveActivityChangesPhaseOnceWithoutTheApp() throws {
        let snapshot = try XCTUnwrap(ScheduleSnapshot.decode(Data(demoJSON(at: lessonTime).utf8)))
        let running = try XCTUnwrap(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: lessonTime)))
        XCTAssertEqual(running.phase(isStale: false, now: lessonTime), .inClass)
        XCTAssertEqual(running.phase(isStale: true, now: running.end.addingTimeInterval(-1)), .ended)
        XCTAssertEqual(running.phase(isStale: false, now: running.end), .ended)

        let waiting = try XCTUnwrap(LessonActivityAttributes.ContentState(glance: snapshot.glance(at: running.end)))
        XCTAssertEqual(waiting.phase(isStale: false, now: running.end), .upcoming)
        // The stale date is the lesson start, so the system re-render reads "上課中".
        XCTAssertEqual(waiting.phase(isStale: true, now: waiting.start), .inClass)
        XCTAssertEqual(waiting.phase(isStale: true, now: waiting.end), .ended)
    }

    // MARK: Deep links

    func testDeepLinksAcceptOnlyBareKnownDestinations() {
        for link in PocketDeepLink.allCases {
            XCTAssertEqual(PocketDeepLink(url: link.url), link)
        }
        XCTAssertEqual(PocketDeepLink(url: URL(string: "CLHSPocket://Grades/")!), .grades)
        let rejected = [
            "https://grades", "clhspocket://unknown", "clhspocket://grades/113_2_E2",
            "clhspocket://grades?student=1", "clhspocket://user@grades", "clhspocket://grades#x", "clhspocket:grades",
        ]
        for text in rejected {
            XCTAssertNil(PocketDeepLink(url: URL(string: text)!), text)
        }
    }

    func testOpeningALinkSelectsItsTabWithoutSkippingOnboarding() {
        let model = AppModel()
        model.hasCompletedOnboarding = true
        model.showingPersonal = true
        model.open(.announcements)
        XCTAssertEqual(model.selectedTab, .campus)
        XCTAssertEqual(model.paths[.campus], [.announcements])
        XCTAssertFalse(model.showingPersonal)

        model.open(.grades)
        XCTAssertEqual(model.selectedTab, .grades)
        XCTAssertEqual(model.paths[.grades], [])

        model.hasCompletedOnboarding = false
        model.open(.home)
        XCTAssertEqual(model.selectedTab, .grades)
    }

    /// A cold background launch knows nothing about the timetable; that must not erase the widget.
    func testUnknownTimetableKeepsTheStoredSnapshotAndPublishesNoDemoData() throws {
        let json = try demoJSON(at: lessonTime)
        let stored = Data(json.utf8)

        let kept = PlatformSnapshot.unknown.resolved(stored: stored, now: lessonTime)
        XCTAssertEqual(kept.change, .keep)
        XCTAssertEqual(kept.json, json, "reminders keep being planned from the snapshot that is still valid")

        let expired = try XCTUnwrap(ScheduleSnapshot.decode(stored)).validUntil.addingTimeInterval(1)
        let stale = PlatformSnapshot.unknown.resolved(stored: stored, now: expired)
        XCTAssertEqual(stale.change, .keep)
        XCTAssertNil(stale.json)

        XCTAssertEqual(PlatformSnapshot.none.resolved(stored: stored, now: lessonTime).change, .clear)
        XCTAssertEqual(PlatformSnapshot.available(json).resolved(stored: nil, now: lessonTime).change, .save(json))

        // Before the shell installs a provider there is nothing to publish, least of all demo data.
        let fallback = UnavailablePlatformData()
        XCTAssertEqual(fallback.scheduleSnapshot(scheduleNow: lessonTime, deviceNow: lessonTime), .unknown)
        XCTAssertNil(fallback.announcementHeadlines(at: lessonTime))
    }

    /// A background refresh runs with the device unlocked but the app still behind its lock.
    func testRefreshBehindTheAppLockLoadsAndPublishesNothingPersonal() async throws {
        let coordinator = PocketPlatformCoordinator.shared
        let savedStore = coordinator.store, savedData = coordinator.data, savedLocked = coordinator.isLocked
        let wasSignedIn = coordinator.isSignedIn
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = RecordingPlatformData()
        data.snapshot = .available(try demoJSON(at: lessonTime))
        var locked = false
        coordinator.isLocked = { locked }
        coordinator.store = ScheduleSnapshotStore(directory: directory)
        coordinator.data = UnavailablePlatformData()
        await coordinator.sessionChanged(signedIn: true)
        coordinator.data = data
        addTeardownBlock { @MainActor in
            coordinator.isLocked = savedLocked
            coordinator.data = savedData
            await coordinator.sessionChanged(signedIn: wasSignedIn)
            coordinator.store = savedStore
            try? FileManager.default.removeItem(at: directory)
        }

        locked = true
        await coordinator.refresh(deviceNow: lessonTime)
        XCTAssertFalse(data.calls.contains("prepare"), "the stored session is not restored behind the lock")
        XCTAssertFalse(data.calls.contains("snapshot"))
        XCTAssertFalse(data.calls.contains("grades"), "no grade check behind the lock")
        XCTAssertNil(coordinator.store.loadData(), "nothing is published to the widget")
        // The public announcement list is still fetched and checked, by the refresh itself.
        XCTAssertEqual(data.calls, ["loadAnnouncements", "headlines"])

        locked = false
        await coordinator.refresh(deviceNow: lessonTime)
        XCTAssertTrue(data.calls.contains("prepare"))
        XCTAssertNotNil(coordinator.store.loadData(), "the same refresh publishes once the lock is open")
        let unlocked = data.calls.drop { $0 != "prepare" }
        XCTAssertLessThan(
            try XCTUnwrap(unlocked.firstIndex(of: "loadAnnouncements")), try XCTUnwrap(unlocked.lastIndex(of: "headlines")),
            "the list is fetched before it is compared"
        )

        // With no shell installed (a cold background launch) the stored setting decides.
        coordinator.isLocked = nil
        XCTAssertEqual(coordinator.lockIsClosed, AppLockSetting().isEnabled)
    }

    func testDeleteAllEndsThePlatformSessionAndReportsASnapshotThatCouldNotBeDeleted() async throws {
        let coordinator = PocketPlatformCoordinator.shared
        let savedStore = coordinator.store
        let wasSignedIn = coordinator.isSignedIn
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
            try? FileManager.default.removeItem(at: directory)
            coordinator.store = savedStore
        }
        coordinator.store = ScheduleSnapshotStore(directory: directory)
        try coordinator.store.save(json: demoJSON(at: lessonTime), account: "a", generation: 1)
        await coordinator.sessionChanged(signedIn: true)

        // A read-only directory stands for a snapshot file the system refuses to remove.
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: directory.path)
        do {
            try await coordinator.endSessionAndClear()
            XCTFail("a snapshot that is still on disk must be reported")
        } catch {}
        XCTAssertFalse(coordinator.isSignedIn, "the session ends even when the cleanup is incomplete")
        XCTAssertNotNil(coordinator.store.loadData())
        XCTAssertNil(coordinator.store.loadAuthorized(), "the widget stops showing it: revoked before deletion")

        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        try await coordinator.endSessionAndClear()
        XCTAssertNil(coordinator.store.loadData())
        if wasSignedIn { await coordinator.sessionChanged(signedIn: true) }
    }

    /// As on Android, a guest gets the public announcement reminder, and nothing else: no session
    /// restore, widget snapshot or grade check, and no repeated sign-out cleanup.
    func testGuestRefreshChecksOnlyThePublicAnnouncements() async throws {
        let coordinator = PocketPlatformCoordinator.shared
        let settings = coordinator.settings
        let savedStore = coordinator.store, savedData = coordinator.data, savedLocked = coordinator.isLocked
        let wasSignedIn = coordinator.isSignedIn
        let savedSettings = (settings.notificationsEnabled, settings.announcements, settings.announcementUnitIds, settings.announcementLastCheck)
        let seenKey = PocketNotificationCenter.seenAnnouncementsKey
        let savedSeen = UserDefaults.standard.stringArray(forKey: seenKey)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        coordinator.store = ScheduleSnapshotStore(directory: directory)
        coordinator.isLocked = { false }
        coordinator.data = UnavailablePlatformData()
        await coordinator.sessionChanged(signedIn: false)
        let data = RecordingPlatformData()
        data.snapshot = .available(try demoJSON(at: lessonTime))
        data.headlines = [AnnouncementHeadline(id: "1", title: "公告")]
        coordinator.data = data
        settings.notificationsEnabled = true
        settings.announcements = true
        settings.announcementUnitIds = [PocketAnnouncementFeed.shared.ALL_ID]
        settings.announcementLastCheck = nil
        UserDefaults.standard.removeObject(forKey: seenKey)
        addTeardownBlock { @MainActor in
            coordinator.isLocked = savedLocked
            coordinator.data = savedData
            (settings.notificationsEnabled, settings.announcements, settings.announcementUnitIds, settings.announcementLastCheck) = savedSettings
            UserDefaults.standard.set(savedSeen, forKey: seenKey)
            coordinator.notifications.coursePausedUntilMillis = 0
            await coordinator.sessionChanged(signedIn: wasSignedIn)
            coordinator.store = savedStore
            try? FileManager.default.removeItem(at: directory)
        }

        await coordinator.refresh(deviceNow: lessonTime)
        XCTAssertEqual(data.calls, ["loadAnnouncements", "headlines"], "a guest's refresh reads only the public list")
        XCTAssertNil(coordinator.store.loadData(), "no widget snapshot for a guest")
        XCTAssertEqual(settings.announcementLastCheck, lessonTime, "the announcement check ran")
        XCTAssertEqual(UserDefaults.standard.stringArray(forKey: seenKey), ["1"], "the guest's baseline is recorded")

        // Later guest passes check again without repeating the sign-out cleanup.
        coordinator.notifications.coursePausedUntilMillis = 42
        let later = lessonTime.addingTimeInterval(2 * 3600)
        await coordinator.refresh(deviceNow: later)
        XCTAssertEqual(settings.announcementLastCheck, later)
        XCTAssertEqual(coordinator.notifications.coursePausedUntilMillis, 42, "nothing was cleared again")
        XCTAssertFalse(data.calls.contains("prepare") || data.calls.contains("snapshot") || data.calls.contains("grades"))

        // With reminders off, a guest is not checked at all.
        settings.announcements = false
        settings.announcementLastCheck = nil
        let calls = data.calls.count
        await coordinator.refresh(deviceNow: later)
        XCTAssertEqual(data.calls.count, calls)
    }

    // MARK: Notifications

    private func center(_ scheduler: FakeScheduler) -> PocketNotificationCenter {
        PocketNotificationCenter(
            scheduler: scheduler,
            capabilities: NotificationCapabilityStore(defaults: defaults),
            defaults: defaults
        )
    }

    func testCapabilitiesExpireAndAreNotKeptForNotificationsThatWereNeverScheduled() async {
        let store = NotificationCapabilityStore(defaults: defaults)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let fire = start.addingTimeInterval(3600)
        let token = store.issue(for: "a", link: .grades, fireDate: fire, now: start)
        let late = fire.addingTimeInterval(NotificationCapabilityStore.lifetime + 1)
        XCTAssertEqual(store.link(for: token), .grades)
        // A swiped-away notification's token is dropped the next time one is issued...
        _ = store.issue(for: "b", link: .announcements, now: late)
        XCTAssertEqual(store.count, 1)
        // ...and is worthless even before that.
        let stale = store.issue(for: "c", link: .grades, now: start)
        XCTAssertNil(store.consume(stale, now: late))
        store.revokeAll()

        let scheduler = FakeScheduler()
        let center = center(scheduler)
        await scheduler.setFailsAdd(true)
        let delivered = await center.deliver(PlannedNotification(
            identifier: "grade-x", fireAtMillis: nil, title: "t", body: "b", link: "clhspocket://grades"
        ))
        XCTAssertFalse(delivered)
        XCTAssertEqual(center.capabilities.count, 0, "no notification carries the token, so it is not kept")
    }

    func testCourseRemindersAreReplacedNotAccumulated() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let json = try demoJSON(at: lessonTime)

        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime, settings: settings())
        let first = await scheduler.pending
        XCTAssertFalse(first.isEmpty)
        XCTAssertLessThanOrEqual(first.count, 40)
        // 10:35 is past the 10:05 reminder for period 3; the next one is 11:05 for period 4.
        XCTAssertEqual(first.first?.fireDate, lessonTime.addingTimeInterval(30 * 60))
        XCTAssertEqual(first.first?.title, "即將上課 · 第 4 節")
        XCTAssertEqual(center.capabilities.count, first.count)

        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime.addingTimeInterval(3600), settings: settings())
        let second = await scheduler.pending
        XCTAssertEqual(second.count, first.count - 1)
        XCTAssertEqual(center.capabilities.count, second.count)

        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime, settings: settings(enabled: false))
        let disabled = await scheduler.pending
        XCTAssertTrue(disabled.isEmpty)
        XCTAssertEqual(center.capabilities.count, 0)
    }

    func testSignOutDuringSchedulingLeavesNoReminderBehind() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let session = SessionFlag()
        await scheduler.setAfterAdd { await session.signOut() }

        await center.syncCourseReminders(
            snapshotJSON: try demoJSON(at: lessonTime), now: lessonTime, settings: settings(),
            isCurrent: { session.signedIn }
        )
        let pending = await scheduler.pending
        XCTAssertTrue(pending.isEmpty, "the reminder added as the session ended must be withdrawn")
        XCTAssertEqual(center.capabilities.count, 0)
    }

    func testPauseForTheDayRemovesTodaysRemindersAndKeepsTomorrows() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let json = try demoJSON(at: lessonTime)
        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime, settings: settings())
        let before = await scheduler.pending
        XCTAssertTrue(before.allSatisfy(\.isCourseReminder), "course reminders carry the pause action")

        await center.pauseCourseRemindersForToday(now: lessonTime)
        let midnight = PlatformClock.parseDemoClock("2026-11-19T00:00")!
        XCTAssertEqual(Date(epochMillis: center.coursePausedUntilMillis), midnight)
        XCTAssertTrue(center.isCoursePaused(at: lessonTime))
        XCTAssertFalse(center.isCoursePaused(at: midnight))
        var pending = await scheduler.pending
        XCTAssertFalse(pending.isEmpty)
        XCTAssertLessThan(pending.count, before.count)
        XCTAssertTrue(pending.allSatisfy { $0.fireDate! >= midnight })
        XCTAssertEqual(center.capabilities.count, pending.count)

        // A later refresh on the same day must not bring today's reminders back.
        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime.addingTimeInterval(60), settings: settings())
        pending = await scheduler.pending
        XCTAssertTrue(pending.allSatisfy { $0.fireDate! >= midnight })

        await center.removeEverything()
        XCTAssertEqual(center.coursePausedUntilMillis, 0)
    }

    func testPauseDuringSchedulingDoesNotBringTodaysRemindersBack() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let pauseAt = lessonTime
        // The pause lands right after the first reminder of the pre-pause plan is added.
        await scheduler.setAfterAdd { await center.pauseCourseRemindersForToday(now: pauseAt) }

        await center.syncCourseReminders(snapshotJSON: try demoJSON(at: lessonTime), now: lessonTime, settings: settings())
        let midnight = PlatformClock.parseDemoClock("2026-11-19T00:00")!
        let pending = await scheduler.pending
        XCTAssertFalse(pending.isEmpty)
        XCTAssertTrue(pending.allSatisfy { $0.fireDate! >= midnight }, "today's reminders must stay paused")
        XCTAssertEqual(center.capabilities.count, pending.count)
    }

    func testDemoCourseAlertDoesNotReplaceTheRealReminder() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        await center.syncCourseReminders(snapshotJSON: try demoJSON(at: lessonTime), now: lessonTime, settings: settings())
        let before = await scheduler.pending
        let real = try XCTUnwrap(before.first)

        await center.deliver(PlannedNotification(
            identifier: PocketNotificationCenter.demoCourseIdentifier, fireAtMillis: nil,
            title: real.title, body: real.body, link: "clhspocket://schedule"
        ))
        let after = await scheduler.pending
        XCTAssertEqual(after.count, before.count + 1)
        XCTAssertTrue(after.contains(real), "the scheduled 5-minute reminder must survive the demo alert")
        let demo = try XCTUnwrap(after.first { $0.identifier == PocketNotificationCenter.demoCourseIdentifier })
        XCTAssertTrue(demo.isCourseReminder)
    }

    func testNotificationTapKeepsItsCapabilityUntilTheLockOpensAndHoldsTheLinkWhileLocked() async {
        let coordinator = PocketPlatformCoordinator.shared
        let savedGate = coordinator.unlockGate, savedLocked = coordinator.isLocked, savedOpen = coordinator.openLink
        defer {
            coordinator.unlockGate = savedGate
            coordinator.isLocked = savedLocked
            coordinator.openLink = savedOpen
        }
        var opened: [PocketDeepLink] = []
        var locked = true
        coordinator.openLink = { opened.append($0) }
        coordinator.isLocked = { locked }
        let token = coordinator.notifications.capabilities.issue(for: "tap-test", link: .schedule)

        coordinator.unlockGate = { false }
        await coordinator.handleNotificationTap(capability: token)
        XCTAssertTrue(coordinator.notifications.capabilities.isValid(token), "a cancelled prompt must not use the notification up")
        XCTAssertTrue(opened.isEmpty)

        // A widget link that arrives behind the lock screen waits for the unlock.
        coordinator.handle(url: PocketPlatformID.scheduleLink)
        XCTAssertTrue(opened.isEmpty)
        locked = false
        coordinator.flushPendingLink()
        XCTAssertEqual(opened, [.schedule])

        coordinator.unlockGate = { true }
        await coordinator.handleNotificationTap(capability: token)
        XCTAssertEqual(opened, [.schedule, .schedule])
        XCTAssertFalse(coordinator.notifications.capabilities.isValid(token), "the capability is single-use")
    }

    func testPauseActionNeedsALiveCapabilityAndSession() async throws {
        let coordinator = PocketPlatformCoordinator.shared
        let center = coordinator.notifications
        let wasSignedIn = coordinator.isSignedIn
        await coordinator.sessionChanged(signedIn: true)
        center.coursePausedUntilMillis = 0
        let token = center.capabilities.issue(for: "pause-test", link: .schedule)

        await coordinator.pauseCourseRemindersForToday(capability: nil, now: lessonTime)
        await coordinator.pauseCourseRemindersForToday(capability: "forged", now: lessonTime)
        XCTAssertEqual(center.coursePausedUntilMillis, 0, "a missing or forged token must not pause")

        await coordinator.handleNotificationResponse(action: "unknown-action", capability: token)
        XCTAssertEqual(center.coursePausedUntilMillis, 0)
        await coordinator.handleNotificationResponse(action: PocketNotificationRequest.pauseDayAction, capability: token)
        XCTAssertGreaterThan(center.coursePausedUntilMillis, 0)
        XCTAssertEqual(center.capabilities.consume(token), .schedule, "pausing keeps the notification openable")

        // A notification left on screen past its token's lifetime can neither pause nor open.
        center.coursePausedUntilMillis = 0
        let old = center.capabilities.issue(for: "pause-test-3", link: .schedule, now: lessonTime)
        let later = lessonTime.addingTimeInterval(NotificationCapabilityStore.lifetime + 60)
        await coordinator.pauseCourseRemindersForToday(capability: old, now: later)
        XCTAssertEqual(center.coursePausedUntilMillis, 0, "an expired token must not pause")
        XCTAssertFalse(center.capabilities.isValid(old, now: later))
        XCTAssertNil(center.capabilities.link(for: old, now: later))
        XCTAssertTrue(center.capabilities.isValid(old, now: lessonTime))

        // Sign-out revokes the token, so a notification left on screen can no longer pause.
        let stale = center.capabilities.issue(for: "pause-test-2", link: .schedule)
        await coordinator.sessionChanged(signedIn: false)
        try await coordinator.clearSessionData()
        await coordinator.pauseCourseRemindersForToday(capability: stale, now: lessonTime)
        XCTAssertEqual(center.coursePausedUntilMillis, 0)
        await coordinator.sessionChanged(signedIn: wasSignedIn)
    }

    func testQuietWindowDoesNotDropCourseReminders() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let json = try demoJSON(at: lessonTime)
        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime, settings: settings())
        let normal = await scheduler.pending.count
        let allDay = PlatformNotificationSettings(
            notificationsEnabled: true, courseReminders: true, gradeUpdates: true, announcements: true,
            pauseEnabled: true, pauseStartMinute: 0, pauseEndMinute: 1439, utcOffsetSeconds: 8 * 3600
        )
        await center.syncCourseReminders(snapshotJSON: json, now: lessonTime, settings: allDay)
        let quiet = await scheduler.pending.count
        XCTAssertEqual(quiet, normal)
    }

    func testProtectedNotificationTapWaitsForTheUnlockGate() async throws {
        let suiteDefaults = try XCTUnwrap(defaults)
        let coordinator = PocketPlatformCoordinator.shared
        let store = coordinator.notifications.capabilities
        var opened: [PocketDeepLink] = []
        let savedLocked = coordinator.isLocked
        // The lock itself is open here; this test is about the gate a notification tap asks.
        coordinator.isLocked = { false }
        coordinator.openLink = { opened.append($0) }
        defer {
            coordinator.openLink = nil
            coordinator.unlockGate = nil
            coordinator.isLocked = savedLocked
            store.revokeAll()
        }
        _ = suiteDefaults

        coordinator.unlockGate = { false }
        await coordinator.handleNotificationTap(capability: store.issue(for: "g1", link: .grades))
        XCTAssertEqual(opened, [], "a failed unlock must not reveal grades")

        // Public destinations are not gated.
        await coordinator.handleNotificationTap(capability: store.issue(for: "a1", link: .announcements))
        XCTAssertEqual(opened, [.announcements])

        coordinator.unlockGate = { true }
        await coordinator.handleNotificationTap(capability: store.issue(for: "g2", link: .grades))
        XCTAssertEqual(opened, [.announcements, .grades])
    }

    func testAppLockGatePassesOnlyAfterAuthentication() async {
        let off = AppLockRouteGate(lock: AppLockController(isEnabled: false, authenticator: StubAuthenticator(outcome: .failed)))
        let offPassed = await off.pass()
        XCTAssertTrue(offPassed)
        let denied = AppLockRouteGate(lock: AppLockController(isEnabled: true, authenticator: StubAuthenticator(outcome: .failed)))
        let deniedPassed = await denied.pass()
        XCTAssertFalse(deniedPassed)
        let granted = AppLockRouteGate(lock: AppLockController(isEnabled: true, authenticator: StubAuthenticator(outcome: .success)))
        let grantedPassed = await granted.pass()
        XCTAssertTrue(grantedPassed)
    }

    /// The settings screen and the scheduler read one store: switching notifications off in
    /// 個人 › 設定 › 通知 must be what the coordinator plans from.
    func testSettingsScreenTogglesAreThePlatformSettings() {
        let platform = PlatformSettings.shared
        let before = (platform.notificationsEnabled, platform.courseReminders, platform.pauseEnabled)
        defer { (platform.notificationsEnabled, platform.courseReminders, platform.pauseEnabled) = before }
        let model = AppModel()

        platform.notificationsEnabled = true
        XCTAssertTrue(model.notificationsEnabled)
        model.notificationsEnabled = false
        XCTAssertFalse(platform.notificationsEnabled)
        XCTAssertFalse(platform.shared().notificationsEnabled)

        model.notifyLessons = false
        XCTAssertFalse(platform.courseReminders)
        model.pauseEnabled = true
        XCTAssertTrue(platform.pauseEnabled)
    }

    func testResetReturnsEveryPreferenceToItsFirstLaunchValue() {
        let changed = PlatformSettings(defaults: defaults)
        changed.notificationsEnabled = true
        changed.courseReminders = false
        changed.liveActivity = false
        changed.pauseEnabled = true
        changed.pauseStartMinute = 60
        changed.reset()

        let reloaded = PlatformSettings(defaults: defaults)
        XCTAssertFalse(reloaded.notificationsEnabled)
        XCTAssertTrue(reloaded.courseReminders)
        XCTAssertTrue(reloaded.liveActivity)
        XCTAssertFalse(reloaded.pauseEnabled)
        XCTAssertEqual(reloaded.pauseStartMinute, 23 * 60)
    }

    func testNothingIsScheduledWithoutPermission() async throws {
        let scheduler = FakeScheduler()
        await scheduler.setAuthorized(false)
        let center = center(scheduler)
        await center.syncCourseReminders(snapshotJSON: try demoJSON(at: lessonTime), now: lessonTime, settings: settings())
        let delivered = await center.deliver(
            PocketPlatform.shared.demoGradeNotification(nowMillis: lessonTime.epochMillis, settings: settings())
        )
        let pending = await scheduler.pending
        XCTAssertFalse(delivered)
        XCTAssertTrue(pending.isEmpty)
        XCTAssertEqual(center.capabilities.count, 0)
    }

    func testCapabilityOpensItsDestinationExactlyOnce() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let planned = PocketPlatform.shared.demoGradeNotification(nowMillis: lessonTime.epochMillis, settings: settings())
        let delivered = await center.deliver(planned)
        XCTAssertTrue(delivered)
        let sent = await scheduler.pending
        let request = try XCTUnwrap(sent.first)
        XCTAssertFalse(request.body.contains { $0.isNumber }, "grade alerts must not show scores")

        XCTAssertNil(center.capabilities.consume("forged"))
        XCTAssertEqual(center.capabilities.consume(request.capability), .grades)
        XCTAssertNil(center.capabilities.consume(request.capability))
    }

    func testAnnouncementBaselineAdvancesOnlyWhenTheAlertIsDelivered() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        let old = AnnouncementHeadline(id: "a", title: "圖書館閉館時間異動公告")
        let new = AnnouncementHeadline(id: "b", title: "校慶運動會各項競賽報名開始")

        // A check before the list has loaded records nothing, so the list that follows is the
        // baseline and not a burst of "new" announcements.
        await center.checkAnnouncements([], now: lessonTime, settings: settings())
        await center.checkAnnouncements([old], now: lessonTime, settings: settings())
        var pending = await scheduler.pending
        XCTAssertTrue(pending.isEmpty, "the first check only records a baseline")

        await scheduler.setAuthorized(false)
        await center.checkAnnouncements([new, old], now: lessonTime, settings: settings())
        await scheduler.setAuthorized(true)
        await center.checkAnnouncements([new, old], now: lessonTime, settings: settings())
        pending = await scheduler.pending
        XCTAssertEqual(pending.map(\.title), ["1 則新學校公告"])

        await scheduler.removePending(pending.map(\.identifier))
        await center.checkAnnouncements([new, old], now: lessonTime, settings: settings())
        pending = await scheduler.pending
        XCTAssertTrue(pending.isEmpty, "an announced item is not repeated")
    }

    func testSignOutCleanupRevokesEverything() async throws {
        let scheduler = FakeScheduler()
        let center = center(scheduler)
        await center.syncCourseReminders(snapshotJSON: try demoJSON(at: lessonTime), now: lessonTime, settings: settings())
        let scheduled = await scheduler.pending
        let token = try XCTUnwrap(scheduled.first?.capability)
        await center.checkAnnouncements([AnnouncementHeadline(id: "a", title: "公告")], now: lessonTime, settings: settings())

        await center.removeEverything()
        let removedAll = await scheduler.removedAll
        XCTAssertTrue(removedAll)
        XCTAssertNil(center.capabilities.consume(token), "a notification from the old session must not navigate")
        XCTAssertEqual(center.coursePausedUntilMillis, 0)

        // The public announcement baseline is not account data: what is published after the
        // sign-out is still reported by the next check, instead of only becoming a new baseline.
        XCTAssertEqual(defaults.stringArray(forKey: PocketNotificationCenter.seenAnnouncementsKey), ["a"])
        await center.checkAnnouncements(
            [AnnouncementHeadline(id: "b", title: "新公告"), AnnouncementHeadline(id: "a", title: "公告")],
            now: lessonTime.addingTimeInterval(3600), settings: settings()
        )
        let announced = await scheduler.pending
        XCTAssertEqual(announced.count, 1, "the announcement published after the sign-out is reported")
        XCTAssertEqual(Set(defaults.stringArray(forKey: PocketNotificationCenter.seenAnnouncementsKey) ?? []), ["a", "b"])
    }

    func testSettingsPersistAndFollowTheDeviceOffset() {
        let stored = PlatformSettings(defaults: defaults)
        XCTAssertFalse(stored.notificationsEnabled, "notifications are opt-in")
        stored.notificationsEnabled = true
        stored.pauseEnabled = true
        stored.pauseStartMinute = 22 * 60

        let reloaded = PlatformSettings(defaults: defaults)
        let shared = reloaded.shared(at: lessonTime, timeZone: TimeZone(secondsFromGMT: 9 * 3600)!)
        XCTAssertTrue(shared.notificationsEnabled)
        XCTAssertEqual(shared.pauseStartMinute, 22 * 60)
        XCTAssertEqual(shared.utcOffsetSeconds, 9 * 3600)
        // 10:35 in Taipei is 11:35 at UTC+9: outside a 22:00–07:00 window.
        XCTAssertFalse(PocketPlatform.shared.isBackgroundPaused(nowMillis: lessonTime.epochMillis, settings: shared))
        XCTAssertTrue(PocketPlatform.shared.isBackgroundPaused(
            nowMillis: lessonTime.addingTimeInterval(11 * 3600).epochMillis, settings: shared
        ))
    }
}
