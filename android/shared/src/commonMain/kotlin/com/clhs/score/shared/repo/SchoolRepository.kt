@file:OptIn(ExperimentalAtomicApi::class)

package com.clhs.score.shared.repo

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReport
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleFetchResult
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleWeekOption
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.YearTermOption
import com.clhs.score.shared.net.PortableSchoolClient
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.schoolToday
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * School data for the signed-in student: the counterpart of Android's `SchoolGradeRepository` and
 * the network half of `NetworkScheduleRepository`.
 *
 * A call is bound to the session it was given. It is refused when that session is not the active
 * one, and its result is neither cached nor returned when the authorization changed while it ran.
 * Attendance and merit records are never cached.
 */
class SchoolRepository(
    private val client: PortableSchoolClient,
    private val vault: SessionVault,
    private val cache: PrivateCache,
) {
    private class Active(val session: AuthenticatedSession?, val generation: Long)

    private val active = AtomicReference(Active(null, 0L))

    private fun setActive(session: AuthenticatedSession?): Long {
        while (true) {
            val previous = active.load()
            val next = Active(session, previous.generation + 1)
            if (active.compareAndSet(previous, next)) return next.generation
        }
    }

    suspend fun restoreSession(): AuthenticatedSession? {
        val session = vault.loadSession() ?: return null
        activateSession(session)
        return session
    }

    fun activateSession(session: AuthenticatedSession) {
        check(vault.isAuthorized(session)) { "Session authority cannot be verified" }
        client.restoreSession(session)
        setActive(session)
    }

    /** Verifies cookies from the login web view, stores the session and makes it the active one. */
    suspend fun loginWithCookies(studentNo: String, cookies: Map<String, String>): AuthenticatedSession {
        setActive(null)
        val generation = vault.requestGeneration()
        cache.clearSchoolData()
        val verified = client.loginWithCookies(studentNo, cookies)
        val session = vault.establishSession(verified, generation)
        // The client holds the verified session without its authorization id; align both.
        client.restoreSession(session)
        setActive(session)
        return session
    }

    /**
     * Revokes the session first, then removes stored credentials, cookies and cached school data.
     * Throws [SessionCleanupException] naming everything that could not be removed.
     */
    suspend fun logout() {
        vault.revoke()
        setActive(null)
        val failures = LinkedHashSet<SessionCleanupFailure>()
        try {
            vault.clear()
        } catch (_: StorageException) {
            failures += SessionCleanupFailure.SESSION_DELETE_FAILED
        }
        client.clearSession()
        try {
            cache.clearSchoolData()
        } catch (_: StorageException) {
            failures += SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean = false): List<YearTermOption> {
        val generation = capture(session)
        if (!forceRefresh) {
            cache.loadStructure(session.studentNo)?.let { cached ->
                vault.validateSession(session)
                return cached
            }
        }
        val structure = client.loadStructure(session)
        cache.saveStructure(session.studentNo, structure) { isCurrent(session, generation) }
        requireCurrent(session, generation)
        return structure
    }

    fun loadCachedStudentInfo(session: AuthenticatedSession): StudentInfo? {
        val generation = capture(session)
        return cache.loadStudentInfo(session.studentNo).also { requireCurrent(session, generation) }
    }

    suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo {
        val generation = capture(session)
        val info = client.fetchStudentInfo(session)
        cache.saveStudentInfo(session.studentNo, info) { isCurrent(session, generation) }
        requireCurrent(session, generation)
        return info
    }

    suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean = false,
    ): GradeReport {
        val generation = capture(session)
        if (!forceRefresh) {
            cache.loadGradeReport(session.studentNo, yearValue, examValue)?.let { cached ->
                vault.validateSession(session)
                return cached
            }
        }
        val report = client.fetchGrades(session, yearValue, examValue)
        cache.saveGradeReport(session.studentNo, yearValue, examValue, report) { isCurrent(session, generation) }
        requireCurrent(session, generation)
        return report
    }

    fun loadSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String): String? {
        val generation = capture(session)
        return cache.loadSimulatorCredits(session.studentNo, yearValue, examValue).also { requireCurrent(session, generation) }
    }

    fun saveSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String, settingsJson: String?) {
        val generation = capture(session)
        cache.saveSimulatorCredits(session.studentNo, yearValue, examValue, settingsJson) { isCurrent(session, generation) }
        requireCurrent(session, generation)
    }

    suspend fun fetchRecordSemesters(session: AuthenticatedSession): List<RecordSemester> =
        guarded(session) { client.fetchRecordSemesters(session) }

    suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
        guarded(session) { client.fetchAttendanceHistory(session) }

    suspend fun fetchMeritHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
        guarded(session) { client.fetchMeritHistory(session) }

    suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord =
        guarded(session) { client.fetchAttendance(session, semester) }

    suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> =
        guarded(session) { client.fetchMerits(session, semester) }

    suspend fun getScheduleYears(session: AuthenticatedSession): List<ScheduleYearTermOption> =
        guarded(session) { client.getScheduleYears(session) }

    suspend fun getScheduleClasses(session: AuthenticatedSession, year: String, term: String): List<ScheduleClassOption> =
        guarded(session) { client.getScheduleClasses(session, year, term) }

    suspend fun getScheduleWeeks(session: AuthenticatedSession, year: String, term: String): List<ScheduleWeekOption> =
        guarded(session) { client.getScheduleWeeks(session, year, term) }

    /**
     * Queries the timetable and caches a published result. Failed and unpublished results are
     * returned as they are, with the semester fallback the client could still load.
     */
    suspend fun fetchSchedule(
        session: AuthenticatedSession,
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate = schoolToday(),
    ): ScheduleFetchResult {
        val generation = capture(session)
        val result = client.querySchedule(session, yearValue, year, term, classNo, scope, targetDate)
        val overrides = cache.loadSubjectOverrides(session.studentNo)
        fun ScheduleReport.withOverrides() = copy(subjectOverrides = overrides)
        val decorated = when (result) {
            is ScheduleFetchResult.Available -> result.copy(result.report.withOverrides(), result.semesterBaseline?.withOverrides())
            is ScheduleFetchResult.Empty -> result.copy(result.report.withOverrides(), result.semesterBaseline?.withOverrides())
            is ScheduleFetchResult.Unpublished -> result.copy(semesterFallback = result.semesterFallback?.withOverrides())
            is ScheduleFetchResult.Failed -> result.copy(semesterFallback = result.semesterFallback?.withOverrides())
        }
        val published = when (result) {
            is ScheduleFetchResult.Available -> result.report
            is ScheduleFetchResult.Empty -> result.report
            else -> null
        }
        if (published != null) {
            // Only this week's timetable is "the latest" the widget and reminders are built from;
            // browsing another week must not replace it.
            cache.saveSchedule(session.studentNo, scheduleCacheKey(published), published, latest = targetDate == schoolToday()) {
                isCurrent(session, generation)
            }
        }
        requireCurrent(session, generation)
        return decorated
    }

    fun loadCachedSchedule(session: AuthenticatedSession, yearValue: String, classNo: String, scope: ScheduleScope): CachedSchedule? {
        val generation = capture(session)
        val key = "$yearValue|$classNo|${scope.name}"
        return cache.loadSchedule(session.studentNo, key)?.withOverrides(session).also { requireCurrent(session, generation) }
    }

    /** The most recently fetched published timetable, for a cache-first start. */
    fun loadLatestSchedule(session: AuthenticatedSession): CachedSchedule? {
        val generation = capture(session)
        return cache.loadLatestSchedule(session.studentNo)?.withOverrides(session).also { requireCurrent(session, generation) }
    }

    fun loadSubjectOverrides(session: AuthenticatedSession): List<ScheduleSubjectOverride> {
        val generation = capture(session)
        return cache.loadSubjectOverrides(session.studentNo).also { requireCurrent(session, generation) }
    }

    fun saveSubjectOverrides(session: AuthenticatedSession, overrides: List<ScheduleSubjectOverride>) {
        val generation = capture(session)
        cache.saveSubjectOverrides(session.studentNo, overrides) { isCurrent(session, generation) }
        requireCurrent(session, generation)
    }

    private fun CachedSchedule.withOverrides(session: AuthenticatedSession) =
        CachedSchedule(report.copy(subjectOverrides = cache.loadSubjectOverrides(session.studentNo)), capturedAtEpochMillis)

    private fun scheduleCacheKey(report: ScheduleReport) = "${report.yearTermValue}|${report.classNo}|${report.scope.name}"

    private suspend fun <T> guarded(session: AuthenticatedSession, request: suspend () -> T): T {
        val generation = capture(session)
        return request().also { requireCurrent(session, generation) }
    }

    private fun capture(session: AuthenticatedSession): Long {
        vault.validateSession(session)
        while (true) {
            val current = active.load()
            when {
                current.session == session -> return current.generation
                current.session != null -> throw CancellationException("Session changed")
                else -> {
                    val next = Active(session, current.generation + 1)
                    if (active.compareAndSet(current, next)) {
                        client.restoreSession(session)
                        return next.generation
                    }
                }
            }
        }
    }

    private fun isCurrent(session: AuthenticatedSession, generation: Long): Boolean {
        val current = active.load()
        return vault.isAuthorized(session) && current.session == session && current.generation == generation
    }

    private fun requireCurrent(session: AuthenticatedSession, generation: Long) {
        if (!isCurrent(session, generation)) throw CancellationException("Session changed")
    }
}
