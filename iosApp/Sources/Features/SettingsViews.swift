import SwiftUI
import UIKit
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
            .pocketScrollEdge()
            .background(PocketColor.background.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// The one notifications page: permission, items, Live Activity and (in demo mode) test alerts.
/// Every control writes `PlatformSettings` and asks the platform coordinator to re-plan.
struct NotificationSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var settings = PlatformSettings.shared
    @State private var permissionDenied = false

    private func binding(_ keyPath: ReferenceWritableKeyPath<PlatformSettings, Bool>) -> Binding<Bool> {
        Binding(
            get: { settings[keyPath: keyPath] },
            set: {
                settings[keyPath: keyPath] = $0
                model.platformSettingsChanged()
            }
        )
    }

    var body: some View {
        SettingsForm(title: "通知") {
            Section {
                Toggle("允許通知", isOn: Binding(
                    get: { settings.notificationsEnabled },
                    set: { wanted in
                        guard wanted else {
                            settings.notificationsEnabled = false
                            permissionDenied = false
                            model.platformSettingsChanged()
                            return
                        }
                        Task { permissionDenied = !(await model.enableNotifications()) }
                    }
                ))
                .accessibilityIdentifier("notifications-master")
                if permissionDenied {
                    Button {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    } label: {
                        Label("通知權限已關閉，前往「設定」開啟", systemImage: "exclamationmark.triangle")
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(PocketColor.warning)
                    }
                }
            } footer: {
                Text("通知只在這台裝置上產生，內容不包含分數與排名。")
            }
            Section("通知項目") {
                Toggle(isOn: binding(\.courseReminders)) { row("上課前提醒", "上課前 5 分鐘") }
                Toggle(isOn: binding(\.gradeUpdates)) { row("成績更新", "不顯示分數與排名") }
                Toggle(isOn: binding(\.announcements)) { row("學校公告", nil) }
            }
            .disabled(!settings.notificationsEnabled)
            Section {
                Toggle(isOn: binding(\.liveActivity)) { row("即時動態", "在鎖定畫面與動態島顯示目前課程") }
                    .accessibilityIdentifier("live-activity-toggle")
                NavigationLink {
                    BackgroundPauseView()
                } label: {
                    LabeledContent("安靜時段") {
                        Text(settings.pauseEnabled ? "\(SettingsFormat.time(settings.pauseStartMinute)) – \(SettingsFormat.time(settings.pauseEndMinute))" : "未啟用")
                            .monospacedDigit()
                    }
                }
            } header: {
                Text("動態與時段")
            } footer: {
                Text("iOS 由系統決定背景更新的時機，提醒與小工具可能在開啟 App 後才更新。")
            }
            if model.dataSource.isDemo, settings.notificationsEnabled {
                Section("示範") {
                    Menu {
                        ForEach(PocketPlatformCoordinator.DemoAlert.allCases) { alert in
                            Button(alert.title) { Task { await PocketPlatformCoordinator.shared.sendDemoAlert(alert) } }
                        }
                    } label: {
                        Label("傳送示範通知", systemImage: "paperplane")
                    }
                    .accessibilityIdentifier("demo-alert-menu")
                }
            }
        }
        .tint(PocketColor.primary)
    }

    private func row(_ title: String, _ detail: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if let detail {
                Text(detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
            }
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
                let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
                let other = keyPath == \AppModel.pauseStart ? model.pauseEnd : model.pauseStart
                // The shared window rejects a zero-length range.
                guard minute != other else { return }
                model[keyPath: keyPath] = minute
                model.platformSettingsChanged()
            }
        )
    }

    var body: some View {
        @Bindable var model = model
        SettingsForm(title: "安靜時段") {
            Section {
                Toggle("啟用安靜時段", isOn: Binding(
                    get: { model.pauseEnabled },
                    set: {
                        model.pauseEnabled = $0
                        model.platformSettingsChanged()
                    }
                ))
                    .accessibilityIdentifier("pause-enabled")
            } footer: {
                Text("時段內不在背景檢查成績與公告；上課提醒與小工具不受影響，開啟 App 時仍會正常更新。")
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
                Text(model.usesLiveData ? "切換後會立即重新取得天氣。中央氣象署需要你自己的授權碼，尚未設定時會沿用上次的資料。" : "示範資料的天氣數值固定；使用真實資料時切換會立即重新取得天氣。")
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
                            let ok = await model.lock.setEnabled(wanted)
                            failed = !ok
                            if ok { model.appLockEnabled = wanted }
                        }
                    }
                ))
                .disabled(model.lock.method == .unavailable)
                .accessibilityIdentifier("app-lock-toggle")
            } footer: {
                Text(
                    model.lock.method == .unavailable ? "這部裝置尚未設定密碼。請先到「設定」設定裝置密碼，才能開啟 App 鎖定。"
                        : failed ? "驗證沒有成功，設定未變更。"
                        : "使用 Face ID、Touch ID 或裝置密碼保護成績與在校紀錄。離開 App 後再回來需要重新驗證。"
                )
            }
        }
    }
}

struct DataSettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var importing = false
    @State private var exporting = false
    @State private var exported: PocketSettingsFile?
    @State private var confirmingDelete = false
    @State private var message: String?

    var body: some View {
        SettingsForm(title: "資料管理") {
            Section {
                Button("匯出設定", systemImage: "square.and.arrow.up") {
                    Task {
                        do {
                            exported = PocketSettingsFile(text: try await model.exportSettings())
                            exporting = true
                        } catch {
                            message = PocketError(error).message
                        }
                    }
                }
                .accessibilityIdentifier("data-export")
                Button("匯入設定", systemImage: "square.and.arrow.down") { importing = true }
                    .accessibilityIdentifier("data-import")
            } header: {
                Text("設定備份")
            } footer: {
                Text("備份與 Android 版通用，包含外觀、首頁配置、通知偏好、安靜時段與登入後的自訂科目名稱，不包含帳號、密碼或成績。")
            }
            if let message {
                Section { Text(message).font(.subheadline).accessibilityIdentifier("data-message") }
            }
            Section {
                Button("刪除這台裝置上的資料", systemImage: "trash", role: .destructive) { confirmingDelete = true }
                    .accessibilityIdentifier("data-delete")
                .confirmationDialog("刪除這台裝置上的所有資料？", isPresented: $confirmingDelete, titleVisibility: .visible) {
                    Button("刪除資料", role: .destructive) {
                        Task {
                            message = await model.deleteAllData()
                                ? "已刪除資料並登出。" : "部分資料未能刪除，請再試一次。"
                        }
                    }
                    .accessibilityIdentifier("data-delete-confirm")
                    Button("取消", role: .cancel) {}
                } message: {
                    Text("這個動作無法復原。")
                }
            } footer: {
                Text("會登出帳號並清除所有設定、收藏與已讀狀態，無法復原。")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            guard case .success(let url) = result else { return }
            guard let file = try? PocketSettingsFile(contentsOf: url) else {
                message = "無法讀取這個檔案。"
                return
            }
            Task {
                do {
                    try await model.importSettings(file.text)
                    message = "已匯入設定。"
                } catch {
                    let failure = PocketError(error)
                    message = failure.kind == .invalidInput ? "這不是壢中 Pocket 的設定備份。" : failure.message
                }
            }
        }
        .fileExporter(
            isPresented: $exporting, document: exported, contentType: .json, defaultFilename: PocketSettingsFile.defaultFilename
        ) { result in
            if case .success = result { message = "已匯出設定。" }
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
                Text(model.live != nil ? "非學校官方 App。登入資訊只保存在這台裝置的鑰匙圈，登出後會一併清除。" : "非學校官方 App。示範模式僅提供虛構資料，不會連線到校務系統，也不會儲存任何帳號資訊。")
            }
        }
    }
}
