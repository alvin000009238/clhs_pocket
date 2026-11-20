import SwiftUI
import UIKit

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

/// A window above everything the app presents. Sheets, full-screen covers and the login web
/// view are separate presentations that an overlay on the root view does not cover, so the lock
/// screen and the privacy cover live here instead.
@MainActor
final class PrivacyShield {
    @Observable
    final class State {
        var showsCover = false
    }

    private let lock: AppLockController
    private let state = State()
    private var window: UIWindow?
    private weak var scene: UIWindowScene?

    init(lock: AppLockController) {
        self.lock = lock
    }

    func attach(to scene: UIWindowScene?) {
        guard let scene, scene !== self.scene else { return }
        self.scene = scene
        window?.isHidden = true
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.backgroundColor = .clear
        let host = UIHostingController(rootView: ShieldView(lock: lock, state: state))
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.isHidden = true
        self.window = window
        apply()
    }

    func update(showsCover: Bool, colorScheme: ColorScheme) {
        state.showsCover = showsCover
        window?.overrideUserInterfaceStyle = colorScheme == .dark ? .dark : .light
        apply()
    }

    /// Whether anything is covering the app right now.
    var isShielding: Bool { lock.isLocked || state.showsCover }

    private func apply() {
        guard let window else { return }
        let shielding = isShielding
        window.isHidden = !shielding
        // Content under the shield must not be reachable by VoiceOver or a hardware keyboard either.
        for other in scene?.windows ?? [] where other !== window {
            other.accessibilityElementsHidden = shielding
            other.isUserInteractionEnabled = !shielding
        }
    }
}

private struct ShieldView: View {
    let lock: AppLockController
    let state: PrivacyShield.State

    var body: some View {
        ZStack {
            if lock.isLocked {
                LockScreenView(lock: lock)
            }
            if state.showsCover {
                PrivacyCoverView()
            }
        }
        .tint(PocketColor.primary)
        .environment(\.locale, Locale(identifier: "zh_Hant_TW"))
    }
}

/// Reports the window scene the root view ends up in.
private struct SceneReader: UIViewRepresentable {
    let onScene: (UIWindowScene?) -> Void

    final class Probe: UIView {
        var onScene: ((UIWindowScene?) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let scene = window?.windowScene { onScene?(scene) }
        }
    }

    func makeUIView(context: Context) -> Probe {
        let view = Probe()
        view.isUserInteractionEnabled = false
        view.onScene = onScene
        return view
    }

    func updateUIView(_ view: Probe, context: Context) {
        view.onScene = onScene
    }
}

private struct PrivacyProtection: ViewModifier {
    let lock: AppLockController
    /// Cover personal content in the app switcher even when the app lock is off.
    let coversWhenInactive: Bool
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @State private var shield: PrivacyShield

    init(lock: AppLockController, coversWhenInactive: Bool) {
        self.lock = lock
        self.coversWhenInactive = coversWhenInactive
        _shield = State(initialValue: PrivacyShield(lock: lock))
    }

    private var showsCover: Bool {
        scenePhase != .active && (coversWhenInactive || lock.isEnabled)
    }

    func body(content: Content) -> some View {
        content
            .background { SceneReader { shield.attach(to: $0) }.accessibilityHidden(true) }
            .onChange(of: scenePhase, initial: true) { _, phase in
                lock.scenePhaseChanged(to: phase)
                shield.update(showsCover: showsCover, colorScheme: colorScheme)
                if phase == .active {
                    Task { await lock.sceneBecameActive() }
                }
            }
            .onChange(of: lock.isLocked) { shield.update(showsCover: showsCover, colorScheme: colorScheme) }
            .onChange(of: colorScheme) { shield.update(showsCover: showsCover, colorScheme: colorScheme) }
    }
}

extension View {
    /// Applies the app lock and the app-switcher privacy cover. Attach once, at the root of the window.
    func pocketPrivacyProtected(lock: AppLockController, coversWhenInactive: Bool = true) -> some View {
        modifier(PrivacyProtection(lock: lock, coversWhenInactive: coversWhenInactive))
    }
}
