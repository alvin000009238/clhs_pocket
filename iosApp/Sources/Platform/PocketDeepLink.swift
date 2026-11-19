import Foundation

/// Typed entry points for widgets, notifications and `clhspocket://` URLs.
///
/// A link only names a destination. It never carries identifiers or content,
/// and protected destinations still pass through the shell's sign-in gate.
enum PocketDeepLink: String, CaseIterable, Sendable {
    case home, schedule, grades, campus, announcements, calendar

    static let scheme = "clhspocket"

    init?(url: URL) {
        guard url.scheme?.lowercased() == Self.scheme,
              let host = url.host()?.lowercased(),
              url.path.isEmpty || url.path == "/",
              url.query == nil, url.fragment == nil, url.user == nil, url.port == nil,
              let link = PocketDeepLink(rawValue: host) else { return nil }
        self = link
    }

    var url: URL { URL(string: "\(Self.scheme)://\(rawValue)")! }

    var tab: PocketTab {
        switch self {
        case .home: .home
        case .schedule: .schedule
        case .grades: .grades
        case .campus, .announcements, .calendar: .campus
        }
    }

    var route: PocketRoute? {
        switch self {
        case .announcements: .announcements
        case .calendar: .calendar
        default: nil
        }
    }
}

extension AppModel {
    /// Onboarding is never skipped by an external link.
    func open(_ link: PocketDeepLink) {
        guard hasCompletedOnboarding else { return }
        showingPersonal = false
        selectedTab = link.tab
        paths[link.tab] = link.route.map { [$0] } ?? []
    }
}
