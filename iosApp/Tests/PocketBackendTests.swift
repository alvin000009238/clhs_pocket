import Observation
import Security
import os
import UIKit
import XCTest
@testable import CLHSPocket
@preconcurrency import PocketShared

/// The whole iOS data path against local stubs: URLSession transport, Keychain, protected files and
/// the shared core together.
@MainActor
final class PocketBackendTests: XCTestCase {
    private let service = "com.clhs.pocket.ios.tests.\(UUID().uuidString)"
    private var root: URL!
    private var cleaner: FakeWebDataCleaner!

    private static let tokenPage = "<form><input type=\"hidden\" name=\"__RequestVerificationToken\" value=\"page-token\"></form>"
    private static let calendar = """
    BEGIN:VCALENDAR
    VERSION:2.0
    BEGIN:VEVENT
    UID:demo-1
    DTSTART;VALUE=DATE:20261120
    DTEND;VALUE=DATE:20261121
    SUMMARY:校慶
    END:VEVENT
    END:VCALENDAR
    """.replacingOccurrences(of: "\n", with: "\r\n")

    override func setUp() {
        root = temporaryDirectory()
        cleaner = FakeWebDataCleaner()
        StubURLProtocol.install { request in
            switch request.url.host {
            case "shcloud2.k12ea.gov.tw":
                request.method == "GET" ? StubURLProtocol.text(Self.tokenPage) : StubURLProtocol.text("[]")
            case "calendar.google.com":
                StubURLProtocol.text(Self.calendar, headers: [("Set-Cookie", "public=1; Path=/")])
            default:
                .failure(.notConnectedToInternet)
            }
        }
    }

    override func tearDown() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
        try? FileManager.default.removeItem(at: root)
    }

    private func backend(keychain: KeychainOperations = KeychainOperations()) -> PocketBackend {
        PocketBackend(
            transport: URLSessionTransport(protocolClasses: [StubURLProtocol.self]),
            secureStore: KeychainStore(service: service, operations: keychain),
            fileStore: ProtectedFileStore(root: root),
            webDataCleaner: cleaner
        )
    }

    private func error<T>(_ body: () async throws -> T) async -> PocketError? {
        do {
            _ = try await body()
            return nil
        } catch {
            return PocketError(error)
        }
    }

    func testSchoolCookiesGoOnlyToTheSchoolHostAndTheSessionSurvivesARestart() async throws {
        let backend = backend()
        let session = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        XCTAssertEqual(session.studentNo, "DEMO-001")
        _ = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
        _ = try await backend.call { try await $0.calendar(forceRefresh: true, token: $1) }

        let school = StubURLProtocol.requests.filter { $0.url.host == "shcloud2.k12ea.gov.tw" }
        let publicRequests = StubURLProtocol.requests.filter { $0.url.host != "shcloud2.k12ea.gov.tw" }
        XCTAssertGreaterThanOrEqual(school.count, 2)
        XCTAssertFalse(publicRequests.isEmpty)
        for request in school {
            XCTAssertEqual(request.headers["Cookie"], "ASP.NET_SessionId=web")
        }
        for request in publicRequests {
            XCTAssertNil(request.headers["Cookie"], "public clients never carry school cookies")
            XCTAssertFalse(request.body.contains("page-token"))
        }
        // A cookie set by a public host never reaches the school system.
        _ = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
        XCTAssertEqual(StubURLProtocol.requests.last?.headers["Cookie"], "ASP.NET_SessionId=web")

        let restarted = self.backend()
        let restored = try await restarted.restoreSession()
        XCTAssertEqual(restored?.studentNo, "DEMO-001")
        XCTAssertEqual(restored?.apiToken, "page-token")
    }

    func testLogoutRemovesTheSessionCacheAndWebDataAndRevokesTheOldSession() async throws {
        let backend = backend()
        let session = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        _ = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("private").path))

        try await backend.logout()
        XCTAssertEqual(cleaner.calls, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("private").path))
        XCTAssertNil(KeychainStore(service: service).read(key: "school.session.v1").text)
        let restored = try await self.backend().restoreSession()
        XCTAssertNil(restored)

        let sent = StubURLProtocol.requests.count
        let stale = await error { try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) } }
        XCTAssertEqual(stale?.kind, .cancelled)
        XCTAssertEqual(StubURLProtocol.requests.count, sent, "a revoked session sends nothing")
    }

    func testFailedCleanupIsReportedAndBlocksRestoreUntilItSucceeds() async throws {
        let session = try await backend().login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        XCTAssertFalse(session.authorizationId.isEmpty)

        var broken = KeychainOperations()
        broken.delete = { _ in errSecInteractionNotAllowed }
        cleaner.succeeds = false
        let failing = backend(keychain: broken)
        _ = try await failing.restoreSession()
        let failure = await error { try await failing.logout() }
        XCTAssertEqual(failure?.kind, .cleanupIncomplete)
        XCTAssertEqual(failure?.cleanupFailures, ["SESSION_DELETE_FAILED", "WEB_DATA_DELETE_FAILED"])

        // The credentials are still in the Keychain, yet a restart must not sign the student back in.
        let blocked = await error { try await self.backend(keychain: broken).restoreSession() }
        XCTAssertEqual(blocked?.kind, .storage)

        cleaner.succeeds = true
        let healthy = backend()
        let restored = try await healthy.restoreSession()
        XCTAssertNil(restored)
        XCTAssertNil(KeychainStore(service: service).read(key: "school.session.v1").text)
        _ = try await healthy.login(studentNo: "DEMO-002", cookies: ["ASP.NET_SessionId": "next"])
    }

    func testExpiredSessionIsRemovedFromMemoryKeychainAndCache() async throws {
        let controller = LiveDataController(backend: backend())
        try await controller.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        XCTAssertNotNil(controller.session)
        XCTAssertNotNil(KeychainStore(service: service).read(key: "school.session.v1").text)

        StubURLProtocol.install { _ in StubURLProtocol.text("", status: 401) }
        await controller.refreshPrivate(force: true)
        XCTAssertNil(controller.session, "the school rejected the session")
        // The cleanup is queued behind the request that failed; a logout waits for it.
        try await controller.logout()
        XCTAssertNil(KeychainStore(service: service).read(key: "school.session.v1").text)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("private").path))
        let restored = try await self.backend().restoreSession()
        XCTAssertNil(restored, "an expired session must not come back at the next launch")
    }

    func testOwnTimetableIsRequestedForTheStudentAndRestoredOffline() async throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Taipei")!
        let weekStart = calendar.date(byAdding: .day, value: 1 - calendar.component(.weekday, from: .now), to: .now)!
        let format = DateFormatter()
        format.calendar = calendar
        format.timeZone = calendar.timeZone
        format.dateFormat = "yyyy-MM-dd"
        let start = format.string(from: weekStart)
        let end = format.string(from: calendar.date(byAdding: .day, value: 6, to: weekStart)!)
        let weeks = """
        [{"DisplayText":"第4週 (\(start)~\(end))","Value":"4","Selected":true,
          "Item":{"WeekNo":"4","StartDateDisplay":"\(start)","EndDateDisplay":"\(end)","IsSelected":true}}]
        """
        let timetable = """
        {"payload":{"weeks":[{"items":[
          {"SubjectName":"數學","WeekDay":"1","SectionSeq":"2","TeacherNameDisplay":"範例教師","ClassroomName":"示範教室"}
        ]}]}}
        """
        let controller = LiveDataController(backend: backend())
        try await controller.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        let session = try XCTUnwrap(controller.session)
        let tokenPage = Self.tokenPage
        StubURLProtocol.install { request in
            guard request.url.host == "shcloud2.k12ea.gov.tw" else { return .failure(.notConnectedToInternet) }
            if request.method == "GET" { return StubURLProtocol.text(tokenPage) }
            switch request.url.lastPathComponent {
            case "GetYearTermList": return StubURLProtocol.text(#"[{"Text":"115 上","Value":"115_1"}]"#)
            // The whole school's classes; the first one is not the student's.
            case "GetClassNoList": return StubURLProtocol.text(#"[{"Text":"一年 1 班","Value":"101"},{"Text":"二年 30 班","Value":"230"}]"#)
            case "GetWeekNoList": return StubURLProtocol.text(weeks)
            case "GetTimeTable": return StubURLProtocol.text(timetable)
            default: return StubURLProtocol.text("[]")
            }
        }
        await controller.loadSchedule(classValue: "")

        let posts = StubURLProtocol.requests.filter { $0.url.lastPathComponent == "GetTimeTable" }
        XCTAssertFalse(posts.isEmpty)
        for post in posts {
            XCTAssertTrue(post.body.contains("TimetableType=Student"), post.body)
            XCTAssertTrue(post.body.contains("StudentNo=DEMO-001"), post.body)
            XCTAssertFalse(post.body.contains("ClassNo=101"), "never the first class the school lists")
        }

        // Offline at the next launch: the student's timetable comes back from the cache.
        StubURLProtocol.install { _ in .failure(.notConnectedToInternet) }
        let relaunched = self.backend()
        let restoredSession = try await relaunched.restoreSession()
        let restored = try XCTUnwrap(restoredSession)
        XCTAssertEqual(restored.studentNo, session.studentNo)
        let latest = try await relaunched.call { try await $0.latestSchedule(session: restored, token: $1) }
        XCTAssertEqual(latest?.report.items.first?.subjectName, "數學")
        try await controller.logout()
    }

    func testSubjectOverridesAreStoredInOrderAndRolledBackWhenTheyCannotBeSaved() async throws {
        let controller = LiveDataController(backend: backend())
        try await controller.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        let session = try XCTUnwrap(controller.session)
        func stored() async throws -> [String] {
            try await controller.backend.call { try await $0.subjectOverrides(session: session, token: $1) }.compactMap(\.customSubjectName)
        }
        func json(_ name: String) -> String {
            PocketScheduleStore.shared.overridesJson(models: [ScheduleSubjectOverride(
                originalSubjectName: "數學", customSubjectName: name, customTeacherName: nil, customClassroom: nil
            )])
        }
        controller.setSubjectOverrides(json("一"))
        controller.setSubjectOverrides(json("二"))
        controller.setSubjectOverrides(json("三"))
        await controller.subjectOverridesSettled()
        let inOrder = try await stored()
        XCTAssertEqual(inOrder, ["三"], "the last edit is the one that stays")

        // The account store can no longer be written.
        var isDirectory: ObjCBool = false
        let paths = ([root.path] + (FileManager.default.subpaths(atPath: root.path) ?? []).map { root.appendingPathComponent($0).path })
            .filter { FileManager.default.fileExists(atPath: $0, isDirectory: &isDirectory) && isDirectory.boolValue }
        for path in paths { try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: path) }
        defer { for path in paths { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: path) } }
        let before = controller.subjectOverrides
        controller.setSubjectOverrides(json("四"))
        await controller.subjectOverridesSettled()
        XCTAssertEqual(controller.subjectOverrides, before, "an edit that was not stored is taken back")
        XCTAssertNotNil(controller.lastError)
    }

    func testDeleteAllStopsPublicRequestsStillOnTheWireFromWritingAnythingBack() async throws {
        let list = #"[{"pageNum":0,"totalPages":1},{"newsId":"n1","title":"校慶公告","time":"2026/11/18","attr_name":"一般","name":"教務處","top":"0","content_type":"content"}]"#
        // The first announcement request is held back until the test lets it answer.
        let gate = DispatchSemaphore(value: 0)
        let holding = OSAllocatedUnfairLock(initialState: true)
        let sent = expectation(description: "the announcement request is on the wire")
        sent.assertForOverFulfill = false
        StubURLProtocol.install { request in
            guard request.url.path.hasSuffix("news_query_json.php") else { return .failure(.notConnectedToInternet) }
            if holding.withLock({ $0 }) {
                sent.fulfill()
                _ = gate.wait(timeout: .now() + 5)
            }
            return StubURLProtocol.text(list)
        }
        addTeardownBlock { @MainActor in PocketLive.shared.clearAll() }
        let controller = LiveDataController(backend: backend())
        controller.syncBookmarks(["n1"])
        await controller.bookmarksSettled()

        let refresh = Task { await controller.refreshPublic(weatherSource: .openMeteo) }
        await fulfillment(of: [sent], timeout: 5)
        let deletion = Task { try await controller.deleteAllData() }
        try await Task.sleep(for: .milliseconds(100))
        holding.withLock { $0 = false }
        gate.signal()
        try await deletion.value
        await refresh.value

        let left = (FileManager.default.subpaths(atPath: root.path) ?? []).filter {
            var isDirectory: ObjCBool = false
            return FileManager.default.fileExists(atPath: root.appendingPathComponent($0).path, isDirectory: &isDirectory)
                && !isDirectory.boolValue
        }
        XCTAssertEqual(left, [], "the late answer must not put the cache or the read state back")
        XCTAssertTrue(PocketLive.shared.listedAnnouncements.isEmpty)

        // The bookmark selection went with the data: a later refresh lists n1 without saving it again.
        await controller.refreshPublic(weatherSource: .openMeteo)
        XCTAssertEqual(PocketLive.shared.listedAnnouncements.map(\.id), ["n1"])
        let saved = try await controller.backend.call { try await $0.bookmarkedAnnouncements(token: $1) }
        XCTAssertTrue(saved.isEmpty)
    }

    /// Replaying the onboarding into the demo while signed in, then restarting the app.
    func testDemoDoesNotPublishOverAStoredAccountAfterARestart() async throws {
        let suite = "live-account-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let now = Date()
        func demoShowing(_ controller: LiveDataController) -> LivePlatformData {
            LivePlatformData(live: controller) { false }
        }

        // No account on the device: the demo owns the widget and the reminders.
        let guest = LiveDataController(backend: backend(), defaults: defaults)
        XCTAssertNotEqual(demoShowing(guest).scheduleSnapshot(scheduleNow: now, deviceNow: now), .unknown)

        let signedIn = LiveDataController(backend: backend(), defaults: defaults)
        try await signedIn.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])

        // After a restart nothing is in memory; the session is not restored while the demo shows.
        let restarted = LiveDataController(backend: backend(), defaults: defaults)
        XCTAssertNil(restarted.session)
        let data = demoShowing(restarted)
        XCTAssertEqual(data.scheduleSnapshot(scheduleNow: now, deviceNow: now), .unknown, "the account's snapshot stays")
        XCTAssertNil(data.announcementHeadlines(at: now))
        let alert = await data.gradeNotification(at: now, settings: PlatformSettings.shared.shared(at: now))
        XCTAssertNil(alert)

        // Once the account is gone the demo may publish again, also after another restart.
        try await signedIn.logout()
        let later = LiveDataController(backend: backend(), defaults: defaults)
        XCTAssertNotEqual(demoShowing(later).scheduleSnapshot(scheduleNow: now, deviceNow: now), .unknown)
    }

    /// A launch in the background has no announcement list in memory and no screen to load one.
    func testAnnouncementReminderFetchesTheListItselfOnAColdStart() async throws {
        let list = #"[{"pageNum":0,"totalPages":1},{"newsId":"n1","title":"校慶公告","time":"2026/11/18","attr_name":"一般","name":"教務處","top":"0","content_type":"content"}]"#
        StubURLProtocol.install { request in
            request.url.path.hasSuffix("news_query_json.php") ? StubURLProtocol.text(list) : .failure(.notConnectedToInternet)
        }
        addTeardownBlock { @MainActor in
            PocketLive.shared.clearAll()
            PocketData.shared.useDemo()
        }
        PocketData.shared.useLive()
        let controller = LiveDataController(backend: backend())
        let data = LivePlatformData(live: controller) { true }
        let now = Date()
        XCTAssertNil(data.announcementHeadlines(at: now), "nothing is known before the list is fetched")

        await data.loadAnnouncements()
        XCTAssertEqual(data.announcementHeadlines(at: now)?.map(\.id), ["n1"])
        func listRequests() -> Int { StubURLProtocol.requests.filter { $0.url.path.hasSuffix("news_query_json.php") }.count }
        XCTAssertEqual(listRequests(), 1)

        // A list fetched a moment ago is not fetched again; an old one is.
        await data.loadAnnouncements()
        XCTAssertEqual(listRequests(), 1)
        await controller.loadAnnouncementsIfStale(now: now.addingTimeInterval(16 * 60))
        XCTAssertEqual(listRequests(), 2)

        // While the demo is showing the school's list is left alone.
        let aside = LivePlatformData(live: LiveDataController(backend: backend())) { false }
        await aside.loadAnnouncements()
        XCTAssertEqual(listRequests(), 2)
    }

    func testLoginRightAfterLogoutKeepsTheNewAccountAndNothingOfTheOldOne() async throws {
        let controller = LiveDataController(backend: backend())
        try await controller.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "first"])
        let first = try XCTUnwrap(controller.session)

        // As the app does it: logout is started and not awaited, then the next student signs in.
        let revoked = expectation(description: "logout revokes the previous session")
        withObservationTracking { _ = controller.session } onChange: { revoked.fulfill() }
        let logout = Task { try await controller.logout() }
        await fulfillment(of: [revoked], timeout: 5)
        XCTAssertNil(controller.session)
        try await controller.login(studentNo: "DEMO-002", cookies: ["ASP.NET_SessionId": "second"])
        try await logout.value
        XCTAssertEqual(controller.session?.studentNo, "DEMO-002")
        let restored = try await self.backend().restoreSession()
        XCTAssertEqual(restored?.studentNo, "DEMO-002", "the earlier logout must not delete the new session")

        // Switching without logging out first is a full logout too.
        try await controller.login(studentNo: "DEMO-003", cookies: ["ASP.NET_SessionId": "third"])
        XCTAssertEqual(controller.session?.studentNo, "DEMO-003")

        // A logout asked for while a login is still verifying wins: nothing is left signed in.
        let verifying = expectation(description: "the fourth login is awaiting the school's verification response")
        let gate = DispatchSemaphore(value: 0)
        let holdNextVerification = OSAllocatedUnfairLock(initialState: true)
        let tokenPage = Self.tokenPage
        StubURLProtocol.install { request in
            guard request.url.host == "shcloud2.k12ea.gov.tw" else { return .failure(.notConnectedToInternet) }
            guard request.method == "GET" else { return StubURLProtocol.text("[]") }
            let cookie = request.headers.first { $0.key.caseInsensitiveCompare("Cookie") == .orderedSame }?.value
            if cookie == "ASP.NET_SessionId=fourth", holdNextVerification.withLock({ held in
                defer { held = false }
                return held
            }) {
                verifying.fulfill()
                guard gate.wait(timeout: .now() + 10) == .success else { return .failure(.timedOut) }
            }
            return StubURLProtocol.text(tokenPage)
        }
        let overtaken = Task {
            await self.error { try await controller.login(studentNo: "DEMO-004", cookies: ["ASP.NET_SessionId": "fourth"]) }
        }
        await fulfillment(of: [verifying], timeout: 5)
        // Logout publishes its in-memory revocation before waiting for the queued login to finish.
        withObservationTracking { _ = controller.revision } onChange: { gate.signal() }
        try await controller.logout()
        let interruptedLogin = await overtaken.value
        XCTAssertEqual(interruptedLogin?.kind, .cancelled)
        XCTAssertNil(controller.session)
        let afterLogout = try await self.backend().restoreSession()
        XCTAssertNil(afterLogout)
        try await controller.login(studentNo: "DEMO-003", cookies: ["ASP.NET_SessionId": "third"])
        XCTAssertNotEqual(controller.session?.authorizationId, first.authorizationId)
        XCTAssertNil(controller.subjectOverrides.flatMap { $0 == "[]" ? nil : $0 }, "no overrides carried over")
        try await controller.logout()
    }

    func testSchoolRejectionExpiresTheSessionAndPublicFailuresStaySeparate() async throws {
        let backend = backend()
        let session = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        StubURLProtocol.install { request in
            request.url.host == "shcloud2.k12ea.gov.tw" ? StubURLProtocol.text("", status: 401) : StubURLProtocol.text("", status: 503)
        }
        let expired = await error { try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) } }
        XCTAssertEqual(expired?.kind, .authenticationExpired)
        let publicFailure = await error { try await backend.call { try await $0.calendar(forceRefresh: true, token: $1) } }
        XCTAssertEqual(publicFailure?.kind, .publicData)
    }

    func testCancellingTheSwiftTaskCancelsTheRequest() async throws {
        let backend = backend()
        StubURLProtocol.install { _ in .hang }
        let task = Task { @MainActor in
            await self.error { try await backend.call { try await $0.calendar(forceRefresh: true, token: $1) } }
        }
        while StubURLProtocol.requests.isEmpty {
            try await Task.sleep(for: .milliseconds(10))
        }
        task.cancel()
        let result = await task.value
        XCTAssertEqual(result?.kind, .cancelled)
    }

    func testDeleteAllDataLeavesNothingBehind() async throws {
        let backend = backend()
        let session = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        _ = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
        _ = try await backend.call { try await $0.calendar(forceRefresh: true, token: $1) }
        let settings = try await backend.call { try await $0.loadSettings(token: $1) }
        try await backend.call { try await $0.saveSettings(settings: settings, token: $1) }
        XCTAssertTrue(KeychainStore(service: service).write(key: "weather.cwa-api-key.v1", value: "CWA-KEY").succeeded)

        try await backend.deleteAllData()
        let remaining = (try? FileManager.default.subpathsOfDirectory(atPath: root.path)) ?? []
        XCTAssertEqual(remaining, [])
        XCTAssertNil(KeychainStore(service: service).read(key: "weather.cwa-api-key.v1").text)
        XCTAssertNil(KeychainStore(service: service).read(key: "school.session.v1").text)
    }

    func testProfilePhotoIsStoredForTheAccountAndRemovedByLogout() async throws {
        let backend = backend()
        let session = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        let photo = try XCTUnwrap(ProfilePhoto.prepared(UIGraphicsImageRenderer(size: CGSize(width: 2000, height: 1000)).pngData { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 2000, height: 1000))
        }))
        XCTAssertEqual(UIImage(data: photo)?.size, CGSize(width: 512, height: 256), "a picked photo is scaled down before it is stored")
        try await backend.call { try await $0.saveProfilePhoto(session: session, base64: photo.base64EncodedString(), token: $1) }
        let stored = try await backend.call { try await $0.profilePhoto(session: session, token: $1) }
        XCTAssertEqual(stored.flatMap { Data(base64Encoded: $0) }, photo)
        XCTAssertTrue(FileManager.default.fileExists(atPath: root.appendingPathComponent("private/profile-photo.json").path),
                      "the photo lives in the protected private area")

        try await backend.call { try await $0.clearLocalData(areas: [.gradeCache, .scheduleCache, .publicCache, .webData], token: $1) }
        let kept = try await backend.call { try await $0.profilePhoto(session: session, token: $1) }
        XCTAssertNotNil(kept, "clearing caches keeps the photo and the session")
        XCTAssertEqual(KeychainStore(service: service).read(key: "school.session.v1").succeeded, true)

        try await backend.logout()
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.appendingPathComponent("private/profile-photo.json").path))
        let next = try await backend.login(studentNo: "DEMO-002", cookies: ["ASP.NET_SessionId": "next"])
        let other = try await backend.call { try await $0.profilePhoto(session: next, token: $1) }
        XCTAssertNil(other, "the next account never sees the previous photo")
    }

    func testClearingCachesThatCannotBeDeletedIsReported() async throws {
        let backend = backend()
        _ = try await backend.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "web"])
        cleaner.succeeds = false
        let failure = await error { try await backend.call { try await $0.clearLocalData(areas: [.webData], token: $1) } }
        XCTAssertEqual(failure?.kind, .cleanupIncomplete)
        XCTAssertEqual(failure?.cleanupFailures, ["WEB_DATA_DELETE_FAILED"])
        cleaner.succeeds = true
    }

    func testFeedbackLinkOnlyOpensGoogleForms() {
        XCTAssertNotNil(FeedbackForm.allowedURL(FeedbackForm.target))
        XCTAssertNotNil(FeedbackForm.allowedURL("https://docs.google.com/forms/d/e/example/viewform"))
        for rejected in ["http://forms.gle/AbCdEf", "https://forms.gle/", "https://forms.gle.evil.example/AbCdEf",
                         "https://forms.gle@evil.example/AbCdEf", "https://user@forms.gle/AbCdEf", "https://forms.gle:8443/AbCdEf",
                         "https://docs.google.com/document/d/x", ""] {
            XCTAssertNil(FeedbackForm.allowedURL(rejected), rejected)
        }
    }

    func testSettingsBackupRoundTripsThroughAFileAndRejectsForeignOrOversizedFiles() async throws {
        let backend = backend()
        let exported = try await backend.call { try await $0.exportSettingsJson(session: nil, token: $1) }
        let url = root.appendingPathComponent(PocketSettingsFile.defaultFilename)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        try Data(exported.utf8).write(to: url)

        let file = try PocketSettingsFile(contentsOf: url)
        let document = try await backend.call { try await $0.inspectSettingsJson(text: file.text, token: $1) }
        XCTAssertEqual(document.app, "clhs-pocket")
        XCTAssertNil(document.subjects)
        let changed = file.text.replacingOccurrences(of: "\"themeMode\": \"SYSTEM\"", with: "\"themeMode\": \"DARK\"")
        XCTAssertNotEqual(changed, file.text)
        try await backend.call { try await $0.restoreSettingsJson(text: changed, includeSubjects: false, session: nil, token: $1) }
        let applied = try await backend.call { try await $0.loadSettings(token: $1) }
        XCTAssertEqual(applied.themeMode, .dark)

        let foreign = await error { try await backend.call { try await $0.inspectSettingsJson(text: "{\"app\":\"other\"}", token: $1) } }
        XCTAssertEqual(foreign?.kind, .invalidInput)
        try Data(count: PocketSettingsFile.maximumBytes + 1).write(to: url)
        XCTAssertThrowsError(try PocketSettingsFile(contentsOf: url)) {
            XCTAssertEqual($0 as? PocketSettingsFile.ReadError, .tooLarge)
        }
    }
}
