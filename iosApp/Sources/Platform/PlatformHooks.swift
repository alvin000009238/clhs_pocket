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
            .task(id: RefreshTrigger(
                active: scenePhase == .active, signedIn: model.isSignedIn, schedule: model.live?.scheduleRevision ?? 0
            )) {
                guard scenePhase == .active else {
                    PocketPlatformCoordinator.shared.scheduleBackgroundRefresh()
                    return
                }
                await PocketPlatformCoordinator.shared.sessionChanged(signedIn: model.isSignedIn)
            }
    }

    private struct RefreshTrigger: Equatable {
        let active: Bool
        let signedIn: Bool
        let schedule: Int
    }
}
