package com.clhs.score.data

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

interface GradeRepository {
    suspend fun restoreSession(): AuthenticatedSession?
    fun activateSession(session: AuthenticatedSession)

    suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean = false): List<YearTermOption>

    suspend fun loadCachedStudentInfo(session: AuthenticatedSession): StudentInfo? = null

    suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo

    suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean = false,
    ): GradeReport

    suspend fun logout(currentSession: AuthenticatedSession? = null)

    suspend fun loginWithCookies(
        studentNo: String,
        cookies: Map<String, String>,
    ): AuthenticatedSession
}

internal suspend fun clearLogoutCache(
    studentNo: String?,
    clearStudent: suspend (String) -> Unit,
    clearAll: suspend () -> Unit,
) {
    if (studentNo == null) clearAll() else clearStudent(studentNo)
}

class SchoolGradeRepository(
    private val client: SchoolGradeClient,
    private val sessionStore: SessionStore,
    private val cacheStore: GradeCacheStore,
) : GradeRepository {
    private val sessionGeneration = AtomicLong()
    private val sessionLock = Any()
    @Volatile
    private var activeSession: AuthenticatedSession? = null

    override suspend fun restoreSession(): AuthenticatedSession? {
        val session = sessionStore.loadSession() ?: return null
        activateSession(session)
        return session
    }

    override fun activateSession(session: AuthenticatedSession) {
        check(sessionStore.isAuthorized(session)) { "Session authority cannot be verified" }
        client.restoreSession(session)
        synchronized(sessionLock) {
            activeSession = session
            sessionGeneration.incrementAndGet()
        }
    }

    override suspend fun loadStructure(session: AuthenticatedSession, forceRefresh: Boolean): List<YearTermOption> {
        val generation = captureSessionGeneration(session)
        if (!forceRefresh) {
            val cached = cacheStore.loadStructure(session.studentNo)
            if (cached != null) {
                sessionStore.validateSession(session)
                return cached
            }
        }
        val structure = client.loadStructure(session)
        cacheStore.saveStructure(session.studentNo, structure) {
            isCurrentSession(session, generation)
        }
        sessionStore.validateSession(session)
        return structure
    }

    override suspend fun loadCachedStudentInfo(session: AuthenticatedSession): StudentInfo? {
        captureSessionGeneration(session)
        return cacheStore.loadStudentInfo(session.studentNo).also { sessionStore.validateSession(session) }
    }

    override suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo {
        val generation = captureSessionGeneration(session)
        val studentInfo = client.fetchStudentInfo(session)
        cacheStore.saveStudentInfo(session.studentNo, studentInfo) {
            isCurrentSession(session, generation)
        }
        sessionStore.validateSession(session)
        return studentInfo
    }

    override suspend fun fetchGrades(
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean,
    ): GradeReport {
        val generation = captureSessionGeneration(session)
        if (!forceRefresh) {
            val cached = cacheStore.loadGradeReport(session.studentNo, yearValue, examValue)
            if (cached != null) {
                sessionStore.validateSession(session)
                return cached
            }
        }
        val report = client.fetchGrades(session, yearValue, examValue)
        cacheStore.saveGradeReport(session.studentNo, yearValue, examValue, report) {
            isCurrentSession(session, generation)
        }
        sessionStore.validateSession(session)
        return report
    }

    override suspend fun logout(currentSession: AuthenticatedSession?) {
        sessionStore.revoke()
        invalidateSession()
        val failures = linkedSetOf<SessionCleanupFailure>()
        sessionStore.cleanupStep(failures, SessionCleanupFailure.SESSION_DELETE_FAILED) { sessionStore.clear() }
        client.clearSession()
        try {
            clearLogoutCache(
                currentSession?.studentNo,
                { cacheStore.clearStudent(it, preserveSubjectOverrides = true) },
                { cacheStore.clearAll(preserveSubjectOverrides = true) },
            )
        } catch (_: java.io.IOException) {
            failures += SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    override suspend fun loginWithCookies(
        studentNo: String,
        cookies: Map<String, String>,
    ): AuthenticatedSession {
        invalidateSession()
        val generation = sessionStore.requestGeneration()
        cacheStore.clearAll(preserveSubjectOverrides = true)
        val verified = client.loginWithCookies(studentNo, cookies)
        val session = sessionStore.establishSession(verified, generation)
        synchronized(sessionLock) {
            activeSession = session
            sessionGeneration.incrementAndGet()
        }
        return session
    }

    private suspend fun captureSessionGeneration(session: AuthenticatedSession): Long {
        sessionStore.validateSession(session)
        return synchronized(sessionLock) {
            val active = activeSession
            when {
                active == null -> {
                    client.restoreSession(session)
                    activeSession = session
                    sessionGeneration.incrementAndGet()
                }
                active != session -> throw CancellationException("Session changed")
            }
            sessionGeneration.get()
        }
    }

    private fun isCurrentSession(session: AuthenticatedSession, generation: Long): Boolean =
        sessionStore.isAuthorized(session) && activeSession == session && sessionGeneration.get() == generation

    private fun invalidateSession() {
        synchronized(sessionLock) {
            activeSession = null
            sessionGeneration.incrementAndGet()
        }
    }
}
