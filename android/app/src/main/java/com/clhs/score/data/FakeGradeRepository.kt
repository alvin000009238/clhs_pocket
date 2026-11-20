package com.clhs.score.data

import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate

internal fun fakeYouBikeSnapshot(now: Instant): YouBikeSnapshot = YouBikeSnapshot(
    availableBikes = 4, emptyDocks = 44, totalDocks = 52,
    ordinaryBikes = 2, electricBikes = 2, isOperating = true,
    updatedAt = now, fetchedAt = now,
)

class FakeGradeRepository(
    private val sessionStore: SessionStore? = null,
    private val cacheStore: GradeCacheStore? = null,
) : GradeRepository {
    private val sessionLock = Any()
    private var sessionGeneration = 0L
    private var simulatorSessionRevoked = false
    private val simulatorCredits = mutableMapOf<Triple<String, String, String>, SavedSimulatorCredits>()
    @Volatile
    private var activeSession: AuthenticatedSession? = null

    override suspend fun restoreSession(): AuthenticatedSession? = activeSession

    override fun activateSession(session: AuthenticatedSession) {
        check(sessionStore?.isAuthorized(session) != false) { "Session authority cannot be verified" }
        synchronized(sessionLock) {
            activeSession = session
            sessionGeneration++
            simulatorSessionRevoked = false
        }
    }

    override suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean): List<YearTermOption> = FakeData.structure

    override suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo =
        MockGradeSystem.generateReport().studentInfo

    override suspend fun fetchRecordSemesters(session: AuthenticatedSession): List<RecordSemester> = FakeRecordData.semesters

    override suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
        FakeRecordData.attendanceHistory

    override suspend fun fetchMeritHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> = FakeRecordData.meritHistory

    override suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord =
        FakeRecordData.attendance(semester)

    override suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> =
        FakeRecordData.merits(semester)

    override suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean,
    ): GradeReport = FakeData.reportFor(yearValue, examValue)

    override suspend fun loadSimulatorCredits(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
    ): SavedSimulatorCredits? {
        val generation = captureSimulatorSessionGeneration(session)
        val settings = if (cacheStore != null) {
            cacheStore.loadSimulatorCredits(session.studentNo, yearValue, examValue)
        } else {
            synchronized(sessionLock) {
                if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
                simulatorCredits[Triple(session.studentNo, yearValue, examValue)]
            }
        }
        sessionStore?.validateSession(session)
        if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
        return settings?.copy(subjectNames = settings.subjectNames.toList(), credits = settings.credits.toMap())
    }

    override suspend fun saveSimulatorCredits(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        settings: SavedSimulatorCredits?,
    ) {
        val generation = captureSimulatorSessionGeneration(session)
        if (cacheStore != null) {
            cacheStore.saveSimulatorCredits(session.studentNo, yearValue, examValue, settings) {
                isCurrentSimulatorSession(session, generation)
            }
        } else {
            val snapshot = settings?.copy(subjectNames = settings.subjectNames.toList(), credits = settings.credits.toMap())
            synchronized(sessionLock) {
                if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
                val key = Triple(session.studentNo, yearValue, examValue)
                if (snapshot == null) simulatorCredits.remove(key) else simulatorCredits[key] = snapshot
            }
        }
        sessionStore?.validateSession(session)
        if (!isCurrentSimulatorSession(session, generation)) throw CancellationException("Session changed")
    }

    private suspend fun captureSimulatorSessionGeneration(session: AuthenticatedSession): Long {
        sessionStore?.validateSession(session)
        return synchronized(sessionLock) {
            when {
                simulatorSessionRevoked -> throw CancellationException("Session revoked")
                activeSession == null -> {
                    activeSession = session
                    sessionGeneration++
                }
                activeSession != session -> throw CancellationException("Session changed")
            }
            sessionGeneration
        }
    }

    private fun isCurrentSimulatorSession(session: AuthenticatedSession, generation: Long): Boolean =
        synchronized(sessionLock) {
            sessionStore?.isAuthorized(session) != false && activeSession == session && sessionGeneration == generation
        }

    override suspend fun logout(currentSession: AuthenticatedSession?) {
        synchronized(sessionLock) {
            activeSession = null
            sessionGeneration++
            simulatorSessionRevoked = true
            simulatorCredits.clear()
        }
        val failures = linkedSetOf<SessionCleanupFailure>()
        if (sessionStore != null) {
            sessionStore.cleanupStep(failures, SessionCleanupFailure.SESSION_DELETE_FAILED) { sessionStore.clear() }
            sessionStore.cleanupStep(failures, SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED) {
                cacheStore?.clearAll(preserveSubjectOverrides = true)
            }
        } else {
            cacheStore?.clearAll(preserveSubjectOverrides = true)
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    override suspend fun loginWithCookies(
        studentNo: String,
        cookies: Map<String, String>,
    ): AuthenticatedSession {
        val authenticated = sessionStore?.establishSession(FakeData.session) ?: FakeData.session
        activateSession(authenticated)
        FakeScheduleRepository(cacheStore, { activeSession }, sessionStore).getLatestSchedule()
        return authenticated
    }
}
