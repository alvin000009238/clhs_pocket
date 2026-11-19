package com.clhs.score.shared.repo

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.shared.net.FakeHttpTransport
import com.clhs.score.shared.net.HttpRequest
import com.clhs.score.shared.net.HttpResponse
import com.clhs.score.shared.net.SchoolFixtures
import com.clhs.score.shared.net.response
import com.clhs.score.shared.net.schoolClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionAndSchoolRepositoryTest {
    private val secure = FakeSecureStore()
    private val files = FakeFileStore()
    private val login = AuthenticatedSession("DEMO-001", "api-token", mapOf("ASP.NET_SessionId" to "abc"))
    private val tokenPage = """<input name="__RequestVerificationToken" value="page-token">"""

    private class Rig(
        val repository: SchoolRepository,
        val vault: SessionVault,
        val cache: PrivateCache,
        val transport: FakeHttpTransport,
    )

    private fun TestScope.rig(handler: (HttpRequest) -> HttpResponse = { response(SchoolFixtures.gradeJson) }): Rig {
        val transport = FakeHttpTransport(handler)
        val vault = SessionVault(secure, files)
        val cache = PrivateCache(files) { 5_000L }
        return Rig(SchoolRepository(schoolClient(transport), vault, cache), vault, cache, transport)
    }

    private fun TestScope.account(rig: Rig, web: Boolean = true) = AccountService(
        rig.repository, rig.cache, SettingsStore(files) { 5_000L }, files, secure,
        object : WebDataCleaner {
            override fun clear(completion: (Boolean) -> Unit) = completion(web)
        },
    )

    // Vault

    @Test
    fun onlyAFreshLoginCreatesAuthorityAndItSurvivesARestart() = runTest {
        val vault = SessionVault(secure, files)
        assertFalse(vault.isAuthorized(login))
        val session = vault.establishSession(login)
        assertTrue(session.authorizationId.isNotBlank())
        assertTrue(vault.isAuthorized(session))
        // A copy with a guessed or missing id carries no authority.
        assertFalse(vault.isAuthorized(session.copy(authorizationId = "guess")))

        val restarted = SessionVault(secure, files)
        assertFalse(restarted.isAuthorized(session))
        assertEquals(session, restarted.loadSession())
        assertTrue(restarted.isAuthorized(session))
    }

    @Test
    fun revokeEndsAuthorityBeforeStorageIsTouched() = runTest {
        val vault = SessionVault(secure, files)
        val session = vault.establishSession(login)
        vault.revoke()
        assertFalse(vault.isAuthorized(session))
        assertFailsWith<CancellationException> { vault.validateSession(session) }
        // A login that started before the revocation cannot store its session afterwards.
        val generation = vault.requestGeneration()
        vault.revoke()
        assertFailsWith<CancellationException> { vault.establishSession(login, generation) }
        vault.clear()
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun failedSessionDeleteIsReportedAndBlocksRestoreUntilItIsRetried() = runTest {
        val vault = SessionVault(secure, files)
        val session = vault.establishSession(login)
        secure.failDelete = true
        assertFailsWith<StorageException> { vault.clear() }
        assertFalse(vault.isAuthorized(session))
        assertTrue(vault.hasPendingCleanup())
        // The credentials are still in storage, but nothing may use them.
        assertTrue(secure.values.isNotEmpty())
        val restarted = SessionVault(secure, files)
        assertFailsWith<StorageException> { restarted.loadSession() }
        assertFailsWith<SessionStorageUnavailableException> { restarted.establishSession(login) }
        assertFalse(restarted.isAuthorized(session))

        secure.failDelete = false
        assertNull(restarted.loadSession())
        assertTrue(secure.values.isEmpty())
        assertFalse(restarted.hasPendingCleanup())
        assertTrue(restarted.isAuthorized(restarted.establishSession(login)))
    }

    @Test
    fun unreadableStoredSessionIsDeletedInsteadOfRestored() = runTest {
        secure.values["school.session.v1"] = "{broken"
        assertNull(SessionVault(secure, files).loadSession())
        assertTrue(secure.values.isEmpty())
        secure.failRead = true
        assertFailsWith<StorageException> { SessionVault(secure, files).loadSession() }
    }

    // Repository

    @Test
    fun loginStoresTheSessionAndGradesAreServedFromTheAccountCache() = runTest {
        val rig = rig { request -> if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.gradeJson) }
        val session = rig.repository.loginWithCookies("DEMO-001", mapOf("ASP.NET_SessionId" to "web"))
        assertEquals("page-token", session.apiToken)
        assertTrue(rig.vault.isAuthorized(session))

        val first = rig.repository.fetchGrades(session, "114_1", "期末考")
        val requests = rig.transport.requests.size
        // The cache keeps the parsed report, not the raw school payload.
        val cached = rig.repository.fetchGrades(session, "114_1", "期末考")
        assertEquals(first.subjects, cached.subjects)
        assertEquals(first.studentInfo, cached.studentInfo)
        assertEquals(requests, rig.transport.requests.size)
        rig.repository.fetchGrades(session, "114_1", "期末考", forceRefresh = true)
        assertEquals(requests + 1, rig.transport.requests.size)

        // Cached entries belong to one student and are invisible to any other.
        assertNotNull(rig.cache.loadGradeReport("DEMO-001", "114_1", "期末考"))
        assertNull(rig.cache.loadGradeReport("DEMO-002", "114_1", "期末考"))

        // After a restart the stored session is restored and still reads its cache without network.
        val restarted = rig()
        val restored = assertNotNull(restarted.repository.restoreSession())
        assertEquals(cached, restarted.repository.fetchGrades(restored, "114_1", "期末考"))
        assertTrue(restarted.transport.requests.isEmpty())
    }

    @Test
    fun sessionWithoutAuthorityOrOfAnotherAccountSendsNothing() = runTest {
        val rig = rig()
        assertFailsWith<CancellationException> { rig.repository.fetchGrades(login, "114_1", "期末考") }
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        val forged = session.copy(studentNo = "DEMO-002")
        assertFailsWith<CancellationException> { rig.repository.fetchGrades(forged, "114_1", "期末考") }
        assertFailsWith<CancellationException> { rig.repository.fetchAttendanceHistory(forged) }
        assertTrue(rig.transport.requests.isEmpty())
    }

    @Test
    fun responseThatArrivesAfterLogoutIsNeitherReturnedNorCached() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.transport.hold = true
        val late = async { runCatching { rig.repository.fetchGrades(session, "114_1", "期末考") } }
        runCurrent()
        val call = rig.transport.pending.single()

        rig.repository.logout()
        call.respond(response(SchoolFixtures.gradeJson))
        assertIs<CancellationException>(late.await().exceptionOrNull())
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun responseOfThePreviousAccountCannotReachTheNextAccountsCache() = runTest {
        val rig = rig { request -> if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.gradeJson) }
        val first = rig.repository.loginWithCookies("DEMO-001", mapOf("ASP.NET_SessionId" to "one"))
        rig.repository.fetchGrades(first, "114_1", "期末考")
        rig.transport.hold = true
        val late = async { runCatching { rig.repository.fetchGrades(first, "114_1", "期末考", forceRefresh = true) } }
        runCurrent()
        val call = rig.transport.pending.single()
        rig.transport.hold = false

        rig.repository.logout()
        val second = rig.repository.loginWithCookies("DEMO-002", mapOf("ASP.NET_SessionId" to "two"))
        call.respond(response(SchoolFixtures.gradeJson))
        assertIs<CancellationException>(late.await().exceptionOrNull())
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
        assertFalse(rig.vault.isAuthorized(first))
        assertTrue(rig.vault.isAuthorized(second))
        assertEquals("ASP.NET_SessionId=two", rig.transport.requests.last().headers.first { it.name == "Cookie" }.value)
    }

    @Test
    fun subjectOverridesAreAccountScopedAndSurviveLogout() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        val override = ScheduleSubjectOverride("國語文", customSubjectName = "國文")
        rig.repository.saveSubjectOverrides(session, listOf(override))
        assertEquals(listOf(override), rig.repository.loadSubjectOverrides(session))
        rig.repository.logout()
        assertEquals(listOf(override), rig.cache.loadSubjectOverrides("DEMO-001"))
        assertTrue(rig.cache.loadSubjectOverrides("DEMO-002").isEmpty())
        assertFailsWith<CancellationException> { rig.repository.saveSubjectOverrides(session, emptyList()) }
    }

    // Logout and deletion

    @Test
    fun logoutReportsEveryFailedCleanupAndStillRevokesTheSession() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.repository.fetchGrades(session, "114_1", "期末考")
        secure.failDelete = true
        files.failDeletes += FileStore.PRIVATE

        val error = assertFailsWith<SessionCleanupException> { account(rig, web = false).logout() }
        assertEquals(
            setOf(
                SessionCleanupFailure.SESSION_DELETE_FAILED,
                SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED,
                SessionCleanupFailure.WEB_DATA_DELETE_FAILED,
            ),
            error.failures,
        )
        assertEquals(PocketFailureKind.CLEANUP_INCOMPLETE, PocketFailure.from(error).kind)
        assertEquals(error.failures, PocketFailure.from(error).cleanupFailures)
        assertFalse(rig.vault.isAuthorized(session))
        assertFailsWith<CancellationException> { rig.repository.fetchGrades(session, "114_1", "期末考") }

        // Retrying after the fault is gone completes the cleanup.
        secure.failDelete = false
        files.failDeletes.clear()
        account(rig).logout()
        assertTrue(secure.values.isEmpty())
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
        assertFalse(rig.vault.hasPendingCleanup())
    }

    @Test
    fun deleteAllDataRemovesEverythingOrNamesWhatRemains() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.repository.fetchGrades(session, "114_1", "期末考")
        rig.repository.saveSubjectOverrides(session, listOf(ScheduleSubjectOverride("國語文", customSubjectName = "國文")))
        SettingsStore(files) { 0L }.save(PocketSettings(appLockEnabled = true))
        files.write("${FileStore.PUBLIC}/school-calendar.ics", "x", 0L)
        secure.write(WeatherRepository.CWA_KEY, "key")

        files.failDeletes += FileStore.PUBLIC
        val error = assertFailsWith<SessionCleanupException> { account(rig).deleteAllData() }
        assertEquals(setOf(SessionCleanupFailure.PUBLIC_CACHE_DELETE_FAILED), error.failures)
        assertEquals(setOf("${FileStore.PUBLIC}/school-calendar.ics"), files.files.keys)
        assertTrue(secure.values.isEmpty())

        files.failDeletes.clear()
        account(rig).deleteAllData()
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun deleteAllDataKeepsTheCleanupMarkerWhileCredentialsRemain() = runTest {
        val rig = rig()
        rig.repository.activateSession(rig.vault.establishSession(login))
        SettingsStore(files) { 0L }.save(PocketSettings(appLockEnabled = true))
        secure.failDelete = true
        val error = assertFailsWith<SessionCleanupException> { account(rig).deleteAllData() }
        assertEquals(
            setOf(SessionCleanupFailure.SESSION_DELETE_FAILED, SessionCleanupFailure.CREDENTIAL_DELETE_FAILED),
            error.failures,
        )
        assertEquals(setOf("${FileStore.SETTINGS}/session-cleanup-pending"), files.files.keys)
        assertFailsWith<StorageException> { rig().repository.restoreSession() }
    }
}
