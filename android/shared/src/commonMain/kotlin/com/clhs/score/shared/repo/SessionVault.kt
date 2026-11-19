@file:OptIn(ExperimentalAtomicApi::class)

package com.clhs.score.shared.repo

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.shared.time.randomUuidString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

enum class SessionCleanupFailure {
    SESSION_DELETE_FAILED,
    PRIVATE_CACHE_DELETE_FAILED,
    WEB_DATA_DELETE_FAILED,
    CREDENTIAL_DELETE_FAILED,
    SETTINGS_DELETE_FAILED,
    PUBLIC_CACHE_DELETE_FAILED,
}

/** Logout or deletion finished with data left behind. The session is already unusable in memory. */
class SessionCleanupException(val failures: Set<SessionCleanupFailure>) :
    Exception("Session cleanup failed: ${failures.joinToString { it.name }}")

/** A previous cleanup has not completed; no new session may be stored until it does. */
class SessionStorageUnavailableException : Exception("Session storage has a pending cleanup")

/**
 * Stored school session and its runtime authority, the counterpart of Android's `SessionStore`.
 *
 * Only [establishSession] (a fresh school login) creates authority. [revoke] removes it in memory
 * first, so every later check fails even when deleting the stored copy does not succeed; a tombstone
 * outside the Keychain then blocks restoring that copy until cleanup has been retried successfully.
 */
class SessionVault(private val secure: SecureStore, private val files: FileStore) {
    @Serializable
    private data class StoredSession(val authorizationId: String, val session: AuthenticatedSession)

    private val json = Json { ignoreUnknownKeys = true }
    private val storageMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val authorizationId = AtomicReference<String?>(null)

    fun requestGeneration(): Long = generation.load()

    /** Called only after a new school login; restoring or unlocking never grants authority. */
    suspend fun establishSession(
        session: AuthenticatedSession,
        expectedGeneration: Long = requestGeneration(),
    ): AuthenticatedSession = storageMutex.withLock {
        if (hasTombstone()) throw SessionStorageUnavailableException()
        val authenticated = session.copy(authorizationId = randomUuidString())
        if (expectedGeneration != generation.load()) throw CancellationException("Session revoked")
        secure.write(SESSION_KEY, json.encodeToString(StoredSession(authenticated.authorizationId, authenticated)))
            .orThrow("Session write")
        if (expectedGeneration != generation.load()) {
            // Revoked while writing: the stored copy must not outlive the revocation.
            if (!secure.delete(SESSION_KEY).succeeded) writeTombstone()
            throw CancellationException("Session revoked")
        }
        authorizationId.store(authenticated.authorizationId)
        authenticated
    }

    /** The stored session, or null. Completes a pending cleanup first and never restores across one. */
    suspend fun loadSession(): AuthenticatedSession? = storageMutex.withLock {
        if (hasTombstone()) {
            deleteStoredSession()
            return@withLock null
        }
        val text = secure.readOrNull(SESSION_KEY) ?: return@withLock null
        val stored = runCatching { json.decodeFromString<StoredSession>(text) }.getOrNull()
        if (stored == null || stored.authorizationId.isBlank() || stored.session.authorizationId != stored.authorizationId) {
            // Unreadable credentials are useless and must not linger.
            deleteStoredSession()
            return@withLock null
        }
        authorizationId.store(stored.authorizationId)
        stored.session
    }

    fun isAuthorized(session: AuthenticatedSession): Boolean =
        session.authorizationId.isNotBlank() && authorizationId.load() == session.authorizationId

    fun validateSession(session: AuthenticatedSession) {
        if (!isAuthorized(session)) throw CancellationException("Session revoked")
    }

    /** Ends the current authority immediately. Stored data is removed by [clear]. */
    fun revoke() {
        generation.incrementAndFetch()
        authorizationId.store(null)
    }

    /** Deletes the stored session. Throws [StorageException] when it could not be removed. */
    suspend fun clear() = storageMutex.withLock {
        revoke()
        deleteStoredSession()
    }

    suspend fun hasPendingCleanup(): Boolean = storageMutex.withLock { hasTombstone() }

    private fun deleteStoredSession() {
        // The tombstone goes first so an interrupted or failed delete is retried on the next load.
        val marked = writeTombstone()
        val deleted = secure.delete(SESSION_KEY).succeeded
        if (!deleted) {
            // Last resort when the item cannot be removed: replace the credentials with nothing.
            if (!marked) secure.write(SESSION_KEY, "")
            throw StorageException("Session delete failed")
        }
        if (marked) files.delete(TOMBSTONE_PATH).orThrow("Cleanup marker delete")
    }

    private fun writeTombstone(): Boolean = files.write(TOMBSTONE_PATH, "1", 0L).succeeded

    private fun hasTombstone(): Boolean = files.read(TOMBSTONE_PATH).orThrow("Cleanup marker read").text != null

    private companion object {
        const val SESSION_KEY = "school.session.v1"
        const val TOMBSTONE_PATH = "${FileStore.SETTINGS}/session-cleanup-pending"
    }
}
