import SwiftUI
import XCTest
@testable import CLHSPocket

private struct StubAuthenticator: DeviceAuthenticating {
    var outcome: AppLockOutcome = .failed
    func method() -> AppLockMethod { .faceID }
    func authenticate(reason: String) async -> AppLockOutcome { outcome }
}

/// Renders the security screens in light and dark and writes PNGs to `SHOT_DIR` for visual review.
/// Skipped when `SHOT_DIR` is not set (pass `TEST_RUNNER_SHOT_DIR=…` to `xcodebuild`).
@MainActor
final class SecurityScreenshotTests: XCTestCase {
    private func capture<Content: View>(_ name: String, settle: Duration = .milliseconds(600), @ViewBuilder _ content: () -> Content) async throws {
        guard let directory = ProcessInfo.processInfo.environment["SHOT_DIR"] else {
            throw XCTSkip("SHOT_DIR is not set")
        }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
        for (suffix, style) in [("light", UIUserInterfaceStyle.light), ("dark", .dark)] {
            let window = UIWindow(windowScene: scene)
            window.overrideUserInterfaceStyle = style
            window.rootViewController = UIHostingController(rootView: content().tint(PocketColor.primary))
            window.windowLevel = .alert + 1
            window.makeKeyAndVisible()
            try await Task.sleep(for: settle)
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
            }
            let data = try XCTUnwrap(image.pngData())
            try data.write(to: URL(fileURLWithPath: directory).appendingPathComponent("\(name)-\(suffix).png"))
            window.isHidden = true
        }
    }

    func testLockScreen() async throws {
        let lock = AppLockController(isEnabled: true, authenticator: StubAuthenticator())
        try await capture("lock-screen") { LockScreenView(lock: lock) }
        await lock.unlock()
        XCTAssertNotNil(lock.message)
        try await capture("lock-screen-failed") { LockScreenView(lock: lock) }
    }

    func testPrivacyCover() async throws {
        try await capture("privacy-cover") { PrivacyCoverView() }
    }

    func testSchoolLoginChrome() async throws {
        // The school system is not contacted here: these are the states around the web view.
        try await capture("login-load-failed") {
            VStack(spacing: 0) {
                SchoolWebHeader(page: SchoolWebPageState(title: "", isLoading: false, progress: 0, loadFailed: true), onClose: {}, onReload: {})
                SchoolWebLoadFailure {}
            }
            .background(PocketColor.background.ignoresSafeArea())
        }
    }
}
