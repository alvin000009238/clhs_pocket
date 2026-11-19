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

    override suspend fun fetchRecordSemesters(session: AuthenticatedSession): List<RecordSemester> =
        listOf(RecordSemester(115, 1, "115 學年度第 1 學期"), RecordSemester(114, 2, "114 學年度第 2 學期"), RecordSemester(114, 1, "114 學年度第 1 學期"))

    override suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
        listOf(
            RecordHistoryStatistic("病假", listOf(2, 0, 4, 0, null, null, null, null, null, null)),
            RecordHistoryStatistic("事假", listOf(1, 0, 2, 0, null, null, null, null, null, null)),
            RecordHistoryStatistic("曠課", listOf(0, 0, 0, 0, null, null, null, null, null, null)),
        )

    override suspend fun fetchMeritHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
        listOf(RecordHistoryStatistic("嘉獎", listOf(2, 1, 2, 0, null, null, null, null, null, null)))

    override suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord =
        if (semester.year == 115) AttendanceRecord(
            listOf(RecordCategoryCount("病假", 4), RecordCategoryCount("事假", 2), RecordCategoryCount("曠課", 0), RecordCategoryCount("遲到", 0), RecordCategoryCount("公假", 0)),
            listOf(
                AttendanceDetail("115/10/21", "病假", 3, "第 1–3 節"),
                AttendanceDetail("115/10/15", "事假", 2, "第 3–4 節"),
                AttendanceDetail("115/10/08", "病假", 1, "第 2 節"),
            ),
        ) else if (semester.term == 2) AttendanceRecord(
            listOf(RecordCategoryCount("病假", 0), RecordCategoryCount("事假", 0), RecordCategoryCount("曠課", 0)),
            emptyList(),
        ) else AttendanceRecord(emptyList(), emptyList())

    override suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> =
        if (semester.year == 115) listOf(
            MeritRecord("115/10/22", "115/10/26", "", "嘉獎", 2, "協助校園活動，服務認真。", "115 學年度第 1 學期", "獎"),
            MeritRecord("115/10/14", "115/10/16", "115/10/23", "警告", 1, "未依規定完成教室清潔，已完成服務銷過。", "115 學年度第 1 學期", "懲"),
            MeritRecord("115/10/06", "115/10/08", "", "小功", 1, "代表班級參與校內服務。", "115 學年度第 1 學期", "獎"),
        ) else emptyList()

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
