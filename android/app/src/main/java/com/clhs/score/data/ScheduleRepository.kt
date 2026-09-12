package com.clhs.score.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ScheduleRepository {
    suspend fun getScheduleYears(): List<ScheduleYearTermOption>
    suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption>
    suspend fun fetchSchedule(
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
    ): ScheduleReport
    suspend fun getLatestSchedule(): ScheduleReport?
    suspend fun getLatestScheduleSnapshot(): CachedScheduleReport? =
        getLatestSchedule()?.let { CachedScheduleReport(it, null) }
    fun subjectOverridesFlow(): Flow<List<ScheduleSubjectOverride>> = flowOf(emptyList())
    suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) = Unit
}

class NetworkScheduleRepository(
    private val client: SchoolGradeClient,
    private val sessionStore: SessionStore,
    private val cacheStore: GradeCacheStore,
    activeSessionProvider: () -> AuthenticatedSession? = { null },
    sessionAccessAllowedProvider: () -> Boolean = { true },
    private val onAuthenticationExpired: () -> Unit = {},
) : ScheduleRepository {
    private val sessionResolver = ActiveSessionResolver(
        activeSessionProvider = activeSessionProvider,
        storedSessionProvider = sessionStore::loadSession,
        biometricSessionPresentProvider = sessionStore::hasBiometricSession,
        sessionAccessAllowedProvider = sessionAccessAllowedProvider,
    )
    private val fetchMutex = Mutex()

    override suspend fun getScheduleYears(): List<ScheduleYearTermOption> {
        return withSession(client::getScheduleYears)
    }

    override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> {
        return withSession { session -> client.getScheduleClasses(session, year, term) }
    }

    override suspend fun fetchSchedule(
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
    ): ScheduleReport {
        return withSession { session ->
            val requestStartedAt = System.currentTimeMillis()
            fetchMutex.withLock {
                val semesterCacheKey = if (classNo.isNotBlank()) "${yearValue}_${classNo}" else yearValue
                val cacheKey = if (scope == ScheduleScope.CURRENT_WEEK) {
                    "${semesterCacheKey}_current_week"
                } else {
                    semesterCacheKey
                }
                val completedWhileWaiting = cacheStore.loadScheduleSnapshot(session.studentNo, cacheKey)
                if (completedWhileWaiting != null &&
                    completedWhileWaiting.fetchedAtMillis?.let { it > requestStartedAt } == true &&
                    completedWhileWaiting.report.matches(
                        yearValue = yearValue,
                        classNo = classNo,
                        scope = scope,
                        targetDate = targetDate,
                    )
                ) {
                    return@withLock completedWhileWaiting.report.copy(
                        subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
                    )
                }

                val report = client.fetchSchedule(
                    session,
                    yearValue,
                    year,
                    term,
                    classNo,
                    scope,
                    targetDate,
                    onSemesterReport = { semesterReport ->
                        cacheStore.saveScheduleReport(session.studentNo, semesterCacheKey, semesterReport) { sessionStore.isAuthorized(session) }
                    },
                )
                cacheStore.saveScheduleReport(session.studentNo, cacheKeyFor(report, semesterCacheKey), report) { sessionStore.isAuthorized(session) }

                val displayedReport = report.copy(
                    subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
                )
                cacheStore.saveWidgetScheduleReport(session.studentNo, displayedReport) { sessionStore.isAuthorized(session) }
                displayedReport
            }
        }
    }

    override suspend fun getLatestSchedule(): ScheduleReport? {
        return getLatestScheduleSnapshot()?.report
    }

    override suspend fun getLatestScheduleSnapshot(): CachedScheduleReport? {
        val session = sessionResolver.currentSession() ?: return null
        sessionStore.validateSession(session)
        val snapshot = cacheStore.loadLatestScheduleSnapshot(session.studentNo) ?: return null
        val displayedReport = snapshot.report.copy(
            subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
        )
        return snapshot.copy(report = displayedReport)
    }

    override fun subjectOverridesFlow(): Flow<List<ScheduleSubjectOverride>> = flow {
        val session = sessionResolver.currentSession()
        if (session == null) {
            emit(emptyList())
        } else {
            emitAll(cacheStore.scheduleSubjectOverridesFlow(session.studentNo))
        }
    }

    override suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) {
        withSession { session ->
            cacheStore.saveScheduleSubjectOverrides(session.studentNo, overrides)
        }
    }

    private suspend fun <T> withSession(block: suspend (AuthenticatedSession) -> T): T {
        val session = sessionResolver.requireSession()
        sessionStore.validateSession(session)
        return try {
            block(session).also { sessionStore.validateSession(session) }
        } catch (error: SchoolAuthenticationException) {
            onAuthenticationExpired()
            throw error
        }
    }

    private fun cacheKeyFor(report: ScheduleReport, semesterCacheKey: String): String =
        if (report.scope == ScheduleScope.CURRENT_WEEK) {
            "${semesterCacheKey}_current_week"
        } else {
            semesterCacheKey
        }

    private fun ScheduleReport.matches(
        yearValue: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
    ): Boolean =
        this.yearTermValue == yearValue &&
            this.classNo == classNo &&
            this.scope == scope &&
            (scope != ScheduleScope.CURRENT_WEEK ||
                (isValidOn(targetDate) && !shouldRefreshAt(LocalDateTime.now())))

}

internal class ActiveSessionResolver(
    private val activeSessionProvider: () -> AuthenticatedSession?,
    private val storedSessionProvider: suspend () -> AuthenticatedSession?,
    private val biometricSessionPresentProvider: () -> Boolean,
    private val sessionAccessAllowedProvider: () -> Boolean = { true },
) {
    suspend fun currentSession(): AuthenticatedSession? {
        if (!sessionAccessAllowedProvider()) return null
        activeSessionProvider()?.let { return it }
        if (biometricSessionPresentProvider()) return null
        return storedSessionProvider()
    }

    suspend fun requireSession(): AuthenticatedSession =
        currentSession() ?: throw SchoolException("未登入")
}

class FakeScheduleRepository(
    private val cacheStore: GradeCacheStore? = null,
) : ScheduleRepository {
    private val inMemoryOverrides = MutableStateFlow<List<ScheduleSubjectOverride>>(emptyList())

    private suspend fun report(yearValue: String, classNo: String, scope: ScheduleScope = ScheduleScope.SEMESTER) =
        FakeScheduleData.report(yearValue, classNo, scope).copy(
            subjectOverrides = cacheStore?.loadScheduleSubjectOverrides(DEMO_STUDENT_NO)
                ?: inMemoryOverrides.value,
        )

    override suspend fun getScheduleYears(): List<ScheduleYearTermOption> {
        return FakeScheduleData.years
    }

    override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> {
        return FakeScheduleData.classes
    }

    override suspend fun fetchSchedule(
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
    ): ScheduleReport {
        return report(yearValue, classNo, scope)
    }

    override suspend fun getLatestSchedule(): ScheduleReport {
        return report("114_2", "230")
    }

    override suspend fun getLatestScheduleSnapshot(): CachedScheduleReport =
        CachedScheduleReport(report("114_2", "230"), System.currentTimeMillis())

    override fun subjectOverridesFlow(): Flow<List<ScheduleSubjectOverride>> =
        cacheStore?.scheduleSubjectOverridesFlow(DEMO_STUDENT_NO) ?: inMemoryOverrides

    override suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) {
        val normalized = overrides.normalizedSubjectOverrides()
        if (cacheStore == null) {
            inMemoryOverrides.value = normalized
        } else {
            cacheStore.saveScheduleSubjectOverrides(DEMO_STUDENT_NO, normalized)
        }
    }

    private companion object {
        const val DEMO_STUDENT_NO = "demo"
    }

}
