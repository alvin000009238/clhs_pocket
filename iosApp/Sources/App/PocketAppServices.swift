import Foundation

/// Seams for capabilities owned by other layers (networking, Keychain,
/// notifications). Milestone 2 ships demo implementations only; the real ones
/// are injected through `PocketAppServices` once they exist.
@MainActor
protocol SchoolAccountService {
    /// Returns nil on success, otherwise a user-facing zh-TW error message.
    func signIn(account: String, password: String) async -> String?
    func signOut() async
}

@MainActor
protocol NotificationPermissionService {
    /// Asks for permission and reports whether notifications are now allowed.
    func requestAuthorization() async -> Bool
}

@MainActor
struct PocketAppServices {
    var account: any SchoolAccountService = DemoSchoolAccountService()
    var notifications: any NotificationPermissionService = DemoNotificationPermissionService()
}

/// Accepts only the published demo credentials and never touches the network.
struct DemoSchoolAccountService: SchoolAccountService {
    static let demoAccount = "DEMO-000"
    static let demoPassword = "demo"

    func signIn(account: String, password: String) async -> String? {
        let account = account.trimmingCharacters(in: .whitespacesAndNewlines)
        if account.isEmpty || password.isEmpty { return "請輸入帳號與密碼。" }
        if account.uppercased() == Self.demoAccount, password == Self.demoPassword { return nil }
        return "此版本尚未連接校務系統，請使用示範帳號登入。"
    }

    func signOut() async {}
}

struct DemoNotificationPermissionService: NotificationPermissionService {
    func requestAuthorization() async -> Bool { true }
}

#if DEBUG
/// Stands in for the system authentication prompt in UI tests. Debug builds only, with `-lockAuth`.
final class ScriptedAuthenticator: DeviceAuthenticating, @unchecked Sendable {
    enum Script {
        /// Every prompt succeeds.
        case succeed
        /// Prompts during launch are cancelled, later ones succeed: the lock screen stays until tapped.
        case manual
        /// Prompts during launch succeed, later ones are cancelled: the app opens, then stays locked
        /// once it has been sent to the background.
        case relock
    }

    private let script: Script
    /// The app asks by itself each time the scene becomes active, which can happen more than
    /// once during launch, so the scripts switch on time rather than on a prompt count.
    private let launchEnds = Date().addingTimeInterval(6)

    init(_ script: Script) { self.script = script }

    func method() -> AppLockMethod { .faceID }

    func authenticate(reason: String) async -> AppLockOutcome {
        let launching = Date() < launchEnds
        switch script {
        case .succeed: return .success
        case .manual: return launching ? .cancelled : .success
        case .relock: return launching ? .success : .cancelled
        }
    }
}
#endif
