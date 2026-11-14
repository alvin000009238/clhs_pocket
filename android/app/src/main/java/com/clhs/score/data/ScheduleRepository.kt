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
import kotlinx.coroutines.CancellationException

interface ScheduleRepository {
    suspend fun selectCachedReport(report: ScheduleReport, automatic: Boolean) = Unit
    suspend fun getScheduleYears(): List<ScheduleYearTermOption>
    suspend fun getScheduleWeeks(year: String, term: String): List<ScheduleWeekOption> = emptyList()
    suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption>
    suspend fun fetchSchedule(
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
        expectedSession: AuthenticatedSession? = null,
        updateOverviewSnapshot: Boolean = false,
        forceRefresh: Boolean = true,
        updateBrowseSelection: Boolean = true,
    ): ScheduleReport
    suspend fun getLatestSchedule(): ScheduleReport?
    suspend fun getLatestScheduleSnapshot(): CachedScheduleReport? =
        getLatestSchedule()?.let { CachedScheduleReport(it, null) }
    suspend fun getOverviewScheduleSnapshot(): CachedScheduleReport? = null
    fun subjectOverridesFlow(): Flow<List<ScheduleSubjectOverride>> = flowOf(emptyList())
    suspend fun saveSubjectOverrides(overrides: List<ScheduleSubjectOverride>) = Unit
    suspend fun restoreSubjectOverrides(overrides: List<ScheduleSubjectOverride>, expectedSession: AuthenticatedSession) {
        throw UnsupportedOperationException("Account-bound restore is not supported")
    }
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
        return withSession(block = client::getScheduleYears)
    }

    override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> {
        return withSession { session -> client.getScheduleClasses(session, year, term) }
    }

    override suspend fun getScheduleWeeks(year: String, term: String): List<ScheduleWeekOption> =
        withSession { session -> client.getScheduleWeeks(session, year, term) }

    override suspend fun fetchSchedule(
        yearValue: String,
        year: String,
        term: String,
        classNo: String,
        scope: ScheduleScope,
        targetDate: LocalDate,
        expectedSession: AuthenticatedSession?,
        updateOverviewSnapshot: Boolean,
        forceRefresh: Boolean,
        updateBrowseSelection: Boolean,
    ): ScheduleReport {
        return withSession(expectedSession) { session ->
            val requestStartedAt = System.currentTimeMillis()
            fetchMutex.withLock {
                validateCurrentSession(session)
                val semesterCacheKey = if (classNo.isNotBlank()) "${yearValue}_${classNo}" else yearValue
                val cacheKey = if (scope == ScheduleScope.CURRENT_WEEK) {
                    "${semesterCacheKey}_current_week"
                } else {
                    semesterCacheKey
                }
                val completedWhileWaiting = cacheStore.loadScheduleSnapshot(session.studentNo, cacheKey, targetDate.takeIf { scope == ScheduleScope.CURRENT_WEEK })
                if (completedWhileWaiting != null &&
                    ((!forceRefresh && (!updateOverviewSnapshot || !completedWhileWaiting.report.shouldRefreshAt(LocalDateTime.now(SCHOOL_COURSE_ZONE)))) ||
                        completedWhileWaiting.fetchedAtMillis?.let { it > requestStartedAt } == true) &&
                    completedWhileWaiting.report.matches(
                        yearValue = yearValue,
                        classNo = classNo,
                        scope = scope,
                        targetDate = targetDate,
                    )
                ) {
                    cacheStore.selectSchedule(session.studentNo, completedWhileWaiting.report, updateOverviewSnapshot, updateBrowseSelection) { sessionStore.isAuthorized(session) }
                    return@withLock completedWhileWaiting.report.copy(
                        subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
                    )
                }

                val result = client.querySchedule(session, yearValue, year, term, classNo, scope, targetDate)
                val report: ScheduleReport
                val baseline: ScheduleReport?
                when (result) {
                    is ScheduleFetchResult.Available -> { report = result.report; baseline = result.semesterBaseline }
                    is ScheduleFetchResult.Empty -> { report = result.report; baseline = result.semesterBaseline }
                    is ScheduleFetchResult.Unpublished -> {
                        result.semesterFallback?.let { cacheStore.saveScheduleReport(session.studentNo, semesterCacheKey, it, updateBrowseSelection = false) { sessionStore.isAuthorized(session) } }
                        throw ScheduleFetchException(result)
                    }
                    is ScheduleFetchResult.Failed -> {
                        result.semesterFallback?.let { cacheStore.saveScheduleReport(session.studentNo, semesterCacheKey, it, updateBrowseSelection = false) { sessionStore.isAuthorized(session) } }
                        throw ScheduleFetchException(result)
                    }
                }
                cacheStore.saveScheduleReport(session.studentNo, cacheKeyFor(report, semesterCacheKey), report,
                    updateOverviewSnapshot = updateOverviewSnapshot, semesterBaseline = baseline, updateBrowseSelection = updateBrowseSelection) { sessionStore.isAuthorized(session) }

                val displayedReport = report.copy(
                    subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
                )
                displayedReport
            }
        }
    }

    override suspend fun getLatestSchedule(): ScheduleReport? {
        return getLatestScheduleSnapshot()?.report
    }

    override suspend fun selectCachedReport(report: ScheduleReport, automatic: Boolean) = withSession { session ->
        cacheStore.selectSchedule(session.studentNo, report, automatic) { sessionStore.isAuthorized(session) }
    }

    override suspend fun getLatestScheduleSnapshot(): CachedScheduleReport? = loadSnapshot(overview = false)

    override suspend fun getOverviewScheduleSnapshot(): CachedScheduleReport? = fetchMutex.withLock {
        loadSnapshot(overview = true)
    }

    private suspend fun loadSnapshot(overview: Boolean): CachedScheduleReport? {
        val session = sessionResolver.currentSession() ?: return null
        sessionStore.validateSession(session)
        val snapshot = (if (overview) cacheStore.loadOverviewScheduleSnapshot(session.studentNo)
            else cacheStore.loadLatestScheduleSnapshot(session.studentNo)) ?: return null
        val displayedReport = snapshot.report.copy(
            subjectOverrides = cacheStore.loadScheduleSubjectOverrides(session.studentNo),
        )
        validateCurrentSession(session)
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
            cacheStore.saveScheduleSubjectOverrides(session.studentNo, overrides) { sessionStore.isAuthorized(session) }
        }
    }

    override suspend fun restoreSubjectOverrides(overrides: List<ScheduleSubjectOverride>, expectedSession: AuthenticatedSession) {
        withSession(expectedSession) { session ->
            cacheStore.saveScheduleSubjectOverrides(session.studentNo, overrides) { sessionStore.isAuthorized(session) }
        }
    }

    private suspend fun <T> withSession(
        expectedSession: AuthenticatedSession? = null,
        block: suspend (AuthenticatedSession) -> T,
    ): T {
        val session = sessionResolver.requireSession()
        if (expectedSession != null && session != expectedSession) throw CancellationException("Session changed")
        validateCurrentSession(session)
        return try {
            block(session).also { validateCurrentSession(session) }
        } catch (error: SchoolAuthenticationException) {
            if (sessionResolver.currentSession() == session) onAuthenticationExpired()
            throw error
        }
    }

    private suspend fun validateCurrentSession(session: AuthenticatedSession) {
        if (sessionResolver.currentSession() != session) throw CancellationException("Session changed")
        sessionStore.validateSession(session)
        if (sessionResolver.currentSession() != session) throw CancellationException("Session changed")
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
            (scope != ScheduleScope.CURRENT_WEEK || isValidOn(targetDate))

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
        val stored = storedSessionProvider()
        if (!sessionAccessAllowedProvider()) return null
        if (biometricSessionPresentProvider()) return activeSessionProvider()
        return activeSessionProvider() ?: stored
    }

    suspend fun requireSession(): AuthenticatedSession =
        currentSession() ?: throw SchoolException("未登入")
}

class FakeScheduleRepository(
    private val cacheStore: GradeCacheStore? = null,
    private val activeSessionProvider: () -> AuthenticatedSession? = { null },
    private val sessionStore: SessionStore? = null,
) : ScheduleRepository {
    private val inMemoryOverrides = MutableStateFlow<List<ScheduleSubjectOverride>>(emptyList())

    override suspend fun selectCachedReport(report: ScheduleReport, automatic: Boolean) {
        val session = activeSessionProvider() ?: return
        cacheStore?.selectSchedule(session.studentNo, report, automatic) {
            activeSessionProvider() == session && sessionStore?.isAuthorized(session) == true
        }
    }

    private suspend fun report(
        yearValue: String,
        classNo: String,
        scope: ScheduleScope = ScheduleScope.SEMESTER,
        targetDate: LocalDate = LocalDate.now(SCHOOL_COURSE_ZONE),
        updateOverviewSnapshot: Boolean = false,
        updateBrowseSelection: Boolean = true,
    ): ScheduleReport {
        val session = activeSessionProvider()
        val report = FakeScheduleData.report(yearValue, classNo, scope, targetDate).copy(
            subjectOverrides = cacheStore?.loadScheduleSubjectOverrides(DEMO_STUDENT_NO)
                ?: inMemoryOverrides.value,
        )
        if (session != null && sessionStore != null) {
            val shouldCommit = { activeSessionProvider() == session && sessionStore.isAuthorized(session) }
            val semesterKey = "${yearValue}_$classNo"
            if (scope == ScheduleScope.CURRENT_WEEK) {
                cacheStore?.saveScheduleReport(
                    session.studentNo, semesterKey,
                    FakeScheduleData.report(yearValue, classNo, ScheduleScope.SEMESTER, targetDate),
                    updateBrowseSelection = false, shouldCommit = shouldCommit,
                )
            }
            val cacheKey = if (scope == ScheduleScope.CURRENT_WEEK) "${semesterKey}_current_week" else semesterKey
            cacheStore?.saveScheduleReport(session.studentNo, cacheKey, report.copy(subjectOverrides = emptyList()),
                updateOverviewSnapshot = updateOverviewSnapshot,
                updateBrowseSelection = updateBrowseSelection,
                semesterBaseline = if (scope == ScheduleScope.CURRENT_WEEK)
                    FakeScheduleData.report(yearValue, classNo, ScheduleScope.SEMESTER, targetDate) else null,
                shouldCommit = shouldCommit)
        }
        return report
    }

    override suspend fun getScheduleYears(): List<ScheduleYearTermOption> {
        return FakeScheduleData.years
    }

    override suspend fun getScheduleWeeks(year: String, term: String): List<ScheduleWeekOption> {
        val first = LocalDate.of(year.toInt() + 1911, if (term == "1") 8 else 2, 1)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return (0L..26L).map { index ->
            val start = first.plusWeeks(index)
            ScheduleWeekOption("第 ${index + 1} 週", "${index + 1}", start.toString(), start.plusDays(6).toString())
        }
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
        expectedSession: AuthenticatedSession?,
        updateOverviewSnapshot: Boolean,
        forceRefresh: Boolean,
        updateBrowseSelection: Boolean,
    ): ScheduleReport {
        if (expectedSession != null && activeSessionProvider() != expectedSession) {
            throw CancellationException("Session changed")
        }
        val session = activeSessionProvider()
        if (!forceRefresh && session != null && cacheStore != null) {
            val key = yearValue + classNo.takeIf(String::isNotBlank)?.let { "_$it" }.orEmpty() +
                if (scope == ScheduleScope.CURRENT_WEEK) "_current_week" else ""
            cacheStore.loadScheduleSnapshot(session.studentNo, key, targetDate.takeIf { scope == ScheduleScope.CURRENT_WEEK })?.let { cached ->
                if (!updateOverviewSnapshot || !cached.report.shouldRefreshAt(LocalDateTime.now(SCHOOL_COURSE_ZONE))) {
                    cacheStore.selectSchedule(session.studentNo, cached.report, updateOverviewSnapshot, updateBrowseSelection) {
                        activeSessionProvider() == session && sessionStore?.isAuthorized(session) == true
                    }
                    return cached.report.copy(subjectOverrides = cacheStore.loadScheduleSubjectOverrides(DEMO_STUDENT_NO))
                }
            }
        }
        return report(yearValue, classNo, scope, targetDate, updateOverviewSnapshot, updateBrowseSelection)
    }

    override suspend fun getLatestSchedule(): ScheduleReport = getLatestScheduleSnapshot().report

    override suspend fun getLatestScheduleSnapshot(): CachedScheduleReport {
        val session = activeSessionProvider()
        if (session != null && sessionStore != null) {
            sessionStore.validateSession(session)
            val cached = cacheStore?.loadLatestScheduleSnapshot(session.studentNo)
            sessionStore.validateSession(session)
            if (activeSessionProvider() != session) throw CancellationException("Session changed")
            if (cached != null) {
                val displayed = cached.report.copy(
                    subjectOverrides = cacheStore.loadScheduleSubjectOverrides(DEMO_STUDENT_NO),
                )
                return cached.copy(report = displayed)
            }
        }
        return CachedScheduleReport(
            report(FakeScheduleData.currentYearValue(), "230"), System.currentTimeMillis(),
        )
    }

    override suspend fun getOverviewScheduleSnapshot(): CachedScheduleReport? {
        val session = activeSessionProvider() ?: return null
        val authority = sessionStore ?: return null
        authority.validateSession(session)
        val snapshot = cacheStore?.loadOverviewScheduleSnapshot(session.studentNo) ?: return null
        val overrides = cacheStore.loadScheduleSubjectOverrides(DEMO_STUDENT_NO)
        authority.validateSession(session)
        if (activeSessionProvider() != session) throw CancellationException("Session changed")
        return snapshot.copy(report = snapshot.report.copy(subjectOverrides = overrides))
    }

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

    override suspend fun restoreSubjectOverrides(overrides: List<ScheduleSubjectOverride>, expectedSession: AuthenticatedSession) {
        if (activeSessionProvider() != expectedSession) throw CancellationException("Session changed")
        val normalized = overrides.normalizedSubjectOverrides()
        if (cacheStore == null) inMemoryOverrides.value = normalized
        else cacheStore.saveScheduleSubjectOverrides(DEMO_STUDENT_NO, normalized) {
            activeSessionProvider() == expectedSession && (sessionStore?.isAuthorized(expectedSession) != false)
        }
    }

    private companion object {
        const val DEMO_STUDENT_NO = "demo"
    }

}
