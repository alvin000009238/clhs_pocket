import PocketShared
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

struct SettingsForm<Content: View>: View {
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
                NavigationLink {
                    AnnouncementReminderView()
                } label: {
                    LabeledContent("公告提醒設定", value: "單位與檢查頻率")
                }
                .accessibilityIdentifier("settings-announcement-reminders")
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
    @State private var sameTimeRejected = false

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
                guard minute != other else {
                    sameTimeRejected = true
                    return
                }
                sameTimeRejected = false
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
                    Text(
                        sameTimeRejected ? "開始與結束不能是同一時間，已保留原本的設定。"
                            : model.pauseEnd <= model.pauseStart ? "結束時間早於開始時間，視為跨到隔天。" : ""
                    )
                    .foregroundStyle(sameTimeRejected ? PocketColor.negative : PocketColor.onSurfaceVariant)
                    .accessibilityIdentifier("pause-message")
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
            if model.weatherSource == "cwa" {
                CwaKeySection()
            }
        }
    }
}

/// The user's own CWA open-data key. It is checked against the API before it is kept in the Keychain
/// and is never shown again once saved.
struct CwaKeySection: View {
    @Environment(AppModel.self) private var model
    @State private var hasKey: Bool?
    @State private var key = ""
    @State private var saving = false
    @State private var message: String?

    var body: some View {
        Section {
            if hasKey == true {
                LabeledContent("授權碼", value: "已設定")
                Button("移除授權碼", role: .destructive) {
                    Task {
                        do {
                            try await model.clearCwaKey()
                            hasKey = false
                            message = "已移除授權碼。"
                        } catch {
                            message = PocketError(error).message
                        }
                    }
                }
                .accessibilityIdentifier("cwa-key-clear")
            } else {
                SecureField("CWA-XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX", text: $key)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .textContentType(.password)
                    .accessibilityLabel("中央氣象署授權碼")
                    .accessibilityIdentifier("cwa-key-field")
                Button {
                    Task { await save() }
                } label: {
                    if saving {
                        HStack { ProgressView(); Text("正在驗證…") }
                    } else {
                        Text("驗證並儲存")
                    }
                }
                .disabled(saving || key.trimmingCharacters(in: .whitespaces).isEmpty)
                .accessibilityIdentifier("cwa-key-save")
            }
            if let message {
                Text(message).font(.footnote).accessibilityIdentifier("cwa-key-message")
            }
        } header: {
            Text("中央氣象署授權碼")
        } footer: {
            Text("到中央氣象署開放資料平臺登入會員後取得。授權碼只保存在這台裝置的鑰匙圈，不會包含在設定備份中。")
        }
        .task { hasKey = await model.hasCwaKey() }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            switch try await model.saveCwaKey(key) {
            case .valid:
                key = ""
                hasKey = true
                message = "授權碼有效，已儲存並重新取得天氣。"
            case .invalidFormat: message = "授權碼格式不正確，請確認後再試一次。"
            case .invalidAuthorization, .apiRejected: message = "中央氣象署不接受這組授權碼。"
            default: message = "目前無法連線到中央氣象署，請稍後再試。"
            }
        } catch {
            message = PocketError(error).message
        }
    }
}

struct AppLockSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
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
        // The user may have set a passcode in Settings while away.
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { model.lock.refreshMethod() }
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
    @State private var pendingImport: PendingImport?
    @State private var settingsDamaged = false

    private static let damagedMessage = "設定檔已損壞，請重設設定後再試一次"

    private func show(_ error: PocketError) {
        message = error.message
        settingsDamaged = error.message == Self.damagedMessage
    }

    var body: some View {
        SettingsForm(title: "資料管理") {
            Section {
                Button("匯出設定", systemImage: "square.and.arrow.up") {
                    Task {
                        do {
                            exported = PocketSettingsFile(text: try await model.exportSettings())
                            exporting = true
                        } catch {
                            show(PocketError(error))
                        }
                    }
                }
                .accessibilityIdentifier("data-export")
                Button("匯入設定", systemImage: "square.and.arrow.down") { importing = true }
                    .accessibilityIdentifier("data-import")
                NavigationLink {
                    SettingsJSONEditorView()
                } label: {
                    Label("進階：JSON 編輯", systemImage: "curlybraces")
                }
                .accessibilityIdentifier("data-json")
            } header: {
                Text("設定備份")
            } footer: {
                Text("備份與 Android 版通用，包含外觀、首頁配置、通知偏好、安靜時段與登入後的自訂科目名稱，不包含帳號、密碼或成績。")
            }
            if let shown = message {
                Section {
                    Text(shown).font(.subheadline).accessibilityIdentifier("data-message")
                    if settingsDamaged {
                        Button("重設設定", systemImage: "arrow.counterclockwise", role: .destructive) {
                            Task {
                                do {
                                    try await model.resetDamagedSettings()
                                    settingsDamaged = false
                                    message = "已重設設定，可以再匯出或匯入。"
                                } catch {
                                    show(PocketError(error))
                                }
                            }
                        }
                        .accessibilityIdentifier("data-reset-settings")
                    }
                }
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
                Text("會登出帳號並清除所有設定、收藏與已讀狀態，無法復原。App 鎖定維持原本的設定，要關閉請到「App 鎖定」並通過驗證。")
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
                    let document = try await model.inspectSettings(file.text)
                    pendingImport = PendingImport(text: file.text, document: document)
                } catch {
                    let failure = PocketError(error)
                    show(failure)
                    if failure.kind == .invalidInput { message = "這不是壢中 Pocket 的設定備份。" }
                }
            }
        }
        .sheet(item: $pendingImport) { pending in
            ImportPreviewView(pending: pending) { includeSubjects in
                pendingImport = nil
                Task {
                    do {
                        try await model.importSettings(pending.text, includeSubjects: includeSubjects)
                        message = "已匯入設定。"
                        settingsDamaged = false
                    } catch {
                        show(PocketError(error))
                    }
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

/// 進階：JSON 編輯, as on Android: the backup as text, checked and previewed exactly like an
/// imported file before anything is replaced.
struct SettingsJSONEditorView: View {
    @Environment(AppModel.self) private var model
    @State private var draft = ""
    @State private var message: String?
    @State private var pendingImport: PendingImport?

    var body: some View {
        SettingsForm(title: "JSON 編輯") {
            Section {
                TextEditor(text: $draft)
                    .font(.footnote.monospaced())
                    .frame(minHeight: 240, maxHeight: 420)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .accessibilityLabel("設定 JSON")
                    .accessibilityIdentifier("json-editor")
            } header: {
                Text("設定 JSON")
            } footer: {
                Text("套用前會先檢查內容並列出將變更的設定。自訂文字可能含個人資訊，請妥善保管。")
            }
            if let message {
                Section {
                    Text(message).font(.subheadline).accessibilityIdentifier("json-message")
                }
            }
            Section {
                Button("套用 JSON", systemImage: "checkmark.circle") { inspect() }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("json-apply")
                Button("複製", systemImage: "doc.on.doc") { UIPasteboard.general.string = draft }
                    .disabled(draft.isEmpty)
                    .accessibilityIdentifier("json-copy")
                Button("重設草稿", systemImage: "arrow.counterclockwise") { Task { await load() } }
                    .accessibilityIdentifier("json-reset")
            }
        }
        .task { await load() }
        .sheet(item: $pendingImport) { pending in
            ImportPreviewView(pending: pending) { includeSubjects in
                pendingImport = nil
                Task {
                    do {
                        try await model.importSettings(pending.text, includeSubjects: includeSubjects)
                        await load()
                        message = "已套用設定。"
                    } catch {
                        message = PocketError(error).message
                    }
                }
            }
        }
    }

    /// The current settings, as an export would write them.
    private func load() async {
        do {
            draft = try await model.exportSettings()
            message = nil
        } catch {
            message = PocketError(error).message
        }
    }

    private func inspect() {
        let text = draft
        Task {
            do {
                pendingImport = PendingImport(text: text, document: try await model.inspectSettings(text))
                message = nil
            } catch {
                let failure = PocketError(error)
                message = failure.kind == .invalidInput ? "這不是壢中 Pocket 的設定備份。" : failure.message
            }
        }
    }
}

struct PendingImport: Identifiable {
    let id = UUID()
    let text: String
    let document: SettingsBackupDocument
}

/// What a backup will change, confirmed before anything is overwritten.
private struct ImportPreviewView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let pending: PendingImport
    let onConfirm: (Bool) -> Void
    @State private var includeSubjects = true

    private var subjectCount: Int? { pending.document.subjects.map(\.count) }
    private var disabledWidgets: Int { pending.document.overview.filter { !$0.enabled }.count }

    var body: some View {
        let settings = pending.document.settings
        NavigationStack {
            Form {
                Section("將套用的設定") {
                    LabeledContent("主題", value: settings.themeMode == .light ? "淺色" : settings.themeMode == .dark ? "深色" : "跟隨系統")
                    LabeledContent("天氣來源", value: settings.weatherSource == .cwa ? "中央氣象署" : "Open-Meteo")
                    LabeledContent("首頁元件", value: disabledWidgets > 0
                        ? "\(pending.document.overview.count) 個（含 \(disabledWidgets) 個停用）"
                        : "\(pending.document.overview.count) 個")
                    LabeledContent("安靜時段", value: settings.backgroundPauseEnabled
                        ? "\(SettingsFormat.time(Int(settings.backgroundPauseStartMinute))) – \(SettingsFormat.time(Int(settings.backgroundPauseEndMinute)))"
                        : "未啟用")
                    LabeledContent("上課前提醒", value: pending.document.remindBeforeClass ? "開啟" : "關閉")
                }
                Section {
                    Toggle("套用自訂科目名稱", isOn: $includeSubjects)
                        .disabled(!canApplySubjects)
                        .accessibilityIdentifier("import-subjects")
                } footer: {
                    Text(subjectFooter)
                }
            }
            .navigationTitle("匯入設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("匯入") { onConfirm(includeSubjects && canApplySubjects) }
                        .accessibilityIdentifier("import-confirm")
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var canApplySubjects: Bool { model.canImportSubjects && subjectCount != nil }

    private var subjectFooter: String {
        guard let subjectCount else { return "這份備份沒有自訂科目名稱，目前帳號的科目名稱不會變更。" }
        guard model.canImportSubjects else { return "備份含 \(subjectCount) 筆自訂科目名稱；登入校務帳號後才能套用。" }
        return "備份含 \(subjectCount) 筆自訂科目名稱，開啟後會取代目前帳號的設定。"
    }
}

/// SIL Open Font License texts for the fonts bundled with the app (DESIGN.md).
struct FontLicensesView: View {
    private static let fonts = [("Huninn 粉圓", "Huninn-OFL"), ("Outfit", "Outfit-OFL")]

    var body: some View {
        SettingsForm(title: "開放原始碼授權") {
            ForEach(Self.fonts, id: \.1) { name, file in
                Section {
                    NavigationLink(name) { FontLicenseText(title: name, resource: file) }
                        .accessibilityIdentifier("license-\(file)")
                } footer: {
                    Text("以 SIL Open Font License 1.1 授權並隨 App 封裝。")
                }
            }
        }
    }
}

private struct FontLicenseText: View {
    let title: String
    let resource: String

    var body: some View {
        ScrollView {
            Text(Self.text(resource))
                .font(.footnote.monospaced())
                .foregroundStyle(PocketColor.onSurface)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .background(PocketColor.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    static func text(_ resource: String) -> String {
        Bundle.main.url(forResource: resource, withExtension: "txt")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "找不到授權文字。"
    }
}

struct AboutView: View {
    @Environment(AppModel.self) private var model
    @State private var versionTaps = 0
    @State private var developerMessage: String?
    @State private var feedbackMessage: String?
    @Environment(\.openURL) private var openURL
    private static let sourceCode = URL(string: "https://github.com/alvin000009238/clhs_pocket")!

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
                Button {
                    tapVersion()
                } label: {
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                        .foregroundStyle(PocketColor.onSurface)
                }
                .accessibilityIdentifier("about-version")
                if let developerMessage {
                    Text(developerMessage).font(.footnote).foregroundStyle(PocketColor.primary)
                        .accessibilityIdentifier("about-developer-message")
                }
                LabeledContent("資料來源", value: model.dataSource.isDemo ? "示範資料" : "校務系統")
                Link(destination: Self.sourceCode) {
                    LabeledContent("原始碼", value: "GitHub").foregroundStyle(PocketColor.onSurface)
                }
                .accessibilityIdentifier("about-source")
                Button {
                    if let url = FeedbackForm.allowedURL(FeedbackForm.target) {
                        openURL(url)
                    } else {
                        feedbackMessage = "暫時無法取得回饋表單，請稍後再試"
                    }
                } label: {
                    LabeledContent("意見回饋", value: "Google 表單").foregroundStyle(PocketColor.onSurface)
                }
                .accessibilityIdentifier("about-feedback")
                if let feedbackMessage {
                    Text(feedbackMessage).font(.footnote).foregroundStyle(PocketColor.negative)
                }
                NavigationLink("開放原始碼授權") { FontLicensesView() }
                    .accessibilityIdentifier("about-licenses")
            } footer: {
                Text(model.live != nil ? "非學校官方 App。登入資訊只保存在這台裝置的鑰匙圈，登出後會一併清除。" : "非學校官方 App。示範模式僅提供虛構資料，不會連線到校務系統，也不會儲存任何帳號資訊。")
            }
        }
    }

    /// Ten taps on the version unlock 開發者選項, matching Android.
    private func tapVersion() {
        guard !model.developerEnabled else {
            developerMessage = "開發者選項已開啟"
            return
        }
        versionTaps += 1
        if versionTaps >= 10 {
            versionTaps = 0
            model.developerEnabled = true
            developerMessage = "已開啟開發者選項"
        } else if versionTaps >= 5 {
            developerMessage = "再點 \(10 - versionTaps) 下即可開啟開發者選項"
        }
    }
}

/// The feedback form Android opens. Android reads it from the Remote Config key
/// `feedback_form_url`; iOS has no Firebase, so it ships the same value and the same check.
enum FeedbackForm {
    static let target = "https://forms.gle/T8tbh8qKk88pJyC98"

    /// Only an https Google Forms link, as on Android (`isAllowedFeedbackFormUrl`).
    static func allowedURL(_ value: String) -> URL? {
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              components.user == nil, components.password == nil,
              components.port == nil || components.port == 443,
              let host = components.host?.lowercased() else { return nil }
        switch host {
        case "forms.gle": guard !components.path.isEmpty, components.path != "/" else { return nil }
        case "docs.google.com": guard components.path.hasPrefix("/forms/") else { return nil }
        default: return nil
        }
        return components.url
    }
}
