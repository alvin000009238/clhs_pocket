import SwiftUI

extension View {
    /// The app shell's single hook into widgets, notifications, the Live
    /// Activity, background refresh and deep links.
    func pocketPlatform(_ model: AppModel) -> some View {
        modifier(PocketPlatformHook(model: model))
    }
}

private struct PocketPlatformHook: ViewModifier {
    let model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .onOpenURL { PocketPlatformCoordinator.shared.handle(url: $0) }
            .task {
                PocketPlatformCoordinator.shared.openLink = { [weak model] in model?.open($0) }
            }
            // Runs on launch, on every return to the foreground, on sign-in or sign-out, and when
            // the timetable the snapshot is built from arrives or changes.
            .onChange(of: model.lock.isLocked) { PocketPlatformCoordinator.shared.flushPendingLink() }
            .task(id: RefreshTrigger(
                active: scenePhase == .active, signedIn: model.isSignedIn, schedule: model.live?.scheduleRevision ?? 0,
                locked: model.lock.isLocked, restored: model.live?.restoreSettled ?? true
            )) {
                // Nothing personal is loaded or published while the lock screen is up.
                guard !model.lock.isLocked else { return }
                guard scenePhase == .active else {
                    PocketPlatformCoordinator.shared.scheduleBackgroundRefresh()
                    return
                }
                // Before the stored session has been read, not being signed in is unknown, not a
                // sign-out: clearing now would drop delivered alerts and an active「暫停一日」.
                guard model.isSignedIn || (model.live?.restoreSettled ?? true) else { return }
                await PocketPlatformCoordinator.shared.sessionChanged(signedIn: model.isSignedIn)
            }
    }

    private struct RefreshTrigger: Equatable {
        let active: Bool
        let signedIn: Bool
        let schedule: Int
        let locked: Bool
        let restored: Bool
    }
}
