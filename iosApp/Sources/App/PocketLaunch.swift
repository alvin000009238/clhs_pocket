import Foundation

/// Launch arguments exist for UI tests, screenshots and previews only. A Release build ignores
/// every one of them, including the `UserDefaults` overrides iOS derives from `-key value`.
enum PocketLaunch {
    /// Call before anything reads `UserDefaults`.
    static func seal() {
        #if !DEBUG
        ignoreLaunchArguments(in: .standard)
        #endif
    }

    /// Removes the overrides iOS builds from the command line, so only stored preferences remain.
    static func ignoreLaunchArguments(in defaults: UserDefaults) {
        // `removeVolatileDomain` leaves the argument domain in place; replacing it empties it.
        defaults.setVolatileDomain([:], forName: UserDefaults.argumentDomain)
    }

    #if DEBUG
    private static var defaults: UserDefaults { .standard }
    /// `-resetLocalState YES`: start from a clean install.
    static var resetsLocalState: Bool { defaults.bool(forKey: "resetLocalState") }
    /// `-dataMode demo`: deterministic fixtures instead of the school and public services.
    static var usesDemoData: Bool { defaults.string(forKey: "dataMode") == "demo" }
    /// `-demoClock 2026-11-18T10:35` pins time.
    static var demoClock: String? { defaults.string(forKey: "demoClock") }
    static var initialTab: String? { defaults.string(forKey: "initialTab") }

    /// `-lockAuth succeed|manual|relock` replaces the system prompt in UI tests; `manual` dismisses
    /// the automatic prompt once so the lock screen stays visible until its button is tapped.
    static func authenticator() -> DeviceAuthenticating {
        switch defaults.string(forKey: "lockAuth") {
        case "succeed": ScriptedAuthenticator(.succeed)
        case "manual": ScriptedAuthenticator(.manual)
        case "relock": ScriptedAuthenticator(.relock)
        default: SystemDeviceAuthenticator()
        }
    }
    #else
    static var resetsLocalState: Bool { false }
    static var usesDemoData: Bool { false }
    static var demoClock: String? { nil }
    static var initialTab: String? { nil }
    static func authenticator() -> DeviceAuthenticating { SystemDeviceAuthenticator() }
    #endif
}
