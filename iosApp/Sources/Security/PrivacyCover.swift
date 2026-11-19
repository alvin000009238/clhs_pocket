import SwiftUI

/// Opaque cover shown while the app is not active, so the app-switcher snapshot and the moment
/// before Face ID completes never show grades, schedules or records.
struct PrivacyCoverView: View {
    var body: some View {
        ZStack {
            PocketColor.background.ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "eye.slash.fill")
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(PocketColor.onPrimaryContainer)
                    .frame(width: 80, height: 80)
                    .background(PocketColor.primaryContainer, in: Circle())
                Text("Pocket")
                    .font(PocketFont.hero)
                    .foregroundStyle(PocketColor.onSurface)
                Text("內容已隱藏")
                    .font(.callout)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("壢中 Pocket")
    }
}

private struct PrivacyProtection: ViewModifier {
    let lock: AppLockController
    /// Cover personal content in the app switcher even when the app lock is off.
    let coversWhenInactive: Bool
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            // Hidden content must not be reachable by VoiceOver or a hardware keyboard either.
            .accessibilityHidden(lock.isLocked)
            .disabled(lock.isLocked)
            .overlay {
                if lock.isLocked {
                    LockScreenView(lock: lock)
                        .transition(.opacity)
                }
            }
            .overlay {
                if scenePhase != .active, coversWhenInactive || lock.isEnabled {
                    PrivacyCoverView()
                }
            }
            .onChange(of: scenePhase, initial: true) { _, phase in
                lock.scenePhaseChanged(to: phase)
                if phase == .active, lock.isLocked {
                    Task { await lock.unlock() }
                }
            }
    }
}

extension View {
    /// Applies the app lock and the app-switcher privacy cover. Attach once, at the root of the window.
    func pocketPrivacyProtected(lock: AppLockController, coversWhenInactive: Bool = true) -> some View {
        modifier(PrivacyProtection(lock: lock, coversWhenInactive: coversWhenInactive))
    }
}
