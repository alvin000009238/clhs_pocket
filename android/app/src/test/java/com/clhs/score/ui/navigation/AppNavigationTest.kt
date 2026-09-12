package com.clhs.score.ui.navigation

import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {
    @Test
    fun topLevelNavigationUsesCompactBarBelow600AndRailFrom600() {
        assertEquals(NavigationSuiteType.ShortNavigationBarCompact, topLevelNavigationType(599.dp))
        assertEquals(NavigationSuiteType.NavigationRail, topLevelNavigationType(600.dp))
    }

    @Test
    fun topLevelInformationArchitectureStaysInProductOrder() {
        assertEquals(
            listOf("總覽", "課表", "成績", "校園"),
            TopLevelDestination.entries.map(TopLevelDestination::label),
        )
    }

    @Test
    fun topLevelStacksRestoreAndReselectReturnsToRoot() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)

        navigator.navigate(ScoreSimulatorRoute)
        navigator.selectTopLevel(TopLevelDestination.Schedule)
        navigator.selectTopLevel(TopLevelDestination.Grades)

        assertEquals(ScoreSimulatorRoute, state.currentRoute)

        navigator.selectTopLevel(TopLevelDestination.Grades)

        assertEquals(listOf(GradesRoute), state.currentBackStack.toList())
    }

    @Test
    fun campusDeepDestinationSelectsCampusStack() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)
        val detail = SchoolAnnouncementDetailRoute("123", "news")

        navigator.navigate(detail)

        assertEquals(CampusRoute, state.topLevelRoute)
        assertEquals(listOf(CampusRoute, detail), state.currentBackStack.toList())
    }

    @Test
    fun schoolWebsiteStackIsRestoredAfterSwitchingTopLevel() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)

        navigator.navigate(SchoolWebsiteRoute)
        navigator.selectTopLevel(TopLevelDestination.Schedule)
        navigator.selectTopLevel(TopLevelDestination.Campus)

        assertEquals(SchoolWebsiteRoute, state.currentRoute)
    }

    @Test
    fun protectedRoutesDeclareSessionRequirementWhilePublicRoutesStayOpen() {
        assertEquals(AuthRequirement.Session, GradesRoute.authRequirement())
        assertEquals(AuthRequirement.Session, ScheduleRoute.authRequirement())
        assertEquals(AuthRequirement.Session, SchoolWebsiteRoute.authRequirement())
        assertEquals(AuthRequirement.Public, OverviewRoute.authRequirement())
        assertEquals(AuthRequirement.Public, PersonalRoute.authRequirement())
        assertEquals(AuthRequirement.Public, SchoolAnnouncementsRoute.authRequirement())
    }

    @Test
    fun loginActionPushesOnlyWebViewAndReturnsToTheOriginalFeatureStack() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)

        navigator.navigate(GradesRoute)
        navigator.openWebViewLogin()

        assertEquals(listOf(GradesRoute, WebViewLoginRoute), state.currentBackStack.toList())

        navigator.popAuthRoutes()

        assertEquals(listOf(GradesRoute), state.currentBackStack.toList())
        assertTrue(state.currentRoute == GradesRoute)
    }

    @Test
    fun finishingOnboardingClearsItsHistoryAndOpensOverview() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)
        val overviewStack = requireNotNull(state.backStacks[OverviewRoute])

        overviewStack.add(AppearanceRoute)
        overviewStack.add(PermissionsRoute)
        overviewStack.add(AccountRoute)
        overviewStack.add(WebViewLoginRoute)

        navigator.finishOnboarding()

        assertEquals(listOf(OverviewRoute), state.currentBackStack.toList())
        assertEquals(OverviewRoute, state.currentRoute)
    }

    @Test
    fun restartingOnboardingReplacesOverviewHistoryWithWelcome() {
        val state = navigationStateForTest()
        val navigator = AppNavigator(state)

        navigator.navigate(PersonalRoute)
        navigator.navigate(DeveloperSettingsRoute)
        navigator.restartOnboarding()

        assertEquals(listOf(WelcomeRoute), state.currentBackStack.toList())
        assertEquals(OverviewRoute, state.topLevelRoute)
    }

    private fun navigationStateForTest(): AppNavigationState {
        val routes = TopLevelDestination.entries.map { it.route as NavKey }
        val stacks = routes.associateWith { route ->
            NavBackStack(route)
        }
        return AppNavigationState(
            startRoute = OverviewRoute,
            topLevelRoute = mutableStateOf<NavKey>(OverviewRoute),
            backStacks = stacks,
        )
    }
}
