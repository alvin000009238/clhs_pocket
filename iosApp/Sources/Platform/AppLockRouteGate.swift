import Foundation

/// Adapts the app lock to the notification unlock gate without editing
/// `Sources/Security`. The shell installs it once it owns an `AppLockController`:
///
///     PocketPlatformCoordinator.shared.unlockGate = AppLockRouteGate(lock: lock).pass
@MainActor
struct AppLockRouteGate {
    let lock: AppLockController

    /// True when protected content may be shown: the lock is off, already
    /// open, or the user just authenticated.
    func pass() async -> Bool {
        guard lock.isEnabled, lock.isLocked else { return true }
        await lock.unlock()
        return !lock.isLocked
    }
}
