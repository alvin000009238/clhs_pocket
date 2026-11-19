import Foundation
import Observation
@preconcurrency import PocketShared

/// Loads real data through `PocketBackend` and publishes it to the shared `PocketLive`
/// store that every facade reads. Views observe `revision` and stay synchronous.
@MainActor
@Observable
final class LiveDataController {
    let backend: PocketBackend
    private(set) var session: AuthenticatedSession?
    /// Bumped after every store change so SwiftUI re-reads the facades.
    private(set) var revision = 0
    private(set) var isLoadingPublic = false
    private(set) var isLoadingPrivate = false
    /// zh-TW message of the most recent failure, safe to show.
    var lastError: String?

    init(backend: PocketBackend) {
        self.backend = backend
    }

    private func publish() { revision += 1 }

    /// Announcements, calendar, weather and YouBike need no account.
    func refreshPublic(weatherSource: WeatherSource, force: Bool = false) async {
        guard !isLoadingPublic else { return }
        isLoadingPublic = true
        defer { isLoadingPublic = false; publish() }
        let live = PocketLive.shared
        do {
            let page = try await backend.call { try await $0.announcements(pageIndex: 1, keyword: "", unitId: "-1", categoryId: "-1", token: $1) }
            live.putAnnouncements(items: page.announcements)
        } catch { lastError = error.message }
        publish()
        do {
            let snapshot = try await backend.call { try await $0.calendar(forceRefresh: force, token: $1) }
            live.putEvents(items: snapshot.feed.events)
        } catch { lastError = error.message }
        publish()
        for location in live.weatherLocations {
            if let load = try? await backend.call({ try await $0.weather(source: weatherSource, location: location, forceRefresh: force, token: $1) }),
               let snapshot = load.snapshot {
                live.putWeather(locationId: location.id, snapshot: snapshot)
            }
        }
        if let loads = try? await backend.call({ try await $0.youBike(stations: live.youBikeStations, forceRefresh: force, token: $1) }) {
            for load in loads { if let snapshot = load.snapshot { live.putYouBike(stationId: load.stationId, snapshot: snapshot) } }
        }
    }

    // MARK: Account

    /// Restores a stored session at launch. A failure leaves the app in guest mode.
    func restore() async {
        do {
            session = try await backend.restoreSession()
        } catch {
            session = nil
            if error.kind != .cancelled { lastError = error.message }
        }
        publish()
        if session != nil { await refreshPrivate() }
    }

    /// Verifies the cookies captured by the school web view and stores the session.
    func login(studentNo: String, cookies: [String: String]) async throws(PocketError) {
        session = try await backend.login(studentNo: studentNo, cookies: cookies)
        publish()
        await refreshPrivate()
    }

    /// Memory is revoked first; a `.cleanupIncomplete` failure is rethrown so the caller offers a retry.
    func logout() async throws(PocketError) {
        session = nil
        PocketLive.shared.clearPrivate()
        publish()
        try await backend.logout()
    }

    func deleteAllData() async throws(PocketError) {
        session = nil
        PocketLive.shared.clearAll()
        publish()
        try await backend.deleteAllData()
    }

    /// An expired session ends here; everything else is only reported.
    private func note(_ error: PocketError) {
        guard error.kind != .cancelled else { return }
        lastError = error.message
        if error.kind == .authenticationExpired {
            session = nil
            PocketLive.shared.clearPrivate()
        }
    }

    // MARK: Signed-in data

    func refreshPrivate(force: Bool = false) async {
        guard let session, !isLoadingPrivate else { return }
        isLoadingPrivate = true
        defer { isLoadingPrivate = false; publish() }
        let live = PocketLive.shared
        // Each write is skipped when the account changed while the request was in flight.
        func current() -> Bool { self.session?.authorizationId == session.authorizationId && self.session?.studentNo == session.studentNo }

        do {
            if let cached = try await backend.call({ try await $0.latestSchedule(session: session, token: $1) }), current() {
                live.putSchedule(classValue: "", report: cached.report)
                publish()
            }
            let structure = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: force, token: $1) }
            guard current() else { return }
            live.putStructure(items: structure)
            publish()
            for exam in live.currentTermExamValues().reversed() {
                await loadReport(examValue: exam, force: force)
            }
            let info = try await backend.call { try await $0.fetchStudentInfo(session: session, token: $1) }
            guard current() else { return }
            live.studentInfo = info
            publish()
        } catch { note(error) }

        await loadSchedule(classValue: "")

        do {
            let semesters = try await backend.call { try await $0.recordSemesters(session: session, token: $1) }
            guard current() else { return }
            live.recordSemesters = semesters
            publish()
        } catch { note(error) }
    }

    func loadReport(examValue: String, force: Bool = false) async {
        guard let session, let year = PocketLive.shared.yearValueOf(examValue: examValue) else { return }
        if !force, PocketLive.shared.hasReport(yearValue: year, examValue: examValue) { return }
        do {
            let report = try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: examValue, forceRefresh: force, token: $1)
            }
            guard self.session?.authorizationId == session.authorizationId else { return }
            PocketLive.shared.putReport(yearValue: year, examValue: examValue, report: report)
        } catch { note(error) }
        publish()
    }

    /// `classValue` is empty for the student's own class (the first the school lists).
    func loadSchedule(classValue: String) async {
        guard let session else { return }
        let live = PocketLive.shared
        do {
            let years = try await backend.call { try await $0.scheduleYears(session: session, token: $1) }
            guard let yearValue = years.first?.value, let parts = live.yearTermParts(value: yearValue), parts.count == 2 else { return }
            if live.scheduleClasses.isEmpty {
                let classes = try await backend.call { try await $0.scheduleClasses(session: session, year: parts[0], term: parts[1], token: $1) }
                guard self.session?.authorizationId == session.authorizationId else { return }
                live.scheduleClasses = classes
            }
            guard let classNo = classValue.isEmpty ? live.scheduleClasses.first?.value : classValue else { return }
            for scope in [ScheduleScope.currentWeek, .semester] {
                let result = try await backend.call {
                    try await $0.fetchSchedule(
                        session: session, yearValue: yearValue, year: parts[0], term: parts[1], classNo: classNo, scope: scope, token: $1
                    )
                }
                guard self.session?.authorizationId == session.authorizationId else { return }
                let report: ScheduleReport? = switch result {
                case let value as ScheduleFetchResultAvailable: value.report
                case let value as ScheduleFetchResultEmpty: value.report
                case let value as ScheduleFetchResultUnpublished: value.semesterFallback
                default: nil
                }
                if let report { live.putSchedule(classValue: classValue, report: report) }
                publish()
            }
        } catch { note(error) }
        publish()
    }

    func loadRecords(semesterIndex: Int) async {
        guard let session else { return }
        let semesters = PocketLive.shared.recordSemesters
        guard semesters.indices.contains(semesterIndex) else { return }
        let semester = semesters[semesterIndex]
        do {
            let attendance = try await backend.call { try await $0.attendance(session: session, semester: semester, token: $1) }
            let merits = try await backend.call { try await $0.merits(session: session, semester: semester, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return }
            PocketLive.shared.putAttendance(semester: semester, record: attendance)
            PocketLive.shared.putMerits(semester: semester, records: merits)
        } catch { note(error) }
        publish()
    }

    func loadAnnouncementDetail(id: String, category: String) async {
        do {
            let detail = try await backend.call { try await $0.announcementDetail(id: id, categoryHint: category, token: $1) }
            PocketLive.shared.putAnnouncementDetail(detail: detail)
        } catch { note(error) }
        publish()
    }
}
