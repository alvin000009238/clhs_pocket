@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.analytics.AnalyticsScreenNames
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey

@Serializable data object OverviewRoute : AppRoute
@Serializable data object ScheduleRoute : AppRoute
@Serializable data object ScheduleCustomizationsRoute : AppRoute
@Serializable data object GradesRoute : AppRoute
@Serializable data object ScoreSimulatorRoute : AppRoute
@Serializable data object SubjectTrendRoute : AppRoute
@Serializable data object CampusRoute : AppRoute
@Serializable data object CampusSearchRoute : AppRoute
@Serializable data class SchoolCalendarRoute(val eventId: String? = null) : AppRoute
@Serializable data object SchoolAnnouncementsRoute : AppRoute
@Serializable data class SchoolAnnouncementDetailRoute(val id: String, val category: String = "") : AppRoute
@Serializable data object AnnouncementReminderSettingsRoute : AppRoute
@Serializable data object AnnouncementReminderUnitsRoute : AppRoute
@Serializable data object WorkManagerInfoRoute : AppRoute
@Serializable data object BackgroundPauseSettingsRoute : AppRoute
@Serializable data object ScheduleReliabilitySettingsRoute : AppRoute
@Serializable data object CourseNotificationSettingsRoute : AppRoute
@Serializable data object SchoolWebsiteRoute : AppRoute
@Serializable data object PersonalRoute : AppRoute
@Serializable data object SettingsTransferRoute : AppRoute
@Serializable data object UsageStatisticsRoute : AppRoute
@Serializable data object OpenSourceLicensesRoute : AppRoute
@Serializable data object DeveloperSettingsRoute : AppRoute
@Serializable data object WebViewLoginRoute : AppRoute
@Serializable data object WelcomeRoute : AppRoute
@Serializable data object AppearanceRoute : AppRoute
@Serializable data object PermissionsRoute : AppRoute
@Serializable data object AccountRoute : AppRoute

sealed interface AppLaunchTarget {
    data object Schedule : AppLaunchTarget
    data class GradeExam(val yearValue: String, val examValue: String) : AppLaunchTarget
    data class Announcement(val id: String, val category: String = "") : AppLaunchTarget
    data object Announcements : AppLaunchTarget
}

enum class TopLevelDestination(
    val route: AppRoute,
    val label: String,
    val icon: String,
) {
    Overview(OverviewRoute, "首頁", "home"),
    Schedule(ScheduleRoute, "課表", "calendar_view_week"),
    Grades(GradesRoute, "成績", "bar_chart"),
    Campus(CampusRoute, "校園", "school"),
}

enum class AuthRequirement { Public, Session }

fun AppRoute.authRequirement(): AuthRequirement = when (this) {
    ScheduleRoute,
    ScheduleCustomizationsRoute,
    CourseNotificationSettingsRoute,
    GradesRoute,
    ScoreSimulatorRoute,
    SubjectTrendRoute,
    SchoolWebsiteRoute,
    -> AuthRequirement.Session
    OverviewRoute,
    CampusRoute,
    CampusSearchRoute,
    is SchoolCalendarRoute,
    SchoolAnnouncementsRoute,
    is SchoolAnnouncementDetailRoute,
    PersonalRoute,
    AnnouncementReminderSettingsRoute,
    AnnouncementReminderUnitsRoute,
    BackgroundPauseSettingsRoute,
    ScheduleReliabilitySettingsRoute,
    WorkManagerInfoRoute,
    SettingsTransferRoute,
    UsageStatisticsRoute,
    OpenSourceLicensesRoute,
    DeveloperSettingsRoute,
    WebViewLoginRoute,
    WelcomeRoute,
    AppearanceRoute,
    PermissionsRoute,
    AccountRoute,
    -> AuthRequirement.Public
}

internal fun AppRoute.analyticsScreenName(): String = when (this) {
    OverviewRoute -> AnalyticsScreenNames.OVERVIEW
    ScheduleRoute -> AnalyticsScreenNames.SCHEDULE
    ScheduleCustomizationsRoute -> AnalyticsScreenNames.SCHEDULE_CUSTOMIZATIONS
    GradesRoute -> AnalyticsScreenNames.GRADES
    ScoreSimulatorRoute -> AnalyticsScreenNames.SCORE_SIMULATOR
    SubjectTrendRoute -> AnalyticsScreenNames.SUBJECT_TREND
    CampusRoute -> AnalyticsScreenNames.CAMPUS
    CampusSearchRoute -> AnalyticsScreenNames.CAMPUS_SEARCH
    is SchoolCalendarRoute -> AnalyticsScreenNames.SCHOOL_CALENDAR
    SchoolAnnouncementsRoute -> AnalyticsScreenNames.SCHOOL_ANNOUNCEMENTS
    is SchoolAnnouncementDetailRoute -> AnalyticsScreenNames.SCHOOL_ANNOUNCEMENT_DETAIL
    AnnouncementReminderSettingsRoute -> AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_SETTINGS
    AnnouncementReminderUnitsRoute -> AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_UNITS
    WorkManagerInfoRoute -> AnalyticsScreenNames.WORK_MANAGER_INFO
    BackgroundPauseSettingsRoute -> AnalyticsScreenNames.BACKGROUND_PAUSE_SETTINGS
    ScheduleReliabilitySettingsRoute -> AnalyticsScreenNames.SCHEDULE_RELIABILITY_SETTINGS
    CourseNotificationSettingsRoute -> AnalyticsScreenNames.COURSE_NOTIFICATION_SETTINGS
    SchoolWebsiteRoute -> AnalyticsScreenNames.SCHOOL_WEBSITE
    PersonalRoute -> AnalyticsScreenNames.PERSONAL
    SettingsTransferRoute -> AnalyticsScreenNames.SETTINGS_TRANSFER
    UsageStatisticsRoute -> AnalyticsScreenNames.USAGE_STATISTICS
    OpenSourceLicensesRoute -> AnalyticsScreenNames.OPEN_SOURCE_LICENSES
    DeveloperSettingsRoute -> AnalyticsScreenNames.DEVELOPER_SETTINGS
    WebViewLoginRoute -> AnalyticsScreenNames.WEB_VIEW_LOGIN
    WelcomeRoute -> AnalyticsScreenNames.ONBOARDING_WELCOME
    AppearanceRoute -> AnalyticsScreenNames.ONBOARDING_APPEARANCE
    PermissionsRoute -> AnalyticsScreenNames.ONBOARDING_PERMISSIONS
    AccountRoute -> AnalyticsScreenNames.ONBOARDING_ACCOUNT
}

internal fun topLevelNavigationType(width: Dp, height: Dp = Dp.Infinity): NavigationSuiteType =
    if (width < 600.dp && height >= 480.dp) NavigationSuiteType.ShortNavigationBarCompact else NavigationSuiteType.NavigationRail

@Composable
fun rememberAppNavigationState(onboardingRequired: Boolean = false): AppNavigationState {
    val startRoute: NavKey = OverviewRoute
    val initialRoute: NavKey = remember {
        if (onboardingRequired) WelcomeRoute else startRoute
    }
    val topLevelRoutes = remember { TopLevelDestination.entries.map { it.route as NavKey }.toSet() }
    val topLevelRoute = rememberSerializable(
        startRoute,
        topLevelRoutes,
        serializer = MutableStateSerializer(NavKeySerializer()),
    ) {
        mutableStateOf(startRoute)
    }
    val backStacks = topLevelRoutes.associateWith { route ->
        rememberNavBackStack(if (route == startRoute) initialRoute else route)
    }
    return remember(startRoute, topLevelRoute, backStacks) {
        AppNavigationState(startRoute, topLevelRoute, backStacks)
    }
}

class AppNavigationState internal constructor(
    val startRoute: NavKey,
    topLevelRoute: MutableState<NavKey>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>,
) {
    var topLevelRoute: NavKey by topLevelRoute

    val currentRoute: NavKey
        get() = currentBackStack.last()

    val currentBackStack: NavBackStack<NavKey>
        get() = requireNotNull(backStacks[topLevelRoute]) { "Missing back stack for $topLevelRoute" }

    val stacksInUse: List<NavKey>
        get() = if (topLevelRoute == startRoute) listOf(startRoute) else listOf(startRoute, topLevelRoute)
}

class AppNavigator(private val state: AppNavigationState) {
    fun navigate(route: AppRoute) {
        val parent = route.topLevelDestination()
        if (parent != null && parent.route != route) state.topLevelRoute = parent.route
        if (parent?.route == route) {
            selectTopLevel(parent)
        } else if (state.currentBackStack.lastOrNull() != route) {
            state.currentBackStack.add(route)
        }
    }

    fun selectTopLevel(destination: TopLevelDestination) {
        if (state.topLevelRoute == destination.route) {
            while (state.currentBackStack.size > 1) state.currentBackStack.removeLastOrNull()
        } else {
            state.topLevelRoute = destination.route
        }
    }

    fun openWebViewLogin() {
        if (state.currentBackStack.lastOrNull() != WebViewLoginRoute) {
            state.currentBackStack.add(WebViewLoginRoute)
        }
    }

    fun popAuthRoutes() {
        while (state.currentBackStack.lastOrNull() == WebViewLoginRoute) {
            state.currentBackStack.removeLastOrNull()
        }
    }

    fun finishOnboarding() {
        val overviewStack = requireNotNull(state.backStacks[OverviewRoute])
        while (overviewStack.isNotEmpty()) overviewStack.removeLastOrNull()
        overviewStack.add(OverviewRoute)
        state.topLevelRoute = OverviewRoute
    }

    fun restartOnboarding() {
        val overviewStack = requireNotNull(state.backStacks[OverviewRoute])
        while (overviewStack.isNotEmpty()) overviewStack.removeLastOrNull()
        overviewStack.add(WelcomeRoute)
        state.topLevelRoute = OverviewRoute
    }

    fun goBack(): Boolean {
        if (state.currentBackStack.size > 1) {
            state.currentBackStack.removeLastOrNull()
            return true
        }
        if (state.topLevelRoute != state.startRoute) {
            state.topLevelRoute = state.startRoute
            return true
        }
        return false
    }
}

private fun AppRoute.topLevelDestination(): TopLevelDestination? = when (this) {
    OverviewRoute -> TopLevelDestination.Overview
    ScheduleRoute, ScheduleCustomizationsRoute, CourseNotificationSettingsRoute ->
        TopLevelDestination.Schedule
    GradesRoute, ScoreSimulatorRoute, SubjectTrendRoute -> TopLevelDestination.Grades
    CampusRoute,
    CampusSearchRoute,
    is SchoolCalendarRoute,
    SchoolAnnouncementsRoute,
    is SchoolAnnouncementDetailRoute,
    SchoolWebsiteRoute,
    -> TopLevelDestination.Campus
    PersonalRoute,
    AnnouncementReminderSettingsRoute,
    AnnouncementReminderUnitsRoute,
    BackgroundPauseSettingsRoute,
    ScheduleReliabilitySettingsRoute,
    WorkManagerInfoRoute,
    SettingsTransferRoute,
    UsageStatisticsRoute,
    OpenSourceLicensesRoute,
    DeveloperSettingsRoute,
    WebViewLoginRoute,
    WelcomeRoute,
    AppearanceRoute,
    PermissionsRoute,
    AccountRoute,
    -> null
}

internal fun AppRoute.isOnboardingRoute(): Boolean = when (this) {
    WelcomeRoute,
    AppearanceRoute,
    PermissionsRoute,
    AccountRoute,
    -> true
    else -> false
}

@Composable
fun AppNavigationState.toEntries(
    entryProvider: (NavKey) -> NavEntry<NavKey>,
): SnapshotStateList<NavEntry<NavKey>> {
    val decorators = listOf<NavEntryDecorator<NavKey>>(
        rememberSaveableStateHolderNavEntryDecorator(),
        rememberViewModelStoreNavEntryDecorator(),
    )
    val decoratedEntries = backStacks.mapValues { (parent, stack) ->
        rememberDecoratedNavEntries(stack, decorators) { route ->
            val entry = entryProvider(route)
            // Common routes (e.g. Personal) may exist in several stacks at once.
            NavEntry(route, contentKey = "$parent:${entry.contentKey}", metadata = entry.metadata +
                if (TopLevelDestination.entries.any { it.route == route }) mapOf(RootSceneDestinationKey to route)
                else emptyMap()) {
                entry.Content()
            }
        }
    }
    return stacksInUse.flatMap { decoratedEntries[it].orEmpty() }.toMutableStateList()
}

@Composable
fun AuthenticatedAppShell(
    navigationState: AppNavigationState,
    navigator: AppNavigator,
    unreadAnnouncementCount: Int = 0,
    content: @Composable () -> Unit,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rootReselect = remember { RootReselect() }
    val route = navigationState.currentRoute
    var compact by remember(route) { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollConnection = remember(route, density) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && consumed.y != 0f) {
                    if (distance * consumed.y < 0) distance = 0f
                    distance += consumed.y
                    if (kotlin.math.abs(distance) > with(density) { 12.dp.toPx() }) {
                        compact = distance < 0
                        distance = 0f
                    }
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    BoxWithConstraints {
        val showNavigation = TopLevelDestination.entries.any { it.route == route }
        val rail = topLevelNavigationType(maxWidth, maxHeight) == NavigationSuiteType.NavigationRail
        val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
        val clearance = if (showNavigation && !rail && !imeVisible) PocketNavigationLayout.clearance else 0.dp
        fun select(destination: TopLevelDestination, withHaptic: Boolean = true) {
            if (navigationState.topLevelRoute != destination.route) {
                if (withHaptic) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                navigator.selectTopLevel(destination)
            } else if (navigationState.currentBackStack.size > 1) {
                navigator.selectTopLevel(destination)
            } else {
                scope.launch { rootReselect.scrollToTop?.invoke() }
            }
            compact = false
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            if (showNavigation && rail) {
                androidx.compose.material3.NavigationRail(
                    modifier = Modifier.fillMaxHeight(), containerColor = Color.Transparent,
                ) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = navigationState.topLevelRoute == destination.route
                        NavigationRailItem(
                            selected = selected, onClick = { select(destination) },
                            modifier = Modifier.semantics {
                                contentDescription = destination.label
                                if (destination == TopLevelDestination.Campus && unreadAnnouncementCount > 0) {
                                    stateDescription = "有未讀內容"
                                }
                            },
                            icon = { TopLevelIcon(destination, selected, unreadAnnouncementCount) },
                            label = { Text(destination.label) },
                        )
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).fillMaxHeight().nestedScroll(scrollConnection),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalNavigationClearance provides clearance,
                    LocalNavigationCompact provides compact,
                    LocalExpandNavigation provides { compact = false },
                    LocalNavigationScrollConnection provides scrollConnection,
                    LocalRootReselect provides if (navigationState.currentBackStack.size == 1) rootReselect else null,
                ) { content() }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showNavigation && !rail && !imeVisible,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(horizontal = PocketNavigationLayout.edgePadding)
                        .padding(bottom = PocketNavigationLayout.bottomOffset),
                    enter = androidx.compose.animation.fadeIn(com.clhs.score.ui.theme.PocketMotion.stateChange()) +
                        androidx.compose.animation.slideInVertically(com.clhs.score.ui.theme.PocketMotion.selection()) { it / 2 },
                    exit = androidx.compose.animation.fadeOut(com.clhs.score.ui.theme.PocketMotion.microVisualState()) +
                        androidx.compose.animation.slideOutVertically(com.clhs.score.ui.theme.PocketMotion.selection()) { it / 2 },
                ) {
                    PocketFloatingNavigation(
                        destinations = TopLevelDestination.entries,
                        selected = TopLevelDestination.entries.first { navigationState.topLevelRoute == it.route },
                        hasUnread = unreadAnnouncementCount > 0, compact = compact,
                        onSelect = { select(it) }, onDragSelect = { select(it, withHaptic = false) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopLevelIcon(destination: TopLevelDestination, selected: Boolean, unreadAnnouncementCount: Int) {
    val hasBadge = destination == TopLevelDestination.Campus && unreadAnnouncementCount > 0
    BadgedBox(badge = { if (hasBadge) androidx.compose.material3.Badge(Modifier.clearAndSetSemantics {}) }) {
        if (selected) FilledRoundedSymbol(destination.icon, contentDescription = null)
        else OutlinedRoundedSymbol(destination.icon, contentDescription = null)
    }
}
