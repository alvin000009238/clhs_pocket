import Foundation
@preconcurrency import PocketShared

/// Whether the app lock is on. The answer is kept in the Keychain as well as in the preferences,
/// so clearing or overriding the preferences cannot switch the lock off while the school session
/// is still stored on the device.
struct AppLockSetting {
    static let secureKey = "app.lock.enabled.v1"
    static let defaultsKey = "appLockEnabled"

    let defaults: UserDefaults
    let secure: SecureStore

    init(defaults: UserDefaults = .standard, secure: SecureStore = KeychainStore()) {
        self.defaults = defaults
        self.secure = secure
    }

    /// On when either copy says so. A Keychain that cannot be read (for example before the first
    /// unlock) counts as on: the lock is only ever skipped on a positive answer.
    var isEnabled: Bool {
        let stored = secure.read(key: Self.secureKey)
        guard stored.succeeded else { return true }
        if let text = stored.text { return text == "1" || defaults.bool(forKey: Self.defaultsKey) }
        return defaults.bool(forKey: Self.defaultsKey)
    }

    /// - Returns: false when the Keychain copy could not be written; the caller keeps the old value.
    @discardableResult
    func set(_ enabled: Bool) -> Bool {
        guard secure.write(key: Self.secureKey, value: enabled ? "1" : "0").succeeded else { return false }
        defaults.set(enabled, forKey: Self.defaultsKey)
        return true
    }
}
