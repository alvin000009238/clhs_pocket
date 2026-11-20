import SwiftUI

struct PersonalView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var showingLogin = false
    @State private var confirmingSignOut = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: PocketMetrics.sectionSpacing) {
                    profileCard

                    section("外觀") {
                        VStack(alignment: .leading, spacing: 12) {
                            Label("主題", systemImage: "circle.lefthalf.filled")
                                .font(.body.weight(.medium))
                                .foregroundStyle(PocketColor.onSurface)
                            Picker("主題", selection: $model.appearance) {
                                ForEach(PocketAppearance.allCases) { Text($0.title).tag($0) }
                            }
                            .pickerStyle(.segmented)
                            .accessibilityIdentifier("appearance-picker")
                        }
                        .padding(12)
                    }

                    section("設定") {
                        VStack(spacing: 0) {
                            navRow("bell.badge", "通知", model.notificationsEnabled ? "已開啟" : "已關閉") { NotificationSettingsView() }
                            PocketRowDivider().padding(.leading, 52)
                            navRow("moon.zzz", "安靜時段", model.pauseEnabled ? "\(SettingsFormat.time(model.pauseStart)) – \(SettingsFormat.time(model.pauseEnd))" : "未啟用") { BackgroundPauseView() }
                            PocketRowDivider().padding(.leading, 52)
                            navRow("cloud.sun", "天氣來源", SettingsFormat.weatherSources.first { $0.id == model.weatherSource }?.title ?? "") { WeatherSourceView() }
                            PocketRowDivider().padding(.leading, 52)
                            navRow("lock", "App 鎖定", model.appLockEnabled ? "已開啟" : "已關閉") { AppLockSettingsView() }
                            PocketRowDivider().padding(.leading, 52)
                            navRow("externaldrive", "資料管理", "匯入、匯出與刪除") { DataSettingsView() }
                            PocketRowDivider().padding(.leading, 52)
                            navRow("info.circle", "關於", "") { AboutView() }
                        }
                    }

                    section("帳號") {
                        VStack(spacing: 0) {
                            if model.isSignedIn {
                                Button {
                                    confirmingSignOut = true
                                } label: {
                                    actionRow("rectangle.portrait.and.arrow.right", model.session == .demo ? "登出示範帳號" : "登出", tint: PocketColor.negative)
                                }
                                .accessibilityIdentifier("sign-out")
                                .confirmationDialog(
                                    model.session == .demo ? "登出示範帳號？" : "登出校務帳號？",
                                    isPresented: $confirmingSignOut, titleVisibility: .visible
                                ) {
                                    Button("登出", role: .destructive) { model.signOut() }
                                        .accessibilityIdentifier("sign-out-confirm")
                                    Button("取消", role: .cancel) {}
                                } message: {
                                    Text(model.session == .demo ? "示範資料會清除，可以隨時再進入示範。" : "這台裝置上的成績、課表與快取會一併清除。")
                                }
                            } else {
                                Button {
                                    showingLogin = true
                                } label: {
                                    actionRow("person.crop.circle.badge.plus", "登入校務帳號", tint: PocketColor.primary)
                                }
                                .accessibilityIdentifier("personal-login")
                            }
                            PocketRowDivider().padding(.leading, 52)
                            Button {
                                model.replayOnboarding()
                            } label: {
                                actionRow("sparkles", "重新觀看歡迎畫面", tint: PocketColor.primary)
                            }
                        }
                    }

                    Text(model.live != nil ? "非學校官方 App。登入資訊只保存在這台裝置的鑰匙圈，登出後會一併清除。" : "示範模式僅提供虛構資料，不會連線到校務系統，也不會儲存任何帳號資訊。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.horizontal, 4)
                }
                .frame(maxWidth: 640)
                .pocketPage()
            }
            .pocketScrollEdge()
            .background(PocketColor.background.ignoresSafeArea())
            .navigationTitle("個人")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .preferredColorScheme(model.appearance.colorScheme)
        .sheet(isPresented: $showingLogin) {
            LoginView()
        }
        // The root view's alert cannot show over this sheet, so a failed sign-out cleanup is shown here.
        .alert("帳號資料", isPresented: Binding(get: { model.accountError != nil }, set: { if !$0 { model.accountError = nil } })) {
            Button("好") { model.accountError = nil }
        } message: {
            Text(model.accountError ?? "")
        }
    }

    private func navRow(_ icon: String, _ title: String, _ value: String, @ViewBuilder destination: @escaping () -> some View) -> some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundStyle(PocketColor.primary)
                    .frame(width: 28)
                if typeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title).foregroundStyle(PocketColor.onSurface)
                        if !value.isEmpty {
                            Text(value).font(.subheadline).monospacedDigit().foregroundStyle(PocketColor.onSurfaceVariant)
                        }
                    }
                    Spacer(minLength: 8)
                } else {
                    Text(title).foregroundStyle(PocketColor.onSurface)
                    Spacer(minLength: 8)
                    Text(value)
                        .monospacedDigit()
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .multilineTextAlignment(.trailing)
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(PocketColor.outline)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 48)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("settings-\(title)")
    }

    @ViewBuilder
    private var profileCard: some View {
        if model.isSignedIn {
            let profile = model.profile
            PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
                HStack(spacing: 16) {
                    Group {
                        if let initial = profile.studentName.first {
                            Text(String(initial)).font(PocketFont.display(28, relativeTo: .title))
                        } else {
                            Image(systemName: "person.fill").font(.title)
                        }
                    }
                    .foregroundStyle(PocketColor.onPrimary)
                    .frame(width: 64, height: 64)
                    .background(PocketColor.primary, in: .circle)
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.studentName.isEmpty ? "已登入" : profile.studentName)
                            .font(PocketFont.display(26, relativeTo: .title))
                            .foregroundStyle(PocketColor.onPrimaryContainer)
                        Text(Self.classLine(profile))
                            .font(.subheadline)
                            .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                        if profile.isDemo || !profile.studentNo.isEmpty {
                            PocketChip(
                                text: profile.isDemo ? "示範帳號 \(profile.studentNo)" : "學號 \(profile.studentNo)",
                                foreground: PocketColor.onPrimaryContainer,
                                background: PocketColor.background.opacity(0.6)
                            )
                            .padding(.top, 4)
                        }
                    }
                }
            }
            .accessibilityElement(children: .combine)
        } else {
            PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("訪客模式")
                        .font(PocketFont.display(26, relativeTo: .title))
                        .foregroundStyle(PocketColor.onPrimaryContainer)
                    Text("登入後可查看課表與成績。")
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                    Button {
                        if model.live != nil { showingLogin = true } else { model.enterDemo() }
                    } label: {
                        Text(model.loginActionTitle)
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 18)
                            .frame(minHeight: 44)
                            .foregroundStyle(PocketColor.onPrimary)
                            .background(PocketColor.primary, in: .capsule)
                    }
                    .padding(.top, 8)
                }
            }
        }
    }

    /// "高二 30 班 · 12 號", or a loading note before the school has sent the student's details.
    static func classLine(_ profile: PocketProfile) -> String {
        let parts = [profile.className, profile.seatNo.isEmpty ? "" : "\(profile.seatNo) 號"].filter { !$0.isEmpty }
        return parts.isEmpty ? "正在載入學生資料…" : parts.joined(separator: " · ")
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            PocketSectionHeader(title: title)
            PocketCard(padding: 8) { content() }
        }
    }

    private func infoRow(_ icon: String, _ title: String, _ value: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(PocketColor.primary)
                .frame(width: 28)
            Text(title).foregroundStyle(PocketColor.onSurface)
            Spacer()
            Text(value).foregroundStyle(PocketColor.onSurfaceVariant)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .accessibilityElement(children: .combine)
    }

    private func actionRow(_ icon: String, _ title: String, tint: Color) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).frame(width: 28)
            Text(title).multilineTextAlignment(.leading)
            Spacer()
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .contentShape(.rect)
    }
}
