import PocketShared
import SwiftUI
import UIKit

/// 使用統計: Android's on-device counters, never uploaded.
struct UsageStatisticsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let usage = model.usage
        SettingsForm(title: "使用統計") {
            Section {
                ForEach(UsageMetric.allCases) { metric in
                    LabeledContent(metric.title, value: "\(usage.count(metric)) 次")
                        .monospacedDigit()
                        .accessibilityIdentifier("usage-\(metric.rawValue)")
                }
            } footer: {
                Text("\(usage.startedAt.map { "自 \($0.formatted(.dateTime.year().month(.twoDigits).day(.twoDigits).locale(Locale(identifier: "zh_TW")))) 起" } ?? "尚未開始記錄")\n以下資料只儲存在這台裝置。")
            }
        }
    }
}

/// The storage areas Android's 開發者選項 lists, mapped to where iOS keeps them. Account caches
/// are cleared by the shared core under the account lock, so a clear never races a logout or a
/// login, and anything that could not be removed is reported instead of looking cleared.
enum LocalDataCategory: String, CaseIterable, Identifiable {
    case gradeCache, scheduleCache, settings, session, webData, cache

    var id: String { rawValue }

    var title: String {
        switch self {
        case .gradeCache: "成績快取"
        case .scheduleCache: "課表快照"
        case .settings: "設定資料"
        case .session: "登入資料"
        case .webData: "WebView 資料"
        case .cache: "快取"
        }
    }

    var detail: String {
        switch self {
        case .gradeCache: "成績、模擬器學分、學期與學生資料"
        case .scheduleCache: "已下載的課表"
        case .settings: "外觀、首頁配置與自訂科目"
        case .session: "鑰匙圈中的登入資訊，清除後會登出"
        case .webData: "校務登入網頁的 Cookie 與快取"
        case .cache: "公告、行事曆、天氣等公開資料與系統快取"
        }
    }

    var isClearable: Bool { self != .settings }

    /// The part the shared core clears; the session goes through logout, system caches here.
    var area: LocalDataArea? {
        switch self {
        case .gradeCache: .gradeCache
        case .scheduleCache: .scheduleCache
        case .webData: .webData
        case .cache: .publicCache
        case .settings, .session: nil
        }
    }

    private static var library: URL? { FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first }
    private static var caches: URL? { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first }
    private static var pocketData: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("PocketData", isDirectory: true)
    }

    private var locations: [URL] {
        let privateArea = Self.pocketData?.appendingPathComponent("private", isDirectory: true)
        return switch self {
        case .gradeCache: ["grades", "simulator", "structure.json", "student.json"].compactMap { privateArea?.appendingPathComponent($0) }
        case .scheduleCache: ["schedule", "schedule-latest.json"].compactMap { privateArea?.appendingPathComponent($0) }
        case .settings: [Self.pocketData?.appendingPathComponent("settings", isDirectory: true)].compactMap { $0 }
        case .session: []
        case .webData: [Self.library?.appendingPathComponent("WebKit", isDirectory: true)].compactMap { $0 }
        case .cache: [Self.pocketData?.appendingPathComponent("public", isDirectory: true), Self.caches, FileManager.default.temporaryDirectory].compactMap { $0 }
        }
    }

    func bytes() -> Int64 { locations.reduce(0) { $0 + Self.bytes(at: $1) } }

    private static func bytes(at url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.totalFileAllocatedSizeKey, .isRegularFileKey]
        if let values = try? url.resourceValues(forKeys: keys), values.isRegularFile == true {
            return Int64(values.totalFileAllocatedSize ?? 0)
        }
        guard let files = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return 0 }
        var total: Int64 = 0
        for case let file as URL in files {
            let values = try? file.resourceValues(forKeys: keys)
            if values?.isRegularFile == true { total += Int64(values?.totalFileAllocatedSize ?? 0) }
        }
        return total
    }

    /// Empties the system cache and temporary directories. Throws when anything is left behind.
    static func clearSystemCaches() throws(PocketError) {
        URLCache.shared.removeAllCachedResponses()
        let manager = FileManager.default
        var failed = 0
        for directory in [caches, manager.temporaryDirectory].compactMap({ $0 }) {
            let items: [URL]
            do {
                items = try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            } catch CocoaError.fileReadNoSuchFile {
                continue
            } catch {
                failed += 1
                continue
            }
            for item in items {
                do { try manager.removeItem(at: item) } catch { failed += 1 }
            }
        }
        if failed > 0 { throw PocketError(kind: .storage, message: "有 \(failed) 個快取檔案未能刪除") }
    }

    static func size(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowsNonnumericFormatting = false
        return formatter.string(fromByteCount: bytes)
    }
}

/// 開發者選項: storage, diagnostics, test notifications and onboarding, as on Android.
struct DeveloperSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var sizes: [LocalDataCategory: Int64] = [:]
    @State private var selected: Set<LocalDataCategory> = []
    @State private var freed: Int64?
    @State private var clearError: String?
    @State private var clearing = false
    @State private var confirmingClear = false
    @State private var report: String?

    var body: some View {
        SettingsForm(title: "開發者選項") {
            Section {
                ForEach(LocalDataCategory.allCases) { category in
                    Toggle(isOn: Binding(
                        get: { selected.contains(category) },
                        set: { if $0 { selected.insert(category) } else { selected.remove(category) } }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            LabeledContent(category.title, value: value(for: category))
                            Text(category.detail).font(.footnote).foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                    }
                    .disabled(!category.isClearable || clearing)
                    .accessibilityIdentifier("storage-\(category.rawValue)")
                }
                LabeledContent("合計", value: LocalDataCategory.size(sizes.values.reduce(0, +)))
                    .font(.body.weight(.semibold))
                Button(selected.isEmpty ? "請先選擇" : "清除選取", role: .destructive) { confirmingClear = true }
                    .disabled(selected.isEmpty || clearing)
                    .accessibilityIdentifier("storage-clear")
                    .confirmationDialog("清除選取的資料？", isPresented: $confirmingClear, titleVisibility: .visible) {
                        Button("清除", role: .destructive) { Task { await clearSelected() } }
                            .accessibilityIdentifier("storage-clear-confirm")
                        Button("取消", role: .cancel) {}
                    } message: {
                        Text(selected.contains(.session) ? "包含登入資料，清除後會登出校務帳號。" : "清除後需要時會重新下載。")
                    }
                if let freed {
                    Text("已釋放 \(LocalDataCategory.size(freed))")
                        .foregroundStyle(PocketColor.primary)
                        .accessibilityIdentifier("storage-freed")
                }
                if let clearError {
                    Text(clearError)
                        .foregroundStyle(PocketColor.negative)
                        .accessibilityIdentifier("storage-error")
                }
            } header: {
                Text("本機資料與儲存空間")
            } footer: {
                Text("設定資料只能透過「資料管理」中的刪除資料移除。自訂科目名稱與大頭貼不會被清除快取影響。")
            }

            Section {
                Button("產生錯誤診斷包", systemImage: "stethoscope") { report = diagnosticReport() }
                    .accessibilityIdentifier("developer-diagnostics")
                if let report {
                    Text(report)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .accessibilityIdentifier("developer-report")
                    Button("複製", systemImage: "doc.on.doc") { UIPasteboard.general.string = report }
                    ShareLink(item: report) { Label("分享", systemImage: "square.and.arrow.up") }
                }
            } header: {
                Text("錯誤診斷包")
            } footer: {
                Text("不含帳號、密碼、成績或公告內容。")
            }

            Section("測試") {
                Menu {
                    ForEach(PocketPlatformCoordinator.DemoAlert.allCases) { alert in
                        Button(alert.title) { Task { await PocketPlatformCoordinator.shared.sendDemoAlert(alert) } }
                    }
                } label: {
                    Label("傳送測試通知", systemImage: "paperplane")
                }
                .disabled(!PlatformSettings.shared.notificationsEnabled)
                .accessibilityIdentifier("developer-test-alert")
                Button("重跑 onboarding", systemImage: "sparkles") { model.replayOnboarding() }
            }

            Section {
                Button("關閉開發者選項", role: .destructive) {
                    model.developerEnabled = false
                    dismiss()
                }
                .accessibilityIdentifier("developer-disable")
            }
        }
        .task { measure() }
    }

    private func value(for category: LocalDataCategory) -> String {
        if category == .session { return model.live?.session != nil ? "已儲存" : "無" }
        return sizes[category].map(LocalDataCategory.size) ?? "計算中…"
    }

    /// Account caches first, then system caches; the session last, since logout also clears the rest.
    private func clearSelected() async {
        let chosen = selected
        selected = []
        clearing = true
        defer { clearing = false }
        let before = chosen.reduce(Int64(0)) { $0 + (sizes[$1] ?? 0) }
        var failures: [String] = []
        do {
            try await model.clearLocalData(chosen.compactMap(\.area))
        } catch {
            failures.append(PocketError(error).message)
        }
        if chosen.contains(.cache) {
            do { try LocalDataCategory.clearSystemCaches() } catch { failures.append(error.message) }
        }
        if chosen.contains(.session) {
            // Leaving the demo comes first; the school account kept behind it is then signed out too.
            for _ in 0..<2 where model.isSignedIn {
                do { try await model.clearSession() } catch { failures.append(error.message); break }
            }
        }
        measure()
        freed = max(0, before - chosen.reduce(Int64(0)) { $0 + (sizes[$1] ?? 0) })
        clearError = failures.isEmpty ? nil : "部分資料未能清除，請再試一次。\n" + failures.joined(separator: "\n")
    }

    private func measure() {
        sizes = Dictionary(uniqueKeysWithValues: LocalDataCategory.allCases.map { ($0, $0.bytes()) })
    }

    private func diagnosticReport() -> String {
        let info = Bundle.main.infoDictionary
        let device = UIDevice.current
        var lines = [
            "CLHS Pocket 診斷包",
            "產生時間: \(Date().formatted(.iso8601))",
            "App ID: \(Bundle.main.bundleIdentifier ?? "")",
            "版本: \(info?["CFBundleShortVersionString"] as? String ?? "") (\(info?["CFBundleVersion"] as? String ?? ""))",
            "資料模式: \(model.dataSource.isDemo ? "示範" : "正式")",
            "\(device.systemName): \(device.systemVersion)",
            "裝置: \(device.model)",
            "登入狀態: \(model.isSignedIn ? "已登入" : "未登入")",
            "",
            "儲存空間",
        ]
        lines += LocalDataCategory.allCases.filter { $0 != .session }.map { "- \($0.title): \(LocalDataCategory.size(sizes[$0] ?? 0))" }
        lines.append("- 合計: \(LocalDataCategory.size(sizes.values.reduce(0, +)))")
        return lines.joined(separator: "\n")
    }
}
