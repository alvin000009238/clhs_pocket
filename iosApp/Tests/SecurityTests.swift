@preconcurrency import PocketShared
import SwiftUI
import XCTest
@testable import CLHSPocket

private final class FakeAuthenticator: DeviceAuthenticating, @unchecked Sendable {
    nonisolated(unsafe) var outcome: AppLockOutcome = .success
    nonisolated(unsafe) var available: AppLockMethod = .faceID
    nonisolated(unsafe) var prompts = 0
    nonisolated(unsafe) var delay: Duration?

    func method() -> AppLockMethod { available }

    func authenticate(reason: String) async -> AppLockOutcome {
        prompts += 1
        if let delay { try? await Task.sleep(for: delay) }
        return outcome
    }
}

private final class MemorySecureStore: NSObject, SecureStore, @unchecked Sendable {
    nonisolated(unsafe) var values: [String: String] = [:]
    nonisolated(unsafe) var failing = false

    func read(key: String) -> StoreResult {
        if failing { return .companion.failure(message: "locked") }
        return values[key].map { .companion.value(text: $0, modifiedAtEpochMillis: 0) } ?? .companion.ok()
    }

    func write(key: String, value: String) -> StoreResult {
        if failing { return .companion.failure(message: "locked") }
        values[key] = value
        return .companion.ok()
    }

    func delete(key: String) -> StoreResult {
        values[key] = nil
        return .companion.ok()
    }
}

final class AppLockSettingTests: XCTestCase {
    private let suite = "AppLockSettingTests"
    private var defaults: UserDefaults!

    override func setUp() {
        defaults = UserDefaults(suiteName: suite)
        defaults.removePersistentDomain(forName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
    }

    func testLockStaysOnWhenThePreferencesAreClearedOrOverridden() {
        let secure = MemorySecureStore()
        let setting = AppLockSetting(defaults: defaults, secure: secure)
        XCTAssertFalse(setting.isEnabled)
        XCTAssertTrue(setting.set(true))
        XCTAssertTrue(setting.isEnabled)

        defaults.removePersistentDomain(forName: suite)
        XCTAssertTrue(setting.isEnabled, "clearing the preferences must not switch the lock off")
        defaults.set(false, forKey: AppLockSetting.defaultsKey)
        XCTAssertTrue(setting.isEnabled, "a preference override must not switch the lock off")

        XCTAssertTrue(setting.set(false))
        XCTAssertFalse(setting.isEnabled)
    }

    func testUnreadableKeychainCountsAsLockedAndFailedWriteKeepsTheOldValue() {
        let secure = MemorySecureStore()
        let setting = AppLockSetting(defaults: defaults, secure: secure)
        XCTAssertTrue(setting.set(true))
        secure.failing = true
        XCTAssertTrue(setting.isEnabled)
        XCTAssertFalse(setting.set(false), "the lock cannot be switched off without updating the Keychain")
        secure.failing = false
        XCTAssertTrue(setting.isEnabled)
    }

    func testLaunchArgumentOverridesCanBeIgnored() {
        let standard = UserDefaults.standard
        let saved = standard.volatileDomain(forName: UserDefaults.argumentDomain)
        defer { standard.setVolatileDomain(saved, forName: UserDefaults.argumentDomain) }
        standard.setVolatileDomain(["appLockEnabled": "NO", "lockAuth": "succeed"], forName: UserDefaults.argumentDomain)
        XCTAssertEqual(standard.string(forKey: "lockAuth"), "succeed")

        PocketLaunch.ignoreLaunchArguments(in: standard)
        XCTAssertNil(standard.string(forKey: "lockAuth"))
        XCTAssertNil(standard.volatileDomain(forName: UserDefaults.argumentDomain)["appLockEnabled"])
    }
}

@MainActor
final class AppLockControllerTests: XCTestCase {
    /// A notification tap and the automatic prompt can ask at the same moment.
    func testConcurrentUnlockCallersShareOnePromptAndItsResult() async {
        let authenticator = FakeAuthenticator()
        authenticator.delay = .milliseconds(100)
        let lock = AppLockController(isEnabled: true, authenticator: authenticator)
        let gate = AppLockRouteGate(lock: lock)

        async let first: Void = lock.sceneBecameActive()
        async let second = gate.pass()
        let passed = await second
        await first
        XCTAssertEqual(authenticator.prompts, 1)
        XCTAssertTrue(passed, "the second caller must see the result of the prompt that was already showing")
        XCTAssertFalse(lock.isLocked)
    }

    /// The system prompt itself makes the scene inactive and then active again.
    func testCancelledPromptIsNotRepeatedWhenTheSceneBecomesActiveAgain() async {
        let authenticator = FakeAuthenticator()
        authenticator.outcome = .cancelled
        let lock = AppLockController(isEnabled: true, authenticator: authenticator)

        await lock.sceneBecameActive()
        lock.scenePhaseChanged(to: .inactive)
        lock.scenePhaseChanged(to: .active)
        await lock.sceneBecameActive()
        XCTAssertEqual(authenticator.prompts, 1, "a cancelled prompt must leave the lock screen up, not ask again")
        XCTAssertTrue(lock.isLocked)

        authenticator.outcome = .failed
        await lock.unlock()
        await lock.sceneBecameActive()
        XCTAssertEqual(authenticator.prompts, 2, "only the lock screen's button asks again")
        XCTAssertNotNil(lock.message)

        lock.scenePhaseChanged(to: .background)
        authenticator.outcome = .success
        await lock.sceneBecameActive()
        XCTAssertEqual(authenticator.prompts, 3, "returning from the background asks once more")
        XCTAssertFalse(lock.isLocked)
    }

    func testEnabledLockStartsLockedAndOpensOnlyOnSuccessfulAuthentication() async {
        let authenticator = FakeAuthenticator()
        let lock = AppLockController(isEnabled: true, authenticator: authenticator)
        XCTAssertTrue(lock.isLocked)

        authenticator.outcome = .failed
        await lock.unlock()
        XCTAssertTrue(lock.isLocked)
        XCTAssertEqual(lock.message, "驗證失敗，請再試一次")

        authenticator.outcome = .cancelled
        await lock.unlock()
        XCTAssertTrue(lock.isLocked)
        XCTAssertNil(lock.message)

        authenticator.outcome = .unavailable
        await lock.unlock()
        XCTAssertTrue(lock.isLocked, "a device without a passcode must not open the lock")
        XCTAssertNotNil(lock.message)

        authenticator.outcome = .success
        await lock.unlock()
        XCTAssertFalse(lock.isLocked)
        XCTAssertNil(lock.message)
        await lock.unlock()
        XCTAssertEqual(authenticator.prompts, 4, "an unlocked app does not prompt again")
    }

    func testLocksAgainInTheBackgroundButNotWhenMerelyInactive() async {
        let lock = AppLockController(isEnabled: true, authenticator: FakeAuthenticator())
        await lock.unlock()
        lock.scenePhaseChanged(to: .inactive)
        XCTAssertFalse(lock.isLocked)
        lock.scenePhaseChanged(to: .background)
        XCTAssertTrue(lock.isLocked)
        lock.scenePhaseChanged(to: .active)
        XCTAssertTrue(lock.isLocked, "returning to the foreground alone never unlocks")
    }

    func testDisabledLockNeverLocksAndChangingTheSettingRequiresAuthentication() async {
        let authenticator = FakeAuthenticator()
        let lock = AppLockController(isEnabled: false, authenticator: authenticator)
        lock.scenePhaseChanged(to: .background)
        lock.lockNow()
        XCTAssertFalse(lock.isLocked)

        authenticator.outcome = .failed
        let refused = await lock.setEnabled(true)
        XCTAssertFalse(refused)
        XCTAssertFalse(lock.isEnabled)
        authenticator.outcome = .success
        let enabled = await lock.setEnabled(true)
        XCTAssertTrue(enabled)
        XCTAssertTrue(lock.isEnabled)
        XCTAssertFalse(lock.isLocked)
        lock.lockNow()
        XCTAssertTrue(lock.isLocked)

        await lock.unlock()
        authenticator.outcome = .cancelled
        let kept = await lock.setEnabled(false)
        XCTAssertFalse(kept)
        XCTAssertTrue(lock.isEnabled, "the lock cannot be switched off without authenticating")
    }
}

final class SchoolWebPolicyTests: XCTestCase {
    func testOnlyHttpsSchoolHostOnTheDefaultPortIsTrusted() {
        let trusted = [
            "https://shcloud2.k12ea.gov.tw/CLHSTYC/Auth/Auth/CloudLogin",
            "https://SHCLOUD2.k12ea.gov.tw:443/CLHSTYC/ICampus/Home/Index2",
        ]
        let untrusted = [
            "http://shcloud2.k12ea.gov.tw/CLHSTYC/Auth/Auth/CloudLogin",
            "https://shcloud2.k12ea.gov.tw:8443/CLHSTYC/",
            "https://shcloud2.k12ea.gov.tw.evil.example/CLHSTYC/Auth/Auth/CloudLogin",
            "https://evil.example/shcloud2.k12ea.gov.tw/CLHSTYC/Auth/Auth/CloudLogin",
            "https://user:pass@shcloud2.k12ea.gov.tw/CLHSTYC/",
            "https://sub.shcloud2.k12ea.gov.tw/",
            "javascript:alert(1)", "file:///etc/hosts", "about:blank",
        ]
        for url in trusted { XCTAssertTrue(SchoolWebPolicy.isTrusted(URL(string: url)), url) }
        for url in untrusted { XCTAssertFalse(SchoolWebPolicy.isTrusted(URL(string: url)), url) }
        XCTAssertFalse(SchoolWebPolicy.isTrusted(nil))

        XCTAssertTrue(SchoolWebPolicy.isLoginPage(SchoolWebPolicy.loginURL))
        XCTAssertFalse(SchoolWebPolicy.isLoginPage(SchoolWebPolicy.homeURL))
        XCTAssertFalse(SchoolWebPolicy.isLoginPage(URL(string: "https://evil.example/CLHSTYC/Auth/Auth/CloudLogin")))
    }

    func testTopLevelNavigationStaysOnTheSchoolSiteAndFramesStayOnHttps() {
        let other = URL(string: "https://example.org/page")
        XCTAssertTrue(SchoolWebPolicy.allowsNavigation(to: SchoolWebPolicy.homeURL, isMainFrame: true))
        XCTAssertFalse(SchoolWebPolicy.allowsNavigation(to: other, isMainFrame: true))
        XCTAssertFalse(SchoolWebPolicy.allowsNavigation(to: URL(string: "about:blank"), isMainFrame: true))
        XCTAssertTrue(SchoolWebPolicy.allowsNavigation(to: other, isMainFrame: false))
        XCTAssertFalse(SchoolWebPolicy.allowsNavigation(to: URL(string: "http://example.org/"), isMainFrame: false))
        XCTAssertFalse(SchoolWebPolicy.allowsNavigation(to: URL(string: "file:///etc/hosts"), isMainFrame: false))
        XCTAssertFalse(SchoolWebPolicy.allowsNavigation(to: nil, isMainFrame: false))
    }

    func testOnlySchoolCookiesAreHandedOverAndTheReportedAccountMustBePlain() throws {
        func cookie(_ name: String, _ domain: String) throws -> HTTPCookie {
            try XCTUnwrap(HTTPCookie(properties: [.name: name, .value: "v-\(name)", .domain: domain, .path: "/"]))
        }
        let cookies = try [
            cookie("session", "shcloud2.k12ea.gov.tw"),
            cookie("parent", ".k12ea.gov.tw"),
            cookie("sibling", "other.k12ea.gov.tw"),
            cookie("tracker", ".example.org"),
            cookie("lookalike", "evilshcloud2.k12ea.gov.tw"),
        ]
        XCTAssertEqual(SchoolWebPolicy.schoolCookies(from: cookies), ["session": "v-session", "parent": "v-parent"])

        XCTAssertEqual(SchoolWebPolicy.studentNumber(fromMessage: " 1140001 "), "1140001")
        for bad: Any in ["", "   ", "a b", "<script>", String(repeating: "1", count: 33), "１２３", 42, ["x"]] {
            XCTAssertNil(SchoolWebPolicy.studentNumber(fromMessage: bad))
        }
        XCTAssertFalse(SchoolWebPolicy.loginHook.lowercased().contains("password"), "the hook must not touch the password")
    }
}
