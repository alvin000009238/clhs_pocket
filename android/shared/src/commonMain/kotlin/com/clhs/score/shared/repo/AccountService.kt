package com.clhs.score.shared.repo

import com.clhs.score.data.AuthenticatedSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Removes cookies, cache and site data of the school web views. Reports the outcome exactly once. */
interface WebDataCleaner {
    fun clear(completion: (Boolean) -> Unit)
}

/** A part of the device storage that 開發者選項 can clear without signing out. */
enum class LocalDataArea { GRADE_CACHE, SCHEDULE_CACHE, PUBLIC_CACHE, WEB_DATA }

/**
 * Account lifecycle. Every operation revokes the in-memory session before touching storage and
 * throws [SessionCleanupException] listing what could not be removed, so a failed cleanup is never
 * reported as a successful logout.
 */
class AccountService(
    private val school: SchoolRepository,
    private val cache: PrivateCache,
    private val settings: SettingsStore,
    private val files: FileStore,
    private val secure: SecureStore,
    private val web: WebDataCleaner,
) {
    private val lifecycle = Mutex()

    /** Signs out. Subject overrides and device preferences are kept, like on Android. */
    suspend fun logout() {
        school.revoke()
        withContext(NonCancellable) { lifecycle.withLock { signOut() } }
    }

    /**
     * Signs the current account out completely and then verifies the new account's cookies, as one
     * serialized step: a cleanup failure stops the switch before anything of the new account is stored.
     */
    suspend fun switchAccount(studentNo: String, cookies: Map<String, String>): AuthenticatedSession =
        lifecycle.withLock {
            withContext(NonCancellable) { signOut() }
            school.loginWithCookies(studentNo, cookies)
        }

    /**
     * Signs out and removes every local trace: caches, preferences, read state, bookmarks and the
     * weather key. Every item is attempted even when an earlier one failed.
     */
    suspend fun deleteAllData() {
        school.revoke()
        withContext(NonCancellable) { lifecycle.withLock { deleteEverything() } }
    }

    /**
     * Clears the chosen caches and keeps the session, settings and the user's own edits. Every
     * area is attempted; anything left behind is thrown as [SessionCleanupException], so a partial
     * cleanup is never reported as done. Writes still guarded by the account generation may refill
     * the caches for the signed-in student afterwards, as on Android.
     */
    suspend fun clearLocalData(areas: List<LocalDataArea>) {
        withContext(NonCancellable) {
            lifecycle.withLock {
                val failures = LinkedHashSet<SessionCleanupFailure>()
                if (LocalDataArea.GRADE_CACHE in areas && !attempt(cache::clearGradeData)) {
                    failures += SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED
                }
                if (LocalDataArea.SCHEDULE_CACHE in areas && !attempt(cache::clearScheduleData)) {
                    failures += SessionCleanupFailure.PRIVATE_CACHE_DELETE_FAILED
                }
                if (LocalDataArea.PUBLIC_CACHE in areas && !files.deleteTree(FileStore.PUBLIC).succeeded) {
                    failures += SessionCleanupFailure.PUBLIC_CACHE_DELETE_FAILED
                }
                if (LocalDataArea.WEB_DATA in areas && !clearWebData()) failures += SessionCleanupFailure.WEB_DATA_DELETE_FAILED
                if (failures.isNotEmpty()) throw SessionCleanupException(failures)
            }
        }
    }

    private suspend fun deleteEverything() {
        val failures = LinkedHashSet<SessionCleanupFailure>()
        try {
            signOut()
        } catch (error: SessionCleanupException) {
            failures += error.failures
        }
        if (!secure.delete(WeatherRepository.CWA_KEY).succeeded) failures += SessionCleanupFailure.CREDENTIAL_DELETE_FAILED
        if (!files.deleteTree(FileStore.PUBLIC).succeeded) failures += SessionCleanupFailure.PUBLIC_CACHE_DELETE_FAILED
        // The settings area also holds the pending-cleanup marker; keep it when credentials remain.
        val settingsDeleted = if (SessionCleanupFailure.SESSION_DELETE_FAILED in failures) {
            listOf(
                attempt(settings::clear),
                attempt(cache::clearSubjectOverrides),
                files.delete(AnnouncementsRepository.READ_STATE).succeeded,
                files.delete(AnnouncementsRepository.BOOKMARKS).succeeded,
            ).all { it }
        } else {
            files.deleteTree(FileStore.SETTINGS).succeeded
        }
        if (!settingsDeleted) failures += SessionCleanupFailure.SETTINGS_DELETE_FAILED
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    private suspend fun signOut() {
        val failures = LinkedHashSet<SessionCleanupFailure>()
        try {
            school.logout()
        } catch (error: SessionCleanupException) {
            failures += error.failures
        }
        if (!clearWebData()) failures += SessionCleanupFailure.WEB_DATA_DELETE_FAILED
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    private suspend fun attempt(delete: suspend () -> Unit): Boolean = try {
        delete()
        true
    } catch (_: StorageException) {
        false
    }

    /** A cleaner that never reports back counts as a failure instead of blocking cleanup forever. */
    private suspend fun clearWebData(): Boolean = withTimeoutOrNull(WEB_DATA_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            web.clear { succeeded -> if (continuation.isActive) continuation.resume(succeeded) }
        }
    } ?: false

    private companion object {
        const val WEB_DATA_TIMEOUT_MILLIS = 15_000L
    }
}
