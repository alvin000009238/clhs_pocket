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
    var transfer: any DataTransferService = DemoDataTransferService()
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

/// Settings backup and local-data removal. Session B owns the real implementation
/// (shared settings transfer format, Keychain and cache cleanup).
@MainActor
protocol DataTransferService {
    func exportSettings(_ values: [String: String]) -> String
    /// Returns the imported values, or `nil` when the file is not a Pocket settings backup.
    func importSettings(_ text: String) -> [String: String]?
    func deleteAllData() async
}

struct DemoDataTransferService: DataTransferService {
    func exportSettings(_ values: [String: String]) -> String {
        let payload: [String: Any] = ["format": "clhs-pocket-settings", "version": 1, "settings": values]
        let data = try? JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    func importSettings(_ text: String) -> [String: String]? {
        guard let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              object["format"] as? String == "clhs-pocket-settings" else { return nil }
        return object["settings"] as? [String: String]
    }

    func deleteAllData() async {}
}

/// Stands in for the system authentication prompt in UI tests. Never used without `-lockAuth`.
final class ScriptedAuthenticator: DeviceAuthenticating, @unchecked Sendable {
    private var cancelNext: Bool

    init(cancelFirst: Bool) { cancelNext = cancelFirst }

    func method() -> AppLockMethod { .faceID }

    func authenticate(reason: String) async -> AppLockOutcome {
        if cancelNext {
            cancelNext = false
            return .cancelled
        }
        return .success
    }
}
