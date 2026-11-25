@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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

        // Another account saving its own list must not replace the first account's.
        val other = ScheduleSubjectOverride("數學", customSubjectName = "數甲")
        rig.cache.saveSubjectOverrides("DEMO-002", listOf(other)) { true }
        assertEquals(listOf(other), rig.cache.loadSubjectOverrides("DEMO-002"))
        assertEquals(listOf(override), rig.cache.loadSubjectOverrides("DEMO-001"))
        rig.cache.clearSubjectOverrides()
        assertTrue(rig.cache.loadSubjectOverrides("DEMO-001").isEmpty() && rig.cache.loadSubjectOverrides("DEMO-002").isEmpty())
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

    // C6: authority, serialization and non-cancellable cleanup

    @Test
    fun restoreThatRacesARevocationGrantsNoAuthority() = runTest {
        val stored = SessionVault(secure, files).establishSession(login)
        val vault = SessionVault(secure, files)
        // Logout revokes while the Keychain read of a restore is still in progress.
        secure.onRead = { vault.revoke() }
        assertNull(vault.loadSession())
        assertFalse(vault.isAuthorized(stored))
    }

    @Test
    fun loginRevokedWhileStoringNeitherGrantsAuthorityNorLeavesItsSession() = runTest {
        val vault = SessionVault(secure, files)
        secure.onWrite = { vault.revoke() }
        assertFailsWith<CancellationException> { vault.establishSession(login) }
        assertTrue(secure.values.isEmpty())
        assertNull(SessionVault(secure, files).loadSession())
    }

    @Test
    fun newLoginRevokesThePreviousAccountBeforeVerifyingCookies() = runTest {
        var rig: Rig? = null
        var previousAuthorizedDuringLogin: Boolean? = null
        var first: AuthenticatedSession? = null
        rig = rig { request ->
            first?.let { previousAuthorizedDuringLogin = rig!!.vault.isAuthorized(it) }
            if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.gradeJson)
        }
        first = rig.repository.loginWithCookies("DEMO-001", mapOf("ASP.NET_SessionId" to "one"))
        rig.repository.fetchGrades(first, "114_1", "期末考")

        val second = rig.repository.loginWithCookies("DEMO-002", mapOf("ASP.NET_SessionId" to "two"))
        assertEquals(false, previousAuthorizedDuringLogin)
        assertFalse(rig.vault.isAuthorized(first))
        assertFailsWith<CancellationException> { rig.repository.fetchGrades(first, "114_1", "期末考") }
        assertTrue(rig.vault.isAuthorized(second))
        assertNull(rig.cache.loadGradeReport("DEMO-001", "114_1", "期末考"))
    }

    @Test
    fun logoutDuringLoginCancelsItAndNothingOfTheNewAccountIsStored() = runTest {
        val rig = rig { response(tokenPage) }
        rig.transport.hold = true
        val pending = async { runCatching { rig.repository.loginWithCookies("DEMO-002", mapOf("ASP.NET_SessionId" to "two")) } }
        runCurrent()
        val call = rig.transport.pending.single()

        val logout = async { account(rig).logout() }
        runCurrent()
        call.respond(response(tokenPage))
        logout.await()
        assertIs<CancellationException>(pending.await().exceptionOrNull())
        assertTrue(secure.values.isEmpty())
        assertNull(SessionVault(secure, files).loadSession())
    }

    @Test
    fun cancellingDeleteAllDataStillCompletesEveryCleanupStep() = runTest {
        val rig = rig()
        rig.repository.activateSession(rig.vault.establishSession(login))
        SettingsStore(files) { 0L }.save(PocketSettings(appLockEnabled = true))
        files.write("${FileStore.PUBLIC}/school-calendar.ics", "x", 0L)
        var finishWebCleanup: ((Boolean) -> Unit)? = null
        val service = AccountService(
            rig.repository, rig.cache, SettingsStore(files) { 0L }, files, secure,
            object : WebDataCleaner {
                override fun clear(completion: (Boolean) -> Unit) {
                    finishWebCleanup = completion
                }
            },
        )
        val deletion = async { service.deleteAllData() }
        runCurrent()
        deletion.cancel()
        runCurrent()
        assertNotNull(finishWebCleanup).invoke(true)
        runCurrent()
        assertTrue(files.files.isEmpty())
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun profilePhotoBelongsToOneAccountAndGoesWithLogout() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.repository.saveProfilePhoto(session, "cGhvdG8=")
        assertEquals("cGhvdG8=", rig.repository.loadProfilePhoto(session))
        account(rig).clearLocalData(LocalDataArea.entries)
        assertEquals("cGhvdG8=", rig.repository.loadProfilePhoto(session), "clearing caches keeps the photo")
        assertNull(rig.cache.loadProfilePhoto("DEMO-002"), "another student never reads it")

        rig.repository.logout()
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
        assertFailsWith<CancellationException> { rig.repository.saveProfilePhoto(session, "bGF0ZQ==") }
        assertTrue(files.area(FileStore.PRIVATE).isEmpty(), "a photo picked before logout is not stored after it")

        val next = rig.vault.establishSession(login.copy(studentNo = "DEMO-002"))
        rig.repository.activateSession(next)
        assertNull(rig.repository.loadProfilePhoto(next))
        rig.repository.saveProfilePhoto(next, "bmV4dA==")
        rig.repository.saveProfilePhoto(next, null)
        assertNull(rig.repository.loadProfilePhoto(next))
    }

    @Test
    fun clearingLocalDataKeepsTheSessionSettingsAndUnchosenAreas() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        SettingsStore(files) { 0L }.save(PocketSettings(appLockEnabled = true))
        listOf("grades/a.json", "simulator/a.json", "structure.json", "student.json", "schedule/a.json", "schedule-latest.json")
            .forEach { files.write("${FileStore.PRIVATE}/$it", "x", 0L) }
        files.write("${FileStore.PUBLIC}/school-calendar.ics", "x", 0L)

        account(rig).clearLocalData(listOf(LocalDataArea.GRADE_CACHE))
        assertEquals(setOf("${FileStore.PRIVATE}/schedule/a.json", "${FileStore.PRIVATE}/schedule-latest.json"), files.area(FileStore.PRIVATE))
        assertEquals(1, files.area(FileStore.PUBLIC).size)
        assertTrue(rig.vault.isAuthorized(session))
        assertTrue(SettingsStore(files) { 0L }.load().appLockEnabled)

        account(rig).clearLocalData(listOf(LocalDataArea.SCHEDULE_CACHE, LocalDataArea.PUBLIC_CACHE))
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
        assertTrue(files.area(FileStore.PUBLIC).isEmpty())
        assertEquals(session, SessionVault(secure, files).loadSession())
    }

    @Test
    fun aFailedLocalDataClearIsReportedAndTheOtherAreasAreStillCleared() = runTest {
        val rig = rig()
        files.write("${FileStore.PRIVATE}/grades/a.json", "x", 0L)
        files.write("${FileStore.PUBLIC}/school-calendar.ics", "x", 0L)
        files.failDeletes += "${FileStore.PRIVATE}/grades"
        val error = assertFailsWith<SessionCleanupException> {
            account(rig, web = false).clearLocalData(LocalDataArea.entries)
        }
        assertEquals(
            setOf(SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED, SessionCleanupFailure.WEB_DATA_DELETE_FAILED),
            error.failures,
        )
        assertTrue(files.area(FileStore.PUBLIC).isEmpty())
    }

    @Test
    fun switchAccountSignsOutCompletelyBeforeTheNewLogin() = runTest {
        val rig = rig { request -> if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.gradeJson) }
        val first = rig.repository.loginWithCookies("DEMO-001", mapOf("ASP.NET_SessionId" to "one"))
        rig.repository.fetchGrades(first, "114_1", "期末考")
        val second = account(rig).switchAccount("DEMO-002", mapOf("ASP.NET_SessionId" to "two"))
        assertEquals("DEMO-002", second.studentNo)
        assertFalse(rig.vault.isAuthorized(first))
        assertTrue(rig.vault.isAuthorized(second))
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())

        // A failed cleanup stops the switch: the next account is not stored.
        secure.failDelete = true
        assertFailsWith<SessionCleanupException> { account(rig).switchAccount("DEMO-003", mapOf("ASP.NET_SessionId" to "three")) }
        assertFalse(rig.vault.isAuthorized(second))
        secure.failDelete = false
        assertNull(SessionVault(secure, files).loadSession())
    }

    // H10: schedule cache and private cache contract

    @Test
    fun semesterOrOtherClassTimetablesNeverReplaceTheLatestSchedule() = runTest {
        val rig = rig { request -> if (request.method == "GET") response(tokenPage) else response(SchoolFixtures.scheduleJson) }
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.repository.fetchSchedule(session, "114_1", "114", "1", "", com.clhs.score.data.ScheduleScope.SEMESTER)
        rig.repository.fetchSchedule(session, "114_1", "114", "1", "230", com.clhs.score.data.ScheduleScope.SEMESTER)
        assertNull(rig.repository.loadLatestSchedule(session))
        assertNotNull(rig.repository.loadCachedSchedule(session, "114_1", "230", com.clhs.score.data.ScheduleScope.SEMESTER, null))
    }

    @Test
    fun unreadableSchoolCacheIsAMissButUnreadableUserSettingsAreErrors() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.repository.fetchGrades(session, "114_1", "期末考")
        val requests = rig.transport.requests.size

        files.failReads += "${FileStore.PRIVATE}/grades"
        rig.repository.fetchGrades(session, "114_1", "期末考")
        assertEquals(requests + 1, rig.transport.requests.size, "a failed cache read falls back to the network")
        files.failWrites += "${FileStore.PRIVATE}/grades"
        rig.repository.fetchGrades(session, "114_1", "期末考")
        files.failReads.clear()
        files.failWrites.clear()

        rig.repository.saveSimulatorCredits(session, "114_1", "期末考", "{}")
        assertEquals("{}", rig.repository.loadSimulatorCredits(session, "114_1", "期末考"))
        val simulator = files.files.keys.single { it.startsWith("${FileStore.PRIVATE}/simulator/") }
        files.files[simulator] = "{broken" to 0L
        assertFailsWith<StorageException> { rig.repository.loadSimulatorCredits(session, "114_1", "期末考") }
        files.failReads += simulator
        assertFailsWith<StorageException> { rig.repository.loadSimulatorCredits(session, "114_1", "期末考") }
        files.failReads.clear()

        rig.repository.saveSubjectOverrides(session, listOf(ScheduleSubjectOverride("國語文", customSubjectName = "國文")))
        files.failReads += "${FileStore.SETTINGS}/subject-overrides"
        assertFailsWith<StorageException> { rig.repository.loadSubjectOverrides(session) }
    }

    @Test
    fun writeThatPassedItsGuardCannotResurrectDataAfterLogout() = runTest {
        val rig = rig()
        val session = rig.vault.establishSession(login)
        rig.repository.activateSession(session)
        rig.transport.hold = true
        val late = async { runCatching { rig.repository.fetchGrades(session, "114_1", "期末考") } }
        runCurrent()
        // Logout starts while the response is being written: its cache deletion runs after the write.
        files.onWrite["${FileStore.PRIVATE}/grades"] = { rig.repository.revoke() }
        rig.transport.pending.single().respond(response(SchoolFixtures.gradeJson))
        runCurrent()
        rig.repository.logout()
        assertIs<CancellationException>(late.await().exceptionOrNull())
        assertTrue(files.area(FileStore.PRIVATE).isEmpty())
    }
}
