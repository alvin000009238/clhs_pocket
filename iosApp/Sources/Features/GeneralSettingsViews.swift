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

/// Storage categories on iOS; the account data is only removed through sign-out or 刪除資料.
enum LocalDataCategory: String, CaseIterable, Identifiable {
    case appData, caches, temporary

    var id: String { rawValue }

    var title: String {
        switch self {
        case .appData: "帳號與設定資料"
        case .caches: "快取"
        case .temporary: "暫存檔"
        }
    }

    var isClearable: Bool { self != .appData }

    var directory: URL? {
        let manager = FileManager.default
        return switch self {
        case .appData: manager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        case .caches: manager.urls(for: .cachesDirectory, in: .userDomainMask).first
        case .temporary: manager.temporaryDirectory
        }
    }

    func bytes() -> Int64 {
        guard let directory, let files = FileManager.default.enumerator(
            at: directory, includingPropertiesForKeys: [.totalFileAllocatedSizeKey, .isRegularFileKey]
        ) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in files {
            let values = try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey, .isRegularFileKey])
            if values?.isRegularFile == true { total += Int64(values?.totalFileAllocatedSize ?? 0) }
        }
        return total
    }

    /// Removes the directory's contents, not the directory; returns the bytes freed.
    func clear() -> Int64 {
        guard isClearable, let directory else { return 0 }
        let before = bytes()
        if self == .caches { URLCache.shared.removeAllCachedResponses() }
        let manager = FileManager.default
        for item in (try? manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] {
            try? manager.removeItem(at: item)
        }
        return max(0, before - bytes())
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
    @State private var report: String?

    var body: some View {
        SettingsForm(title: "開發者選項") {
            Section {
                ForEach(LocalDataCategory.allCases) { category in
                    Toggle(isOn: Binding(
                        get: { selected.contains(category) },
                        set: { if $0 { selected.insert(category) } else { selected.remove(category) } }
                    )) {
                        LabeledContent(category.title, value: sizes[category].map(LocalDataCategory.size) ?? "計算中…")
                    }
                    .disabled(!category.isClearable)
                    .accessibilityIdentifier("storage-\(category.rawValue)")
                }
                LabeledContent("合計", value: LocalDataCategory.size(sizes.values.reduce(0, +)))
                    .font(.body.weight(.semibold))
                Button(selected.isEmpty ? "請先選擇" : "清除選取", role: .destructive) {
                    freed = selected.reduce(0) { $0 + $1.clear() }
                    selected = []
                    measure()
                }
                .disabled(selected.isEmpty)
                .accessibilityIdentifier("storage-clear")
                if let freed {
                    Text("已釋放 \(LocalDataCategory.size(freed))")
                        .foregroundStyle(PocketColor.primary)
                        .accessibilityIdentifier("storage-freed")
                }
            } header: {
                Text("本機資料與儲存空間")
            } footer: {
                Text("帳號與設定資料只能透過登出或「資料管理」中的刪除資料移除。")
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
        lines += LocalDataCategory.allCases.map { "- \($0.title): \(LocalDataCategory.size(sizes[$0] ?? 0))" }
        lines.append("- 合計: \(LocalDataCategory.size(sizes.values.reduce(0, +)))")
        return lines.joined(separator: "\n")
    }
}
