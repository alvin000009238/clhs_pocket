@file:Suppress("SimplifyBooleanWithConstants")

package com.clhs.score

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.FirebaseAnalyticsLogger
import com.clhs.score.data.AppSettings
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.BiometricHelper
import com.clhs.score.data.SessionStore
import com.clhs.score.data.SessionStorageException
import com.clhs.score.data.PinAttemptLimiter
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.ThemeMode
import com.clhs.score.notifications.NotificationChannels
import com.clhs.score.notifications.NotificationAction
import com.clhs.score.notifications.consumeNotificationAction
import com.clhs.score.ui.BiometricLockScreen
import com.clhs.score.ui.ScoreApp
import com.clhs.score.ui.navigation.AppLaunchTarget
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.ScoreViewModel
import com.clhs.score.viewmodel.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.IOException

private const val KEY_LAUNCH_TARGET_TYPE = "launch_target_type"
private const val KEY_LAUNCH_TARGET_YEAR = "launch_target_year"
private const val KEY_LAUNCH_TARGET_EXAM = "launch_target_exam"
private const val KEY_LAUNCH_TARGET_ANNOUNCEMENT_ID = "launch_target_announcement_id"
private const val KEY_LAUNCH_TARGET_CATEGORY = "launch_target_category"

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val checkUpdateChannel = Channel<String>(Channel.BUFFERED)
    private val routeAnalyticsChannel = Channel<Pair<String, Boolean>>(Channel.BUFFERED)
    private val pendingLaunchTarget = mutableStateOf<AppLaunchTarget?>(null)
    private val isAppLocked = mutableStateOf(false)
    private val isBiometricInvalidated = mutableStateOf(false)
    private val isInitialLockResolved = mutableStateOf(false)
    private val launchSettings = mutableStateOf<AppSettings?>(null)
    private val launchSettingsFailed = mutableStateOf(false)
    private var wasInBackground = false
    private var shouldLockOnInitialReady = false
    private var isBiometricPromptShowing = false
    private lateinit var sessionStore: SessionStore
    private lateinit var analyticsLogger: AnalyticsLogger
    private val pinAttemptLimiter by lazy(LazyThreadSafetyMode.NONE) {
        PinAttemptLimiter.create(applicationContext)
    }

    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("zh-TW"))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    private fun loadLaunchSettings() {
        launchSettingsFailed.value = false
        lifecycleScope.launch {
            try {
                launchSettings.value = withContext(Dispatchers.IO) {
                    SettingsRepository(applicationContext).loadForStartup()
                }
            } catch (_: IOException) {
                launchSettingsFailed.value = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            launchSettings.value == null && !launchSettingsFailed.value
        }
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = runCatching { splashScreenView.iconView }.getOrNull()
            if (iconView == null) {
                splashScreenView.remove()
                applyLaunchSystemBarAppearance()
                return@setOnExitAnimationListener
            }
            iconView.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(200L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    splashScreenView.remove()
                    applyLaunchSystemBarAppearance()
                }
                .start()
        }

        super.onCreate(savedInstanceState)
        sessionStore = SessionStore(applicationContext)
        analyticsLogger = FirebaseAnalyticsLogger(applicationContext)
        val hasBiometricSession = hasBiometricSessionForLock()
        isAppLocked.value = savedInstanceState?.getBoolean(KEY_APP_LOCKED, false) == true &&
            hasBiometricSession
        pendingLaunchTarget.value = savedInstanceState?.toLaunchTarget()
        shouldLockOnInitialReady = savedInstanceState == null
        isInitialLockResolved.value = !shouldLockOnInitialReady || isAppLocked.value
        applySecureWindowPolicy(hasBiometricSession)
        lifecycleScope.launch(Dispatchers.IO) {
            NotificationChannels.ensureCreated(applicationContext)
        }
        enableEdgeToEdge()
        handleIntent(intent)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                super.onStart(owner)
                if (wasInBackground && !isBiometricPromptShowing) {
                    wasInBackground = false
                    if (hasBiometricSessionForLock()) {
                        isAppLocked.value = true
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                super.onStop(owner)
                if (!isChangingConfigurations && !isBiometricPromptShowing) {
                    wasInBackground = true
                }
            }
        })

        loadLaunchSettings()

        setContent {
            if (launchSettingsFailed.value) {
                ScoreTheme { SettingsLoadError(onRetry = ::loadLaunchSettings) }
                return@setContent
            }
            if (launchSettings.value == null) {
                ScoreTheme { SettingsLoadError(isLoading = true, onRetry = ::loadLaunchSettings) }
                return@setContent
            }
            val initialSettings = launchSettings.value ?: return@setContent
            val settingsVm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(applicationContext, initialSettings),
            )
            val appSettings by settingsVm.settings.collectAsStateWithLifecycle()
            val settingsUi by settingsVm.uiState.collectAsStateWithLifecycle()
            val isReady by settingsVm.isReady.collectAsStateWithLifecycle()
            val settingsLoadFailed by settingsVm.settingsLoadFailed.collectAsStateWithLifecycle()

            LaunchedEffect(settingsVm) {
                settingsVm.checkUpdateAutomatically()
            }

            LaunchedEffect(Unit) {
                checkUpdateChannel.receiveAsFlow().collect { trigger ->
                    settingsVm.checkUpdate(trigger)
                }
            }

            LaunchedEffect(Unit) {
                routeAnalyticsChannel.receiveAsFlow().collect { (source, locked) ->
                    withFrameNanos { }
                    withContext(Dispatchers.Default) {
                        analyticsLogger.logEvent(
                            AnalyticsEvents.APP_OPEN_ROUTE,
                            mapOf(
                                AnalyticsParams.SOURCE to source,
                                AnalyticsParams.LOCKED to locked,
                            ),
                        )
                    }
                }
            }

            ScoreTheme(
                themeMode = appSettings.themeMode,
                dynamicColor = appSettings.dynamicColor,
                amoledBlack = appSettings.amoledBlack,
            ) {
                if (settingsLoadFailed) {
                    SettingsLoadError(onRetry = settingsVm::retrySettings)
                    return@ScoreTheme
                }
                if (!isReady) {
                    SettingsLoadError(isLoading = true, onRetry = settingsVm::retrySettings)
                    return@ScoreTheme
                }

                // Avoid briefly showing app content before the biometric lock decision is applied.
                LaunchedEffect(isReady) {
                    if (!isInitialLockResolved.value) {
                        val pendingCleanup = try {
                            sessionStore.hasPendingCleanup()
                        } catch (_: SessionStorageException) {
                            com.clhs.score.data.DeveloperDiagnostics.recordEvent(
                                applicationContext, "PrivacyCleanup", "SESSION_VALIDITY_READ_FAILED",
                            )
                            true
                        }
                        if (shouldLockOnInitialReady &&
                            !pendingCleanup &&
                            hasBiometricSessionForLock()
                        ) {
                            isAppLocked.value = true
                        }
                        shouldLockOnInitialReady = false
                        isInitialLockResolved.value = true
                    }
                }
                if (!isInitialLockResolved.value) {
                    return@ScoreTheme
                }

                val shouldSecureWindow = appSettings.biometricEnabled ||
                    isAppLocked.value ||
                    hasBiometricSessionForLock()
                LaunchedEffect(shouldSecureWindow) {
                    applySecureWindowPolicy(shouldSecureWindow)
                }

                LaunchedEffect(appSettings.themeMode, appSettings.dynamicColor, appSettings.amoledBlack) {
                    com.clhs.score.widget.syncAllScheduleWidgets(applicationContext, appSettings)
                    com.clhs.score.widget.refreshScheduleWidgetPreview(applicationContext, appSettings)
                }

                val useFakeData = BuildConfig.USE_FAKE_DATA || appSettings.demoMode
                val scoreVm: ScoreViewModel = viewModel(
                    factory = ScoreViewModel.factory(
                        context = applicationContext,
                        useFakeData = useFakeData,
                        onAuthenticationExpired = {
                            runOnUiThread {
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                settingsVm.setBiometricEnabled(false)
                            }
                        },
                    ),
                )
                val loginState by scoreVm.loginState.collectAsStateWithLifecycle()
                val gradesState by scoreVm.gradesState.collectAsStateWithLifecycle()
                val authState by scoreVm.authState.collectAsStateWithLifecycle()

                // 監聽鎖定狀態變化，自動彈出解鎖
                LaunchedEffect(isAppLocked.value) {
                    if (isAppLocked.value) {
                        showBiometricUnlockPrompt(scoreVm, settingsVm)
                    }
                }

                if (isAppLocked.value) {
                    BiometricLockScreen(
                        isBiometricInvalidated = isBiometricInvalidated.value,
                        onTriggerBiometric = {
                            showBiometricUnlockPrompt(scoreVm, settingsVm)
                        },
                        onUnlockWithPin = { pin ->
                            val remainingDelay = pinAttemptLimiter.remainingDelayMillis()
                            if (remainingDelay > 0L) {
                                val seconds = ((remainingDelay + 999L) / 1_000L).coerceAtLeast(1L)
                                Toast.makeText(
                                    this@MainActivity,
                                    "嘗試次數過多，請 $seconds 秒後再試",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@BiometricLockScreen
                            }
                            lifecycleScope.launch {
                                val sessionResult = runCatching { sessionStore.loadSessionWithPin(pin) }
                                sessionResult.exceptionOrNull()?.throwIfCancellation()
                                val session = sessionResult.getOrNull()
                                if (sessionResult.exceptionOrNull() is SessionStorageException) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "無法讀取解鎖資訊，請登出後重新登入",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else if (session != null) {
                                    pinAttemptLimiter.reset()
                                    scoreVm.loginWithBiometricSession(session)
                                    analyticsLogger.logEvent(
                                        AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                                        mapOf(
                                            AnalyticsParams.METHOD to AnalyticsValues.METHOD_PIN,
                                            AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                                        ),
                                    )
                                    isAppLocked.value = false
                                    if (isBiometricInvalidated.value) {
                                        isBiometricInvalidated.value = false
                                        showBiometricEnrollPrompt(
                                            currentSession = session,
                                            pin = pin,
                                            settingsVm = settingsVm,
                                            replaceInvalidatedKey = true,
                                        )
                                    }
                                } else {
                                    val delay = pinAttemptLimiter.recordFailure()
                                    analyticsLogger.logEvent(
                                        AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                                        mapOf(
                                            AnalyticsParams.METHOD to AnalyticsValues.METHOD_PIN,
                                            AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                                        ),
                                    )
                                    val message = if (delay > 0L) {
                                        "密碼錯誤，請 ${delay / 1_000L} 秒後再試"
                                    } else {
                                        "密碼錯誤"
                                    }
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onLogout = {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            scoreVm.logout(AnalyticsValues.SOURCE_LOCK_SCREEN)
                            settingsVm.setBiometricEnabled(false)
                            isAppLocked.value = false
                            isBiometricInvalidated.value = false
                        }
                    )
                } else {
                    ScoreApp(
                        scoreViewModel = scoreVm,
                        loginState = loginState,
                        gradesState = gradesState,
                        authState = authState,
                        settings = appSettings,
                        settingsUiState = settingsUi,
                        launchTarget = pendingLaunchTarget.value,
                        onLaunchTargetHandled = { pendingLaunchTarget.value = null },
                        onGradeTargetOpened = scoreVm::openGradeReminderTarget,
                        onWebViewLoginSuccess = scoreVm::loginWithWebViewCookies,
                        onCompleteOnboarding = settingsVm::completeOnboarding,
                        onSelectYear = scoreVm::selectYear,
                        onSelectExam = scoreVm::selectExam,
                        onReload = scoreVm::reloadStructure,
                        onLogout = {
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            scoreVm.logout()
                            settingsVm.setBiometricEnabled(false)
                        },
                        onRestartOnboarding = settingsVm::restartOnboarding,
                        onToggleSubject = scoreVm::toggleSubjectExpanded,
                        onDismissLoginError = scoreVm::clearLoginError,
                        onDismissGradesError = scoreVm::clearGradesError,
                        onStartGradeReminder = scoreVm::startGradeReminder,
                        onStopGradeReminder = scoreVm::stopGradeReminder,
                        onGradeReminderPrerequisiteFailed = scoreVm::reportGradeReminderPrerequisiteError,
                        onDismissGradeReminderError = scoreVm::clearGradeReminderError,
                        onDismissGradeReminderChanges = scoreVm::dismissGradeReminderChanges,
                        onSetThemeMode = settingsVm::setThemeMode,
                        onSetDynamicColor = settingsVm::setDynamicColor,
                        onSetAmoledBlack = settingsVm::setAmoledBlack,
                        onSetNotificationsEnabled = settingsVm::setNotificationsEnabled,
                        onSetWeatherSource = settingsVm::setWeatherSource,
                        onSaveCwaApiKey = settingsVm::saveCwaApiKey,
                        onClearCwaApiKey = settingsVm::clearCwaApiKey,
                        onDismissCwaKeyMessage = settingsVm::dismissCwaKeyMessage,
                        onCheckUpdate = { settingsVm.checkUpdate() },
                        onDownloadUpdate = settingsVm::downloadUpdateApk,
                        onDismissUpdateDownloadResult = settingsVm::dismissUpdateDownloadResult,
                        onDismissUpdateResult = settingsVm::dismissUpdateResult,
                        onVersionTap = settingsVm::onVersionTap,
                        onDismissDeveloperToast = settingsVm::dismissDeveloperToast,
                        onSetDemoMode = settingsVm::setDemoMode,
                        onDismissRestartDialog = settingsVm::dismissRestartDialog,
                        onExportGrades = { selections -> scoreVm.exportGrades(selections, applicationContext) },
                        onDismissExportResult = scoreVm::dismissExportResult,
                        analyticsLogger = analyticsLogger,
                        onSetBiometricEnabled = { enabled, pin ->
                            if (enabled && pin != null) {
                                val currentSession = scoreVm.getCurrentSession()
                                if (currentSession != null) {
                                    showBiometricEnrollPrompt(currentSession, pin, settingsVm)
                                } else {
                                    Toast.makeText(applicationContext, "請先登入後再開啟生物解鎖", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val currentSession = scoreVm.getCurrentSession()
                                lifecycleScope.launch {
                                    runCatching {
                                        if (!useFakeData && currentSession != null) {
                                            // 先持久化並驗證一般 session，成功後才能刪除 biometric session。
                                            sessionStore.saveSession(currentSession)
                                        }
                                        sessionStore.clearBiometricSession()
                                    }.onSuccess {
                                        settingsVm.setBiometricEnabled(false)
                                        Toast.makeText(
                                            applicationContext,
                                            "已關閉生物識別解鎖",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }.onFailure { error ->
                                        error.throwIfCancellation()
                                        Toast.makeText(
                                            applicationContext,
                                            "無法安全保存登入資訊，生物識別解鎖維持開啟",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_APP_LOCKED, isAppLocked.value)
        outState.putLaunchTarget(pendingLaunchTarget.value)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        var routeSource: String? = null

        when (val action = consumeNotificationAction(applicationContext, intent)) {
            NotificationAction.CheckUpdate -> {
                routeSource = AnalyticsValues.SOURCE_NOTIFICATION_UPDATE
                checkUpdateChannel.trySend(AnalyticsValues.TRIGGER_NOTIFICATION)
            }
            is NotificationAction.OpenGradeReminder -> {
                routeSource = AnalyticsValues.SOURCE_GRADE_REMINDER
                pendingLaunchTarget.value = AppLaunchTarget.GradeExam(action.yearValue, action.examValue)
            }
            is NotificationAction.OpenAnnouncement -> {
                routeSource = AnalyticsValues.SOURCE_NOTIFICATION_GENERAL
                pendingLaunchTarget.value = AppLaunchTarget.Announcement(action.id, action.category)
            }
            NotificationAction.OpenAnnouncements -> {
                routeSource = AnalyticsValues.SOURCE_NOTIFICATION_GENERAL
                pendingLaunchTarget.value = AppLaunchTarget.Announcements
            }
            null -> Unit
        }

        val data = intent.data
        if (data?.scheme == "scoreapp" && data.host == "schedule") {
            routeSource = AnalyticsValues.SOURCE_WIDGET_SCHEDULE
            pendingLaunchTarget.value = AppLaunchTarget.Schedule
            intent.data = null
        }

        if (routeSource == null &&
            intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        ) {
            routeSource = AnalyticsValues.SOURCE_LAUNCHER
        }

        routeSource?.let { source ->
            routeAnalyticsChannel.trySend(
                source to (isAppLocked.value || hasBiometricSessionForLock()),
            )
        }
    }

    private fun showBiometricUnlockPrompt(
        scoreVm: ScoreViewModel,
        settingsVm: SettingsViewModel
    ) {
        if (isBiometricPromptShowing) return
        val iv = try {
            sessionStore.getBiometricIv()
        } catch (_: SessionStorageException) {
            isBiometricInvalidated.value = true
            return
        }
        if (iv == null) {
            handleKeyInvalidated(scoreVm, settingsVm, "解鎖資訊不完整，請重新登入")
            return
        }

        val cipher = try {
            BiometricHelper.getDecryptCipher(iv)
        } catch (e: Exception) {
            if (BiometricHelper.isKeyPermanentlyInvalidated(e)) {
                isBiometricInvalidated.value = true
                analyticsLogger.logEvent(
                    AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                    mapOf(
                        AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_INVALIDATED,
                    ),
                )
            } else {
                handleKeyInvalidated(scoreVm, settingsVm, "初始化安全金鑰失敗，請重新登入")
            }
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isBiometricPromptShowing = false
                analyticsLogger.logEvent(
                    AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                    mapOf(
                        AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                        AnalyticsParams.RESULT to if (
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            AnalyticsValues.RESULT_CANCELLED
                        } else {
                            AnalyticsValues.RESULT_FAILURE
                        },
                    ),
                )
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@MainActivity, "驗證錯誤: $errString", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isBiometricPromptShowing = false
                val decryptCipher = result.cryptoObject?.cipher
                if (decryptCipher != null) {
                    lifecycleScope.launch {
                        val session = try {
                            sessionStore.loadBiometricSession(decryptCipher)
                        } catch (_: SessionStorageException) {
                            handleKeyInvalidated(scoreVm, settingsVm, "無法讀取解鎖資訊，請重新登入")
                            return@launch
                        }
                        if (session != null) {
                            pinAttemptLimiter.reset()
                            scoreVm.loginWithBiometricSession(session)
                            analyticsLogger.logEvent(
                                AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                                mapOf(
                                    AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                                    AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                                ),
                            )
                            isAppLocked.value = false
                        } else {
                            analyticsLogger.logEvent(
                                AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                                mapOf(
                                    AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                                    AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                                ),
                            )
                            handleKeyInvalidated(scoreVm, settingsVm, "解析登入資訊失敗，請重新登入")
                        }
                    }
                }
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("已鎖定")
            .setSubtitle("請使用生物識別以解鎖")
            .setAllowedAuthenticators(BiometricHelper.STRONG_BIOMETRIC_AUTHENTICATORS)
            .setNegativeButtonText("取消")
            .build()

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        isBiometricPromptShowing = true
        runCatching {
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }.onFailure { error ->
            isBiometricPromptShowing = false
            analyticsLogger.logEvent(
                AnalyticsEvents.BIOMETRIC_UNLOCK_RESULT,
                mapOf(
                    AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                    AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                ),
            )
            Toast.makeText(this, "無法啟動生物識別，請稍後再試", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBiometricEnrollPrompt(
        currentSession: AuthenticatedSession,
        pin: String,
        settingsVm: SettingsViewModel,
        replaceInvalidatedKey: Boolean = false,
    ) {
        if (isBiometricPromptShowing) return
        val cipher = try {
            BiometricHelper.getEncryptCipher()
        } catch (e: Exception) {
            if (replaceInvalidatedKey) {
                fallBackToNormalSession(currentSession, settingsVm)
            }
            Toast.makeText(this, "金鑰生成失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                isBiometricPromptShowing = false
                if (replaceInvalidatedKey) {
                    fallBackToNormalSession(currentSession, settingsVm)
                }
                Toast.makeText(this@MainActivity, "驗證失敗，未開啟生物解鎖", Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                isBiometricPromptShowing = false
                val encryptCipher = result.cryptoObject?.cipher
                if (encryptCipher != null) {
                    lifecycleScope.launch {
                        runCatching {
                            sessionStore.saveBiometricSession(currentSession, pin, encryptCipher)
                            sessionStore.clearNormalSession()
                        }.onSuccess {
                            settingsVm.setBiometricEnabled(true)
                            Toast.makeText(
                                this@MainActivity,
                                "已開啟生物識別解鎖",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure { error ->
                            error.throwIfCancellation()
                            if (replaceInvalidatedKey) {
                                fallBackToNormalSession(currentSession, settingsVm)
                            } else {
                                runCatching { sessionStore.clearBiometricSession() }
                            }
                            Toast.makeText(
                                this@MainActivity,
                                "無法安全保存生物識別解鎖資訊",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("啟用生物識別解鎖")
            .setSubtitle("請驗證指紋或臉部以進行安全性授權")
            .setAllowedAuthenticators(BiometricHelper.STRONG_BIOMETRIC_AUTHENTICATORS)
            .setNegativeButtonText("取消")
            .build()

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        isBiometricPromptShowing = true
        runCatching {
            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        }.onFailure { error ->
            isBiometricPromptShowing = false
            Toast.makeText(this, "無法啟動生物識別，請稍後再試", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleKeyInvalidated(
        scoreVm: ScoreViewModel,
        settingsVm: SettingsViewModel,
        message: String
    ) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        try {
            sessionStore.clearBiometricSession()
        } catch (_: SessionStorageException) {
            // Logout below still clears the authoritative DataStore and in-memory session.
        }
        settingsVm.setBiometricEnabled(false)
        scoreVm.logout(AnalyticsValues.SOURCE_LOCK_SCREEN)
        isAppLocked.value = false
    }

    private fun fallBackToNormalSession(
        currentSession: AuthenticatedSession,
        settingsVm: SettingsViewModel,
    ) {
        lifecycleScope.launch {
            runCatching {
                sessionStore.saveSession(currentSession)
                sessionStore.clearBiometricSession()
            }.onSuccess {
                settingsVm.setBiometricEnabled(false)
                isBiometricInvalidated.value = false
            }.onFailure { error ->
                error.throwIfCancellation()
                Toast.makeText(
                    this@MainActivity,
                    "無法安全保存登入資訊，請稍後再試",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun applySecureWindowPolicy(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun hasBiometricSessionForLock(): Boolean = try {
        sessionStore.hasBiometricSession()
    } catch (_: SessionStorageException) {
        isBiometricInvalidated.value = true
        true
    }

    private fun applyLaunchSystemBarAppearance() {
        val useDark = when (launchSettings.value?.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !useDark
            isAppearanceLightNavigationBars = !useDark
        }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private companion object {
        const val KEY_APP_LOCKED = "app_locked"
    }
}

private fun Bundle.putLaunchTarget(target: AppLaunchTarget?) {
    remove(KEY_LAUNCH_TARGET_TYPE)
    when (target) {
        AppLaunchTarget.Schedule -> putString(KEY_LAUNCH_TARGET_TYPE, "schedule")
        is AppLaunchTarget.GradeExam -> {
            putString(KEY_LAUNCH_TARGET_TYPE, "grade")
            putString(KEY_LAUNCH_TARGET_YEAR, target.yearValue)
            putString(KEY_LAUNCH_TARGET_EXAM, target.examValue)
        }
        is AppLaunchTarget.Announcement -> {
            putString(KEY_LAUNCH_TARGET_TYPE, "announcement")
            putString(KEY_LAUNCH_TARGET_ANNOUNCEMENT_ID, target.id)
            putString(KEY_LAUNCH_TARGET_CATEGORY, target.category)
        }
        AppLaunchTarget.Announcements -> putString(KEY_LAUNCH_TARGET_TYPE, "announcements")
        null -> Unit
    }
}

private fun Bundle.toLaunchTarget(): AppLaunchTarget? = when (getString(KEY_LAUNCH_TARGET_TYPE)) {
    "schedule" -> AppLaunchTarget.Schedule
    "grade" -> {
        val year = getString(KEY_LAUNCH_TARGET_YEAR)
        val exam = getString(KEY_LAUNCH_TARGET_EXAM)
        if (year.isNullOrBlank() || exam.isNullOrBlank()) null else AppLaunchTarget.GradeExam(year, exam)
    }
    "announcement" -> getString(KEY_LAUNCH_TARGET_ANNOUNCEMENT_ID)
        ?.takeIf(String::isNotBlank)
        ?.let { AppLaunchTarget.Announcement(it, getString(KEY_LAUNCH_TARGET_CATEGORY).orEmpty()) }
    "announcements" -> AppLaunchTarget.Announcements
    else -> null
}

@Composable
private fun SettingsLoadError(isLoading: Boolean = false, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (isLoading) {
            CircularProgressIndicator()
            Text("正在讀取設定")
        } else {
            Text("無法讀取設定，請重試")
            Button(onClick = onRetry) { Text("重試") }
        }
    }
}
