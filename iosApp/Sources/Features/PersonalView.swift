import SwiftUI

struct PersonalView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

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

                    section("關於") {
                        VStack(spacing: 0) {
                            infoRow("info.circle", "版本", Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                            PocketRowDivider().padding(.leading, 52)
                            infoRow("shippingbox", "資料來源", "示範資料")
                            PocketRowDivider().padding(.leading, 52)
                            Button {
                                model.replayOnboarding()
                            } label: {
                                actionRow("sparkles", "重新觀看歡迎畫面", tint: PocketColor.primary)
                            }
                            if model.isSignedIn {
                                PocketRowDivider().padding(.leading, 52)
                                Button {
                                    model.signOut()
                                } label: {
                                    actionRow("rectangle.portrait.and.arrow.right", "登出示範帳號", tint: PocketColor.negative)
                                }
                                .accessibilityIdentifier("sign-out")
                            }
                        }
                    }

                    Text("此版本僅提供示範資料，不會連線到校務系統，也不會儲存任何帳號資訊。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .padding(.horizontal, 4)
                }
                .frame(maxWidth: 640)
                .pocketPage()
            }
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
    }

    @ViewBuilder
    private var profileCard: some View {
        if model.isSignedIn {
            let profile = model.profile
            PocketCard(fill: PocketColor.primaryContainer, padding: 24) {
                HStack(spacing: 16) {
                    Text(String(profile.studentName.prefix(1)))
                        .font(PocketFont.display(28, relativeTo: .title))
                        .foregroundStyle(PocketColor.onPrimary)
                        .frame(width: 64, height: 64)
                        .background(PocketColor.primary, in: .circle)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.studentName)
                            .font(PocketFont.display(26, relativeTo: .title))
                            .foregroundStyle(PocketColor.onPrimaryContainer)
                        Text("\(profile.className) · \(profile.seatNo) 號")
                            .font(.subheadline)
                            .foregroundStyle(PocketColor.onPrimaryContainer.opacity(0.85))
                        PocketChip(
                            text: "示範帳號 \(profile.studentNo)",
                            foreground: PocketColor.onPrimaryContainer,
                            background: PocketColor.background.opacity(0.6)
                        )
                        .padding(.top, 4)
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
                        model.enterDemo()
                    } label: {
                        Text("使用示範帳號")
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
            Text(title)
            Spacer()
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .contentShape(.rect)
    }
}
