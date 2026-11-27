import Foundation

/// Android's on-device usage counters (使用統計). Counts stay in this app's defaults and are
/// never sent anywhere; deleting all data clears them.
enum UsageMetric: String, CaseIterable, Identifiable {
    case appOpen = "app_open"
    case overviewOpen = "overview_open"
    case scheduleOpen = "schedule_open"
    case gradesOpen = "grades_open"
    case campusOpen = "campus_open"
    case personalOpen = "personal_open"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .appOpen: "應用程式開啟"
        case .overviewOpen: "首頁畫面開啟"
        case .scheduleOpen: "課表畫面開啟"
        case .gradesOpen: "成績畫面開啟"
        case .campusOpen: "校園畫面開啟"
        case .personalOpen: "個人頁開啟"
        }
    }

    init(tab: PocketTab) {
        switch tab {
        case .home: self = .overviewOpen
        case .schedule: self = .scheduleOpen
        case .grades: self = .gradesOpen
        case .campus: self = .campusOpen
        }
    }
}

struct UsageStatisticsStore {
    private static let startedKey = "usage.startedAt"
    let defaults: UserDefaults

    func record(_ metric: UsageMetric) {
        let key = Self.key(metric)
        defaults.set(defaults.integer(forKey: key) + 1, forKey: key)
        if defaults.object(forKey: Self.startedKey) == nil {
            defaults.set(Date().timeIntervalSince1970, forKey: Self.startedKey)
        }
    }

    func count(_ metric: UsageMetric) -> Int { defaults.integer(forKey: Self.key(metric)) }

    var startedAt: Date? {
        (defaults.object(forKey: Self.startedKey) as? Double).map(Date.init(timeIntervalSince1970:))
    }

    func clear() {
        UsageMetric.allCases.forEach { defaults.removeObject(forKey: Self.key($0)) }
        defaults.removeObject(forKey: Self.startedKey)
    }

    private static func key(_ metric: UsageMetric) -> String { "usage.count_\(metric.rawValue)" }
}
