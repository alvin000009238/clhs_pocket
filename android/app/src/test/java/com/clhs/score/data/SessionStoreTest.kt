package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.clhs.score.data.proto.EncryptedSessionPayload
import com.clhs.score.data.proto.SessionStorage
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class SessionStoreTest {
    @Test
    fun legacyDeletionFailureIsObservableAndRetried() {
        var attempts = 0
        val legacy = LegacySessionPreferences { ++attempts > 1 }
        val failure = org.junit.Assert.assertThrows(SessionCleanupException::class.java) { legacy.clearAll() }
        assertEquals(setOf(SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED), failure.failures)
        legacy.clearAll()
        legacy.clearGeneral()
        legacy.clearReminder()
        legacy.clearBiometric()
        assertEquals(5, attempts)
    }

    @Test
    fun legacyDeletionSecurityFailureDoesNotLeakExceptionDetails() {
        val legacy = LegacySessionPreferences { throw SecurityException("private injected content") }
        val failure = org.junit.Assert.assertThrows(SessionCleanupException::class.java) { legacy.clearAll() }
        assertEquals("LEGACY_SESSION_DELETE_FAILED", failure.message)
        assertNull(failure.cause)
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cipher = AesGcmSessionCipher(FixedKeyProvider())
    private val sessionA = AuthenticatedSession("A001", "token-a", mapOf("sid" to "cookie-a"))
    private val sessionB = AuthenticatedSession("B002", "token-b", mapOf("sid" to "cookie-b"))

    @Test
    fun generalAndReminderSessionsStaySeparated() = runTest {
        val store = store()

        val authorized = store.establishSession(sessionA)
        store.saveReminderSession(authorized, expiresAtMillis = 2_000L)
        store.clearNormalSession()

        assertNull(store.loadSession())
        assertEquals(authorized, store.loadReminderSession(nowMillis = 1_000L))
    }

    @Test
    fun deleteAndLogoutClearEncryptedSessions() = runTest {
        val legacy = FakeLegacySource(general = sessionA, reminder = sessionB)
        val store = store(legacy = legacy)
        store.loadSession()

        store.clearReminderSession()
        assertNull(store.loadReminderSession(nowMillis = 1_000L))

        store.clear()
        assertNull(store.loadSession())
        assertTrue(legacy.clearAllCalled)
    }

    @Test
    fun expiredReminderIsDeleted() = runTest {
        val store = store()
        store.saveReminderSession(store.establishSession(sessionA), expiresAtMillis = 1_000L)

        assertNull(store.loadReminderSession(nowMillis = 1_000L))
        assertNull(store.loadReminderSession(nowMillis = 999L))
    }

    @Test
    fun studentMismatchDeletesReminder() = runTest {
        val store = store()
        store.saveReminderSession(store.establishSession(sessionA), expiresAtMillis = 2_000L)

        assertNull(
            store.loadReminderSession(
                nowMillis = 1_000L,
                expectedStudentNo = sessionB.studentNo,
            ),
        )
        assertNull(store.loadReminderSession(nowMillis = 1_000L))
    }

    @Test
    fun normalSessionClearCannotReimportLeftoverLegacyData() = runTest {
        val legacy = FakeLegacySource(general = sessionA, retainAfterClear = true)
        val store = store(legacy = legacy)

        store.clearNormalSession()

        assertNull(store.loadSession())
    }

    @Test
    fun normalSessionClearDoesNotReadDataItIsDeleting() = runTest {
        val legacy = FakeLegacySource(
            general = sessionA,
        )
        val store = store(legacy = legacy)

        store.clearNormalSession()

        assertTrue(legacy.generalCleared)
        assertNull(store.loadSession())
    }

    @Test
    fun corruptNewPayloadCannotRecoverAuthorityFromLegacy() = runTest {
        val dataStore = dataStore()
        dataStore.updateData {
            it.toBuilder().setGeneralSession(corruptPayload()).build()
        }
        val legacy = FakeLegacySource(general = sessionA)
        val store = store(dataStore = dataStore, legacy = legacy)

        assertNull(store.loadSession())
    }

    @Test
    fun corruptNewPayloadWithoutLegacyIsReported() = runTest {
        val dataStore = dataStore()
        dataStore.updateData {
            it.toBuilder().setGeneralSession(corruptPayload()).build()
        }

        assertNull(store(dataStore = dataStore).loadSession())
    }

    @Test
    fun logoutInvalidatesAnOlderPendingSave() = runTest {
        val encryptStarted = CompletableDeferred<Unit>()
        val releaseEncrypt = CompletableDeferred<Unit>()
        val blockingCipher = object : SessionCipher {
            override suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedPayload {
                encryptStarted.complete(Unit)
                releaseEncrypt.await()
                return cipher.encrypt(plaintext, associatedData)
            }

            override suspend fun decrypt(payload: EncryptedPayload, associatedData: ByteArray): ByteArray =
                cipher.decrypt(payload, associatedData)
        }
        val store = store(cipher = blockingCipher)

        val save = async { runCatching { store.establishSession(sessionA) } }
        encryptStarted.await()
        val logout = async { store.clear() }
        releaseEncrypt.complete(Unit)
        assertTrue(save.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        logout.await()

        assertNull(store.loadSession())
    }

    @Test
    fun failedPhysicalDeletionDoesNotRestoreSessionInRecreatedOwner() = runTest {
        val disk = dataStore()
        val failingDeletes = object : DataStore<SessionStorage> {
            override val data = disk.data
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                disk.updateData { before ->
                    val after = transform(before)
                    if (before.hasGeneralSession() && !after.hasGeneralSession()) {
                        throw java.io.IOException("injected deletion failure")
                    }
                    after
                }
        }
        val original = store(dataStore = failingDeletes)
        original.establishSession(sessionA)

        val failure = runCatching { original.clear() }.exceptionOrNull()

        assertTrue("The physical deletion failure must remain observable", failure is SessionStorageException)
        assertTrue("Fault injection must leave encrypted bytes on disk", disk.data.first().hasGeneralSession())
        // A different DataStore identity prevents the process-local guard from making this pass.
        val recreated = store(dataStore = disk)
        assertNull("A recreated owner must reject the revoked encrypted session", recreated.loadSession())
        assertTrue(disk.data.first().cleanupPending)
        assertEquals(SessionStorage.Validity.REVOKED, disk.data.first().validity)
    }

    @Test
    fun encryptedDeletionFailureStillAttemptsLegacyAndBiometricCleanup() = runTest {
        val disk = dataStore()
        var failWrites = false
        val failingStore = object : DataStore<SessionStorage> {
            override val data = disk.data
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage {
                if (failWrites) throw java.io.IOException("injected storage failure")
                return disk.updateData(transform)
            }
        }
        val legacy = FakeLegacySource()
        var biometricCleanupAttempted = false
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun clear() {
                biometricCleanupAttempted = true
            }
        }
        val original = SessionStore(failingStore, cipher, legacy, biometric)
        original.establishSession(sessionA)
        failWrites = true

        val failure = runCatching { original.clear() }.exceptionOrNull()

        assertTrue(failure is SessionStorageException)
        assertTrue("Encrypted storage failure must not skip legacy cleanup", legacy.clearAllCalled)
        assertTrue("Encrypted storage failure must not skip biometric cleanup", biometricCleanupAttempted)
    }

    @Test
    fun validSessionRestoresAfterDataStoreAndOwnerRecreation() = runTest {
        val file = File(temporaryFolder.root, "recreation.pb")
        val firstScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val firstDisk = DataStoreFactory.create(SessionStorageSerializer, scope = firstScope) { file }
        val authorized = store(dataStore = firstDisk).establishSession(sessionA)
        firstScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        firstScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        val secondScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val secondDisk = DataStoreFactory.create(SessionStorageSerializer, scope = secondScope) { file }
            assertEquals(authorized, store(dataStore = secondDisk).loadSession())
        } finally {
            secondScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            secondScope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        }
    }

    @Test
    fun biometricMaterialWithoutValidityEvidenceCannotAuthorize() = runTest {
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun loadWithPin(pin: String) = sessionA
            override fun load(cipher: Cipher) = sessionA
        }
        val original = SessionStore(dataStore(), cipher, FakeLegacySource(), biometric)
        val pinResult = runCatching { original.loadSessionWithPin("1234") }
        val biometricResult = runCatching { original.loadBiometricSession(Cipher.getInstance("AES/GCM/NoPadding")) }
        assertTrue("UNKNOWN cannot be authorized by PIN", pinResult.getOrNull() == null)
        assertTrue("UNKNOWN cannot be authorized by biometric", biometricResult.getOrNull() == null)
        assertTrue(pinResult.exceptionOrNull() == null || pinResult.exceptionOrNull() is SessionStorageException)
        assertTrue(biometricResult.exceptionOrNull() == null || biometricResult.exceptionOrNull() is SessionStorageException)
    }

    @Test
    fun biometricMaterialCannotAuthorizeAfterRevocationAndFailedCleanup() = runTest {
        val disk = dataStore()
        var authorized = sessionA
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun loadWithPin(pin: String) = authorized
            override fun load(cipher: Cipher) = authorized
            override fun clear() { throw SessionStorageUnavailableException() }
        }
        val original = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        authorized = original.establishSession(sessionA)
        assertEquals(authorized, original.loadSessionWithPin("1234"))
        assertTrue(runCatching { original.clear() }.exceptionOrNull() is SessionStorageException)
        val recreated = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        val pinResult = runCatching { recreated.loadSessionWithPin("1234") }
        val biometricResult = runCatching { recreated.loadBiometricSession(Cipher.getInstance("AES/GCM/NoPadding")) }
        assertTrue(pinResult.getOrNull() == null)
        assertTrue(biometricResult.getOrNull() == null)
        assertTrue(pinResult.exceptionOrNull() == null || pinResult.exceptionOrNull() is SessionStorageException)
        assertTrue(biometricResult.exceptionOrNull() == null || biometricResult.exceptionOrNull() is SessionStorageException)
    }

    @Test
    fun unreadableValidityCannotFallBackToBiometricMaterial() = runTest {
        val disk = object : DataStore<SessionStorage> {
            override val data = kotlinx.coroutines.flow.flow<SessionStorage> {
                throw androidx.datastore.core.CorruptionException("injected corruption")
            }
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                throw java.io.IOException("injected unavailable storage")
        }
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun loadWithPin(pin: String) = sessionA
        }
        val original = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        assertTrue(runCatching { original.loadSession() }.exceptionOrNull() is SessionStorageException)
        val result = runCatching { original.loadSessionWithPin("1234") }
        assertTrue(result.getOrNull() == null)
        assertTrue(result.exceptionOrNull() == null || result.exceptionOrNull() is SessionStorageException)
    }

    @Test
    fun validBiometricCredentialSurvivesNormalSessionDeletion() = runTest {
        val disk = dataStore()
        var authorized = sessionA
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun loadWithPin(pin: String) = authorized
            override fun load(cipher: Cipher) = authorized
        }
        val original = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        authorized = original.establishSession(sessionA)
        original.clearNormalSession()
        val recreated = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        assertNull(recreated.loadSession())
        assertEquals(authorized, recreated.loadSessionWithPin("1234"))
        assertEquals(authorized, recreated.loadBiometricSession(Cipher.getInstance("AES/GCM/NoPadding")))
    }

    @Test
    fun unlockedOldCredentialCannotRenewRevokedAuthorityBySaving() = runTest {
        val original = store()
        val authorized = original.establishSession(sessionA)
        original.clear()
        assertTrue(runCatching { original.saveSession(authorized) }.exceptionOrNull() is SessionStorageException)
        assertNull(original.loadSession())
    }

    @Test
    fun corruptPayloadMarksValidityUnknownAndBlocksMatchingBiometricCredential() = runTest {
        val disk = dataStore()
        var authorized = sessionA
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun loadWithPin(pin: String) = authorized
        }
        val original = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        authorized = original.establishSession(sessionA)
        disk.updateData { it.toBuilder().setGeneralSession(corruptPayload()).build() }
        assertTrue(runCatching { original.loadSession() }.exceptionOrNull() is SessionStorageException)
        assertEquals(SessionStorage.Validity.UNKNOWN, disk.data.first().validity)
        val recreatedDisk = object : DataStore<SessionStorage> by disk {}
        assertTrue(runCatching { SessionStore(recreatedDisk, cipher, FakeLegacySource(), biometric).loadSessionWithPin("1234") }.exceptionOrNull() is SessionStorageException)
    }

    @Test
    fun pendingCleanupRetriesWithoutRestoringAuthority() = runTest {
        val disk = dataStore()
        var failCleanup = true
        var attempts = 0
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun clear() {
                attempts++
                if (failCleanup) throw SessionStorageUnavailableException()
            }
        }
        val original = SessionStore(disk, cipher, FakeLegacySource(), biometric)
        original.establishSession(sessionA)
        val failure = runCatching { original.clear() }.exceptionOrNull() as SessionCleanupException
        assertEquals(setOf(SessionCleanupFailure.BIOMETRIC_DELETE_FAILED), failure.failures)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        failCleanup = false
        original.clear()
        original.completeCleanup()
        assertEquals(2, attempts)
        assertFalse(disk.data.first().cleanupPending)
        assertNull(original.loadSession())
    }

    @Test
    fun revokedBytesRemainRejectedAfterClosingAndReopeningDataStore() = runTest {
        val file = File(temporaryFolder.root, "revoked-recreation.pb")
        val firstJob = kotlinx.coroutines.SupervisorJob()
        val firstDisk = DataStoreFactory.create(
            SessionStorageSerializer,
            scope = kotlinx.coroutines.CoroutineScope(firstJob + kotlinx.coroutines.Dispatchers.IO),
        ) { file }
        val failedDelete = object : DataStore<SessionStorage> by firstDisk {
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                firstDisk.updateData { before ->
                    val after = transform(before)
                    if (before.hasGeneralSession() && !after.hasGeneralSession()) throw java.io.IOException("injected delete")
                    after
                }
        }
        try {
            val original = store(dataStore = failedDelete)
            original.establishSession(sessionA)
            assertTrue(runCatching { original.clear() }.exceptionOrNull() is SessionCleanupException)
            assertTrue(firstDisk.data.first().hasGeneralSession())
        } finally {
            firstJob.cancel()
            firstJob.join()
        }
        val secondJob = kotlinx.coroutines.SupervisorJob()
        try {
            val secondDisk = DataStoreFactory.create(
                SessionStorageSerializer,
                scope = kotlinx.coroutines.CoroutineScope(secondJob + kotlinx.coroutines.Dispatchers.IO),
            ) { file }
            assertNull(store(dataStore = secondDisk).loadSession())
        } finally {
            secondJob.cancel()
            secondJob.join()
        }
    }

    @Test
    fun logoutRejectsLateResponseAndNewRequestsAcrossRepositoryInstances() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer()
        val arrived = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                arrived.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return okhttp3.mockwebserver.MockResponse().setBody(
                    """<script>const userInfo = JSON.parse('{"ClassNo":"230","ClassName":"Demo","SeatNo":"12","No":"A001","UserName":"Demo","Year":115,"Term":1}');</script>""",
                )
            }
        }
        server.start()
        try {
            val original = store()
            val authorized = original.establishSession(sessionA)
            val cacheDisk = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = backgroundScope,
            ) { File(temporaryFolder.root, "late-response.preferences_pb") }
            val cache = GradeCacheStore(cacheDisk) {}
            fun repository() = SchoolGradeRepository(
                SchoolGradeClient(baseUrl = server.url("/").toString()), original, cache,
            )
            val workerRepository = repository()
            val response = async { runCatching { workerRepository.fetchStudentInfo(authorized) } }
            arrived.await()
            repository().logout(authorized)
            release.countDown()
            assertTrue(response.await().exceptionOrNull() is SessionStorageException)
            assertNull(cache.loadStudentInfo(sessionA.studentNo))
            assertTrue(runCatching { workerRepository.fetchStudentInfo(authorized) }.exceptionOrNull() is SessionStorageException)
            assertEquals(1, server.requestCount)
        } finally {
            release.countDown()
            server.shutdown()
        }
    }

    @Test
    fun revokedReminderCannotPublishEvenWhenItsEncryptedDeletionFails() = runTest {
        val disk = dataStore()
        val failedDelete = object : DataStore<SessionStorage> by disk {
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                disk.updateData { before ->
                    val after = transform(before)
                    if (before.hasReminderSession() && !after.hasReminderSession()) throw java.io.IOException("injected delete")
                    after
                }
        }
        val original = store(dataStore = failedDelete)
        val authorized = original.establishSession(sessionA)
        original.saveReminderSession(authorized, 2000L)
        assertEquals(authorized, original.loadReminderSession(1000L))
        assertTrue(runCatching { original.clearReminderSession() }.exceptionOrNull() is SessionCleanupException)
        var published = false
        val result = runCatching { original.withReminderAuthorization(authorized) { published = true } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(published)
        assertNull(store(dataStore = disk).loadReminderSession(1000L))
        assertTrue(disk.data.first().reminderCleanupPending)
    }

    @Test
    fun simultaneousSessionAndCacheFailuresAreAggregatedWithoutSensitiveCauses() = runTest {
        val disk = dataStore()
        val failingDelete = object : DataStore<SessionStorage> by disk {
            override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                disk.updateData { before ->
                    val after = transform(before)
                    if (before.hasGeneralSession() && !after.hasGeneralSession()) throw java.io.IOException("sensitive injected content")
                    after
                }
        }
        var biometricAttempted = false
        val biometric = object : BiometricSessionStorage by NoOpBiometricStorage {
            override fun clear() {
                biometricAttempted = true
                throw SessionStorageUnavailableException(java.io.IOException("sensitive injected content"))
            }
        }
        val legacy = FakeLegacySource()
        val original = SessionStore(failingDelete, cipher, legacy, biometric)
        val authorized = original.establishSession(sessionA)
        var cacheAttempted = false
        val cacheDisk = object : DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ): androidx.datastore.preferences.core.Preferences {
                cacheAttempted = true
                throw java.io.IOException("sensitive injected content")
            }
        }
        val repository = SchoolGradeRepository(SchoolGradeClient(), original, GradeCacheStore(cacheDisk) {})
        val failure = runCatching { repository.logout(authorized) }.exceptionOrNull() as SessionCleanupException
        assertTrue(legacy.clearAllCalled)
        assertTrue(biometricAttempted)
        assertTrue(cacheAttempted)
        assertEquals(setOf(
            SessionCleanupFailure.SESSION_DELETE_FAILED,
            SessionCleanupFailure.BIOMETRIC_DELETE_FAILED,
            SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED,
        ), failure.failures)
        assertFalse(failure.toString().contains("sensitive"))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertNull(store(dataStore = disk).loadSession())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun startupPublishesGuestBeforePendingCleanupFinishes() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val cleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cleanupStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            val disk = object : DataStore<SessionStorage> {
                override val data = kotlinx.coroutines.flow.MutableStateFlow(SessionStorage.getDefaultInstance())
                override suspend fun updateData(transform: suspend (SessionStorage) -> SessionStorage): SessionStorage =
                    transform(data.value).also { data.value = it }
            }
            val sessionStore = store(dataStore = disk)
            sessionStore.establishSession(sessionA)
            sessionStore.clear()
            val repository = object : GradeRepository by FakeGradeRepository() {
                override suspend fun restoreSession(): AuthenticatedSession? =
                    error("Revoked session must not be restored")

                override suspend fun logout(currentSession: AuthenticatedSession?) {
                    cleanupStarted.complete(Unit)
                    cleanup.await()
                }
            }
            val viewModel = com.clhs.score.viewmodel.ScoreViewModel(repository, sessionStore = sessionStore)
            runCurrent()
            cleanupStarted.await()
            assertEquals(com.clhs.score.viewmodel.AuthState.Guest, viewModel.authState.value)
            assertNull(viewModel.getCurrentSession())
            assertFalse(cleanup.isCompleted)
        } finally {
            cleanup.complete(Unit)
            advanceUntilIdle()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    private fun store(
        dataStore: DataStore<SessionStorage> = dataStore(),
        cipher: SessionCipher = this.cipher,
        legacy: FakeLegacySource = FakeLegacySource(),
    ): SessionStore = SessionStore(
        dataStore = dataStore,
        cipher = cipher,
        legacySource = legacy,
        biometricStorage = NoOpBiometricStorage,
    )

    private fun dataStore(): DataStore<SessionStorage> {
        val file = File(temporaryFolder.root, "session-${fileCounter.incrementAndGet()}.pb")
        return DataStoreFactory.create(
            serializer = SessionStorageSerializer,
            produceFile = { file },
        )
    }

    private fun corruptPayload(): EncryptedSessionPayload = EncryptedSessionPayload.newBuilder()
        .setVersion(AesGcmSessionCipher.CURRENT_PAYLOAD_VERSION)
        .setKeyVersion(AesGcmSessionCipher.CURRENT_KEY_VERSION)
        .setIv(ByteString.copyFrom(ByteArray(AesGcmSessionCipher.IV_SIZE_BYTES)))
        .setCiphertext(ByteString.copyFrom(ByteArray(AesGcmSessionCipher.TAG_SIZE_BYTES)))
        .build()

    private class FixedKeyProvider : SessionKeyProvider {
        private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

        override fun getOrCreateEncryptionKey(version: Int): SecretKey = key

        override fun getDecryptionKey(version: Int): SecretKey = key
    }

    private class FakeLegacySource(
        var general: AuthenticatedSession? = null,
        var reminder: AuthenticatedSession? = null,
        private val generalClearFailure: SessionStorageException? = null,
        private val reminderClearFailure: SessionStorageException? = null,
        private val retainAfterClear: Boolean = false,
    ) : LegacySessionSource {
        var generalCleared = false
        var reminderCleared = false
        var biometricCleared = false
        var clearAllCalled = false

        override fun clearGeneral() {
            generalClearFailure?.let { throw it }
            generalCleared = true
            if (!retainAfterClear) general = null
        }

        override fun clearReminder() {
            reminderClearFailure?.let { throw it }
            reminderCleared = true
            if (!retainAfterClear) reminder = null
        }

        override fun clearBiometric() {
            biometricCleared = true
        }

        override fun clearAll() {
            clearAllCalled = true
            clearGeneral()
            clearReminder()
            clearBiometric()
        }
    }

    private object NoOpBiometricStorage : BiometricSessionStorage {
        override fun save(session: AuthenticatedSession, pin: String, cipher: Cipher) = Unit
        override fun load(cipher: Cipher): AuthenticatedSession? = null
        override fun loadWithPin(pin: String): AuthenticatedSession? = null
        override fun hasSession(): Boolean = false
        override fun pinIv(): ByteArray? = null
        override fun clear() = Unit
    }

    private companion object {
        val fileCounter = AtomicInteger()
        val GENERAL_AAD = "app/session/general/v1".encodeToByteArray()
        val REMINDER_AAD = "app/session/reminder/v1".encodeToByteArray()
    }
}
