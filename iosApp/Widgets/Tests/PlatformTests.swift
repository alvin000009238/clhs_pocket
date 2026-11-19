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
    func add(_ request: PocketNotificationRequest) async throws {
        pending.removeAll { $0.identifier == request.identifier }
        pending.append(request)
    }
    func pendingIdentifiers() async -> [String] { pending.map(\.identifier) }
    func removePending(_ identifiers: [String]) async { pending.removeAll { identifiers.contains($0.identifier) } }
    func removeAll() async {
        pending = []
        removedAll = true
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
        try XCTUnwrap(DemoPlatformData().scheduleSnapshotJSON(scheduleNow: date, deviceNow: date))
    }

    // MARK: Snapshot contract between Kotlin and the widget

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
        try store.save(json: demoJSON(at: lessonTime))
        XCTAssertNotNil(store.load())
        try store.clear()
        XCTAssertNil(store.loadData())
        XCTAssertNoThrow(try store.clear())
        XCTAssertThrowsError(try ScheduleSnapshotStore(directory: nil).save(json: "{}"))
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

    // MARK: Notifications

    private func center(_ scheduler: FakeScheduler) -> PocketNotificationCenter {
        PocketNotificationCenter(
            scheduler: scheduler,
            capabilities: NotificationCapabilityStore(defaults: defaults),
            defaults: defaults
        )
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
        XCTAssertNil(defaults.stringArray(forKey: "platform.seenAnnouncementIds"))
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
