package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.BuildConfig
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.FirebaseAnalyticsLogger
import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ApkAsset
import com.clhs.score.data.ChecksumMismatchException
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.CwaApiKeyStore
import com.clhs.score.data.CwaKeyValidationResult
import com.clhs.score.data.WeatherRepository
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.UpdateApkDownloader
import com.clhs.score.data.UpdateApkTooLargeException
import com.clhs.score.data.UpdateChecker
import com.clhs.score.data.UpdateResult
import com.clhs.score.data.isNewerVersion
import com.clhs.score.notifications.NotificationTopicManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val UPDATE_CHECK_INTERVAL_MILLIS = 12 * 60 * 60 * 1_000L

internal fun isAutomaticUpdateCheckDue(lastCheckTime: Long, now: Long): Boolean =
    lastCheckTime !in 1L..now || now - lastCheckTime >= UPDATE_CHECK_INTERVAL_MILLIS

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val progress: Float?) : UpdateDownloadState
    data class Ready(val apk: File) : UpdateDownloadState
    data class Error(val reason: UpdateDownloadError) : UpdateDownloadState
}

enum class UpdateDownloadError {
    TOO_LARGE,
    CHECKSUM_MISMATCH,
    DOWNLOAD_FAILED,
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class UpdateAvailable(val release: UpdateResult.NewVersion) : UpdateState
    data class Error(val message: String) : UpdateState
}

data class SettingsUiState(
    val updateState: UpdateState = UpdateState.Idle,
    val updateResult: UpdateResult? = null,
    val updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    val versionTapCount: Int = 0,
    val showDeveloperUnlockedToast: Boolean = false,
    val showRestartDialog: Boolean = false,
    val cwaKeyConfigured: Boolean = false,
    val isSavingCwaKey: Boolean = false,
    val cwaKeyMessage: String? = null,
) {
    val isCheckingUpdate: Boolean get() = updateState is UpdateState.Checking
}

class SettingsViewModel internal constructor(
    private val repository: SettingsRepository,
    private val updateChecker: UpdateChecker,
    private val updateApkDownloader: UpdateApkDownloader,
    private val notificationTopicManager: NotificationTopicManager,
    private val analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    initialSettings: AppSettings = AppSettings(),
    private val cwaApiKeyStore: CwaApiKeyStore? = null,
    private val weatherRepository: WeatherRepository? = null,
) : ViewModel() {
    private var lastNotificationsEnabled: Boolean? = null
    private var updateDownloadJob: Job? = null

    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val updateStateRestoreJob: Job = viewModelScope.launch {
        restoreLastKnownUpdate()
    }

    // MainActivity already loaded initialSettings before creating this ViewModel.
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    private val _settingsLoadFailed = MutableStateFlow(false)
    val settingsLoadFailed: StateFlow<Boolean> = _settingsLoadFailed.asStateFlow()
    private var settingsJob: Job? = null

    init {
        retrySettings()
        viewModelScope.launch {
            _uiState.update { it.copy(cwaKeyConfigured = cwaApiKeyStore?.hasKey() == true) }
        }
    }

    fun retrySettings() {
        settingsJob?.cancel()
        _isReady.value = false
        _settingsLoadFailed.value = false
        settingsJob = viewModelScope.launch {
            repository.settings.catch { error ->
                if (error !is IOException) throw error
                _isReady.value = false
                _settingsLoadFailed.value = true
            }.collect { newSettings ->
                _settings.value = newSettings
                if (lastNotificationsEnabled != newSettings.notificationsEnabled) {
                    val previousNotificationsEnabled = lastNotificationsEnabled
                    lastNotificationsEnabled = newSettings.notificationsEnabled
                    // Default-off has never subscribed; avoid initializing FCM to unsubscribe.
                    if (newSettings.notificationsEnabled || previousNotificationsEnabled != null) {
                        withContext(Dispatchers.IO) {
                            notificationTopicManager.setNotificationsEnabled(newSettings.notificationsEnabled)
                        }
                    }
                }
                if (!_isReady.value) {
                    _isReady.value = true
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }

    fun setAmoledBlack(enabled: Boolean) {
        viewModelScope.launch { repository.setAmoledBlack(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        analyticsLogger.logEvent(
            AnalyticsEvents.NOTIFICATION_TOGGLE,
            mapOf(AnalyticsParams.ENABLED to enabled),
        )
        viewModelScope.launch { repository.setNotificationsEnabled(enabled) }
    }

    fun checkUpdate(trigger: String = AnalyticsValues.TRIGGER_MANUAL) {
        viewModelScope.launch {
            updateStateRestoreJob.join()
            val available = _uiState.value.updateState as? UpdateState.UpdateAvailable
            if (available != null) {
                _uiState.update { it.copy(updateResult = available.release) }
                return@launch
            }
            if (_uiState.value.isCheckingUpdate || updateDownloadJob?.isActive == true) return@launch
            _uiState.update {
                it.copy(
                    updateState = UpdateState.Checking,
                    updateResult = null,
                    updateDownloadState = UpdateDownloadState.Idle,
                )
            }
            performUpdateCheck(trigger = trigger, showResult = true)
        }
    }

    fun checkUpdateAutomatically() {
        viewModelScope.launch {
            updateStateRestoreJob.join()
            if (_uiState.value.updateState is UpdateState.Checking || updateDownloadJob?.isActive == true) {
                return@launch
            }
            val lastCheckTime = try {
                repository.getLastUpdateCheckTime()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                0L
            }
            val now = System.currentTimeMillis()
            if (!isAutomaticUpdateCheckDue(lastCheckTime, now)) return@launch
            _uiState.update {
                it.copy(
                    updateState = UpdateState.Checking,
                    updateResult = null,
                )
            }
            performUpdateCheck(trigger = AnalyticsValues.TRIGGER_INITIAL, showResult = false, checkedAt = now)
        }
    }

    private suspend fun performUpdateCheck(
        trigger: String,
        showResult: Boolean,
        checkedAt: Long = System.currentTimeMillis(),
    ) {
        val result = updateChecker.check(BuildConfig.VERSION_NAME)
        analyticsLogger.logEvent(
            AnalyticsEvents.UPDATE_CHECK,
            mapOf(
                AnalyticsParams.TRIGGER to trigger,
                AnalyticsParams.RESULT to result.toAnalyticsResult(),
            ),
        )
        try {
            repository.setLastUpdateCheckTime(checkedAt)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The update result is still useful when the throttle timestamp cannot be persisted.
        }
        try {
            when (result) {
                is UpdateResult.NewVersion -> repository.setLastKnownUpdate(result)
                is UpdateResult.UpToDate -> repository.clearLastKnownUpdate()
                is UpdateResult.Error -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the in-memory result when the cached release cannot be persisted.
        }
        _uiState.update {
            it.copy(
                updateState = result.toUpdateState(),
                updateResult = result.takeIf { showResult },
            )
        }
    }

    private suspend fun restoreLastKnownUpdate() {
        val stored = try {
            repository.getLastKnownUpdate()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return

        if (isNewerVersion(stored.versionName, BuildConfig.VERSION_NAME) == true) {
            _uiState.update { current ->
                if (current.updateState is UpdateState.Idle) {
                    current.copy(updateState = UpdateState.UpdateAvailable(stored))
                } else {
                    current
                }
            }
        } else {
            try {
                repository.clearLastKnownUpdate()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Ignore stale-cache cleanup failures; the next check can replace it.
            }
        }
    }

    fun dismissUpdateResult() {
        if (updateDownloadJob?.isActive == true) return
        _uiState.update { it.copy(updateResult = null) }
    }

    fun downloadUpdateApk(asset: ApkAsset) {
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = viewModelScope.launch {
            _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading(0f)) }
            try {
                val apk = updateApkDownloader.download(asset) { progress ->
                    _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Downloading(progress)) }
                }
                _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Ready(apk)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: UpdateApkTooLargeException) {
                _uiState.update {
                    it.copy(updateDownloadState = UpdateDownloadState.Error(UpdateDownloadError.TOO_LARGE))
                }
            } catch (_: ChecksumMismatchException) {
                _uiState.update {
                    it.copy(updateDownloadState = UpdateDownloadState.Error(UpdateDownloadError.CHECKSUM_MISMATCH))
                }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(updateDownloadState = UpdateDownloadState.Error(UpdateDownloadError.DOWNLOAD_FAILED))
                }
            }
        }
    }

    fun dismissUpdateDownloadResult() {
        if (updateDownloadJob?.isActive == true) return
        _uiState.update { it.copy(updateDownloadState = UpdateDownloadState.Idle) }
    }

    fun onVersionTap() {
        val current = _uiState.value
        if (settings.value.developerEnabled) return
        val newCount = current.versionTapCount + 1
        if (newCount >= DEVELOPER_TAP_THRESHOLD) {
            viewModelScope.launch { repository.setDeveloperEnabled(true) }
            _uiState.update {
                it.copy(versionTapCount = 0, showDeveloperUnlockedToast = true)
            }
        } else {
            _uiState.update { it.copy(versionTapCount = newCount) }
        }
    }

    fun dismissDeveloperToast() {
        _uiState.update { it.copy(showDeveloperUnlockedToast = false) }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDemoMode(enabled)
            _uiState.update { it.copy(showRestartDialog = true) }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setBiometricEnabled(enabled)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repository.setHasCompletedOnboarding(true) }
    }

    fun restartOnboarding() {
        viewModelScope.launch { repository.setHasCompletedOnboarding(false) }
    }

    fun setWeatherSource(source: WeatherSource) {
        viewModelScope.launch {
            if (source == WeatherSource.CWA && cwaApiKeyStore?.hasKey() != true) return@launch
            repository.setWeatherSource(source)
        }
    }

    fun saveCwaApiKey(rawKey: String) {
        val store = cwaApiKeyStore ?: return
        val weather = weatherRepository ?: return
        if (_uiState.value.isSavingCwaKey) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingCwaKey = true, cwaKeyMessage = null) }
            try {
                val validation = weather.validateCwaKey(rawKey)
                if (validation != CwaKeyValidationResult.VALID) {
                    _uiState.update {
                        it.copy(
                            isSavingCwaKey = false,
                            cwaKeyMessage = when (validation) {
                                CwaKeyValidationResult.INVALID_FORMAT -> "授權碼格式不符"
                                CwaKeyValidationResult.INVALID_AUTHORIZATION -> "授權碼驗證失敗，請確認後再試"
                                CwaKeyValidationResult.API_REJECTED -> "中央氣象署拒絕此請求，請稍後再試"
                                CwaKeyValidationResult.UNAVAILABLE -> "中央氣象署暫時無法連線，請稍後再試"
                                CwaKeyValidationResult.VALID -> null
                            },
                        )
                    }
                    return@launch
                }
                store.save(rawKey)
                repository.setWeatherSourceAfterCwaCredentialChange(WeatherSource.CWA)
                _uiState.update {
                    it.copy(cwaKeyConfigured = true, isSavingCwaKey = false, cwaKeyMessage = "授權碼已儲存")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isSavingCwaKey = false, cwaKeyMessage = "授權碼驗證成功，但目前無法安全儲存")
                }
            }
        }
    }

    fun clearCwaApiKey() {
        val store = cwaApiKeyStore ?: return
        viewModelScope.launch {
            runCatching { store.clear() }
                .onSuccess {
                    repository.setWeatherSource(WeatherSource.OPEN_METEO)
                    _uiState.update { it.copy(cwaKeyConfigured = false, cwaKeyMessage = "授權碼已移除") }
                }
                .onFailure { _uiState.update { it.copy(cwaKeyMessage = "目前無法移除授權碼") } }
        }
    }

    fun dismissCwaKeyMessage() {
        _uiState.update { it.copy(cwaKeyMessage = null) }
    }

    fun dismissRestartDialog() {
        _uiState.update { it.copy(showRestartDialog = false) }
    }

    companion object {
        private const val DEVELOPER_TAP_THRESHOLD = 10

        fun factory(
            context: Context,
            initialSettings: AppSettings = AppSettings(),
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val appContext = context.applicationContext
                    val repo = SettingsRepository(appContext)
                    val checker = UpdateChecker()
                    val keyStore = CwaApiKeyStore(appContext)
                    return SettingsViewModel(
                        repo,
                        checker,
                        UpdateApkDownloader(appContext.cacheDir),
                        NotificationTopicManager(),
                        FirebaseAnalyticsLogger(appContext),
                        initialSettings,
                        keyStore,
                        WeatherRepository(appContext.cacheDir, keyStore),
                    ) as T
                }
            }
    }

    private fun UpdateResult.toAnalyticsResult(): String = when (this) {
        is UpdateResult.NewVersion -> AnalyticsValues.RESULT_AVAILABLE
        is UpdateResult.UpToDate -> AnalyticsValues.RESULT_NOT_AVAILABLE
        is UpdateResult.Error -> AnalyticsValues.RESULT_ERROR
    }

    private fun UpdateResult.toUpdateState(): UpdateState = when (this) {
        is UpdateResult.NewVersion -> UpdateState.UpdateAvailable(this)
        is UpdateResult.UpToDate -> UpdateState.UpToDate
        is UpdateResult.Error -> UpdateState.Error(message)
    }
}
