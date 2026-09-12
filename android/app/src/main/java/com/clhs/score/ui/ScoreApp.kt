package com.clhs.score.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.ApkAsset
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ExamSelection
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.WeatherSource
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.ui.navigation.AppLaunchTarget
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.LoginUiState
import com.clhs.score.viewmodel.SettingsUiState
import com.clhs.score.viewmodel.AnnouncementReminderViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScoreApp(
    scoreViewModel: com.clhs.score.viewmodel.ScoreViewModel,
    loginState: LoginUiState,
    gradesState: GradesUiState,
    authState: AuthState,
    settings: AppSettings,
    settingsUiState: SettingsUiState,
    launchTarget: AppLaunchTarget?,
    onLaunchTargetHandled: () -> Unit,
    onGradeTargetOpened: (String, String) -> Unit,
    onWebViewLoginSuccess: (studentNo: String, cookieString: String) -> Unit,
    onCompleteOnboarding: () -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectExam: (String) -> Unit,
    onReload: () -> Unit,
    onLogout: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onToggleSubject: (String) -> Unit,
    onDismissLoginError: () -> Unit,
    onDismissGradesError: () -> Unit,
    onStartGradeReminder: () -> Unit,
    onStopGradeReminder: () -> Unit,
    onGradeReminderPrerequisiteFailed: (String) -> Unit,
    onDismissGradeReminderError: () -> Unit,
    onDismissGradeReminderChanges: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (ApkAsset) -> Unit,
    onDismissUpdateDownloadResult: () -> Unit,
    onDismissUpdateResult: () -> Unit,
    onVersionTap: () -> Unit,
    onDismissDeveloperToast: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onDismissRestartDialog: () -> Unit,
    onExportGrades: (List<ExamSelection>) -> Unit,
    onDismissExportResult: () -> Unit,
    analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    onSetBiometricEnabled: (Boolean, String?) -> Unit,
    onSetWeatherSource: (WeatherSource) -> Unit,
    onSaveCwaApiKey: (String) -> Unit,
    onClearCwaApiKey: () -> Unit,
    onDismissCwaKeyMessage: () -> Unit,
) {
    val context = LocalContext.current
    val announcementReminderViewModel = androidx.lifecycle.viewmodel.compose.viewModel<AnnouncementReminderViewModel>(
        factory = AnnouncementReminderViewModel.factory(context, analyticsLogger),
    )
    val announcementReminderUiState by announcementReminderViewModel.uiState.collectAsStateWithLifecycle()
    SystemNotificationPermissionSync(
        settings = settings,
        onSetNotificationsEnabled = onSetNotificationsEnabled,
        analyticsLogger = analyticsLogger,
    )
    AnnouncementReminderPermissionSync(
        enabled = announcementReminderUiState.reminder.enabled,
        onDisable = { announcementReminderViewModel.setEnabled(false) },
    )

    UpdateResultDialog(
        result = settingsUiState.updateResult,
        downloadState = settingsUiState.updateDownloadState,
        onDownloadUpdate = onDownloadUpdate,
        onDownloadResultHandled = onDismissUpdateDownloadResult,
        onDismiss = onDismissUpdateResult,
    )

    AppShell(
        scoreViewModel = scoreViewModel,
        authState = authState,
        loginState = loginState,
        gradesState = gradesState,
        settings = settings,
        settingsUiState = settingsUiState,
        launchTarget = launchTarget,
        onLaunchTargetHandled = onLaunchTargetHandled,
        onGradeTargetOpened = onGradeTargetOpened,
        onWebViewLoginSuccess = onWebViewLoginSuccess,
        onSelectYear = onSelectYear,
        onSelectExam = onSelectExam,
        onReload = onReload,
        onLogout = onLogout,
        onCompleteOnboarding = onCompleteOnboarding,
        onRestartOnboarding = onRestartOnboarding,
        onToggleSubject = onToggleSubject,
        onDismissLoginError = onDismissLoginError,
        onDismissGradesError = onDismissGradesError,
        onStartGradeReminder = onStartGradeReminder,
        onStopGradeReminder = onStopGradeReminder,
        onGradeReminderPrerequisiteFailed = onGradeReminderPrerequisiteFailed,
        onDismissGradeReminderError = onDismissGradeReminderError,
        onDismissGradeReminderChanges = onDismissGradeReminderChanges,
        onSetThemeMode = onSetThemeMode,
        onSetDynamicColor = onSetDynamicColor,
        onSetAmoledBlack = onSetAmoledBlack,
        onSetNotificationsEnabled = onSetNotificationsEnabled,
        onCheckUpdate = onCheckUpdate,
        onVersionTap = onVersionTap,
        onDismissDeveloperToast = onDismissDeveloperToast,
        onSetDemoMode = onSetDemoMode,
        onDismissRestartDialog = onDismissRestartDialog,
        onExportGrades = onExportGrades,
        onDismissExportResult = onDismissExportResult,
        analyticsLogger = analyticsLogger,
        onSetBiometricEnabled = onSetBiometricEnabled,
        onSetWeatherSource = onSetWeatherSource,
        onSaveCwaApiKey = onSaveCwaApiKey,
        onClearCwaApiKey = onClearCwaApiKey,
        onDismissCwaKeyMessage = onDismissCwaKeyMessage,
        announcementReminderViewModel = announcementReminderViewModel,
        announcementReminderUiState = announcementReminderUiState,
    )
}

@Composable
private fun AnnouncementReminderPermissionSync(
    enabled: Boolean,
    onDisable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnDisable by rememberUpdatedState(onDisable)

    fun syncIfNeeded() {
        if (currentEnabled && !context.canPostNotifications()) currentOnDisable()
    }

    LaunchedEffect(enabled) { syncIfNeeded() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun SystemNotificationPermissionSync(
    settings: AppSettings,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    analyticsLogger: AnalyticsLogger,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationsEnabled by rememberUpdatedState(settings.notificationsEnabled)
    val currentOnSetNotificationsEnabled by rememberUpdatedState(onSetNotificationsEnabled)

    fun syncIfNeeded() {
        if (notificationsEnabled && !context.canPostNotifications()) {
            analyticsLogger.logEvent(
                AnalyticsEvents.NOTIFICATION_PROMPT_ACTION,
                mapOf(AnalyticsParams.ACTION to AnalyticsValues.ACTION_AUTO_DISABLED),
            )
            currentOnSetNotificationsEnabled(false)
        }
    }

    LaunchedEffect(settings.notificationsEnabled) {
        syncIfNeeded()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncIfNeeded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
