import SwiftUI
import UniformTypeIdentifiers

enum SettingsFormat {
    static let weatherSources: [(id: String, title: String, detail: String)] = [
        ("open-meteo", "Open-Meteo", "全球模式預報，更新頻率高"),
        ("cwa", "中央氣象署", "臺灣鄉鎮預報"),
    ]

    static func time(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60, minutes % 60)
    }
}

private struct SettingsForm<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        Form { content }
            .scrollContentBackground(.hidden)
            .background(PocketColor.background.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

struct NotificationSettingsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        SettingsForm(title: "通知") {
            Section {
                Toggle("允許通知", isOn: Binding(
                    get: { model.notificationsEnabled },
                    set: { wanted in
                        guard wanted else { model.notificationsEnabled = false; return }
                        Task { model.notificationsEnabled = await model.services.notifications.requestAuthorization() }
                    }
                ))
                .accessibilityIdentifier("notifications-master")
            } footer: {
                Text("通知只在這台裝置上產生。此版本以示範服務代替系統通知權限。")
            }
            Section("通知項目") {
                Toggle("上課提醒", isOn: $model.notifyLessons)
                Toggle("成績更新", isOn: $model.notifyGrades)
                Toggle("重要公告", isOn: $model.notifyAnnouncements)
            }
            .disabled(!model.notificationsEnabled)
        }
    }
}

struct BackgroundPauseView: View {
    @Environment(AppModel.self) private var model

    private func binding(_ keyPath: ReferenceWritableKeyPath<AppModel, Int>) -> Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(bySettingHour: model[keyPath: keyPath] / 60, minute: model[keyPath: keyPath] % 60, second: 0, of: Date()) ?? Date()
            },
            set: {
                let parts = Calendar.current.dateComponents([.hour, .minute], from: $0)
                model[keyPath: keyPath] = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
            }
        )
    }

    var body: some View {
        @Bindable var model = model
        SettingsForm(title: "背景暫停時段") {
            Section {
                Toggle("啟用暫停時段", isOn: $model.pauseEnabled)
                    .accessibilityIdentifier("pause-enabled")
            } footer: {
                Text("時段內不在背景更新成績與公告，也不會發出通知；開啟 App 時仍會正常更新。")
            }
            if model.pauseEnabled {
                Section {
                    DatePicker("開始", selection: binding(\.pauseStart), displayedComponents: .hourAndMinute)
                    DatePicker("結束", selection: binding(\.pauseEnd), displayedComponents: .hourAndMinute)
                } footer: {
                    Text(model.pauseEnd <= model.pauseStart ? "結束時間早於開始時間，視為跨到隔天。" : "")
                }
            }
        }
    }
}

struct WeatherSourceView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        SettingsForm(title: "天氣來源") {
            Section {
                ForEach(SettingsFormat.weatherSources, id: \.id) { source in
                    Button {
                        model.weatherSource = source.id
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(source.title).foregroundStyle(PocketColor.onSurface)
                                Text(source.detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                            }
                            Spacer()
                            if model.weatherSource == source.id {
                                Image(systemName: "checkmark").foregroundStyle(PocketColor.primary).accessibilityHidden(true)
                            }
                        }
                    }
                    .accessibilityAddTraits(model.weatherSource == source.id ? .isSelected : [])
                    .accessibilityIdentifier("weather-source-\(source.id)")
                }
            } footer: {
                Text("示範資料的天氣數值固定，來源會在連上實際服務後生效。")
            }
        }
    }
}

struct AppLockSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var failed = false

    var body: some View {
        SettingsForm(title: "App 鎖定") {
            Section {
                Toggle("開啟 App 時需要驗證", isOn: Binding(
                    get: { model.appLockEnabled },
                    set: { wanted in
                        Task {
                            let ok = await model.services.appLock.authenticate(reason: wanted ? "開啟 App 鎖定" : "關閉 App 鎖定")
                            failed = !ok
                            if ok { model.appLockEnabled = wanted }
                        }
                    }
                ))
                .disabled(!model.services.appLock.isBiometryAvailable)
                .accessibilityIdentifier("app-lock-toggle")
            } footer: {
                Text(failed ? "驗證沒有成功，設定未變更。" : "使用 Face ID、Touch ID 或裝置密碼保護成績與在校紀錄。此版本以示範服務代替系統驗證。")
            }
        }
    }
}

struct DataSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false
    @State private var confirmingDelete = false
    @State private var message: String?

    var body: some View {
        SettingsForm(title: "資料管理") {
            Section {
                ShareLink(
                    item: model.services.transfer.exportSettings(model.exportableSettings),
                    preview: SharePreview("壢中 Pocket 設定備份")
                ) {
                    Label("匯出設定", systemImage: "square.and.arrow.up")
                }
                .accessibilityIdentifier("data-export")
                Button("匯入設定", systemImage: "square.and.arrow.down") { importing = true }
                    .accessibilityIdentifier("data-import")
            } header: {
                Text("設定備份")
            } footer: {
                Text("備份包含外觀、首頁配置、自訂科目與暫停時段，不包含帳號、密碼或成績。")
            }
            if let message {
                Section { Text(message).font(.subheadline).accessibilityIdentifier("data-message") }
            }
            Section {
                Button("刪除這台裝置上的資料", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .accessibilityIdentifier("data-delete")
            } footer: {
                Text("會登出帳號並清除所有設定、收藏與已讀狀態，無法復原。")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let text = try? String(contentsOf: url, encoding: .utf8),
               let values = model.services.transfer.importSettings(text) {
                model.applyImportedSettings(values)
                message = "已匯入設定。"
            } else {
                message = "這不是壢中 Pocket 的設定備份。"
            }
        }
        .confirmationDialog("刪除這台裝置上的所有資料？", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("刪除資料", role: .destructive) {
                Task {
                    await model.deleteAllData()
                    message = "已刪除資料並登出。"
                }
            }
            .accessibilityIdentifier("data-delete-confirm")
            Button("取消", role: .cancel) {}
        } message: {
            Text("這個動作無法復原。")
        }
    }
}

struct AboutView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        SettingsForm(title: "關於") {
            Section {
                VStack(spacing: 8) {
                    Image("PocketLogo").resizable().scaledToFit().frame(width: 64, height: 64).accessibilityHidden(true)
                    Text("壢中 Pocket").font(PocketFont.display(24, relativeTo: .title2))
                    Text("把校園生活放進口袋").font(.subheadline).foregroundStyle(PocketColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            Section {
                LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                LabeledContent("資料來源", value: model.dataSource.isDemo ? "示範資料" : "校務系統")
            } footer: {
                Text("非學校官方 App。此版本僅提供示範資料，不會連線到校務系統，也不會儲存任何帳號資訊。")
            }
        }
    }
}
