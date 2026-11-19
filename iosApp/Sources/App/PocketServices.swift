import Foundation

/// Seams for capabilities owned by other layers (networking, Keychain,
/// notifications). Milestone 2 ships demo implementations only; the real ones
/// are injected through `PocketServices` once they exist.
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
protocol AppLockService {
    var isBiometryAvailable: Bool { get }
    func authenticate(reason: String) async -> Bool
}

@MainActor
struct PocketServices {
    var account: any SchoolAccountService = DemoSchoolAccountService()
    var notifications: any NotificationPermissionService = DemoNotificationPermissionService()
    var appLock: any AppLockService = DemoAppLockService()
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

struct DemoAppLockService: AppLockService {
    var isBiometryAvailable: Bool { true }
    func authenticate(reason: String) async -> Bool { true }
}
