import LocalAuthentication
import Observation
import SwiftUI

/// What the device can use to unlock the app.
enum AppLockMethod: Equatable, Sendable {
    case faceID, touchID, opticID, passcode
    /// No passcode is set, so the device cannot authenticate its owner at all.
    case unavailable

    var title: String {
        switch self {
        case .faceID: "Face ID"
        case .touchID: "Touch ID"
        case .opticID: "Optic ID"
        case .passcode, .unavailable: "裝置密碼"
        }
    }

    var systemImage: String {
        switch self {
        case .faceID: "faceid"
        case .touchID: "touchid"
        case .opticID: "opticid"
        case .passcode, .unavailable: "lock.fill"
        }
    }
}

enum AppLockOutcome: Equatable, Sendable {
    case success
    /// The user dismissed the prompt or the system interrupted it; not an error worth showing.
    case cancelled
    case failed
    case unavailable
}

/// Device-owner authentication, replaceable in tests.
protocol DeviceAuthenticating: Sendable {
    func method() -> AppLockMethod
    func authenticate(reason: String) async -> AppLockOutcome
}

/// Face ID / Touch ID with the device passcode as fallback (`.deviceOwnerAuthentication`).
/// The app has no PIN of its own: the passcode fallback is the device passcode.
struct SystemDeviceAuthenticator: DeviceAuthenticating {
    func method() -> AppLockMethod {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { return .unavailable }
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return .passcode }
        switch context.biometryType {
        case .faceID: return .faceID
        case .touchID: return .touchID
        case .opticID: return .opticID
        default: return .passcode
        }
    }

    func authenticate(reason: String) async -> AppLockOutcome {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return .unavailable }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) ? .success : .failed
        } catch let error as LAError {
            switch error.code {
            case .userCancel, .appCancel, .systemCancel: return .cancelled
            case .passcodeNotSet: return .unavailable
            default: return .failed
            }
        } catch {
            return .failed
        }
    }
}

/// App lock state. Content is hidden whenever `isLocked` is true.
///
/// With the lock enabled the app starts locked and locks again as soon as it enters the background,
/// so nothing is visible when it is reopened or shown in the app switcher. The lock is never released
/// by anything other than a successful device-owner authentication.
@MainActor
@Observable
final class AppLockController {
    private(set) var isEnabled: Bool
    private(set) var isLocked: Bool
    private(set) var isAuthenticating = false
    /// zh-TW message for the lock screen after a failed attempt.
    private(set) var message: String?
    private(set) var method: AppLockMethod

    @ObservationIgnored private let authenticator: DeviceAuthenticating
    /// The system prompt makes the scene inactive and active again, so prompting on every
    /// activation would ask again the moment a prompt is cancelled or fails. The app asks by
    /// itself once per lock (cold start, return from the background); after that the lock
    /// screen's button asks.
    @ObservationIgnored private var promptsOnActivation: Bool

    init(isEnabled: Bool, authenticator: DeviceAuthenticating = SystemDeviceAuthenticator()) {
        self.authenticator = authenticator
        self.isEnabled = isEnabled
        isLocked = isEnabled
        promptsOnActivation = isEnabled
        method = authenticator.method()
    }

    /// Call when the scene becomes active. Prompts at most once for each time the app locked.
    func sceneBecameActive() async {
        guard isLocked, promptsOnActivation else { return }
        promptsOnActivation = false
        await unlock()
    }

    /// Asks for authentication and unlocks only on success. Safe to call repeatedly.
    func unlock() async {
        guard isLocked, !isAuthenticating else { return }
        isAuthenticating = true
        defer { isAuthenticating = false }
        method = authenticator.method()
        switch await authenticator.authenticate(reason: "解鎖壢中 Pocket 以查看你的校園資料") {
        case .success:
            isLocked = false
            message = nil
        case .cancelled:
            message = nil
        case .failed:
            message = "驗證失敗，請再試一次"
        case .unavailable:
            message = "這部裝置尚未設定密碼。請到「設定」設定裝置密碼後再解鎖。"
        }
    }

    /// Turns the lock on or off. Either change requires a successful authentication first, so the
    /// lock cannot be removed by someone who merely holds the unlocked phone.
    /// - Returns: whether the setting changed; persist `isEnabled` only then.
    func setEnabled(_ enabled: Bool) async -> Bool {
        guard enabled != isEnabled, !isAuthenticating else { return false }
        isAuthenticating = true
        defer { isAuthenticating = false }
        method = authenticator.method()
        let reason = enabled ? "啟用 App 鎖定" : "關閉 App 鎖定"
        guard await authenticator.authenticate(reason: reason) == .success else { return false }
        isEnabled = enabled
        isLocked = false
        message = nil
        return true
    }

    func scenePhaseChanged(to phase: ScenePhase) {
        if phase == .background, isEnabled {
            isLocked = true
            promptsOnActivation = true
            message = nil
        }
    }

    /// Locks immediately, for example after logout or when a notification opens protected content.
    func lockNow() {
        guard isEnabled else { return }
        isLocked = true
        promptsOnActivation = true
        message = nil
    }
}
