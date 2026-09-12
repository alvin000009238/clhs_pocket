package com.clhs.score.data

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import com.clhs.score.data.proto.EncryptedSessionPayload
import com.clhs.score.data.proto.SessionStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

private data class SessionStoreDependencies(
    val dataStore: DataStore<SessionStorage>,
    val cipher: SessionCipher,
    val legacySource: LegacySessionSource,
    val biometricStorage: BiometricSessionStorage,
    val recordFailure: (SessionCleanupFailure) -> Unit = {},
)

private fun productionSessionStoreDependencies(context: Context): SessionStoreDependencies {
    val appContext = context.applicationContext
    val legacySource = LegacySessionPreferences(appContext)
    return SessionStoreDependencies(
        dataStore = appContext.sessionDataStore,
        cipher = AesGcmSessionCipher(AndroidKeystoreSessionKeyProvider()),
        legacySource = legacySource,
        biometricStorage = SharedPreferencesBiometricSessionStorage(appContext, legacySource),
        recordFailure = { DeveloperDiagnostics.recordEvent(appContext, "PrivacyCleanup", it.name) },
    )
}

class SessionStore private constructor(
    dependencies: SessionStoreDependencies,
) {
    constructor(context: Context) : this(productionSessionStoreDependencies(context))

    internal constructor(
        dataStore: DataStore<SessionStorage>,
        cipher: SessionCipher,
        legacySource: LegacySessionSource,
        biometricStorage: BiometricSessionStorage,
        recordFailure: (SessionCleanupFailure) -> Unit = {},
    ) : this(SessionStoreDependencies(dataStore, cipher, legacySource, biometricStorage, recordFailure))

    private val dataStore = dependencies.dataStore
    private val cipher = dependencies.cipher
    private val legacySource = dependencies.legacySource
    private val biometricStorage = dependencies.biometricStorage
    private val recordFailure = dependencies.recordFailure

    private val runtime = synchronized(runtimes) { runtimes.getOrPut(dataStore) { RuntimeValidity() } }

    internal val authorizationRevoked: StateFlow<Boolean> get() = runtime.blocked

    internal fun requestGeneration(): Long = runtime.generation.get()

    /** Called only after a new school login; persistence and biometric unlock cannot grant authority. */
    internal suspend fun establishSession(
        session: AuthenticatedSession,
        generation: Long = requestGeneration(),
    ): AuthenticatedSession = persistSession(session, generation, newLogin = true)

    suspend fun saveSession(session: AuthenticatedSession): AuthenticatedSession =
        persistSession(session, requestGeneration(), newLogin = false)

    private suspend fun persistSession(
        session: AuthenticatedSession,
        generation: Long,
        newLogin: Boolean,
    ): AuthenticatedSession {
        return storageMutex.withLock {
            val storage = readStorage()
            if (storage.cleanupPending) throw SessionStorageUnavailableException()
            val authenticated = if (newLogin) {
                session.copy(authorizationId = UUID.randomUUID().toString())
            } else {
                requireValid(storage, session)
                session
            }
            val payload = cipher.encrypt(SessionSerializer.serialize(authenticated), GENERAL_AAD)
            if (generation != runtime.generation.get()) throw CancellationException("Session revoked")
            val saved = updateStorage { current ->
                current.toBuilder()
                    .setGeneralSession(payload.toProto())
                    .setValidity(SessionStorage.Validity.VALID)
                    .setAuthorizationId(authenticated.authorizationId)
                    .setLegacyMigrationComplete(true)
                    .setGeneralLegacyMigrationComplete(true)
                    .setReminderLegacyMigrationComplete(true)
                    .setReminderRevoked(current.authorizationId != authenticated.authorizationId || current.reminderRevoked)
                    .build()
            }
            synchronized(runtime) {
                if (generation != runtime.generation.get()) {
                    throw CancellationException("Session revoked")
                }
                runtime.authorizationId = saved.authorizationId
                runtime.blocked.value = false
            }
            authenticated
        }
    }

    suspend fun loadSession(): AuthenticatedSession? = storageMutex.withLock {
        val storage = readStorage()
        if (!canRestore(storage) || !storage.hasGeneralSession()) return@withLock null
        decodeGeneral(storage.generalSession).also { requireValid(storage, it) }
    }

    suspend fun validateSession(session: AuthenticatedSession) = storageMutex.withLock {
        requireValid(readStorage(), session)
    }

    internal val privateSnapshotAccess = combine(dataStore.data, runtime.blocked) { storage, blocked ->
        !blocked && !storage.cleanupPending && storage.validity == SessionStorage.Validity.VALID &&
            storage.authorizationId.isNotBlank()
    }.retryWhen { error, _ ->
        if (error !is IOException) return@retryWhen false
        recordFailure(SessionCleanupFailure.SESSION_VALIDITY_READ_FAILED)
        try {
            failClosed()
        } catch (failure: SessionCleanupException) {
            failure.failures.forEach(recordFailure)
        }
        emit(false)
        // Retry only after a fresh login has committed authority, never on a timer.
        runtime.blocked.first { !it }
        true
    }

    internal fun <T> withReminderAuthorization(session: AuthenticatedSession, block: () -> T): T =
        synchronized(runtime) {
            if (!isAuthorized(session) || runtime.reminderBlocked) throw CancellationException("Reminder revoked")
            block()
        }

    internal fun isAuthorized(session: AuthenticatedSession): Boolean =
        !runtime.blocked.value && session.authorizationId.isNotEmpty() && runtime.authorizationId == session.authorizationId

    /** Synchronous, shared by all SessionStore instances using the application DataStore. */
    fun revoke() {
        synchronized(runtime) {
            runtime.blocked.value = true
            runtime.authorizationId = null
            runtime.reminderBlocked = true
            runtime.generation.incrementAndGet()
            reminderWriteGeneration.incrementAndGet()
        }
    }

    suspend fun saveReminderSession(session: AuthenticatedSession, expiresAtMillis: Long) {
        val generation = reminderWriteGeneration.get()
        storageMutex.withLock {
            requireValid(readStorage(), session)
            val payload = cipher.encrypt(SessionSerializer.serialize(session, expiresAtMillis), REMINDER_AAD)
            if (generation != reminderWriteGeneration.get() || !isAuthorized(session)) {
                throw CancellationException("Session revoked")
            }
            updateStorage { current ->
                current.toBuilder().setReminderSession(payload.toProto()).setReminderRevoked(false)
                    .setReminderCleanupPending(false).build()
            }
            synchronized(runtime) {
                if (generation != reminderWriteGeneration.get()) throw CancellationException("Reminder revoked")
                runtime.reminderBlocked = false
            }
        }
    }

    suspend fun loadReminderSession(
        nowMillis: Long = System.currentTimeMillis(),
        expectedStudentNo: String? = null,
    ): AuthenticatedSession? = storageMutex.withLock {
        val storage = readStorage()
        if (!canRestore(storage) || runtime.reminderBlocked || storage.reminderRevoked || !storage.hasReminderSession()) return@withLock null
        val reminder = decodeReminder(storage.reminderSession)
        requireValid(storage, reminder.session)
        if (reminder.expiresAtMillis!! <= nowMillis ||
            (expectedStudentNo != null && reminder.session.studentNo != expectedStudentNo)
        ) {
            clearReminderLocked()
            return@withLock null
        }
        reminder.session
    }

    suspend fun clearReminderSession() {
        runtime.reminderBlocked = true
        reminderWriteGeneration.incrementAndGet()
        withContext(NonCancellable) { storageMutex.withLock { clearReminderLocked() } }
    }

    private suspend fun clearReminderLocked() {
        runtime.reminderBlocked = true
        val failures = linkedSetOf<SessionCleanupFailure>()
        cleanupStep(failures, SessionCleanupFailure.REMINDER_REVOCATION_FAILED) {
            updateStorage { it.toBuilder().setReminderRevoked(true).setReminderCleanupPending(true).build() }
        }
        cleanupStep(failures, SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED) {
            updateStorage { it.toBuilder().clearReminderSession().setReminderLegacyMigrationComplete(true).build() }
        }
        cleanupStep(failures, SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED) { legacySource.clearReminder() }
        if (failures.isEmpty()) {
            cleanupStep(failures, SessionCleanupFailure.CLEANUP_STATUS_WRITE_FAILED) {
                updateStorage { it.toBuilder().setReminderCleanupPending(false).build() }
            }
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    suspend fun retryReminderCleanup() {
        if (readStorage().reminderCleanupPending) clearReminderSession()
    }

    suspend fun saveBiometricSession(session: AuthenticatedSession, pin: String, cipher: Cipher) = storageMutex.withLock {
        requireValid(readStorage(), session)
        biometricStorage.save(session, pin, cipher)
    }

    suspend fun loadBiometricSession(cipher: Cipher): AuthenticatedSession? = storageMutex.withLock {
        val storage = readStorage()
        if (!canRestore(storage)) throw SessionCorruptedException("Session authority cannot be verified")
        biometricStorage.load(cipher)?.also { requireValid(storage, it) }
    }

    suspend fun loadSessionWithPin(pin: String): AuthenticatedSession? = storageMutex.withLock {
        val storage = readStorage()
        if (!canRestore(storage)) throw SessionCorruptedException("Session authority cannot be verified")
        biometricStorage.loadWithPin(pin)?.also { requireValid(storage, it) }
    }

    // Presence is only a lock-screen hint. It never establishes authenticated authority.
    fun hasBiometricSession(): Boolean = biometricStorage.hasSession()
    fun getBiometricIv(): ByteArray? = biometricStorage.pinIv()
    fun clearBiometricSession() = biometricStorage.clear()

    suspend fun clearNormalSession() {
        runtime.generation.incrementAndGet()
        storageMutex.withLock {
            val failures = linkedSetOf<SessionCleanupFailure>()
            cleanupStep(failures, SessionCleanupFailure.SESSION_DELETE_FAILED) {
                updateStorage { it.toBuilder().clearGeneralSession().setGeneralLegacyMigrationComplete(true).build() }
            }
            cleanupStep(failures, SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED) { legacySource.clearGeneral() }
            if (failures.isNotEmpty()) throw SessionCleanupException(failures)
        }
    }

    suspend fun clear() {
        revoke()
        withContext(NonCancellable) {
            storageMutex.withLock {
                val failures = linkedSetOf<SessionCleanupFailure>()
                cleanupStep(failures, SessionCleanupFailure.REVOCATION_WRITE_FAILED) {
                    updateStorage { it.toBuilder().setValidity(SessionStorage.Validity.REVOKED).setCleanupPending(true).build() }
                }
                cleanupStep(failures, SessionCleanupFailure.SESSION_DELETE_FAILED) {
                    updateStorage {
                        it.toBuilder().clearGeneralSession().clearReminderSession()
                            .setValidity(SessionStorage.Validity.REVOKED).setReminderRevoked(true)
                            .setCleanupPending(true).setLegacyMigrationComplete(true)
                            .setGeneralLegacyMigrationComplete(true).setReminderLegacyMigrationComplete(true).build()
                    }
                }
                cleanupStep(failures, SessionCleanupFailure.LEGACY_SESSION_DELETE_FAILED) { legacySource.clearAll() }
                cleanupStep(failures, SessionCleanupFailure.BIOMETRIC_DELETE_FAILED) { biometricStorage.clear() }

                if (failures.isNotEmpty()) throw SessionCleanupException(failures)
            }
        }
    }

    suspend fun hasPendingCleanup(): Boolean = readStorage().let {
        it.cleanupPending || it.validity == SessionStorage.Validity.UNKNOWN
    }

    internal suspend fun completeCleanup() = storageMutex.withLock {
        updateStorage { it.toBuilder().setCleanupPending(false).build() }
        Unit
    }

    private fun canRestore(storage: SessionStorage): Boolean {
        if (runtime.blocked.value || storage.cleanupPending || storage.validity != SessionStorage.Validity.VALID || storage.authorizationId.isBlank()) return false
        runtime.authorizationId = storage.authorizationId
        return true
    }

    private fun requireValid(storage: SessionStorage, session: AuthenticatedSession) {
        if (!canRestore(storage) || session.authorizationId != storage.authorizationId) {
            throw SessionCorruptedException("Session authority cannot be verified")
        }
    }

    internal suspend fun cleanupStep(
        failures: MutableSet<SessionCleanupFailure>,
        category: SessionCleanupFailure,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (error: SessionCleanupException) {
            failures += error.failures
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failures += category
        }
    }

    private suspend fun decodeGeneral(payload: EncryptedSessionPayload): AuthenticatedSession {
        try {
            val decoded = SessionSerializer.deserialize(cipher.decrypt(payload.toDomain(), GENERAL_AAD))
            if (decoded.expiresAtMillis != null) {
                throw SessionCorruptedException("General session contains reminder metadata")
            }
            return decoded.session
        } catch (error: SessionStorageException) {
            failClosed()
            throw error
        }
    }

    private suspend fun decodeReminder(payload: EncryptedSessionPayload): DecodedSession {
        val decoded = SessionSerializer.deserialize(cipher.decrypt(payload.toDomain(), REMINDER_AAD))
        if (decoded.expiresAtMillis == null || decoded.expiresAtMillis <= 0L) {
            throw SessionCorruptedException("Reminder session expiry is missing")
        }
        return decoded
    }

    private suspend fun readStorage(): SessionStorage = try {
        dataStore.data.first()
    } catch (error: CorruptionException) {
        failClosed()
        throw SessionCorruptedException("Encrypted session DataStore is corrupted", error)
    } catch (error: IOException) {
        failClosed()
        throw SessionStorageUnavailableException(error)
    }

    private suspend fun updateStorage(transform: (SessionStorage) -> SessionStorage): SessionStorage = try {
        dataStore.updateData(transform)
    } catch (error: CorruptionException) {
        revoke()
        throw SessionCorruptedException("Encrypted session DataStore is corrupted", error)
    } catch (error: IOException) {
        revoke()
        throw SessionStorageUnavailableException(error)
    }

    private suspend fun failClosed() {
        revoke()
        withContext(NonCancellable) {
            try {
                dataStore.updateData {
                    it.toBuilder()
                        .setValidity(
                            if (it.validity == SessionStorage.Validity.REVOKED) it.validity
                            else SessionStorage.Validity.UNKNOWN,
                        )
                        .setCleanupPending(true).build()
                }
            } catch (_: IOException) {
                throw SessionCleanupException(setOf(SessionCleanupFailure.REVOCATION_WRITE_FAILED))
            } finally {
                // A concurrent login must not publish while this failure's invalidation is finishing.
                revoke()
            }
        }
    }

    private class RuntimeValidity {
        val generation = AtomicLong()
        val blocked = MutableStateFlow(false)
        @Volatile var authorizationId: String? = null
        @Volatile var reminderBlocked = false
    }

    private companion object {
        val GENERAL_AAD = "app/session/general/v1".encodeToByteArray()
        val REMINDER_AAD = "app/session/reminder/v1".encodeToByteArray()
        val storageMutex = Mutex()
        val runtimes = WeakHashMap<DataStore<SessionStorage>, RuntimeValidity>()
        val reminderWriteGeneration = AtomicLong()
    }
}

internal enum class SessionCleanupFailure {
    SESSION_VALIDITY_READ_FAILED,
    REVOCATION_WRITE_FAILED,
    SESSION_DELETE_FAILED,
    PRIVATE_CACHE_DELETE_FAILED,
    REMINDER_STATE_DELETE_FAILED,
    BACKGROUND_WORK_CANCEL_FAILED,
    WIDGET_REFRESH_FAILED,
    LEGACY_SESSION_DELETE_FAILED,
    BIOMETRIC_DELETE_FAILED,
    BIOMETRIC_KEY_DELETE_FAILED,
    REMINDER_REVOCATION_FAILED,
    REMINDER_SESSION_DELETE_FAILED,
    CLEANUP_STATUS_WRITE_FAILED,
}

internal class SessionCleanupException(val failures: Set<SessionCleanupFailure>) :
    SessionStorageException(failures.joinToString(",") { it.name })
