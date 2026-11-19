import SwiftUI

@main
struct CLHSPocketApp: App {
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
        }
    }
}
