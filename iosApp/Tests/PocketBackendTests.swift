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
