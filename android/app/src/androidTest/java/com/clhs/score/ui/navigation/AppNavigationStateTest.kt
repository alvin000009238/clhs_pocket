package com.clhs.score.ui.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Rule
import org.junit.Test

class AppNavigationStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchingTopLevelDestinationsRestoresStackAndReselectReturnsHome() {
        composeRule.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                val navigator = remember(navigationState) { AppNavigator(navigationState) }
                val provider = entryProvider<NavKey> {
                    entry<OverviewRoute> { Text("總覽首頁") }
                    entry<ScheduleRoute> { Text("課表首頁") }
                    entry<GradesRoute> {
                        Button(onClick = { navigator.navigate(ScoreSimulatorRoute) }) {
                            Text("成績首頁")
                        }
                    }
                    entry<ScoreSimulatorRoute> { Text("成績詳情") }
                    entry<CampusRoute> { Text("校園首頁") }
                }
                AuthenticatedAppShell(navigationState, navigator) {
                    NavDisplay(
                        entries = navigationState.toEntries(provider),
                        onBack = { navigator.goBack() },
                    )
                }
            }
        }

        composeRule.onNodeWithText("成績").performClick()
        composeRule.onNodeWithText("成績首頁").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithText("課表").performClick()
        composeRule.onNodeWithText("課表首頁").assertIsDisplayed()
        composeRule.onNodeWithText("成績").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithText("成績").performClick()
        composeRule.onNodeWithText("成績首頁").assertIsDisplayed()
        composeRule.onAllNodesWithText("成績詳情").assertCountEquals(0)
    }

    @Test
    fun savedInstanceStateRestoresSelectedStackAndDetail() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                val navigator = remember(navigationState) { AppNavigator(navigationState) }
                val provider = entryProvider<NavKey> {
                    entry<OverviewRoute> { Text("總覽首頁") }
                    entry<ScheduleRoute> { Text("課表首頁") }
                    entry<GradesRoute> {
                        Button(onClick = { navigator.navigate(ScoreSimulatorRoute) }) {
                            Text("成績首頁")
                        }
                    }
                    entry<ScoreSimulatorRoute> { Text("成績詳情") }
                    entry<CampusRoute> { Text("校園首頁") }
                }
                AuthenticatedAppShell(navigationState, navigator) {
                    NavDisplay(
                        entries = navigationState.toEntries(provider),
                        onBack = { navigator.goBack() },
                    )
                }
            }
        }

        composeRule.onNodeWithText("成績").performClick()
        composeRule.onNodeWithText("成績首頁").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithText("課表").performClick()
        composeRule.onNodeWithText("成績").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
    }

    @Test
    fun compactNavigationBadgeAnnouncesUnreadCount() {
        composeRule.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                val navigator = remember(navigationState) { AppNavigator(navigationState) }
                AuthenticatedAppShell(navigationState, navigator, unreadAnnouncementCount = 3) {
                    Text("內容")
                }
            }
        }

        composeRule.onNodeWithContentDescription("校園，3 則未讀公告").assertIsDisplayed()
    }

    @Test
    fun navigationRailBadgeAnnouncesCappedUnreadCount() {
        composeRule.setContent {
            ScoreTheme {
                Box(Modifier.width(800.dp)) {
                    val navigationState = rememberAppNavigationState()
                    val navigator = remember(navigationState) { AppNavigator(navigationState) }
                    AuthenticatedAppShell(navigationState, navigator, unreadAnnouncementCount = 100) {
                        Text("內容")
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("校園，99+ 則未讀公告").assertIsDisplayed()
    }
}
