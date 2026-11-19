import SwiftUI
import PocketShared

/// Native SwiftUI shell. Real school data and authentication are deliberately
/// unavailable until the security and repository adapters have been migrated.
struct PocketRootView: View {
    @State private var showingAbout = false

    private let roots: [(title: String, icon: String)] = [
        (PocketRootCatalog.shared.titleAt(index: 0), "house"),
        (PocketRootCatalog.shared.titleAt(index: 1), "calendar"),
        (PocketRootCatalog.shared.titleAt(index: 2), "chart.bar"),
        (PocketRootCatalog.shared.titleAt(index: 3), "building.2"),
    ]

    var body: some View {
        TabView {
            ForEach(roots.indices, id: \.self) { index in
                NavigationStack {
                    ContentUnavailableView(
                        "功能移植中",
                        systemImage: roots[index].icon,
                        description: Text("目前僅建立原生 iOS 導航基礎，尚未連接校務資料。")
                    )
                    .navigationTitle(roots[index].title)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button {
                                showingAbout = true
                            } label: {
                                Image(systemName: "info.circle")
                            }
                            .accessibilityLabel("關於壢中 Pocket")
                        }
                    }
                }
                .tabItem {
                    Label(roots[index].title, systemImage: roots[index].icon)
                }
            }
        }
        .sheet(isPresented: $showingAbout) {
            NavigationStack {
                Form {
                    LabeledContent("應用程式", value: "壢中 Pocket")
                    Text("這是 KMP 遷移驗證用的原生 SwiftUI 外殼，不是完整 iOS 版本。")
                }
                .navigationTitle("關於")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { showingAbout = false }
                    }
                }
            }
        }
    }
}
