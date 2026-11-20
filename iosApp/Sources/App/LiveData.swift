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
    /// Bumped whenever the signed-in student's own timetable is stored.
    private(set) var scheduleRevision = 0
    /// The signed-in student's custom subject names as the schedule store's JSON; nil while signed
    /// out. They live in the account's protected store, which is also what the settings backup reads.
    private(set) var subjectOverrides: String?
    @ObservationIgnored private var lastGradeCheck: Date?
    private static let gradeCheckInterval: TimeInterval = 15 * 60
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
            let page = try await backend.call { try await $0.announcements(pageIndex: 0, keyword: "", unitId: "-1", categoryId: "-1", token: $1) }
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
    /// Shows the change at once and writes it to the account store.
    func setSubjectOverrides(_ json: String) {
        guard let session else { return }
        subjectOverrides = json
        let models = PocketScheduleStore.shared.overrideModels(overrides: json)
        Task {
            do {
                try await backend.call { try await $0.saveSubjectOverrides(session: session, overrides: models, token: $1) }
            } catch { note(PocketError(error)) }
        }
    }

    /// After a backup was restored into the account store.
    func reloadSubjectOverrides() async {
        guard let session else { return }
        do {
            let overrides = try await backend.call { try await $0.subjectOverrides(session: session, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return }
            subjectOverrides = PocketScheduleStore.shared.overridesJson(models: overrides)
        } catch { note(error) }
    }

    func logout() async throws(PocketError) {
        session = nil
        subjectOverrides = nil
        PocketLive.shared.clearPrivate()
        publish()
        try await backend.logout()
    }

    func deleteAllData() async throws(PocketError) {
        session = nil
        subjectOverrides = nil
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
            subjectOverrides = nil
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
                scheduleRevision += 1
                publish()
            }
            let overrides = try await backend.call { try await $0.subjectOverrides(session: session, token: $1) }
            guard current() else { return }
            subjectOverrides = PocketScheduleStore.shared.overridesJson(models: overrides)
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
        // `examValue` is the term + exam key the grade screens use; the school wants the two parts.
        guard let session, let year = PocketLive.shared.yearValueOf(examValue: examValue),
              let code = PocketLive.shared.examCodeOf(examValue: examValue) else { return }
        if !force, PocketLive.shared.hasReport(yearValue: year, examValue: code) { return }
        do {
            let report = try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: code, forceRefresh: force, token: $1)
            }
            guard self.session?.authorizationId == session.authorizationId else { return }
            PocketLive.shared.putReport(yearValue: year, examValue: code, report: report)
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
                if let report {
                    live.putSchedule(classValue: classValue, report: report)
                    // The widget snapshot, course reminders and the Live Activity are planned from
                    // the student's own timetable, which arrives after sign-in has been announced.
                    if classValue.isEmpty { scheduleRevision += 1 }
                }
                publish()
            }
        } catch { note(error) }
        publish()
    }

    /// Fetches the newest exam again and compares it with the report the app already had.
    /// Returns the alert to show, or nil when nothing changed or the check could not run.
    func checkGradeUpdate(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        guard settings.notificationsEnabled, settings.gradeUpdates else { return nil }
        if let lastGradeCheck, date.timeIntervalSince(lastGradeCheck) < Self.gradeCheckInterval { return nil }
        if session == nil { await restore() }
        let live = PocketLive.shared
        guard let session else { return nil }
        lastGradeCheck = date
        do {
            // Android watches one exam the student picked; here the newest exam is watched
            // automatically, so the exam list is refetched first or a new exam would go unnoticed.
            let known = Set(live.currentTermExamValues())
            let structure = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return nil }
            live.putStructure(items: structure)
            guard let key = live.currentTermExamValues().last,
                  let year = live.yearValueOf(examValue: key), let code = live.examCodeOf(examValue: key) else { return nil }
            // The cached report is the baseline; nothing extra is stored for the comparison. An exam
            // that was not listed before has no baseline, unless this is the very first check.
            let isNewExam = !known.isEmpty && !known.contains(key)
            let before: GradeReport? = isNewExam ? nil : try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: code, forceRefresh: false, token: $1)
            }
            let after = try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: code, forceRefresh: true, token: $1)
            }
            guard self.session?.authorizationId == session.authorizationId else { return nil }
            live.putReport(yearValue: year, examValue: code, report: after)
            publish()
            return PocketPlatform.shared.gradeChangeNotification(
                before: before, after: after, studentNo: session.studentNo, yearValue: year, examValue: code,
                examName: after.examSummary?.examName ?? "", nowMillis: date.epochMillis, settings: settings
            )
        } catch {
            // A failed background check is not worth an error on screen; the next one retries.
            return nil
        }
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

/// Production data for widgets, reminders and background refresh.
struct LivePlatformData: PlatformDataProviding {
    let live: LiveDataController
    /// False while the demo is showing; the school account is then left alone.
    let isActive: @MainActor () -> Bool
    private let shared = DemoPlatformData()

    func scheduleSnapshotJSON(scheduleNow: Date, deviceNow: Date) -> String? {
        shared.scheduleSnapshotJSON(scheduleNow: scheduleNow, deviceNow: deviceNow)
    }

    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline] {
        shared.announcementHeadlines(at: date)
    }

    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        guard isActive() else { return nil }
        return await live.checkGradeUpdate(at: date, settings: settings)
    }
}
