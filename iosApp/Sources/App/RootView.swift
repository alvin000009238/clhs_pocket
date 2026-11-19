import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            PocketColor.background.ignoresSafeArea()
            if model.hasCompletedOnboarding {
                PocketShell()
                    .transition(.opacity)
            } else {
                OnboardingView()
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.35), value: model.hasCompletedOnboarding)
        .sheet(isPresented: Binding(get: { model.showingLogin }, set: { model.showingLogin = $0 })) {
            LoginView()
        }
        .pocketPrivacyProtected(lock: model.lock)
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await model.refreshLiveData()
        }
        .alert("帳號資料", isPresented: Binding(get: { model.accountError != nil }, set: { if !$0 { model.accountError = nil } })) {
            Button("好") { model.accountError = nil }
        } message: {
            Text(model.accountError ?? "")
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                model.refreshClock()
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }
}

/// Four top-level destinations, each owning an independent navigation stack.
struct PocketShell: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: Binding(get: { model.selectedTab }, set: { model.select($0) })) {
            ForEach(PocketTab.allCases, id: \.self) { tab in
                Tab(tab.title, systemImage: tab.systemImage, value: tab) {
                    NavigationStack(path: model.path(for: tab)) {
                        root(for: tab)
                            .background(PocketColor.background.ignoresSafeArea())
                            .navigationDestination(for: PocketRoute.self) { route in
                                destination(for: route)
                                    .background(PocketColor.background.ignoresSafeArea())
                            }
                            .toolbar {
                                ToolbarItem(placement: .topBarTrailing) { PersonalButton() }
                            }
                    }
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .modifier(TabBarMinimizeOnScroll())
        .sheet(isPresented: $model.showingPersonal) {
            PersonalView()
        }
    }

    @ViewBuilder
    private func root(for tab: PocketTab) -> some View {
        if tab.requiresSession && !model.isSignedIn {
            GuestGateView(tab: tab)
        } else {
            switch tab {
            case .home: HomeView()
            case .schedule: ScheduleView()
            case .grades: GradesView()
            case .campus: CampusView()
            }
        }
    }

    @ViewBuilder
    private func destination(for route: PocketRoute) -> some View {
        switch route {
        case .announcements: AnnouncementListView()
        case .calendar: CalendarListView()
        case .announcement(let id): AnnouncementDetailView(id: id)
        case .attendance: StudentRecordsView(kind: .attendance)
        case .merits: StudentRecordsView(kind: .merits)
        case .subject(let exam, let name): SubjectDetailView(examValue: exam, name: name)
        case .simulator(let exam): GradeSimulatorView(examValue: exam)
        }
    }
}

private struct TabBarMinimizeOnScroll: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.tabBarMinimizeBehavior(.onScrollDown)
        } else {
            content
        }
    }
}

struct PersonalButton: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Button {
            model.showingPersonal = true
        } label: {
            Image(systemName: model.isSignedIn ? "person.crop.circle.fill" : "person.crop.circle")
        }
        .accessibilityLabel("個人")
        .accessibilityIdentifier("personal-button")
    }
}
