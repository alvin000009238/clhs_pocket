import SwiftUI
import UIKit

/// Notification, Live Activity and quiet-hours controls for the personal page.
struct PlatformSettingsSection: View {
    @Environment(AppModel.self) private var model
    @State private var settings = PlatformSettings.shared
    @State private var permissionDenied = false

    private var coordinator: PocketPlatformCoordinator { .shared }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            PocketSectionHeader(title: "通知與動態") { EmptyView() }
            PocketCard(padding: 8) {
                VStack(spacing: 0) {
                    toggleRow("bell.badge", "允許通知", "上課前提醒、段考更新與學校公告", isOn: Binding(
                        get: { settings.notificationsEnabled },
                        set: { setNotifications($0) }
                    ))
                    .accessibilityIdentifier("notifications-toggle")
                    if permissionDenied {
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        } label: {
                            Label("通知權限已關閉，前往「設定」開啟", systemImage: "exclamationmark.triangle")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(PocketColor.warning)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 12)
                                .frame(minHeight: 44)
                        }
                    }
                    if settings.notificationsEnabled {
                        divider
                        toggleRow("clock.badge", "上課前提醒", "上課前 5 分鐘", isOn: binding(\.courseReminders))
                        divider
                        toggleRow("chart.bar.doc.horizontal", "段考更新", "不顯示分數與排名", isOn: binding(\.gradeUpdates))
                        divider
                        toggleRow("megaphone", "學校公告", nil, isOn: binding(\.announcements))
                    }
                    divider
                    toggleRow("platter.filled.top.iphone", "即時動態", "在鎖定畫面與動態島顯示目前課程", isOn: binding(\.liveActivity))
                    divider
                    toggleRow("moon", "安靜時段", "時段內不在背景檢查段考與公告", isOn: binding(\.pauseEnabled))
                    if settings.pauseEnabled {
                        HStack(spacing: 12) {
                            Spacer().frame(width: 28)
                            minutePicker("開始", \.pauseStartMinute)
                            Text("至").foregroundStyle(PocketColor.onSurfaceVariant)
                            minutePicker("結束", \.pauseEndMinute)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 12)
                        .frame(minHeight: 48)
                    }
                    if model.dataSource.isDemo, settings.notificationsEnabled {
                        divider
                        Menu {
                            ForEach(PocketPlatformCoordinator.DemoAlert.allCases) { alert in
                                Button(alert.title) { Task { await coordinator.sendDemoAlert(alert) } }
                            }
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "paperplane").frame(width: 28)
                                Text("傳送示範通知")
                                Spacer()
                                Image(systemName: "chevron.up.chevron.down").imageScale(.small)
                            }
                            .foregroundStyle(PocketColor.primary)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 48)
                            .contentShape(.rect)
                        }
                        .accessibilityIdentifier("demo-alert-menu")
                    }
                }
            }
            Text("iOS 由系統決定背景更新的時機，提醒與小工具可能在開啟 App 後才更新。")
                .font(.footnote)
                .foregroundStyle(PocketColor.onSurfaceVariant)
                .padding(.horizontal, 4)
        }
    }

    private var divider: some View {
        PocketRowDivider().padding(.leading, 52)
    }

    private func binding(_ keyPath: ReferenceWritableKeyPath<PlatformSettings, Bool>) -> Binding<Bool> {
        Binding(
            get: { settings[keyPath: keyPath] },
            set: { value in
                settings[keyPath: keyPath] = value
                Task { await coordinator.refresh() }
            }
        )
    }

    private func setNotifications(_ enabled: Bool) {
        Task {
            if enabled {
                permissionDenied = !(await coordinator.enableNotifications())
            } else {
                settings.notificationsEnabled = false
                permissionDenied = false
                await coordinator.refresh()
            }
        }
    }

    private func toggleRow(_ icon: String, _ title: String, _ detail: String?, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(PocketColor.primary)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(PocketColor.onSurface)
                    if let detail {
                        Text(detail)
                            .font(.footnote)
                            .foregroundStyle(PocketColor.onSurfaceVariant)
                    }
                }
            }
        }
        .tint(PocketColor.primary)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(minHeight: 48)
    }

    private func minutePicker(_ label: String, _ keyPath: ReferenceWritableKeyPath<PlatformSettings, Int>) -> some View {
        DatePicker(label, selection: Binding(
            get: {
                let minute = settings[keyPath: keyPath]
                return Calendar.current.date(bySettingHour: minute / 60, minute: minute % 60, second: 0, of: Date()) ?? Date()
            },
            set: { date in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
                let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
                let other = keyPath == \.pauseStartMinute ? settings.pauseEndMinute : settings.pauseStartMinute
                // The shared window rejects a zero-length range.
                guard minute != other else { return }
                settings[keyPath: keyPath] = minute
                Task { await coordinator.refresh() }
            }
        ), displayedComponents: .hourAndMinute)
        .labelsHidden()
        .accessibilityLabel("安靜時段\(label)")
    }
}
