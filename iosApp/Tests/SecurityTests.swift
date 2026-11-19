import SwiftUI
import XCTest
@testable import CLHSPocket

private final class FakeAuthenticator: DeviceAuthenticating, @unchecked Sendable {
    nonisolated(unsafe) var outcome: AppLockOutcome = .success
    nonisolated(unsafe) var available: AppLockMethod = .faceID
    nonisolated(unsafe) var prompts = 0

    func method() -> AppLockMethod { available }

    func authenticate(reason: String) async -> AppLockOutcome {
        prompts += 1
        return outcome
    }
}

@MainActor
final class AppLockControllerTests: XCTestCase {
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
