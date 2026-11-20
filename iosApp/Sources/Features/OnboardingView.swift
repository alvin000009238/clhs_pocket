import SwiftUI

/// Four steps, as on Android: welcome, appearance, notifications, school account.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var step = Step.welcome
    @State private var showingLogin = false

    enum Step: Int, CaseIterable {
        case welcome, appearance, notifications, account
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                content
            }
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .padding(.bottom, 24)
            .id(step)
            .transition(reduceMotion ? .opacity : .asymmetric(
                insertion: .move(edge: .trailing).combined(with: .opacity),
                removal: .opacity
            ))
        }
        .scrollBounceBehavior(.basedOnSize)
        .safeAreaInset(edge: .top) { header }
        .safeAreaInset(edge: .bottom) { footer }
        .background(PocketColor.background.ignoresSafeArea())
        .sheet(isPresented: $showingLogin) {
            LoginView { model.hasCompletedOnboarding = true }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                go(to: Step(rawValue: step.rawValue - 1) ?? .welcome)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
            }
            .opacity(step == .welcome ? 0 : 1)
            .disabled(step == .welcome)
            .accessibilityLabel("上一步")

            HStack(spacing: 6) {
                ForEach(Step.allCases, id: \.self) { item in
                    Capsule()
                        .fill(item.rawValue <= step.rawValue ? PocketColor.primary : PocketColor.outline.opacity(0.35))
                        .frame(height: 4)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("步驟 \(step.rawValue + 1)，共 \(Step.allCases.count) 步")

            Color.clear.frame(width: 44, height: 44)
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 12)
        .background(PocketColor.background)
    }

    @ViewBuilder
    private var content: some View {
        switch step {
        case .welcome:
            Image("PocketLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 88, height: 88)
                .accessibilityHidden(true)
            title("歡迎使用\n壢中 Pocket", "把校園生活放進口袋。")
                .padding(.top, 24)
            VStack(spacing: 12) {
                feature("calendar", "課表一眼看完", "現在上哪一堂、下一堂在哪裡，調課異動也會標示。")
                feature("chart.bar.fill", "成績清楚掌握", "加權平均、排名與各科表現，整理成好讀的摘要。")
                feature("building.columns.fill", "校園資訊集中", "公告、行事曆、天氣與 YouBike，都在同一個地方。")
            }
            .padding(.top, 28)
        case .appearance:
            stepIcon("circle.lefthalf.filled")
            title("選擇外觀", "之後可以在「個人」中隨時調整。")
            VStack(spacing: 12) {
                ForEach(PocketAppearance.allCases) { option in
                    Button {
                        model.appearance = option
                    } label: {
                        choiceRow(option.systemImage, option.title, option.detail, selected: model.appearance == option)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(model.appearance == option ? .isSelected : [])
                    .accessibilityIdentifier("onboarding-appearance-\(option.rawValue)")
                }
            }
            .padding(.top, 28)
        case .notifications:
            stepIcon("bell.badge.fill")
            title("需要時提醒你", "通知只在這台裝置上產生，內容不會離開手機。")
            VStack(spacing: 12) {
                feature("clock.fill", "上課提醒", "下一堂課開始前提醒教室與科目。")
                feature("chart.line.uptrend.xyaxis", "成績更新", "校務系統公布新成績時通知你。")
                feature("megaphone.fill", "重要公告", "置頂公告與行事曆倒數。")
            }
            .padding(.top, 28)
        case .account:
            stepIcon("person.crop.circle.badge.checkmark")
            title("連結校務帳號", "登入後可查看課表、成績與出缺勤。公告、行事曆與天氣不需要登入。")
            VStack(spacing: 12) {
                feature("lock.fill", "帳號只留在裝置", "登入資訊不會上傳到任何第三方伺服器。")
                feature("sparkles", "先看看示範資料", "示範帳號只包含虛構資料，不會連線到校務系統。")
            }
            .padding(.top, 28)
        }
    }

    private var footer: some View {
        VStack(spacing: 4) {
            switch step {
            case .welcome:
                primary("開始設定", id: "onboarding-next") { go(to: .appearance) }
            case .appearance:
                primary("繼續", id: "onboarding-next") { go(to: .notifications) }
            case .notifications:
                primary("開啟通知", id: "onboarding-notifications-allow") {
                    Task {
                        _ = await model.enableNotifications()
                        go(to: .account)
                    }
                }
                secondary("暫時不要", id: "onboarding-notifications-skip") {
                    model.notificationsEnabled = false
                    go(to: .account)
                }
            case .account:
                primary("登入校務帳號", id: "onboarding-login") { showingLogin = true }
                secondary("使用示範帳號體驗", id: "onboarding-demo") { model.enterDemo() }
                secondary("先逛逛", id: "onboarding-guest") { model.continueAsGuest() }
            }
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .background(PocketColor.background)
    }

    private func go(to next: Step) {
        if reduceMotion { step = next } else { withAnimation(.snappy) { step = next } }
    }

    private func stepIcon(_ systemImage: String) -> some View {
        PocketIconTile(systemImage: systemImage, size: 72)
            .padding(.bottom, 20)
    }

    private func title(_ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(PocketFont.display(34, relativeTo: .largeTitle))
                .foregroundStyle(PocketColor.onSurface)
                .lineSpacing(4)
                .accessibilityAddTraits(.isHeader)
            Text(subtitle)
                .font(.title3)
                .foregroundStyle(PocketColor.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func feature(_ icon: String, _ title: String, _ detail: String) -> some View {
        PocketCard(padding: 16) {
            HStack(alignment: .center, spacing: 16) {
                PocketIconTile(systemImage: icon, size: 48)
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(PocketFont.cardTitle)
                        .foregroundStyle(PocketColor.onSurface)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func choiceRow(_ icon: String, _ title: String, _ detail: String, selected: Bool) -> some View {
        HStack(spacing: 16) {
            PocketIconTile(systemImage: icon, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(PocketFont.cardTitle).foregroundStyle(PocketColor.onSurface)
                Text(detail).font(.subheadline).foregroundStyle(PocketColor.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .font(.title2)
                .foregroundStyle(selected ? PocketColor.primary : PocketColor.outline)
        }
        .padding(16)
        .background(
            selected ? PocketColor.primaryContainer : PocketColor.surfaceLow,
            in: .rect(cornerRadius: PocketMetrics.cardRadius)
        )
        .contentShape(.rect(cornerRadius: PocketMetrics.cardRadius))
    }

    private func primary(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 52)
                .foregroundStyle(PocketColor.onPrimary)
                .background(PocketColor.primary, in: .capsule)
        }
        .accessibilityIdentifier(id)
    }

    private func secondary(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.medium))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .accessibilityIdentifier(id)
    }
}

/// School-account form. Authentication is delegated to `SchoolAccountService`.
/// The school's own login page in production; the synthetic account form in demo mode.
struct LoginView: View {
    @Environment(AppModel.self) private var model
    var onSignedIn: () -> Void = {}

    var body: some View {
        if model.live != nil {
            SchoolLoginSheet(onSignedIn: onSignedIn)
        } else {
            DemoLoginForm(onSignedIn: onSignedIn)
        }
    }
}

/// Hosts `SchoolLoginScreen`: the web view captures the session cookies, the shared core verifies them.
private struct SchoolLoginSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onSignedIn: () -> Void
    @State private var isVerifying = false
    @State private var message: String?
    @State private var loginResetToken = 0

    var body: some View {
        SchoolLoginScreen(
            isVerifying: isVerifying,
            errorMessage: message,
            loginResetToken: loginResetToken,
            onLogin: { studentNo, cookies in
                guard !isVerifying else { return }
                isVerifying = true
                Task {
                    message = await model.signIn(studentNo: studentNo, cookies: cookies)
                    isVerifying = false
                    if message == nil {
                        dismiss()
                        onSignedIn()
                    } else {
                        loginResetToken += 1
                    }
                }
            },
            onClose: { dismiss() },
            onDismissError: { message = nil }
        )
        .background(PocketColor.background.ignoresSafeArea())
        .interactiveDismissDisabled(isVerifying)
    }
}

private struct DemoLoginForm: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onSignedIn: () -> Void = {}

    @State private var account = ""
    @State private var password = ""
    @State private var message: String?
    @State private var isWorking = false
    @FocusState private var focus: Field?

    private enum Field { case account, password }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("登入校務帳號")
                        .font(PocketFont.display(30, relativeTo: .title))
                        .foregroundStyle(PocketColor.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Text("使用校務系統的學號與密碼。")
                        .font(.body)
                        .foregroundStyle(PocketColor.onSurfaceVariant)

                    PocketCard(padding: 0) {
                        VStack(spacing: 0) {
                            field("person.fill") {
                                TextField("學號", text: $account)
                                    .textContentType(.username)
                                    .textInputAutocapitalization(.characters)
                                    .autocorrectionDisabled()
                                    .focused($focus, equals: .account)
                                    .submitLabel(.next)
                                    .onSubmit { focus = .password }
                                    .accessibilityIdentifier("login-account")
                            }
                            PocketRowDivider().padding(.leading, 52)
                            field("lock.fill") {
                                SecureField("密碼", text: $password)
                                    .textContentType(.password)
                                    .focused($focus, equals: .password)
                                    .submitLabel(.go)
                                    .onSubmit(submit)
                                    .accessibilityIdentifier("login-password")
                            }
                        }
                    }

                    if let message {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .font(.subheadline)
                            .foregroundStyle(PocketColor.negative)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("login-error")
                    }

                    Button(action: submit) {
                        HStack(spacing: 8) {
                            if isWorking { ProgressView().tint(PocketColor.onPrimary) }
                            Text("登入")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                    }
                    .disabled(isWorking)
                    .accessibilityIdentifier("login-submit")

                    Button {
                        account = DemoSchoolAccountService.demoAccount
                        password = DemoSchoolAccountService.demoPassword
                        message = nil
                    } label: {
                        Text("填入示範帳號")
                            .font(.body.weight(.medium))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .accessibilityIdentifier("login-fill-demo")

                    Text("此版本尚未連接校務系統，僅示範帳號可以登入；示範資料全為虛構。")
                        .font(.footnote)
                        .foregroundStyle(PocketColor.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: 560, alignment: .leading)
                .frame(maxWidth: .infinity)
                .padding(24)
            }
            .background(PocketColor.background.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func field(_ icon: String, @ViewBuilder content: () -> some View) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(PocketColor.primary)
                .frame(width: 24)
                .accessibilityHidden(true)
            content()
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 56)
    }

    private func submit() {
        guard !isWorking else { return }
        isWorking = true
        Task {
            message = await model.signIn(account: account, password: password)
            isWorking = false
            if message == nil {
                dismiss()
                onSignedIn()
            }
        }
    }
}

extension PocketAppearance {
    var systemImage: String {
        switch self {
        case .system: "circle.lefthalf.filled"
        case .light: "sun.max.fill"
        case .dark: "moon.fill"
        }
    }

    var detail: String {
        switch self {
        case .system: "依照裝置的淺色或深色設定"
        case .light: "明亮的淺藍灰背景"
        case .dark: "夜間使用更舒適"
        }
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
                    model.requestLogin()
                } label: {
                    Text(model.loginActionTitle)
                        .font(.headline)
                        .frame(maxWidth: 320, minHeight: 52)
                        .frame(maxWidth: .infinity)
                        .foregroundStyle(PocketColor.onPrimary)
                        .background(PocketColor.primary, in: .capsule)
                }
                .padding(.top, 28)
                .accessibilityIdentifier("guest-gate-demo")
                Text(model.live != nil ? "登入會開啟學校的校務系統頁面，密碼不會經過這個 App。" : "示範模式不會連線到校務系統。")
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
