import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass

    private let features: [(icon: String, title: String, detail: String)] = [
        ("calendar", "課表一眼看完", "現在上哪一堂、下一堂在哪裡，調課異動也會標示。"),
        ("chart.bar.fill", "成績清楚掌握", "加權平均、排名與各科表現，整理成好讀的摘要。"),
        ("building.columns.fill", "校園資訊集中", "公告、行事曆、天氣與 YouBike，都在同一個地方。"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Image("PocketLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 88, height: 88)
                    .padding(.top, 48)
                    .accessibilityHidden(true)

                Text("歡迎使用\n壢中 Pocket")
                    .font(PocketFont.display(36, relativeTo: .largeTitle))
                    .foregroundStyle(PocketColor.onSurface)
                    .lineSpacing(4)
                    .padding(.top, 24)

                Text("把校園生活放進口袋。")
                    .font(.title3)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.top, 10)

                VStack(spacing: 12) {
                    ForEach(features, id: \.title) { feature in
                        PocketCard(padding: 16) {
                            HStack(alignment: .center, spacing: 16) {
                                PocketIconTile(systemImage: feature.icon, size: 48)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(feature.title)
                                        .font(PocketFont.cardTitle)
                                        .foregroundStyle(PocketColor.onSurface)
                                    Text(feature.detail)
                                        .font(.subheadline)
                                        .foregroundStyle(PocketColor.onSurfaceVariant)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
                .padding(.top, 32)
            }
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 4) {
                Button {
                    model.enterDemo()
                } label: {
                    Text("使用示範帳號體驗")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                }
                .accessibilityIdentifier("onboarding-demo")

                Button {
                    model.continueAsGuest()
                } label: {
                    Text("先以訪客身分瀏覽")
                        .font(.body.weight(.medium))
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .accessibilityIdentifier("onboarding-guest")

                Text("示範帳號只包含虛構資料，不會連線到校務系統。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.top, 2)
            }
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .background(PocketColor.background)
        }
        .background(PocketColor.background.ignoresSafeArea())
    }
}

/// Shown in place of a protected root while browsing as a guest.
struct GuestGateView: View {
    @Environment(AppModel.self) private var model
    let tab: PocketTab

    private var copy: (icon: String, title: String, detail: String) {
        switch tab {
        case .schedule: ("calendar", "登入後查看課表", "登入後即可查看本週與學期課表，以及調課異動。")
        case .grades: ("chart.bar.fill", "登入後查看成績", "登入後即可查看各次考試的成績、排名與分析。")
        default: ("person.crop.circle", "登入後使用", "登入後即可使用完整功能。")
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                PocketIconTile(systemImage: copy.icon, size: 88)
                    .padding(.top, 56)
                Text(copy.title)
                    .font(PocketFont.pageTitle)
                    .foregroundStyle(PocketColor.onSurface)
                    .padding(.top, 24)
                Text(copy.detail)
                    .font(.body)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)
                Button {
                    model.enterDemo()
                } label: {
                    Text("使用示範帳號")
                        .font(.headline)
                        .frame(maxWidth: 320, minHeight: 52)
                        .frame(maxWidth: .infinity)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                }
                .padding(.top, 28)
                .accessibilityIdentifier("guest-gate-demo")
                Text("校務系統登入將在後續版本提供。")
                    .font(.footnote)
                    .foregroundStyle(PocketColor.onSurfaceVariant)
                    .padding(.top, 12)
            }
            .frame(maxWidth: 420)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 32)
        }
        .scrollBounceBehavior(.basedOnSize)
        .navigationTitle(tab.title)
    }
}
