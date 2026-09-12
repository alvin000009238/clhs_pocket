@file:Suppress("SimplifyBooleanWithConstants")

package com.clhs.score.ui

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
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
import com.clhs.score.data.WeatherSource
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
import com.clhs.score.ui.navigation.DeveloperSettingsRoute
import com.clhs.score.ui.navigation.GradesRoute
import com.clhs.score.ui.navigation.OpenSourceLicensesRoute
import com.clhs.score.ui.navigation.OverviewRoute
import com.clhs.score.ui.navigation.PersonalRoute
import com.clhs.score.ui.navigation.ScheduleRoute
import com.clhs.score.ui.navigation.ScheduleCustomizationsRoute
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
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.LoginUiState
import com.clhs.score.viewmodel.OverviewViewModel
import com.clhs.score.viewmodel.ScheduleViewModel
import com.clhs.score.viewmodel.ScheduleCustomizationsViewModel
import com.clhs.score.viewmodel.SchoolAnnouncementDetailViewModel
import com.clhs.score.viewmodel.SchoolAnnouncementsViewModel
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
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    onDismissDeveloperToast: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onDismissRestartDialog: () -> Unit,
    onExportGrades: (List<ExamSelection>) -> Unit,
    onDismissExportResult: () -> Unit,
    analyticsLogger: AnalyticsLogger,
    onSetBiometricEnabled: (Boolean, String?) -> Unit,
    onSetWeatherSource: (WeatherSource) -> Unit,
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
    val navigationMotion = MaterialTheme.motionScheme
    val useFakeData = BuildConfig.USE_FAKE_DATA || settings.demoMode
    val currentAuthState = rememberUpdatedState(authState)
    val authGeneration = (authState as? AuthState.Authenticated)?.generation ?: 0L
    val schoolWebsiteInBackStack = navigationState.backStacks[CampusRoute]
        ?.contains(SchoolWebsiteRoute) == true
    var pendingGradeTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var onboardingLoginPending by rememberSaveable { mutableStateOf(false) }
    val scheduleRepository: ScheduleRepository = remember(useFakeData, scoreViewModel) {
        val appContext = context.applicationContext
        if (useFakeData) {
            FakeScheduleRepository(GradeCacheStore(appContext))
        } else {
            NetworkScheduleRepository(
                client = SchoolGradeClient(),
                sessionStore = SessionStore(appContext),
                cacheStore = GradeCacheStore(appContext),
                activeSessionProvider = scoreViewModel::getCurrentSession,
                sessionAccessAllowedProvider = {
                    currentAuthState.value is AuthState.Authenticated
                },
                onAuthenticationExpired = scoreViewModel::handleSessionExpired,
            )
        }
    }
    val overviewViewModel = androidx.lifecycle.viewmodel.compose.viewModel<OverviewViewModel>(
        factory = OverviewViewModel.factory(
            context = context,
            useFakeData = useFakeData,
            activeSessionProvider = scoreViewModel::getCurrentSession,
            sharedScheduleRepository = scheduleRepository,
        ),
    )
    val overviewState by overviewViewModel.uiState.collectAsStateWithLifecycle()
    val showUpdateBadge = settingsUiState.updateState is UpdateState.UpdateAvailable

    ReportDrawnWhen { !overviewState.isInitialLoading }

    LaunchedEffect(authState) {
        overviewViewModel.refresh()
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

    fun requestLogin() = navigator.openWebViewLogin()

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

    AuthenticatedAppShell(
        navigationState = navigationState,
        navigator = navigator,
        unreadAnnouncementCount = announcementReminderUiState.reminder.unreadIds.size,
    ) {
        val topLevelTransitions = topLevelNavigationMetadata(navigationMotion)
        val appEntryProvider = entryProvider<NavKey> {
                entry<WelcomeRoute> {
                    OnboardingWelcomeScreen(
                        onContinue = { navigator.navigate(AppearanceRoute) },
                    )
                }
                entry<AppearanceRoute> {
                    OnboardingAppearanceScreen(
                        settings = settings,
                        onSetThemeMode = onSetThemeMode,
                        onSetDynamicColor = onSetDynamicColor,
                        onSetAmoledBlack = onSetAmoledBlack,
                        onBack = { navigator.goBack() },
                        onContinue = { navigator.navigate(PermissionsRoute) },
                    )
                }
                entry<PermissionsRoute> {
                    OnboardingPermissionsScreen(
                        onBack = { navigator.goBack() },
                        onContinue = { navigator.navigate(AccountRoute) },
                        onSetNotificationsEnabled = onSetNotificationsEnabled,
                    )
                }
                entry<AccountRoute> {
                    OnboardingAccountScreen(
                        onBack = { navigator.goBack() },
                        onLogin = { navigator.openWebViewLogin() },
                        onSkip = ::finishOnboarding,
                    )
                }
                entry<OverviewRoute>(metadata = topLevelTransitions) {
                    val context = LocalContext.current
                    OverviewScreen(
                        state = overviewState,
                        onSavePreferences = overviewViewModel::savePreferences,
                        onRefresh = overviewViewModel::refresh,
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
                                        context.openAnnouncementUrl(destination.externalUrl)
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
                    AuthGate(
                        authState = authState,
                        requirement = ScheduleRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
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
                            onOpenPersonal = { navigator.navigate(PersonalRoute) },
                            showUpdateBadge = showUpdateBadge,
                            onOpenSubjectCustomizations = { navigator.navigate(ScheduleCustomizationsRoute) },
                            onRefresh = viewModel::refresh,
                            onYearSelected = viewModel::selectYear,
                            onClassSelected = viewModel::selectClass,
                            onScopeSelected = viewModel::selectScope,
                            onConfirmSelection = viewModel::confirmSelection,
                            onClearSelection = viewModel::clearSelection,
                            onNoticeShown = viewModel::consumeNotice,
                            onSaveSubjectOverride = viewModel::saveSubjectOverride,
                        )
                    }
                }
                entry<ScheduleCustomizationsRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = ScheduleCustomizationsRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
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
                    ) {
                        GradesScreen(
                            state = gradesState,
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
                    }
                }
                entry<ScoreSimulatorRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = ScoreSimulatorRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
                    ) {
                        ScoreSimulatorScreen(
                            state = gradesState,
                            snackbarHostState = snackbarHostState,
                            onBack = { navigator.goBack() },
                        )
                    }
                }
                entry<SubjectTrendRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = SubjectTrendRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
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
                        state = overviewState,
                        unreadAnnouncementCount = announcementReminderUiState.reminder.unreadIds.size,
                        onOpenPersonal = { navigator.navigate(PersonalRoute) },
                        showUpdateBadge = showUpdateBadge,
                        onOpenAnnouncements = { navigator.navigate(SchoolAnnouncementsRoute) },
                        onOpenCalendar = { navigator.navigate(SchoolCalendarRoute()) },
                        onOpenSchoolWebsite = { navigator.navigate(SchoolWebsiteRoute) },
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
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    com.clhs.score.ui.announcements.SchoolAnnouncementsScreen(
                        uiState = uiState,
                        unreadIds = announcementReminderUiState.reminder.unreadIds,
                        onBack = { navigator.goBack() },
                        onRefresh = viewModel::refresh,
                        onLoadMore = viewModel::loadMore,
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                        onOpenAnnouncement = { announcement ->
                            if (announcement.contentType.equals("url", ignoreCase = true)) {
                                announcement.externalUrl?.let(context::openAnnouncementUrl)
                                    ?: android.widget.Toast.makeText(
                                        context,
                                        "這則消息的連結無法開啟",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                            } else {
                                navigator.navigate(
                                    SchoolAnnouncementDetailRoute(announcement.id, announcement.category),
                                )
                            }
                        },
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
                    com.clhs.score.ui.announcements.SchoolAnnouncementDetailScreen(
                        uiState = uiState,
                        onBack = { navigator.goBack() },
                        onRetry = viewModel::retry,
                        onOpenUrl = context::openAnnouncementUrl,
                        officialUrl = schoolAnnouncementOfficialUrl(route.id),
                    )
                }
                entry<SchoolWebsiteRoute> {
                    AuthGate(
                        authState = authState,
                        requirement = SchoolWebsiteRoute.authRequirement(),
                        onRequestLogin = ::requestLogin,
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
                        uiState = settingsUiState,
                        onBack = { navigator.goBack() },
                        onSetThemeMode = onSetThemeMode,
                        onSetDynamicColor = onSetDynamicColor,
                        onSetAmoledBlack = onSetAmoledBlack,
                        onSetNotificationsEnabled = onSetNotificationsEnabled,
                        onSetBiometricEnabled = onSetBiometricEnabled,
                        onSetWeatherSource = onSetWeatherSource,
                        onSaveCwaApiKey = onSaveCwaApiKey,
                        onClearCwaApiKey = onClearCwaApiKey,
                        onDismissCwaKeyMessage = onDismissCwaKeyMessage,
                        onCheckUpdate = onCheckUpdate,
                        onVersionTap = onVersionTap,
                        onDismissDeveloperToast = onDismissDeveloperToast,
                        onOpenUsageStatistics = { navigator.navigate(UsageStatisticsRoute) },
                        onOpenSourceLicenses = { navigator.navigate(OpenSourceLicensesRoute) },
                        onOpenDeveloperSettings = { navigator.navigate(DeveloperSettingsRoute) },
                        onOpenWorkManagerInfo = { navigator.navigate(WorkManagerInfoRoute) },
                        onLogout = onLogout,
                    )
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
                        onSetDemoMode = onSetDemoMode,
                        onRestartOnboarding = ::restartOnboarding,
                        onDismissRestartDialog = onDismissRestartDialog,
                    )
                }
                entry<WebViewLoginRoute> {
                    WebViewLoginScreen(
                        isProcessingLogin = loginState.isWebViewLoginInProgress,
                        errorMessage = loginState.errorMessage,
                        onLoginSuccess = ::handleWebViewLoginSuccess,
                        onBack = { navigator.goBack() },
                        onDismissError = onDismissLoginError,
                    )
                }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (authState is AuthState.Authenticated && schoolWebsiteInBackStack) {
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
            NavDisplay(
                entries = navigationState.toEntries(appEntryProvider),
                onBack = { navigator.goBack() },
                transitionSpec = { navigationMotion.drillDownNavigationTransition() },
                popTransitionSpec = { navigationMotion.drillUpNavigationTransition() },
                predictivePopTransitionSpec = { _ -> navigationMotion.drillUpNavigationTransition() },
            )
        }
    }
}

private fun android.content.Context.openAnnouncementUrl(value: String) {
    val url = safeAnnouncementWebUrl(value)
    if (url == null) {
        android.widget.Toast.makeText(this, "這個連結無法開啟", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
    }.onFailure {
        android.widget.Toast.makeText(this, "無法開啟連結", android.widget.Toast.LENGTH_SHORT).show()
    }
}
