package com.clhs.score.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
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
import com.clhs.score.data.announcementBadgeText
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
@Serializable data class SchoolCalendarRoute(val eventId: String? = null) : AppRoute
@Serializable data object SchoolAnnouncementsRoute : AppRoute
@Serializable data class SchoolAnnouncementDetailRoute(val id: String, val category: String = "") : AppRoute
@Serializable data object AnnouncementReminderSettingsRoute : AppRoute
@Serializable data object AnnouncementReminderUnitsRoute : AppRoute
@Serializable data object WorkManagerInfoRoute : AppRoute
@Serializable data object SchoolWebsiteRoute : AppRoute
@Serializable data object PersonalRoute : AppRoute
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
    Overview(OverviewRoute, "總覽", "home"),
    Schedule(ScheduleRoute, "課表", "calendar_view_week"),
    Grades(GradesRoute, "成績", "bar_chart"),
    Campus(CampusRoute, "校園", "school"),
}

enum class AuthRequirement { Public, Session }

fun AppRoute.authRequirement(): AuthRequirement = when (this) {
    ScheduleRoute,
    ScheduleCustomizationsRoute,
    GradesRoute,
    ScoreSimulatorRoute,
    SubjectTrendRoute,
    SchoolWebsiteRoute,
    -> AuthRequirement.Session
    OverviewRoute,
    CampusRoute,
    is SchoolCalendarRoute,
    SchoolAnnouncementsRoute,
    is SchoolAnnouncementDetailRoute,
    PersonalRoute,
    AnnouncementReminderSettingsRoute,
    AnnouncementReminderUnitsRoute,
    WorkManagerInfoRoute,
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
    is SchoolCalendarRoute -> AnalyticsScreenNames.SCHOOL_CALENDAR
    SchoolAnnouncementsRoute -> AnalyticsScreenNames.SCHOOL_ANNOUNCEMENTS
    is SchoolAnnouncementDetailRoute -> AnalyticsScreenNames.SCHOOL_ANNOUNCEMENT_DETAIL
    AnnouncementReminderSettingsRoute -> AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_SETTINGS
    AnnouncementReminderUnitsRoute -> AnalyticsScreenNames.ANNOUNCEMENT_REMINDER_UNITS
    WorkManagerInfoRoute -> AnalyticsScreenNames.WORK_MANAGER_INFO
    SchoolWebsiteRoute -> AnalyticsScreenNames.SCHOOL_WEBSITE
    PersonalRoute -> AnalyticsScreenNames.PERSONAL
    UsageStatisticsRoute -> AnalyticsScreenNames.USAGE_STATISTICS
    OpenSourceLicensesRoute -> AnalyticsScreenNames.OPEN_SOURCE_LICENSES
    DeveloperSettingsRoute -> AnalyticsScreenNames.DEVELOPER_SETTINGS
    WebViewLoginRoute -> AnalyticsScreenNames.WEB_VIEW_LOGIN
    WelcomeRoute -> AnalyticsScreenNames.ONBOARDING_WELCOME
    AppearanceRoute -> AnalyticsScreenNames.ONBOARDING_APPEARANCE
    PermissionsRoute -> AnalyticsScreenNames.ONBOARDING_PERMISSIONS
    AccountRoute -> AnalyticsScreenNames.ONBOARDING_ACCOUNT
}

internal fun topLevelNavigationType(width: Dp): NavigationSuiteType =
    if (width < 600.dp) NavigationSuiteType.ShortNavigationBarCompact else NavigationSuiteType.NavigationRail

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
    ScheduleRoute, ScheduleCustomizationsRoute -> TopLevelDestination.Schedule
    GradesRoute, ScoreSimulatorRoute, SubjectTrendRoute -> TopLevelDestination.Grades
    CampusRoute,
    is SchoolCalendarRoute,
    SchoolAnnouncementsRoute,
    is SchoolAnnouncementDetailRoute,
    SchoolWebsiteRoute,
    -> TopLevelDestination.Campus
    PersonalRoute,
    AnnouncementReminderSettingsRoute,
    AnnouncementReminderUnitsRoute,
    WorkManagerInfoRoute,
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
    val decoratedEntries = backStacks.mapValues { (_, stack) ->
        rememberDecoratedNavEntries(stack, decorators, entryProvider)
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
    BoxWithConstraints {
        val hasTopLevelParent = (navigationState.currentRoute as? AppRoute)?.topLevelDestination() != null
        val navigationType = if (hasTopLevelParent) {
            topLevelNavigationType(maxWidth)
        } else {
            NavigationSuiteType.None
        }
        val navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NavigationSuiteScaffold(
            navigationItems = {
                if (navigationType == NavigationSuiteType.ShortNavigationBarCompact) {
                    Row(Modifier.fillMaxWidth()) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = navigationState.topLevelRoute == destination.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigator.selectTopLevel(destination) },
                                icon = {
                                    TopLevelIcon(destination, selected, unreadAnnouncementCount)
                                },
                                label = { Text(destination.label) },
                                alwaysShowLabel = false,
                                colors = navigationBarItemColors,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = navigationState.topLevelRoute == destination.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigator.selectTopLevel(destination) },
                                icon = {
                                    TopLevelIcon(destination, selected, unreadAnnouncementCount)
                                },
                                label = { Text(destination.label) },
                                alwaysShowLabel = false,
                                colors = navigationRailItemColors,
                            )
                        }
                    }
                }
            },
            navigationSuiteType = navigationType,
            containerColor = MaterialTheme.colorScheme.surface,
            navigationSuiteColors = NavigationSuiteDefaults.colors(
                shortNavigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                navigationRailContainerColor = Color.Transparent,
            ),
            content = content,
        )
    }
}

@Composable
private fun TopLevelIcon(
    destination: TopLevelDestination,
    selected: Boolean,
    unreadAnnouncementCount: Int,
) {
    val hasBadge = destination == TopLevelDestination.Campus && unreadAnnouncementCount > 0
    val description = if (hasBadge) {
        "${destination.label}，${announcementBadgeText(unreadAnnouncementCount)} 則未讀公告"
    } else {
        destination.label
    }
    BadgedBox(
        badge = {
            if (hasBadge) Badge {
                Text(
                    announcementBadgeText(unreadAnnouncementCount),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        },
    ) {
        if (selected) {
            FilledRoundedSymbol(destination.icon, contentDescription = description)
        } else {
            OutlinedRoundedSymbol(destination.icon, contentDescription = description)
        }
    }
}
