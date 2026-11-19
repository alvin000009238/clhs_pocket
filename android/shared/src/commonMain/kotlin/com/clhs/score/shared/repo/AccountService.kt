package com.clhs.score.shared.repo

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Removes cookies, cache and site data of the school web views. Reports the outcome exactly once. */
interface WebDataCleaner {
    fun clear(completion: (Boolean) -> Unit)
}

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
    /** Signs out. Subject overrides and device preferences are kept, like on Android. */
    suspend fun logout() {
        val failures = LinkedHashSet<SessionCleanupFailure>()
        try {
            school.logout()
        } catch (error: SessionCleanupException) {
            failures += error.failures
        }
        if (!clearWebData()) failures += SessionCleanupFailure.WEB_DATA_DELETE_FAILED
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    /** Signs out and removes every local trace: caches, preferences, read state and the weather key. */
    suspend fun deleteAllData() {
        val failures = LinkedHashSet<SessionCleanupFailure>()
        try {
            logout()
        } catch (error: SessionCleanupException) {
            failures += error.failures
        }
        if (!secure.delete(WeatherRepository.CWA_KEY).succeeded) failures += SessionCleanupFailure.CREDENTIAL_DELETE_FAILED
        if (!files.deleteTree(FileStore.PUBLIC).succeeded) failures += SessionCleanupFailure.PUBLIC_CACHE_DELETE_FAILED
        // The settings area also holds the pending-cleanup marker; keep it when credentials remain.
        if (SessionCleanupFailure.SESSION_DELETE_FAILED in failures) {
            runCatching {
                settings.clear()
                cache.clearSubjectOverrides()
            }.onFailure { failures += SessionCleanupFailure.SETTINGS_DELETE_FAILED }
        } else if (!files.deleteTree(FileStore.SETTINGS).succeeded) {
            failures += SessionCleanupFailure.SETTINGS_DELETE_FAILED
        }
        if (failures.isNotEmpty()) throw SessionCleanupException(failures)
    }

    private suspend fun clearWebData(): Boolean = suspendCancellableCoroutine { continuation ->
        web.clear { succeeded -> if (continuation.isActive) continuation.resume(succeeded) }
    }
}
