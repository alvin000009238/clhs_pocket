import SwiftUI

@main
struct CLHSPocketApp: App {
    @UIApplicationDelegateAdaptor(PocketAppDelegate.self) private var appDelegate
    @State private var model = AppModel()

    init() {
        PocketFont.installNavigationFonts()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .preferredColorScheme(model.appearance.colorScheme)
                .tint(PocketColor.primary)
                .pocketPlatform(model)
        }
    }
}
