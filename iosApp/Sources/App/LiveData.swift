import Foundation
import Observation
import UIKit
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
    @ObservationIgnored private let gradeBaseline = GradeCheckBaseline()
    /// The one restore of the stored session; foreground and background launches share it.
    @ObservationIgnored private var restoring: Task<Void, Never>?
    /// Whether the announcement list in memory came from the school this launch.
    @ObservationIgnored private(set) var announcementsLoaded = false
    private static let gradeCheckInterval: TimeInterval = 15 * 60
    private(set) var isLoadingPublic = false
    private(set) var isLoadingPrivate = false
    /// Whose data `refreshPrivate` is loading, so a new account never waits behind the previous one.
    @ObservationIgnored private var loadingAuthorization: String?
    @ObservationIgnored private var accountWork: Task<Void, Never>?
    /// Bumped by every sign-in, sign-out and expiry, so a login can tell it was overtaken.
    @ObservationIgnored private var accountEpoch = 0
    /// zh-TW message of the most recent failure, safe to show.
    var lastError: String?

    @ObservationIgnored private let defaults: UserDefaults
    private static let accountStoredKey = "live.accountStored"

    /// Whether a school account is stored on this device, as last seen by a sign-in, a restore or
    /// a sign-out. It survives a restart, unlike `session`, which is nil until the session is
    /// restored and is never restored while the demo is showing. Whoever decides if the demo may
    /// publish to the widget and the reminders asks this, not the session in memory.
    private(set) var hasStoredAccount: Bool {
        get { defaults.bool(forKey: Self.accountStoredKey) }
        set { defaults.set(newValue, forKey: Self.accountStoredKey) }
    }

    init(backend: PocketBackend, defaults: UserDefaults = .standard) {
        self.backend = backend
        self.defaults = defaults
    }

    private func publish() { revision += 1 }

    @ObservationIgnored private var bookmarkIds: Set<String> = []
    @ObservationIgnored private var bookmarkSync: Task<Void, Never>?

    /// Public requests started before a delete-all belong to the data that was deleted. The number
    /// goes up with every delete; work that began under an older one is cancelled, is waited for
    /// before the files are removed, and may no longer put anything in memory.
    @ObservationIgnored private var publicEpoch = 0
    @ObservationIgnored private var publicWork: Task<Void, Never>?
    @ObservationIgnored private var announcementWork: Task<Void, Never>?
    @ObservationIgnored private var announcementsLoadedAt: Date?

    private func isCurrentPublic(_ epoch: Int) -> Bool { epoch == publicEpoch && !Task.isCancelled }

    /// Keeps a copy of every bookmarked announcement, so one that has left the first page of the
    /// school's list can still be listed and opened. `ids` is the student's current selection.
    func syncBookmarks(_ ids: Set<String>) {
        bookmarkIds = ids
        let earlier = bookmarkSync
        let epoch = publicEpoch
        bookmarkSync = Task {
            await earlier?.value
            guard isCurrentPublic(epoch) else { return }
            let live = PocketLive.shared
            do {
                var stored = try await backend.call { try await $0.bookmarkedAnnouncements(token: $1) }
                let known = Set(stored.map(\.id))
                let listed = live.listedAnnouncements
                let changes = stored.filter { !ids.contains($0.id) } + listed.filter { ids.contains($0.id) && !known.contains($0.id) }
                for item in changes {
                    guard isCurrentPublic(epoch) else { return }
                    _ = try await backend.call { try await $0.toggleAnnouncementBookmark(announcement: item, token: $1) }
                }
                if !changes.isEmpty { stored = try await backend.call { try await $0.bookmarkedAnnouncements(token: $1) } }
                guard isCurrentPublic(epoch) else { return }
                live.savedAnnouncements = stored
            } catch {
                // The bookmark itself is kept by the app; only the offline copy is missing.
            }
            publish()
        }
    }

    func bookmarksSettled() async { await bookmarkSync?.value }

    /// Announcements, calendar, weather and YouBike need no account.
    func refreshPublic(weatherSource: WeatherSource, force: Bool = false) async {
        // A second caller waits for the refresh in flight instead of starting another.
        if let publicWork { return await publicWork.value }
        let epoch = publicEpoch
        let work = Task { await loadPublic(weatherSource: weatherSource, force: force, epoch: epoch) }
        publicWork = work
        await work.value
        if epoch == publicEpoch { publicWork = nil }
    }

    private func loadPublic(weatherSource: WeatherSource, force: Bool, epoch: Int) async {
        isLoadingPublic = true
        defer {
            if epoch == publicEpoch { isLoadingPublic = false }
            publish()
        }
        let live = PocketLive.shared
        await fetchAnnouncements(epoch: epoch)
        guard isCurrentPublic(epoch) else { return }
        syncBookmarks(bookmarkIds)
        await bookmarkSync?.value
        guard isCurrentPublic(epoch) else { return }
        publish()
        do {
            let snapshot = try await backend.call { try await $0.calendar(forceRefresh: force, token: $1) }
            guard isCurrentPublic(epoch) else { return }
            live.putEvents(items: snapshot.feed.events)
        } catch { if isCurrentPublic(epoch) { lastError = error.message } }
        publish()
        for location in live.weatherLocations {
            guard isCurrentPublic(epoch) else { return }
            if let load = try? await backend.call({ try await $0.weather(source: weatherSource, location: location, forceRefresh: force, token: $1) }),
               let snapshot = load.snapshot, isCurrentPublic(epoch) {
                live.putWeather(locationId: location.id, snapshot: snapshot)
            }
        }
        guard isCurrentPublic(epoch) else { return }
        if let loads = try? await backend.call({ try await $0.youBike(stations: live.youBikeStations, forceRefresh: force, token: $1) }),
           isCurrentPublic(epoch) {
            for load in loads { if let snapshot = load.snapshot { live.putYouBike(stationId: load.stationId, snapshot: snapshot) } }
        }
    }

    /// The first page of the school's announcement list.
    private func fetchAnnouncements(epoch: Int) async {
        do {
            let page = try await backend.call { try await $0.announcements(pageIndex: 0, keyword: "", unitId: "-1", categoryId: "-1", token: $1) }
            guard isCurrentPublic(epoch) else { return }
            PocketLive.shared.putAnnouncements(items: page.announcements)
            announcementsLoaded = true
            announcementsLoadedAt = Date()
        } catch {
            if isCurrentPublic(epoch), error.kind != .cancelled { lastError = error.message }
        }
    }

    /// For the announcement reminder: fetches the list unless it was fetched in the last
    /// `maxAge` seconds. A launch in the background has no list in memory and nothing else loads
    /// one there; in the foreground the regular refresh has usually just done it.
    func loadAnnouncementsIfStale(maxAge: TimeInterval = 15 * 60, now: Date = Date()) async {
        if let loaded = announcementsLoadedAt, now.timeIntervalSince(loaded) < maxAge { return }
        if let publicWork { return await publicWork.value }
        if let announcementWork { return await announcementWork.value }
        let epoch = publicEpoch
        let work = Task {
            await fetchAnnouncements(epoch: epoch)
            publish()
        }
        announcementWork = work
        await work.value
        if epoch == publicEpoch { announcementWork = nil }
    }

    /// Ends every public request that is under way and waits until none of them can write again.
    private func revokePublicWork() async {
        publicEpoch += 1
        bookmarkIds = []
        let pending = [publicWork, announcementWork, bookmarkSync].compactMap { $0 }
        publicWork = nil
        announcementWork = nil
        bookmarkSync = nil
        isLoadingPublic = false
        announcementsLoadedAt = nil
        for task in pending { task.cancel() }
        for task in pending { await task.value }
    }

    // MARK: Account

    /// Restores the stored session and loads the account's data, once per launch no matter how many
    /// callers ask or how early they ask; everyone waits for the same restore to finish.
    func restoreOnce() async {
        if let restoring { return await restoring.value }
        // Before the first unlock, and on a locked device, the Keychain cannot be read. That is
        // not a failed restore: nothing is reported and the next caller tries again.
        guard UIApplication.shared.isProtectedDataAvailable else { return }
        let task = Task { await restore() }
        restoring = task
        await task.value
        // A restore that failed (storage busy, Keychain error) is tried again by the next caller.
        if !restoreSettled { restoring = nil }
    }

    /// The stored session has been read: either restored, or known to be absent.
    private(set) var restoreSettled = false

    /// Restores a stored session at launch. A failure leaves the app in guest mode.
    private func restore() async {
        do {
            session = try await backend.restoreSession()
            restoreSettled = true
            hasStoredAccount = session != nil
        } catch {
            session = nil
            if error.kind != .cancelled { lastError = error.message }
        }
        publish()
        if session != nil { await refreshPrivate() }
    }

    /// Verifies the cookies captured by the school web view and stores the session.
    /// Signing in over another account is a complete logout first: nothing of the previous
    /// student stays in memory, in the Keychain or in the cache.
    func login(studentNo: String, cookies: [String: String]) async throws(PocketError) {
        let switching = session != nil
        if switching { endSessionInMemory() }
        accountEpoch += 1
        let epoch = accountEpoch
        let result: Result<AuthenticatedSession, PocketError> = await serialized { [backend] in
            do {
                // The shared core signs out and signs in as one locked step.
                return .success(try await switching
                    ? backend.switchAccount(studentNo: studentNo, cookies: cookies)
                    : backend.login(studentNo: studentNo, cookies: cookies))
            } catch {
                return .failure(PocketError(error))
            }
        }
        let established = try result.get()
        // A logout or another login asked for while this one was verifying wins; its cleanup is
        // already queued behind this login and removes what was just stored.
        guard epoch == accountEpoch else { throw PocketError(kind: .cancelled, message: "登入已取消") }
        session = established
        hasStoredAccount = true
        publish()
        await refreshPrivate()
    }

    /// Account storage work (logout, delete, the cleanup after an expired session, login) runs one
    /// at a time in the order it was asked for. A login therefore waits for a logout that is still
    /// deleting, which would otherwise remove the new session it had just stored.
    private func serialized<T: Sendable>(_ work: @escaping @MainActor () async -> T) async -> T {
        let previous = accountWork
        let task = Task { @MainActor in
            await previous?.value
            return await work()
        }
        accountWork = Task { _ = await task.value }
        return await task.value
    }

    /// Forgets the signed-in student everywhere in memory. Storage is cleaned by the caller.
    private func endSessionInMemory() {
        accountEpoch += 1
        session = nil
        hasStoredAccount = false
        subjectOverrides = nil
        lastGradeCheck = nil
        loadingAuthorization = nil
        isLoadingPrivate = false
        gradeBaseline.clear()
        PocketLive.shared.clearPrivate()
        publish()
    }

    /// Memory is revoked first; a `.cleanupIncomplete` failure is rethrown so the caller offers a retry.
    /// Shows the change at once and writes it to the account store.
    func setSubjectOverrides(_ json: String) {
        guard let session else { return }
        let previous = subjectOverrides
        subjectOverrides = json
        let models = PocketScheduleStore.shared.overrideModels(overrides: json)
        // Writes are stored in the order they were made, so a slow earlier save cannot land on
        // top of a later one.
        let earlier = overrideSave
        overrideSave = Task {
            await earlier?.value
            do {
                try await backend.call { try await $0.saveSubjectOverrides(session: session, overrides: models, token: $1) }
            } catch {
                let error = PocketError(error)
                // What is on screen must be what is stored: go back, unless a newer edit or
                // another account has taken over since.
                if subjectOverrides == json, self.session?.authorizationId == session.authorizationId {
                    subjectOverrides = previous
                    publish()
                }
                note(error, for: session)
                if error.kind != .authenticationExpired, error.kind != .cancelled {
                    lastError = "自訂科目名稱未能儲存：\(error.message)"
                }
            }
        }
    }

    @ObservationIgnored private var overrideSave: Task<Void, Never>?

    /// Waits until every subject override written so far is stored or rolled back.
    func subjectOverridesSettled() async { await overrideSave?.value }

    /// After a backup was restored into the account store.
    func reloadSubjectOverrides() async {
        guard let session else { return }
        do {
            let overrides = try await backend.call { try await $0.subjectOverrides(session: session, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return }
            subjectOverrides = PocketScheduleStore.shared.overridesJson(models: overrides)
        } catch { note(error, for: session) }
    }

    func logout() async throws(PocketError) {
        endSessionInMemory()
        let result: Result<Void, PocketError> = await serialized { [backend] in
            do { return .success(try await backend.logout()) } catch { return .failure(PocketError(error)) }
        }
        try result.get()
    }

    func deleteAllData() async throws(PocketError) {
        endSessionInMemory()
        // Requests still on the wire would otherwise write the announcement cache, the read state
        // and the bookmarks back after they were deleted. They are stopped and waited for first,
        // so whatever they managed to store is removed with everything else.
        await revokePublicWork()
        PocketLive.shared.clearAll()
        announcementsLoaded = false
        publish()
        let result: Result<Void, PocketError> = await serialized { [backend] in
            do { return .success(try await backend.deleteAllData()) } catch { return .failure(PocketError(error)) }
        }
        try result.get()
    }

    /// Reports a failure. A session the school no longer accepts ends here: memory, Keychain and
    /// the private cache are cleared exactly as on logout, so the next launch does not restore it.
    /// `session` is the one the failed request was made for; a late failure of a previous account
    /// must not sign out the student who is signed in now.
    private func note(_ error: PocketError, for session: AuthenticatedSession? = nil) {
        guard error.kind != .cancelled else { return }
        if error.kind == .authenticationExpired {
            guard let session, session.authorizationId == self.session?.authorizationId else { return }
            lastError = error.message
            endSessionInMemory()
            Task {
                let failure: PocketError? = await serialized { [backend] in
                    do { try await backend.logout(); return nil } catch { return PocketError(error) }
                }
                if let failure { lastError = failure.message }
            }
            return
        }
        lastError = error.message
    }

    // MARK: Signed-in data

    func refreshPrivate(force: Bool = false) async {
        guard let session, loadingAuthorization != session.authorizationId else { return }
        loadingAuthorization = session.authorizationId
        isLoadingPrivate = true
        defer {
            if loadingAuthorization == session.authorizationId {
                loadingAuthorization = nil
                isLoadingPrivate = false
            }
            publish()
        }
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
        } catch { note(error, for: session) }

        await loadSchedule(classValue: "")

        do {
            let semesters = try await backend.call { try await $0.recordSemesters(session: session, token: $1) }
            guard current() else { return }
            live.recordSemesters = semesters
            publish()
        } catch { note(error, for: session) }
    }

    /// The simulator credits stored for this exam and account, for `PocketSimulator.restoreCredits`.
    func simulatorCredits(examValue: String) async -> String? {
        guard let session, let year = PocketLive.shared.yearValueOf(examValue: examValue),
              let code = PocketLive.shared.examCodeOf(examValue: examValue) else { return nil }
        return try? await backend.call {
            try await $0.simulatorCredits(session: session, yearValue: year, examValue: code, token: $1)
        }
    }

    /// Stores `PocketSimulator.creditsJson()`; nil removes the stored credits. Throws when the
    /// credits were not stored, so the caller does not mark them as confirmed.
    func saveSimulatorCredits(examValue: String, json: String?) async throws(PocketError) {
        guard let session, let year = PocketLive.shared.yearValueOf(examValue: examValue),
              let code = PocketLive.shared.examCodeOf(examValue: examValue) else {
            throw PocketError(kind: .storage, message: "尚未登入，無法儲存學分")
        }
        do {
            try await backend.call {
                try await $0.saveSimulatorCredits(session: session, yearValue: year, examValue: code, settingsJson: json, token: $1)
            }
        } catch {
            let error = PocketError(error)
            note(error, for: session)
            throw error
        }
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
        } catch { note(error, for: session) }
        publish()
    }

    /// `classValue` is empty for the student's own timetable.
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
            // An empty class number asks the school for the signed-in student's own timetable. The
            // class list covers the whole school, so its first entry is somebody else's class.
            let classNo = classValue
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
        } catch { note(error, for: session) }
        publish()
    }

    /// Another week of the weekly timetable, `weekOffset` weeks from the current one.
    func loadScheduleWeek(classValue: String, weekOffset: Int) async {
        guard let session, weekOffset != 0 else { return }
        let live = PocketLive.shared
        do {
            let years = try await backend.call { try await $0.scheduleYears(session: session, token: $1) }
            guard let yearValue = years.first?.value, let parts = live.yearTermParts(value: yearValue), parts.count == 2 else { return }
            if live.scheduleClasses.isEmpty {
                let classes = try await backend.call { try await $0.scheduleClasses(session: session, year: parts[0], term: parts[1], token: $1) }
                guard self.session?.authorizationId == session.authorizationId else { return }
                live.scheduleClasses = classes
            }
            // An empty class number asks the school for the signed-in student's own timetable. The
            // class list covers the whole school, so its first entry is somebody else's class.
            let classNo = classValue
            let result = try await backend.call {
                try await $0.fetchScheduleWeek(
                    session: session, yearValue: yearValue, year: parts[0], term: parts[1], classNo: classNo,
                    weekOffset: Int32(weekOffset), token: $1
                )
            }
            guard self.session?.authorizationId == session.authorizationId else { return }
            switch result {
            case let value as ScheduleFetchResultAvailable: live.putSchedule(classValue: classValue, report: value.report)
            case let value as ScheduleFetchResultEmpty: live.putSchedule(classValue: classValue, report: value.report)
            case is ScheduleFetchResultUnpublished: lastError = "學校尚未提供這一週的課表。"
            default: lastError = "無法更新這一週的課表，請稍後再試。"
            }
        } catch { note(error, for: session) }
        publish()
    }

    /// Fetches the newest exam again and compares it with the report the app already had.
    /// Returns the alert to show, or nil when nothing changed or the check could not run.
    func checkGradeUpdate(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        guard settings.notificationsEnabled, settings.gradeUpdates else { return nil }
        if let lastGradeCheck, date.timeIntervalSince(lastGradeCheck) < Self.gradeCheckInterval { return nil }
        // The session and the grade cache are unreadable while the device is locked. The check
        // waits for the next refresh instead of failing and blocking the following 15 minutes.
        guard UIApplication.shared.isProtectedDataAvailable else { return nil }
        if session == nil { await restoreOnce() }
        let live = PocketLive.shared
        guard let session else { return nil }
        do {
            // Android watches one exam the student picked; here the newest exam is watched
            // automatically, so the exam list is refetched first or a new exam would go unnoticed.
            let baseline = gradeBaseline.exams(authorizationId: session.authorizationId)
            let structure = try await backend.call { try await $0.loadStructure(session: session, forceRefresh: true, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return nil }
            live.putStructure(items: structure)
            let exams = live.currentTermExamValues()
            // Only a check that ran to the end moves the baseline and the 15-minute timer.
            func completed() {
                gradeBaseline.save(exams: exams, authorizationId: session.authorizationId)
                lastGradeCheck = date
            }
            guard let key = exams.last,
                  let year = live.yearValueOf(examValue: key), let code = live.examCodeOf(examValue: key) else {
                completed()
                return nil
            }
            // The cached report is what an exam is compared with. An exam that was not listed at
            // the last completed check has nothing to compare with, even when that check found no
            // exams at all; the very first check after sign-in only records what exists.
            let isNewExam = live.isNewExam(baseline: baseline, key: key)
            let before: GradeReport? = isNewExam ? nil : try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: code, forceRefresh: false, token: $1)
            }
            let after = try await backend.call {
                try await $0.fetchGrades(session: session, yearValue: year, examValue: code, forceRefresh: true, token: $1)
            }
            guard self.session?.authorizationId == session.authorizationId else { return nil }
            live.putReport(yearValue: year, examValue: code, report: after)
            completed()
            publish()
            return PocketPlatform.shared.gradeChangeNotification(
                before: before, after: after, studentNo: session.studentNo, yearValue: year, examValue: code,
                examName: after.examSummary?.examName ?? "", nowMillis: date.epochMillis, settings: settings
            )
        } catch {
            // A failed background check is not worth an error on screen and the next one retries,
            // but a session the school no longer accepts ends here as it does everywhere else.
            let error = PocketError(error)
            if error.kind == .authenticationExpired { note(error, for: session) }
            return nil
        }
    }

    func loadRecords(semesterIndex: Int) async {
        guard let session else { return }
        let semesters = PocketLive.shared.recordSemesters
        guard semesters.indices.contains(semesterIndex) else { return }
        let semester = semesters[semesterIndex]
        // Attendance and merits are independent: one failing must not discard the other.
        do {
            let attendance = try await backend.call { try await $0.attendance(session: session, semester: semester, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return }
            PocketLive.shared.putAttendance(semester: semester, record: attendance)
        } catch { note(error) }
        do {
            let merits = try await backend.call { try await $0.merits(session: session, semester: semester, token: $1) }
            guard self.session?.authorizationId == session.authorizationId else { return }
            PocketLive.shared.putMerits(semester: semester, records: merits)
        } catch { note(error, for: session) }
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

    /// The demo is showing on a device that also holds a school account: that account's widget,
    /// reminders and notifications are left exactly as they are.
    /// Decided from what is stored, not from memory: after a restart in demo mode the session is
    /// not restored, and the account's snapshot and reminders must still be left alone.
    private var standsAside: Bool { !isActive() && (live.session != nil || live.hasStoredAccount) }

    /// A cold background launch has nothing in memory: restore the session and the cached timetable.
    func prepare() async {
        guard isActive(), UIApplication.shared.isProtectedDataAvailable else { return }
        await live.restoreOnce()
    }

    /// Public data, so no session is needed; skipped while the demo is showing.
    func loadAnnouncements() async {
        guard isActive() else { return }
        await live.loadAnnouncementsIfStale()
    }

    func scheduleSnapshot(scheduleNow: Date, deviceNow: Date) -> PlatformSnapshot {
        guard !standsAside else { return .unknown }
        let snapshot = shared.scheduleSnapshot(scheduleNow: scheduleNow, deviceNow: deviceNow)
        // "Nothing to publish" only counts once the student's own timetable has been loaded.
        if snapshot == .none, isActive(), live.scheduleRevision == 0 { return .unknown }
        return snapshot
    }

    func announcementHeadlines(at date: Date) -> [AnnouncementHeadline]? {
        guard !standsAside else { return nil }
        if isActive(), !live.announcementsLoaded { return nil }
        return shared.announcementHeadlines(at: date)
    }

    func gradeNotification(at date: Date, settings: PlatformNotificationSettings) async -> PlannedNotification? {
        guard isActive() else { return nil }
        return await live.checkGradeUpdate(at: date, settings: settings)
    }
}
