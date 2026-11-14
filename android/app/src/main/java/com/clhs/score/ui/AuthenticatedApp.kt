@file:Suppress("SimplifyBooleanWithConstants")

package com.clhs.score.ui

import androidx.compose.ui.unit.dp

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.clhs.score.ui.navigation.OnboardingSceneStrategy
import com.clhs.score.ui.navigation.OnboardingStepKey
import com.clhs.score.BuildConfig
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParameterSanitizer
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ExamSelection
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.NetworkScheduleRepository
import com.clhs.score.data.SCHOOL_ANNOUNCEMENTS_WEB_URL
import com.clhs.score.data.SCHOOL_CALENDAR_WEB_URL
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.SessionStore
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.safeAnnouncementWebUrl
import com.clhs.score.data.schoolAnnouncementOfficialUrl
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.ui.navigation.AppNavigator
import com.clhs.score.ui.navigation.AppLaunchTarget
import com.clhs.score.ui.navigation.AppRoute
import com.clhs.score.ui.navigation.AccountRoute
import com.clhs.score.ui.navigation.AnnouncementReminderSettingsRoute
import com.clhs.score.ui.navigation.AnnouncementReminderUnitsRoute
import com.clhs.score.ui.navigation.AppearanceRoute
import com.clhs.score.ui.navigation.WorkManagerInfoRoute
import com.clhs.score.ui.navigation.AuthenticatedAppShell
import com.clhs.score.ui.navigation.CampusRoute
import com.clhs.score.ui.navigation.CampusSearchRoute
import com.clhs.score.ui.navigation.CourseNotificationSettingsRoute
import com.clhs.score.ui.navigation.DeveloperSettingsRoute
import com.clhs.score.ui.navigation.GradesRoute
import com.clhs.score.ui.navigation.OpenSourceLicensesRoute
import com.clhs.score.ui.navigation.OverviewRoute
import com.clhs.score.ui.navigation.PersonalRoute
import com.clhs.score.ui.navigation.GeneralSettingsRoute
import com.clhs.score.ui.navigation.AttendanceRoute
import com.clhs.score.ui.navigation.MeritRoute
import com.clhs.score.ui.navigation.ScheduleRoute
import com.clhs.score.ui.navigation.SettingsTransferRoute
import com.clhs.score.ui.navigation.ScheduleCustomizationsRoute
import com.clhs.score.ui.navigation.BackgroundPauseSettingsRoute
import com.clhs.score.ui.navigation.ScheduleReliabilitySettingsRoute
import com.clhs.score.ui.navigation.SchoolAnnouncementDetailRoute
import com.clhs.score.ui.navigation.SchoolAnnouncementsRoute
import com.clhs.score.ui.navigation.SchoolCalendarRoute
import com.clhs.score.ui.navigation.SchoolWebsiteRoute
import com.clhs.score.ui.navigation.ScoreSimulatorRoute
import com.clhs.score.ui.navigation.SubjectTrendRoute
import com.clhs.score.ui.navigation.TopLevelDestination
import com.clhs.score.ui.navigation.UsageStatisticsRoute
import com.clhs.score.ui.navigation.WebViewLoginRoute
import com.clhs.score.ui.navigation.PermissionsRoute
import com.clhs.score.ui.navigation.WelcomeRoute
import com.clhs.score.ui.navigation.authRequirement
import com.clhs.score.ui.navigation.analyticsScreenName
import com.clhs.score.ui.navigation.drillDownNavigationTransition
import com.clhs.score.ui.navigation.drillUpNavigationTransition
import com.clhs.score.ui.navigation.isOnboardingRoute
import com.clhs.score.ui.navigation.rememberAppNavigationState
import com.clhs.score.ui.navigation.toEntries
import com.clhs.score.ui.navigation.topLevelNavigationMetadata
import com.clhs.score.ui.overview.OverviewScreen
import com.clhs.score.ui.overview.OverviewDashboard
import com.clhs.score.ui.overview.OverviewEditorActions
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.LoginUiState
import com.clhs.score.viewmodel.OverviewViewModel
import com.clhs.score.domain.overview.forGeneration
import com.clhs.score.viewmodel.ScheduleViewModel
import com.clhs.score.viewmodel.ScheduleCustomizationsViewModel
import com.clhs.score.viewmodel.SchoolAnnouncementDetailViewModel
import com.clhs.score.viewmodel.SchoolAnnouncementsViewModel
import com.clhs.score.viewmodel.AnnouncementBookmarksViewModel
import com.clhs.score.viewmodel.SchoolCalendarViewModel
import com.clhs.score.viewmodel.ScoreViewModel
import com.clhs.score.viewmodel.SettingsUiState
import com.clhs.score.viewmodel.UpdateState
import com.clhs.score.viewmodel.AnnouncementReminderUiState
import com.clhs.score.viewmodel.AnnouncementReminderViewModel
import com.clhs.score.viewmodel.AuthState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppShell(
    scoreViewModel: ScoreViewModel,
    authState: AuthState,
    loginState: LoginUiState,
    gradesState: GradesUiState,
    settings: AppSettings,
    settingsUiState: SettingsUiState,
    launchTarget: AppLaunchTarget?,
    onLaunchTargetHandled: () -> Unit,
    onGradeTargetOpened: (String, String) -> Unit,
    onSelectYear: (String) -> Unit,
    onSelectExam: (String) -> Unit,
    onReload: () -> Unit,
    onLogout: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onRestartOnboarding: () -> Unit,
    onToggleSubject: (String) -> Unit,
    onDismissLoginError: () -> Unit,
    onWebViewLoginSuccess: (studentNo: String, cookieString: String) -> Unit,
    onDismissGradesError: () -> Unit,
    onStartGradeReminder: () -> Unit,
    onStopGradeReminder: () -> Unit,
    onGradeReminderPrerequisiteFailed: (String) -> Unit,
    onDismissGradeReminderError: () -> Unit,
    onDismissGradeReminderChanges: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetStartOnSchedule: (Boolean) -> Unit,
    onCycleProfileAvatarShape: () -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSaveBackgroundPause: suspend (com.clhs.score.data.BackgroundPause) -> Boolean,
    onSetPreferExactCourseAlarms: (Boolean) -> Unit,
    onRefreshAlarmSystemState: () -> Unit,
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    onDismissDeveloperToast: () -> Unit,
    onDismissRestartDialog: () -> Unit,
    onExportGrades: (List<ExamSelection>) -> Unit,
    onDismissExportResult: () -> Unit,
    analyticsLogger: AnalyticsLogger,
    onSetBiometricEnabled: (Boolean, String?) -> Unit,
    onSaveCwaApiKey: (String) -> Unit,
    onClearCwaApiKey: () -> Unit,
    onDismissCwaKeyMessage: () -> Unit,
    announcementReminderViewModel: AnnouncementReminderViewModel,
    announcementReminderUiState: AnnouncementReminderUiState,
) {
    val navigationState = rememberAppNavigationState(!settings.hasCompletedOnboarding)
    val navigator = remember(navigationState) { AppNavigator(navigationState) }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    fun openAnnouncement(announcement: com.clhs.score.data.SchoolAnnouncement) {
        if (announcement.contentType.equals("url", ignoreCase = true)) {
            if (announcement.externalUrl != null) {
                if (context.openAnnouncementUrl(announcement.externalUrl)) {
                    announcementReminderViewModel.markRead(announcement.id)
                }
            } else android.widget.Toast.makeText(
                context,
                "這則消息的連結無法開啟",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        } else {
            navigator.navigate(SchoolAnnouncementDetailRoute(announcement.id, announcement.category))
        }
    }
    val navigationMotion = PocketMotion.navigation()
    val useFakeData = BuildConfig.USE_FAKE_DATA
    val authGeneration = (authState as? AuthState.Authenticated)?.generation ?: 0L
    val schoolWebsiteInBackStack = navigationState.backStacks[CampusRoute]
        ?.contains(SchoolWebsiteRoute) == true
    var pendingGradeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var onboardingLoginPending by rememberSaveable { mutableStateOf(false) }
    val scheduleRepository: ScheduleRepository = remember(useFakeData, scoreViewModel) {
        val appContext = context.applicationContext
        if (useFakeData) {
            FakeScheduleRepository(
                GradeCacheStore(appContext),
                scoreViewModel::getCurrentSession,
                SessionStore(appContext),
            )
        } else {
            NetworkScheduleRepository(
                client = SchoolGradeClient(),
                sessionStore = SessionStore(appContext),
                cacheStore = GradeCacheStore(appContext),
                activeSessionProvider = scoreViewModel::getCurrentSession,
                sessionAccessAllowedProvider = {
                    scoreViewModel.authState.value is AuthState.Authenticated
                },
                onAuthenticationExpired = scoreViewModel::handleSessionExpired,
            )
        }
    }
    val overviewViewModel = androidx.lifecycle.viewmodel.compose.viewModel<OverviewViewModel>(
        factory = OverviewViewModel.factory(
            context = context,
            useFakeData = useFakeData,
            authState = scoreViewModel.authState,
            activeSessionProvider = scoreViewModel::getCurrentSession,
            sharedScheduleRepository = scheduleRepository,
        ),
    )
    val overviewEditor by overviewViewModel.editor.collectAsStateWithLifecycle()
    val collectedOverviewState by overviewViewModel.uiState.collectAsStateWithLifecycle()
    val overviewState = collectedOverviewState.forGeneration((authState as? AuthState.Authenticated)?.generation)
    val showUpdateBadge = settingsUiState.updateState is UpdateState.UpdateAvailable

    val lifecycleOwner = LocalLifecycleOwner.current
    val overviewVisible = navigationState.currentRoute == OverviewRoute
    DisposableEffect(lifecycleOwner, overviewVisible, overviewViewModel) {
        val lifecycle = lifecycleOwner.lifecycle
        overviewViewModel.setClockEnabled(overviewVisible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> overviewViewModel.setClockEnabled(overviewVisible)
                Lifecycle.Event.ON_STOP -> overviewViewModel.setClockEnabled(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            overviewViewModel.setClockEnabled(false)
        }
    }

    ReportDrawnWhen { !overviewState.isInitialLoading }

    LaunchedEffect(navigationState.currentRoute, gradesState.studentNo, gradesState.selectedYearValue, gradesState.selectedExamValue, gradesState.report) {
        if (navigationState.currentRoute != GradesRoute && navigationState.currentRoute != ScoreSimulatorRoute) {
            scoreViewModel.simulatorCreditOwner.discardDraft(leavingRoute = true)
        }
        if (gradesState.report == null || gradesState.selectedYearValue == null || gradesState.selectedExamValue == null) {
            scoreViewModel.simulatorCreditOwner.reset()
        } else if (navigationState.currentRoute == GradesRoute || navigationState.currentRoute == ScoreSimulatorRoute) {
            scoreViewModel.prepareSimulatorCredits()
        }
    }

    LaunchedEffect(navigationState.currentRoute) {
        val route = navigationState.currentRoute as? AppRoute ?: return@LaunchedEffect
        analyticsLogger.logEvent(
            AnalyticsEvents.SCREEN_VIEW,
            mapOf(
                AnalyticsParams.SCREEN_NAME to route.analyticsScreenName(),
                AnalyticsParams.SCREEN_CLASS to AnalyticsValues.SCREEN_CLASS_MAIN_ACTIVITY,
            ),
        )
    }

    LaunchedEffect(gradesState.errorMessage) {
        val message = gradesState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissGradesError()
    }
    LaunchedEffect(gradesState.gradeReminderError) {
        val message = gradesState.gradeReminderError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissGradeReminderError()
    }
    LaunchedEffect(gradesState.exportResult) {
        val message = gradesState.exportResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onDismissExportResult()
    }

    LaunchedEffect(launchTarget, navigator, settings.hasCompletedOnboarding) {
        if (!settings.hasCompletedOnboarding) return@LaunchedEffect
        when (launchTarget) {
            AppLaunchTarget.Schedule -> {
                navigator.selectTopLevel(TopLevelDestination.Schedule)
            }
            is AppLaunchTarget.GradeExam -> {
                navigator.selectTopLevel(TopLevelDestination.Grades)
                pendingGradeTarget = launchTarget.yearValue to launchTarget.examValue
            }
            is AppLaunchTarget.Announcement -> navigator.navigate(
                SchoolAnnouncementDetailRoute(launchTarget.id, launchTarget.category),
            )
            AppLaunchTarget.Announcements -> navigator.navigate(SchoolAnnouncementsRoute)
            null -> return@LaunchedEffect
        }
        if (launchTarget !is AppLaunchTarget.GradeExam) onLaunchTargetHandled()
    }

    LaunchedEffect(authState, pendingGradeTarget, launchTarget, onboardingLoginPending) {
        if (authState !is AuthState.Authenticated) return@LaunchedEffect
        if (onboardingLoginPending) {
            onboardingLoginPending = false
            onCompleteOnboarding()
            navigator.finishOnboarding()
            return@LaunchedEffect
        }
        navigator.popAuthRoutes()
        val target = pendingGradeTarget ?: return@LaunchedEffect
        onGradeTargetOpened(target.first, target.second)
        pendingGradeTarget = null
        if (launchTarget is AppLaunchTarget.GradeExam) onLaunchTargetHandled()
    }

    fun requestLogin() {
        if (!useFakeData) navigator.openWebViewLogin()
        else {
            onboardingLoginPending = navigationState.currentBackStack.any { route ->
                (route as? AppRoute)?.isOnboardingRoute() == true
            }
            onWebViewLoginSuccess(com.clhs.score.data.FakeData.session.studentNo, "fake=cookie")
        }
    }
    LaunchedEffect(announcementReminderUiState.readingError) {
        announcementReminderUiState.readingError?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            announcementReminderViewModel.consumeReadingError()
        }
    }

    fun finishOnboarding() {
        onboardingLoginPending = false
        onCompleteOnboarding()
        navigator.finishOnboarding()
    }

    fun restartOnboarding() {
        onboardingLoginPending = false
        onRestartOnboarding()
        navigator.restartOnboarding()
    }

    fun handleWebViewLoginSuccess(studentNo: String, cookieString: String) {
        val isOnboardingLogin = navigationState.currentBackStack.any { route ->
            (route as? AppRoute)?.isOnboardingRoute() == true
        }
        onWebViewLoginSuccess(studentNo, cookieString)
        if (isOnboardingLogin) onboardingLoginPending = true
    }

    val rootAuthFallback: @Composable (String, String, String) -> Unit = { feature, description, icon ->
        RootFloatingScaffold(dynamicColor = settings.dynamicColor,
            actions = { AccountIconButton(onClick = { navigator.navigate(PersonalRoute) },
                showUpdateBadge = showUpdateBadge, animateShape = false) }) { padding, header ->
            androidx.compose.foundation.layout.Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {
                header()
                AuthGate(authState, com.clhs.score.ui.navigation.AuthRequirement.Session,
                    ::requestLogin, feature = feature, description = description, icon = icon) {}
            }
        }
    }

    AuthenticatedAppShell(
        navigationState = navigationState,
        navigator = navigator,
        unreadAnnouncementCount = announcementReminderUiState.unreadIds.size,
    ) {
        val onboardingContent = androidx.compose.runtime.rememberUpdatedState<@Composable (Int) -> Unit>({ step ->
            OnboardingScreen(
                step = step, settings = settings,
                onSetThemeMode = onSetThemeMode,
                onSetDynamicColor = onSetDynamicColor,
                onSetAmoledBlack = onSetAmoledBlack,
                onSetNotificationsEnabled = onSetNotificationsEnabled,
                onBack = { navigator.goBack() },
                onContinue = {
                    navigator.navigate(when (step) {
                        0 -> AppearanceRoute
                        1 -> PermissionsRoute
                        else -> AccountRoute
                    })
                },
                onLogin = ::requestLogin, onSkip = ::finishOnboarding,
            )
        })
        val sceneStrategies = remember {
            listOf(OnboardingSceneStrategy { step -> onboardingContent.value(step) },
                com.clhs.score.ui.navigation.RootSceneStrategy())
        }
        val topLevelTransitions = topLevelNavigationMetadata()
        val appEntryProvider = entryProvider<NavKey> {
                entry<WelcomeRoute>(metadata = mapOf(OnboardingStepKey to 0)) {
                    onboardingContent.value(0)
                }
                entry<AppearanceRoute>(metadata = mapOf(OnboardingStepKey to 1)) {
                    onboardingContent.value(1)
                }
                entry<PermissionsRoute>(metadata = mapOf(OnboardingStepKey to 2)) {
                    onboardingContent.value(2)
                }
                entry<AccountRoute>(metadata = mapOf(OnboardingStepKey to 3)) {
                    onboardingContent.value(3)
                }
                entry<OverviewRoute>(metadata = topLevelTransitions) {
                    val context = LocalContext.current
                    val bookmarksViewModel = androidx.lifecycle.viewmodel.compose.viewModel<AnnouncementBookmarksViewModel>(
                        factory = AnnouncementBookmarksViewModel.factory(context),
                    )
                    val bookmarksUiState by bookmarksViewModel.uiState.collectAsStateWithLifecycle()
                    OverviewDashboard(
                        state = overviewState,
                        bookmarksUiState = bookmarksUiState,
                        onRetryBookmarks = bookmarksViewModel::retry,
                        dynamicColor = settings.dynamicColor,
                        unreadAnnouncementIds = announcementReminderUiState.unreadIds,
                        isReadingAvailable = announcementReminderUiState.isReadingAvailable,
                        editor = overviewEditor,
                        showNewWidgetBadge = !settings.hasSeenYouBikeWidget,
                        actions = remember(overviewViewModel) { OverviewEditorActions(
                            overviewViewModel::beginEditing, overviewViewModel::cancelEditing, overviewViewModel::saveLayout,
                            overviewViewModel::putWidget, overviewViewModel::removeWidget, overviewViewModel::moveWidget,
                            overviewViewModel::resetLayout, overviewViewModel::undoReset,
                            overviewViewModel::searchLocations, overviewViewModel::searchStations, overviewViewModel::retryLayout,
                            overviewViewModel::markYouBikeWidgetSeen,
                            overviewViewModel::prepareLayoutOrder,
                        ) },
                        cwaKeyConfigured = settingsUiState.cwaKeyConfigured,
                        cwaKeySaving = settingsUiState.isSavingCwaKey,
                        cwaKeyMessage = settingsUiState.cwaKeyMessage,
                        onSaveCwaApiKey = onSaveCwaApiKey,
                        onClearCwaApiKey = onClearCwaApiKey,
                        onDismissCwaKeyMessage = onDismissCwaKeyMessage,
                        onRefreshWeather = overviewViewModel::refreshWeather,
                        onRefresh = overviewViewModel::refresh,
                        onRefreshYouBike = overviewViewModel::refreshYouBike,
                        onOpenPersonal = { navigator.navigate(PersonalRoute) },
                        authState = authState,
                        onRequestLogin = ::requestLogin,
                        showUpdateBadge = showUpdateBadge,
                        onOpenDestination = { destination ->
                            when (destination) {
                                OverviewDestination.Schedule ->
                                    navigator.selectTopLevel(TopLevelDestination.Schedule)
                                is OverviewDestination.CalendarEvent ->
                                    navigator.navigate(SchoolCalendarRoute(destination.id))
                                OverviewDestination.Calendar ->
                                    navigator.navigate(SchoolCalendarRoute())
                                OverviewDestination.Announcements ->
                                    navigator.navigate(SchoolAnnouncementsRoute)
                                is OverviewDestination.GradeExam -> {
                                    navigator.selectTopLevel(TopLevelDestination.Grades)
                                    pendingGradeTarget = destination.yearValue to destination.examValue
                                }
                                is OverviewDestination.Announcement -> {
                                    if (destination.externalUrl != null) {
                                        if (context.openAnnouncementUrl(destination.externalUrl)) {
                                            announcementReminderViewModel.markRead(destination.id)
                                        }
                                    } else {
                                        navigator.navigate(
                                            SchoolAnnouncementDetailRoute(destination.id, destination.category),
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                entry<ScheduleRoute>(metadata = topLevelTransitions) {
                    val courseMenu: @Composable (() -> Unit) -> Unit = { dismiss ->
                        com.clhs.score.ui.schedule.CourseNotificationMenu(
                            onOpen = { navigator.navigate(CourseNotificationSettingsRoute) },
                            dismiss = dismiss,
                        )
                    }
                    AuthGate(
                        authState = authState,
                        requirement = ScheduleRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        fallback = { rootAuthFallback("\u8ab2\u8868", "\u67e5\u770b\u500b\u4eba\u8ab2\u8868\u3001\u4e0a\u8ab2\u6642\u9593\u8207\u6559\u5ba4\u3002", "calendar_view_week") },
                        restoring = { rootAuthFallback("\u8ab2\u8868", "\u67e5\u770b\u500b\u4eba\u8ab2\u8868\u3001\u4e0a\u8ab2\u6642\u9593\u8207\u6559\u5ba4\u3002", "calendar_view_week") },
                    ) {
                        val context = LocalContext.current
                        val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<ScheduleViewModel>(
                            key = "schedule-$authGeneration",
                            factory = ScheduleViewModel.factory(
                                context = context,
                                useFakeData = useFakeData,
                                activeSessionProvider = scoreViewModel::getCurrentSession,
                                sharedScheduleRepository = scheduleRepository,
                            ),
                        )
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(uiState.report) {
                            if (uiState.report != null) {
                                overviewViewModel.refresh()
                                com.clhs.score.widget.syncAllScheduleWidgets(context)
                            }
                        }
                        com.clhs.score.ui.schedule.ScheduleScreen(
                            uiState = uiState,
                            dynamicColor = settings.dynamicColor,
                            notificationMenu = courseMenu,
                            onOpenPersonal = { navigator.navigate(PersonalRoute) },
                            showUpdateBadge = showUpdateBadge,
                            onOpenSubjectCustomizations = { navigator.navigate(ScheduleCustomizationsRoute) },
                            onRefresh = hapticClick(viewModel::refresh),
                            onYearSelected = viewModel::selectYear,
                            onClassSelected = viewModel::selectClass,
                            onScopeSelected = viewModel::selectScope,
                            onDisplayModeSelected = viewModel::setDisplayMode,
                            onPreviousWeek = viewModel::previousWeek,
                            onNextWeek = viewModel::nextWeek,
                            onCurrentWeek = viewModel::currentWeek,
                            onChooseWeek = viewModel::openWeekPicker,
                            onDismissWeekPicker = viewModel::dismissWeekPicker,
                            onWeekSelected = viewModel::selectWeek,
                            onEnsureCurrentWeek = viewModel::ensureCurrentWeek,
                            onConfirmSelection = viewModel::confirmSelection,
                            onClearSelection = viewModel::clearSelection,
                            onNoticeShown = viewModel::consumeNotice,
                            onSaveSubjectOverride = viewModel::saveSubjectOverride,
                        )
                    }
                }
                entry<CourseNotificationSettingsRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = CourseNotificationSettingsRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u7576\u524d\u8ab2\u7a0b\u901a\u77e5", description = "\u4f9d\u500b\u4eba\u8ab2\u8868\u986f\u793a\u8ab2\u7a0b\u8207\u8ab2\u524d\u63d0\u9192\u3002", icon = "notifications",
                        onBack = { navigator.goBack() },
                    ) {
                        com.clhs.score.ui.schedule.CourseNotificationSettingsScreen(
                            allowed = authState is AuthState.Authenticated,
                            onBack = { navigator.goBack() },
                            onOpenScheduleReliability = {
                                navigator.navigate(ScheduleReliabilitySettingsRoute)
                            },
                        )
                    }
                }
                entry<ScheduleCustomizationsRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = ScheduleCustomizationsRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u8ab2\u8868\u81ea\u8a02", description = "\u8abf\u6574\u8ab2\u8868\u79d1\u76ee\u7684\u540d\u7a31\u8207\u986f\u793a\u3002", icon = "calendar_view_week",
                        onBack = { navigator.goBack() },
                    ) {
                        val context = LocalContext.current
                        val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<ScheduleCustomizationsViewModel>(
                            key = "schedule-customizations-$authGeneration",
                            factory = ScheduleCustomizationsViewModel.factory(
                                context = context,
                                useFakeData = useFakeData,
                                activeSessionProvider = scoreViewModel::getCurrentSession,
                                sharedScheduleRepository = scheduleRepository,
                            ),
                        )
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(uiState.overrides, uiState.isLoading) {
                            if (!uiState.isLoading) com.clhs.score.widget.syncAllScheduleWidgets(context)
                        }
                        com.clhs.score.ui.schedule.ScheduleCustomizationsScreen(
                            state = uiState,
                            onBack = { navigator.goBack() },
                            onRetry = viewModel::retry,
                            onSaveOverride = viewModel::saveOverride,
                            onRemoveOverride = viewModel::removeOverride,
                            onReplaceOverrides = viewModel::replaceOverrides,
                            onNoticeShown = viewModel::consumeNotice,
                        )
                    }
                }
                entry<GradesRoute>(metadata = topLevelTransitions) {
                    AuthGate(
                        authState = authState,
                        requirement = GradesRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        fallback = { rootAuthFallback("\u6210\u7e3e", "\u67e5\u770b\u5404\u79d1\u6210\u7e3e\u3001\u6392\u540d\u8207\u6b77\u6b21\u8d70\u52e2\u3002", "bar_chart") },
                        restoring = { rootAuthFallback("\u6210\u7e3e", "\u67e5\u770b\u5404\u79d1\u6210\u7e3e\u3001\u6392\u540d\u8207\u6b77\u6b21\u8d70\u52e2\u3002", "bar_chart") },
                    ) {
                        val creditState by scoreViewModel.simulatorCreditState.collectAsStateWithLifecycle()
                        GradesScreen(
                            state = gradesState,
                            creditState = creditState,
                            dynamicColor = settings.dynamicColor,
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            onSelectYear = onSelectYear,
                            onSelectExam = onSelectExam,
                            onReload = onReload,
                            onToggleSubject = onToggleSubject,
                            onStartGradeReminder = onStartGradeReminder,
                            onStopGradeReminder = onStopGradeReminder,
                            onSetNotificationsEnabled = onSetNotificationsEnabled,
                            onGradeReminderPrerequisiteFailed = onGradeReminderPrerequisiteFailed,
                            onDismissGradeReminderChanges = onDismissGradeReminderChanges,
                            onOpenPersonal = { navigator.navigate(PersonalRoute) },
                            showUpdateBadge = showUpdateBadge,
                            onOpenCreditSettings = {
                                scoreViewModel.prepareSimulatorCredits()
                                scoreViewModel.simulatorCreditOwner.beginEditing()
                            },
                            onOpenScoreSimulator = {
                                analyticsLogger.logEvent(
                                    AnalyticsEvents.SCORE_SIMULATOR_USED,
                                    mapOf(
                                        AnalyticsParams.SUBJECT_COUNT_BUCKET to AnalyticsParameterSanitizer.countBucket(
                                            gradesState.report?.subjects.orEmpty().size,
                                        ),
                                    ),
                                )
                                navigator.navigate(ScoreSimulatorRoute)
                            },
                            onOpenSubjectTrend = {
                                navigator.navigate(SubjectTrendRoute)
                            },
                            onExportGrades = onExportGrades,
                        )
                        val report = gradesState.report
                        if (navigationState.currentRoute == GradesRoute && creditState.isEditing && report != null && creditState.identity == com.clhs.score.viewmodel.simulatorCreditIdentity(
                                gradesState.studentNo, gradesState.selectedYearValue, gradesState.selectedExamValue, report,
                            )
                        ) {
                            SimulatorCreditSheet(
                                report = report,
                                state = creditState,
                                onEdit = scoreViewModel.simulatorCreditOwner::edit,
                                onToggleFixed = scoreViewModel.simulatorCreditOwner::toggleFixed,
                                onInfer = scoreViewModel.simulatorCreditOwner::infer,
                                onCandidate = scoreViewModel.simulatorCreditOwner::applyCandidate,
                                onSave = scoreViewModel.simulatorCreditOwner::save,
                                onReset = scoreViewModel.simulatorCreditOwner::restoreDefaults,
                                onRetry = scoreViewModel.simulatorCreditOwner::retryLoad,
                                onDismiss = { scoreViewModel.simulatorCreditOwner.discardDraft() },
                            )
                        }
                    }
                }
                entry<ScoreSimulatorRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = ScoreSimulatorRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u6210\u7e3e\u6a21\u64ec\u5668", description = "\u8abf\u6574\u5404\u79d1\u5206\u6578\u4e26\u8a66\u7b97\u5e73\u5747\u6210\u7e3e\u3002", icon = "bar_chart",
                        onBack = { navigator.goBack() },
                    ) {
                        val creditState by scoreViewModel.simulatorCreditState.collectAsStateWithLifecycle()
                        ScoreSimulatorScreen(
                            state = gradesState,
                            snackbarHostState = snackbarHostState,
                            onBack = { navigator.goBack() },
                            creditState = creditState,
                            onEditCredits = {
                                scoreViewModel.prepareSimulatorCredits()
                                scoreViewModel.simulatorCreditOwner.beginEditing()
                            },
                        )
                        val report = gradesState.report
                        if (navigationState.currentRoute == ScoreSimulatorRoute && creditState.isEditing && report != null && creditState.identity == com.clhs.score.viewmodel.simulatorCreditIdentity(
                                gradesState.studentNo, gradesState.selectedYearValue, gradesState.selectedExamValue, report,
                            )
                        ) {
                            SimulatorCreditSheet(
                                report = report,
                                state = creditState,
                                onEdit = scoreViewModel.simulatorCreditOwner::edit,
                                onToggleFixed = scoreViewModel.simulatorCreditOwner::toggleFixed,
                                onInfer = scoreViewModel.simulatorCreditOwner::infer,
                                onCandidate = scoreViewModel.simulatorCreditOwner::applyCandidate,
                                onSave = scoreViewModel.simulatorCreditOwner::save,
                                onReset = scoreViewModel.simulatorCreditOwner::restoreDefaults,
                                onRetry = scoreViewModel.simulatorCreditOwner::retryLoad,
                                onDismiss = { scoreViewModel.simulatorCreditOwner.discardDraft() },
                            )
                        }
                    }
                }
                entry<SubjectTrendRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = SubjectTrendRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u6210\u7e3e\u6298\u7dda\u5716", description = "\u6bd4\u8f03\u5404\u79d1\u6b77\u6b21\u6210\u7e3e\u8d70\u52e2\u3002", icon = "bar_chart",
                        onBack = { navigator.goBack() },
                    ) {
                        LaunchedEffect(gradesState.structure) {
                            scoreViewModel.initSubjectTrend()
                        }
                        SubjectTrendScreen(
                            viewModel = scoreViewModel,
                            onBack = { navigator.goBack() },
                        )
                    }
                }
                entry<CampusRoute>(metadata = topLevelTransitions) {
                    CampusScreen(
                        dynamicColor = settings.dynamicColor,
                        state = overviewState,
                        unreadAnnouncementCount = announcementReminderUiState.unreadIds.size,
                        onOpenPersonal = { navigator.navigate(PersonalRoute) },
                        showUpdateBadge = showUpdateBadge,
                        onOpenAnnouncements = { navigator.navigate(SchoolAnnouncementsRoute) },
                        onOpenCalendar = { navigator.navigate(SchoolCalendarRoute()) },
                        onOpenSearch = { navigator.navigate(CampusSearchRoute) },
                        onOpenSchoolWebsite = if (useFakeData) null else { { navigator.navigate(SchoolWebsiteRoute) } },
                    )
                }
                entry<CampusSearchRoute> {
                    val announcementsViewModel = androidx.lifecycle.viewmodel.compose.viewModel<SchoolAnnouncementsViewModel>(
                        factory = SchoolAnnouncementsViewModel.factory(context),
                    )
                    val calendarViewModel = androidx.lifecycle.viewmodel.compose.viewModel<SchoolCalendarViewModel>(
                        factory = SchoolCalendarViewModel.factory(context),
                    )
                    val announcements by announcementsViewModel.uiState.collectAsStateWithLifecycle()
                    val calendar by calendarViewModel.uiState.collectAsStateWithLifecycle()
                    CampusSearchScreen(
                        announcements = announcements,
                        calendar = calendar,
                        unreadIds = announcementReminderUiState.unreadIds,
                        onBack = { navigator.goBack() },
                        onSearch = { keyword ->
                            if (keyword != announcementsViewModel.uiState.value.searchQuery) {
                                announcementsViewModel.search(keyword)
                            }
                            calendarViewModel.search(keyword)
                        },
                        onRetryAnnouncements = announcementsViewModel::refresh,
                        onRetryCalendar = calendarViewModel::refresh,
                        onLoadMore = announcementsViewModel::loadMore,
                        onOpenAnnouncement = ::openAnnouncement,
                        onOpenCalendarEvent = { navigator.navigate(SchoolCalendarRoute(it.id)) },
                    )
                }
                entry<SchoolCalendarRoute> { route ->
                    val context = LocalContext.current
                    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<SchoolCalendarViewModel>(
                        factory = SchoolCalendarViewModel.factory(context, route.eventId),
                    )
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    com.clhs.score.ui.calendar.SchoolCalendarScreen(
                        uiState = uiState,
                        targetEventId = route.eventId,
                        onBack = { navigator.goBack() },
                        onRefresh = viewModel::refresh,
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                        onAddToHome = { event ->
                            if (overviewViewModel.addCalendarCountdown(event.id, event.title)) {
                                navigator.navigate(OverviewRoute)
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    "首頁暫時無法編輯，請稍後重試",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onOpenGoogleCalendar = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        SCHOOL_CALENDAR_WEB_URL.toUri(),
                                    ),
                                )
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    "無法開啟 Google 行事曆",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onNoticeShown = viewModel::consumeNotice,
                    )
                }
                entry<SchoolAnnouncementsRoute> {
                    val context = LocalContext.current
                    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<SchoolAnnouncementsViewModel>(
                        factory = SchoolAnnouncementsViewModel.factory(context),
                    )
                    val bookmarksViewModel = androidx.lifecycle.viewmodel.compose.viewModel<AnnouncementBookmarksViewModel>(
                        factory = AnnouncementBookmarksViewModel.factory(context),
                    )
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    val bookmarksUiState by bookmarksViewModel.uiState.collectAsStateWithLifecycle()
                    com.clhs.score.ui.announcements.SchoolAnnouncementsScreen(
                        uiState = uiState,
                        bookmarksUiState = bookmarksUiState,
                        onToggleBookmark = bookmarksViewModel::toggle,
                        onBookmarkMessageConsumed = bookmarksViewModel::consumeMessage,
                        onRetryBookmarks = bookmarksViewModel::retry,
                        unreadIds = announcementReminderUiState.unreadIds,
                        isReadingAvailable = announcementReminderUiState.isReadingAvailable,
                        isMarkingAllRead = announcementReminderUiState.isMarkingAllRead,
                        onMarkAllRead = announcementReminderViewModel::markAllRead,
                        onBack = { navigator.goBack() },
                        onRefresh = viewModel::refresh,
                        onLoadMore = viewModel::loadMore,
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                        onSetFilters = viewModel::setFilters,
                        onRefreshFilters = viewModel::refreshFilters,
                        onOpenAnnouncement = ::openAnnouncement,
                        onOpenOfficialWebsite = {
                            context.openAnnouncementUrl(SCHOOL_ANNOUNCEMENTS_WEB_URL)
                        },
                        onOpenAnnouncementReminder = {
                            navigator.navigate(AnnouncementReminderSettingsRoute)
                        },
                        onNoticeShown = viewModel::consumeNotice,
                    )
                }
                entry<SchoolAnnouncementDetailRoute> { route ->
                    val context = LocalContext.current
                    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<SchoolAnnouncementDetailViewModel>(
                        key = "school-announcement-detail-${route.id}",
                        factory = SchoolAnnouncementDetailViewModel.factory(context, route.id, route.category),
                    )
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(uiState.detail?.id) {
                        uiState.detail?.id?.let(announcementReminderViewModel::markRead)
                    }
                    val bookmarksViewModel = androidx.lifecycle.viewmodel.compose.viewModel<AnnouncementBookmarksViewModel>(
                        factory = AnnouncementBookmarksViewModel.factory(context),
                    )
                    val bookmarksState by bookmarksViewModel.uiState.collectAsStateWithLifecycle()
                    com.clhs.score.ui.announcements.SchoolAnnouncementDetailScreen(
                        uiState = uiState,
                        onBack = { navigator.goBack() },
                        onRetry = viewModel::retry,
                        onOpenUrl = { context.openAnnouncementUrl(it) },
                        officialUrl = schoolAnnouncementOfficialUrl(route.id),
                        isBookmarked = bookmarksState.bookmarks.any { it.id == route.id },
                        onToggleBookmark = {
                            uiState.detail?.let { detail ->
                                bookmarksViewModel.toggle(com.clhs.score.data.SchoolAnnouncement(
                                    id = detail.id, title = detail.title, date = detail.date,
                                    category = detail.category, unit = detail.unit, issuer = detail.issuer,
                                    isPinned = false, contentType = "html",
                                ))
                            }
                        },
                    )
                }
                entry<SchoolWebsiteRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = SchoolWebsiteRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u6821\u52d9\u7cfb\u7d71", description = "\u958b\u555f\u5b78\u6821\u7684\u500b\u4eba\u6821\u52d9\u7cfb\u7d71\u3002", icon = "school",
                        onBack = { navigator.goBack() },
                    ) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
                entry<PersonalRoute> {
                    LaunchedEffect(authState) {
                        if (authState !is AuthState.Authenticated) return@LaunchedEffect
                        scoreViewModel.refreshStudentInfo()
                    }
                    PersonalScreen(
                        state = gradesState,
                        authState = authState,
                        onRequestLogin = ::requestLogin,
                        settings = settings,
                        onBack = { navigator.goBack() },
                        onCycleProfileAvatarShape = onCycleProfileAvatarShape,
                        onSetBiometricEnabled = onSetBiometricEnabled,
                        onOpenSettings = { navigator.navigate(GeneralSettingsRoute) },
                        onOpenAttendance = { navigator.navigate(AttendanceRoute) },
                        onOpenMerits = { navigator.navigate(MeritRoute) },
                        onLogout = onLogout,
                    )
                }
                entry<GeneralSettingsRoute> {
                    GeneralSettingsScreen(
                        settings = settings,
                        uiState = settingsUiState,
                        onBack = { navigator.goBack() },
                        onSetThemeMode = onSetThemeMode,
                        onSetStartOnSchedule = onSetStartOnSchedule,
                        onSetDynamicColor = onSetDynamicColor,
                        onSetAmoledBlack = onSetAmoledBlack,
                        onSetNotificationsEnabled = onSetNotificationsEnabled,
                        onCheckUpdate = onCheckUpdate,
                        onVersionTap = onVersionTap,
                        onDismissDeveloperToast = onDismissDeveloperToast,
                        onOpenUsageStatistics = { navigator.navigate(UsageStatisticsRoute) },
                        onOpenSourceLicenses = { navigator.navigate(OpenSourceLicensesRoute) },
                        onOpenDeveloperSettings = { navigator.navigate(DeveloperSettingsRoute) },
                        onOpenWorkManagerInfo = { navigator.navigate(WorkManagerInfoRoute) },
                        onOpenBackgroundPauseSettings = { navigator.navigate(BackgroundPauseSettingsRoute) },
                        onOpenScheduleReliabilitySettings = { navigator.navigate(ScheduleReliabilitySettingsRoute) },
                    )
                }
                entry<AttendanceRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = AttendanceRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u7f3a\u66e0\u7d71\u8a08", description = "\u67e5\u770b\u5b78\u671f\u7f3a\u66e0\u7d71\u8a08\u3001\u8acb\u5047\u8207\u7f3a\u5e2d\u660e\u7d30\u3002", icon = "calendar_today",
                        onBack = { navigator.goBack() },
                    ) {
                        val records by scoreViewModel.studentRecords.collectAsStateWithLifecycle()
                        LaunchedEffect(authState, gradesState.studentNo) {
                            scoreViewModel.loadAttendanceRecords()
                        }
                        AttendanceRecordsScreen(
                            state = records,
                            onSelectSemester = scoreViewModel::selectRecordSemester,
                            onRefresh = { scoreViewModel.loadAttendanceRecords(force = true) },
                            onBack = { navigator.goBack() },
                        )
                    }
                }
                entry<MeritRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = MeritRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                        feature = "\u734e\u61f2\u7d00\u9304", description = "\u67e5\u770b\u734e\u52f5\u3001\u61f2\u8655\u660e\u7d30\u8207\u6b77\u5e74\u7d71\u8a08\u3002", icon = "school",
                        onBack = { navigator.goBack() },
                    ) {
                        val records by scoreViewModel.studentRecords.collectAsStateWithLifecycle()
                        LaunchedEffect(authState, gradesState.studentNo) {
                            scoreViewModel.loadMeritRecords()
                        }
                        MeritRecordsScreen(
                            state = records,
                            onSelectSemester = scoreViewModel::selectRecordSemester,
                            onRefresh = { scoreViewModel.loadMeritRecords(force = true) },
                            onBack = { navigator.goBack() },
                        )
                    }
                }
                entry<AnnouncementReminderSettingsRoute> {
                    LaunchedEffect(Unit) {
                        announcementReminderViewModel.refreshUnits()
                    }
                    AnnouncementReminderSettingsScreen(
                        uiState = announcementReminderUiState,
                        onBack = { navigator.goBack() },
                        onSetEnabled = announcementReminderViewModel::setEnabled,
                        onSetIntervalMinutes = announcementReminderViewModel::setIntervalMinutes,
                        onOpenUnits = { navigator.navigate(AnnouncementReminderUnitsRoute) },
                        onCheckResultConsumed = announcementReminderViewModel::consumeCheckResult,
                    )
                }
                entry<AnnouncementReminderUnitsRoute> {
                    AnnouncementReminderUnitsScreen(
                        uiState = announcementReminderUiState,
                        onBack = { navigator.goBack() },
                        onApplyUnits = announcementReminderViewModel::applyUnits,
                        onApplyResultConsumed = announcementReminderViewModel::consumeUnitApplyResult,
                    )
                }
                entry<WorkManagerInfoRoute> {
                    WorkManagerInfoScreen(onBack = { navigator.goBack() })
                }
                entry<BackgroundPauseSettingsRoute> {
                    BackgroundPauseSettingsScreen(
                        settings = settings.backgroundPause,
                        onBack = { navigator.goBack() },
                        onSave = onSaveBackgroundPause,
                    )
                }
                entry<ScheduleReliabilitySettingsRoute> {
                    ScheduleReliabilitySettingsScreen(
                        settings = settings,
                        onBack = { navigator.goBack() },
                        onSetPreferExactAlarms = onSetPreferExactCourseAlarms,
                        onRefreshSystemState = onRefreshAlarmSystemState,
                    )
                }
                entry<SettingsTransferRoute> {
                    val transfer = androidx.lifecycle.viewmodel.compose.viewModel<com.clhs.score.viewmodel.SettingsTransferViewModel>(
                        factory = com.clhs.score.viewmodel.SettingsTransferViewModel.factory(context, scheduleRepository,
                            { if (scoreViewModel.authState.value is AuthState.Authenticated) scoreViewModel.getCurrentSession() else null },
                            overviewViewModel::onLayoutRestored),
                    )
                    LaunchedEffect(authGeneration, authState is AuthState.Authenticated) { transfer.onAccountChanged() }
                    val transferState by transfer.state.collectAsStateWithLifecycle()
                    SettingsTransferScreen(transferState, transfer, onBack = { navigator.goBack() })
                }
                entry<UsageStatisticsRoute> {
                    val context = LocalContext.current
                    val statistics = remember(context) {
                        com.clhs.score.analytics.UsageStatisticsStore(context).snapshot()
                    }
                    UsageStatisticsScreen(statistics, onBack = { navigator.goBack() })
                }
                entry<OpenSourceLicensesRoute> {
                    OpenSourceLicensesScreen(onBack = { navigator.goBack() })
                }
                entry<DeveloperSettingsRoute> {
                    DeveloperSettingsScreen(
                        settings = settings,
                        showRestartDialog = settingsUiState.showRestartDialog,
                        isLoggedIn = authState is AuthState.Authenticated,
                        loginErrorMessage = loginState.errorMessage,
                        gradesErrorMessage = gradesState.errorMessage,
                        onBack = { navigator.goBack() },
                        onRestartOnboarding = ::restartOnboarding,
                        onDismissRestartDialog = onDismissRestartDialog,
                        onOpenSettingsTransfer = {
                            if (overviewEditor.editing) android.widget.Toast.makeText(context,
                                "請先完成或取消首頁編輯", android.widget.Toast.LENGTH_SHORT).show()
                            else navigator.navigate(SettingsTransferRoute)
                        },
                    )
                }
                entry<WebViewLoginRoute> {
                    if (!useFakeData) {
                        WebViewLoginScreen(
                            isProcessingLogin = loginState.isWebViewLoginInProgress,
                            errorMessage = loginState.errorMessage,
                            onLoginSuccess = ::handleWebViewLoginSuccess,
                            onBack = { navigator.goBack() },
                            onDismissError = onDismissLoginError,
                        )
                    }
                }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (!useFakeData && authState is AuthState.Authenticated && schoolWebsiteInBackStack) {
                scoreViewModel.getCurrentSession()?.let { session ->
                    key(authGeneration) {
                        SchoolWebsiteScreen(
                            session = session,
                            isVisible = navigationState.currentRoute == SchoolWebsiteRoute,
                            onBack = { navigator.goBack() },
                            onAuthenticationExpired = scoreViewModel::handleSessionExpired,
                        )
                    }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalRootBodyMotionEnabled provides true) {
                NavDisplay(
                    entries = navigationState.toEntries(appEntryProvider),
                    sceneStrategies = sceneStrategies,
                    onBack = { navigator.goBack() },
                    transitionSpec = { navigationMotion.drillDownNavigationTransition() },
                    popTransitionSpec = { navigationMotion.drillUpNavigationTransition() },
                    predictivePopTransitionSpec = { _ -> navigationMotion.drillUpNavigationTransition() },
                )
            }
        }
    }
}

private fun android.content.Context.openAnnouncementUrl(value: String): Boolean {
    val url = safeAnnouncementWebUrl(value)
    if (url == null) {
        android.widget.Toast.makeText(this, "這個連結無法開啟", android.widget.Toast.LENGTH_SHORT).show()
        return false
    }
    return runCatching {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        android.widget.Toast.makeText(this, "無法開啟連結", android.widget.Toast.LENGTH_SHORT).show()
    }.isSuccess
}
