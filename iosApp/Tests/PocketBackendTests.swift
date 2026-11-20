import Security
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

    func testLoginRightAfterLogoutKeepsTheNewAccountAndNothingOfTheOldOne() async throws {
        let controller = LiveDataController(backend: backend())
        try await controller.login(studentNo: "DEMO-001", cookies: ["ASP.NET_SessionId": "first"])
        let first = try XCTUnwrap(controller.session)

        // As the app does it: logout is started and not awaited, then the next student signs in.
        let logout = Task { try? await controller.logout() }
        await Task.yield()
        XCTAssertNil(controller.session)
        try await controller.login(studentNo: "DEMO-002", cookies: ["ASP.NET_SessionId": "second"])
        await logout.value
        XCTAssertEqual(controller.session?.studentNo, "DEMO-002")
        let restored = try await self.backend().restoreSession()
        XCTAssertEqual(restored?.studentNo, "DEMO-002", "the earlier logout must not delete the new session")

        // Switching without logging out first is a full logout too.
        try await controller.login(studentNo: "DEMO-003", cookies: ["ASP.NET_SessionId": "third"])
        XCTAssertEqual(controller.session?.studentNo, "DEMO-003")

        // A logout asked for while a login is still verifying wins: nothing is left signed in.
        async let overtaken: Void? = try? controller.login(studentNo: "DEMO-004", cookies: ["ASP.NET_SessionId": "fourth"])
        await Task.yield()
        try await controller.logout()
        _ = await overtaken
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
