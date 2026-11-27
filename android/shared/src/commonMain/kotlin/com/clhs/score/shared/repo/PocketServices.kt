package com.clhs.score.shared.repo

import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolException
import com.clhs.score.data.SchoolTransientException
import com.clhs.score.shared.net.HttpTransport
import com.clhs.score.shared.net.PortableSchoolClient
import com.clhs.score.shared.net.PublicAnnouncementsClient
import com.clhs.score.shared.net.PublicCalendarClient
import com.clhs.score.shared.net.PublicDataException
import com.clhs.score.shared.net.PublicWeatherClient
import com.clhs.score.shared.net.PublicYouBikeClient
import com.clhs.score.shared.time.currentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** Lets a Swift `Task` cancel the Kotlin call it is awaiting. One token per call. */
class CancelToken {
    internal val job = Job()
    fun cancel() = job.cancel()
}

enum class PocketFailureKind {
    /** The school session is no longer valid; the user must sign in again. */
    AUTHENTICATION_EXPIRED,
    /** Temporary school or network failure; retrying later may work. */
    TRANSIENT,
    /** The school system answered with something the app cannot use. */
    SCHOOL,
    /** A public data source failed. */
    PUBLIC_DATA,
    /** Local storage or Keychain failure. */
    STORAGE,
    /** Logout or deletion left data behind; see [PocketFailure.cleanupFailures]. */
    CLEANUP_INCOMPLETE,
    /** The call was cancelled or its account is no longer the active one. */
    CANCELLED,
    INVALID_INPUT,
    UNKNOWN,
}

class PocketFailure(
    val kind: PocketFailureKind,
    /** zh-TW text that is safe to show; never contains credentials or response bodies. */
    val message: String,
    val cleanupFailures: Set<SessionCleanupFailure> = emptySet(),
) {
    companion object {
        fun from(error: Throwable): PocketFailure = when (error) {
            is CancellationException -> PocketFailure(PocketFailureKind.CANCELLED, "已取消")
            is SchoolAuthenticationException -> PocketFailure(PocketFailureKind.AUTHENTICATION_EXPIRED, error.message ?: "登入狀態已失效")
            is SchoolTransientException -> PocketFailure(PocketFailureKind.TRANSIENT, error.message ?: "學校系統暫時無法回應")
            is SchoolException -> PocketFailure(PocketFailureKind.SCHOOL, error.message ?: "學校系統回應異常")
            is PublicDataException -> PocketFailure(PocketFailureKind.PUBLIC_DATA, "資料暫時無法取得")
            is SessionCleanupException -> PocketFailure(PocketFailureKind.CLEANUP_INCOMPLETE, "部分資料無法清除，請再試一次", error.failures)
            is SessionStorageUnavailableException -> PocketFailure(PocketFailureKind.STORAGE, "上次登出尚未完成清除，請再試一次")
            is SettingsUnreadableException -> PocketFailure(PocketFailureKind.STORAGE, "設定檔已損壞，請重設設定後再試一次")
            is StorageException -> PocketFailure(PocketFailureKind.STORAGE, "無法存取裝置上的資料")
            is SettingsRestoreException -> PocketFailure(
                if (error.cause is StorageException) PocketFailureKind.STORAGE else PocketFailureKind.INVALID_INPUT,
                if (error.rollbackFailed) "設定匯入失敗，且部分設定無法還原" else "設定匯入失敗，原本的設定已保留",
            )
            is IllegalArgumentException -> PocketFailure(PocketFailureKind.INVALID_INPUT, error.message ?: "輸入內容不符")
            else -> PocketFailure(PocketFailureKind.UNKNOWN, "發生未預期的錯誤")
        }
    }
}

/**
 * Composition root for the iOS data layer. Public clients and the school client share one transport
 * but nothing else: the public clients have no reference to the session, the vault or the cookie state.
 */
class PocketServices(
    transport: HttpTransport,
    secureStore: SecureStore,
    fileStore: FileStore,
    webDataCleaner: WebDataCleaner,
) {
    private val now: () -> Long = ::currentEpochMillis
    private val vault = SessionVault(secureStore, fileStore)
    private val privateCache = PrivateCache(fileStore, now)

    val settings = SettingsStore(fileStore, now)
    val school = SchoolRepository(PortableSchoolClient(transport), vault, privateCache)
    val announcements = AnnouncementsRepository(PublicAnnouncementsClient(transport), fileStore, now)
    val calendar = CalendarRepository(PublicCalendarClient(transport), fileStore, now)
    val weather = WeatherRepository(PublicWeatherClient(transport), fileStore, secureStore, now)
    val youBike = YouBikeRepository(PublicYouBikeClient(transport), now)
    val backup = SettingsBackupService(settings, privateCache, vault)
    val account = AccountService(school, privateCache, settings, fileStore, secureStore, webDataCleaner)

    /** True when a previous logout could not delete the stored session and must be retried. */
    @Throws(Exception::class)
    suspend fun hasPendingCleanup(): Boolean = vault.hasPendingCleanup()
}

/**
 * Runs [block] off the caller's thread, cancellable through [token]. Swift-facing suspend functions
 * go through this so storage and parsing never run on the main thread.
 */
suspend fun <T> pocketCall(token: CancelToken?, block: suspend () -> T): T =
    withContext(if (token == null) Dispatchers.Default else Dispatchers.Default + token.job) { block() }
