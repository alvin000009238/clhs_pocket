package com.clhs.score.ui.navigation

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
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
    fun editingKeepsNavigationAndClearanceStable() {
        var editing by mutableStateOf(false)
        composeRule.setContent {
            ScoreTheme {
                val state = rememberAppNavigationState()
                val navigator = remember(state) { AppNavigator(state) }
                AuthenticatedAppShell(state, navigator) {
                    Text("${if (editing) "編輯" else "瀏覽"}間距 ${LocalNavigationClearance.current.value}")
                }
            }
        }
        composeRule.onNodeWithContentDescription("課表").assertIsDisplayed()
        composeRule.onNodeWithText("瀏覽間距 ${PocketNavigationLayout.clearance.value}").assertIsDisplayed()
        composeRule.runOnIdle { editing = true }
        composeRule.onNodeWithContentDescription("課表").assertIsDisplayed()
        composeRule.onNodeWithText("編輯間距 ${PocketNavigationLayout.clearance.value}").assertIsDisplayed()
        composeRule.runOnIdle { editing = false }
        composeRule.onNodeWithContentDescription("課表").assertIsDisplayed()
    }

    @Test
    fun emptyBottomBarPreservesSystemInsetsBelowScrollContent() {
        composeRule.setContent {
            ScoreTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalNavigationClearance provides 80.dp) {
                    PocketScaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(bottom = 48.dp)) { padding ->
                        Text("系統底部間距 ${padding.calculateBottomPadding().value}")
                    }
                }
            }
        }
        composeRule.onNodeWithText("系統底部間距 48.0").assertIsDisplayed()
    }

    @Test
    fun switchingTopLevelDestinationsRestoresStackAndBackRestoresToolbar() {
        lateinit var navigator: AppNavigator
        composeRule.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                navigator = remember(navigationState) { AppNavigator(navigationState) }
                val provider = entryProvider<NavKey> {
                    entry<OverviewRoute> { Text("首頁") }
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

        composeRule.onNodeWithContentDescription("成績").performClick()
        composeRule.onNodeWithText("成績首頁").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("成績").assertDoesNotExist()
        composeRule.runOnIdle { navigator.selectTopLevel(TopLevelDestination.Schedule) }
        composeRule.onNodeWithText("課表首頁").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("成績").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("成績").assertDoesNotExist()
        composeRule.runOnIdle { navigator.goBack() }
        composeRule.onNodeWithText("成績首頁").assertIsDisplayed()
        composeRule.onAllNodesWithText("成績詳情").assertCountEquals(0)
    }

    @Test
    fun savedInstanceStateRestoresSelectedStackAndDetail() {
        lateinit var navigator: AppNavigator
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                navigator = remember(navigationState) { AppNavigator(navigationState) }
                val provider = entryProvider<NavKey> {
                    entry<OverviewRoute> { Text("首頁") }
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

        composeRule.onNodeWithContentDescription("成績").performClick()
        composeRule.onNodeWithText("成績首頁").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("成績").assertDoesNotExist()
        composeRule.runOnIdle { navigator.selectTopLevel(TopLevelDestination.Schedule) }
        composeRule.onNodeWithContentDescription("成績").performClick()
        composeRule.onNodeWithText("成績詳情").assertIsDisplayed()
    }

    @Test
    fun navigationAndClearanceOnlyExistAtTopLevelRoots() {
        lateinit var navigator: AppNavigator
        composeRule.setContent {
            ScoreTheme {
                val state = rememberAppNavigationState()
                navigator = remember(state) { AppNavigator(state) }
                AuthenticatedAppShell(state, navigator) {
                    Text("底部避讓 ${LocalNavigationClearance.current.value}")
                }
            }
        }
        TopLevelDestination.entries.forEach { destination ->
            composeRule.runOnIdle { navigator.selectTopLevel(destination) }
            composeRule.onNodeWithContentDescription(destination.label).assertIsDisplayed()
        }
        listOf<AppRoute>(SchoolAnnouncementDetailRoute("example"), ScoreSimulatorRoute,
            PersonalRoute, ScheduleCustomizationsRoute).forEach { child ->
            composeRule.runOnIdle { navigator.navigate(child) }
            TopLevelDestination.entries.forEach {
                composeRule.onNodeWithContentDescription(it.label).assertDoesNotExist()
            }
            composeRule.onNodeWithText("底部避讓 0.0").assertIsDisplayed()
            composeRule.runOnIdle { navigator.goBack() }
        }
        composeRule.onNodeWithContentDescription("課表").assertIsDisplayed()
    }

    @Test
    fun floatingNavigationAnnouncesUnreadWithoutCount() {
        composeRule.setContent {
            ScoreTheme {
                val navigationState = rememberAppNavigationState()
                val navigator = remember(navigationState) { AppNavigator(navigationState) }
                AuthenticatedAppShell(navigationState, navigator, unreadAnnouncementCount = 3) {
                    Text("內容")
                }
            }
        }

        composeRule.onNodeWithContentDescription("校園").assertIsDisplayed()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "有未讀內容"))
        composeRule.onNodeWithContentDescription("首頁").assertIsSelected()
    }

    @Test
    fun navigationRailBadgeAnnouncesUnreadWithoutCount() {
        composeRule.setContent {
            ScoreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f),
                ) {
                    val navigationState = rememberAppNavigationState()
                    val navigator = remember(navigationState) { AppNavigator(navigationState) }
                    AuthenticatedAppShell(navigationState, navigator, unreadAnnouncementCount = 100) {
                        Text("內容")
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("校園").assertIsDisplayed()
            .assert(androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "有未讀內容"))
    }

    @Test
    fun rootReselectScrollsWithoutRecreationOrExtraHaptic() {
        lateinit var scroll: androidx.compose.foundation.ScrollState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        var ticks = 0
        var rootCompositions = 0
        composeRule.setContent {
            ScoreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalHapticFeedback provides object : androidx.compose.ui.hapticfeedback.HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) { ticks++ }
                    },
                ) {
                    val state = rememberAppNavigationState()
                    val navigator = remember(state) { AppNavigator(state) }
                    val provider = entryProvider<NavKey> {
                        entry<OverviewRoute> { Text("首頁") }
                        entry<ScheduleRoute> { Text("課表首頁") }
                        entry<GradesRoute> { Text("成績首頁") }
                        entry<CampusRoute> {
                            scroll = androidx.compose.foundation.rememberScrollState()
                            scope = androidx.compose.runtime.rememberCoroutineScope()
                            androidx.compose.runtime.DisposableEffect(Unit) {
                                rootCompositions++
                                onDispose { }
                            }
                            RegisterRootScroll(scroll)
                            androidx.compose.foundation.layout.Column(
                                Modifier.verticalScroll(scroll),
                            ) { repeat(80) { Text("內容 $it") } }
                        }
                    }
                    AuthenticatedAppShell(state, navigator) {
                        NavDisplay(entries = state.toEntries(provider), onBack = { navigator.goBack() })
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("校園").performClick()
        composeRule.runOnIdle { scope.launch { scroll.scrollTo(500) } }
        composeRule.waitUntil { scroll.value > 0 }
        composeRule.onNodeWithContentDescription("校園").performClick()
        composeRule.waitUntil { scroll.value == 0 }
        composeRule.onNodeWithContentDescription("校園").performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(1, ticks)
            org.junit.Assert.assertEquals(1, rootCompositions)
        }
    }

    @Test
    fun rapidSwitchingSettlesOnLastDestination() {
        composeRule.setContent {
            ScoreTheme {
                val state = rememberAppNavigationState()
                val navigator = remember(state) { AppNavigator(state) }
                AuthenticatedAppShell(state, navigator) { Text("內容") }
            }
        }
        composeRule.mainClock.autoAdvance = false
        listOf("課表", "成績", "校園", "首頁", "成績", "課表", "校園").forEach {
            composeRule.onNodeWithContentDescription(it).performClick()
            composeRule.mainClock.advanceTimeBy(48)
        }
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithContentDescription("校園").assertIsSelected()
        composeRule.onNodeWithContentDescription("首頁").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("課表").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("成績").assertIsNotSelected()
    }

    @Test
    fun snackbarAndFabStayAboveFloatingNavigationWithZeroPageInsets() {
        composeRule.setContent {
            ScoreTheme {
                val state = rememberAppNavigationState()
                val navigator = remember(state) { AppNavigator(state) }
                val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
                AuthenticatedAppShell(state, navigator) {
                    PocketScaffold(
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
                        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
                        floatingActionButton = {
                            androidx.compose.material3.FloatingActionButton(onClick = {}) { Text("頁面動作") }
                        },
                    ) { Text("內容") }
                    androidx.compose.runtime.LaunchedEffect(Unit) { snackbar.showSnackbar("訊息") }
                }
            }
        }
        composeRule.onNodeWithText("訊息").assertIsDisplayed()
        val navigation = composeRule.onNodeWithContentDescription("首頁").fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithText("訊息").fetchSemanticsNode().boundsInRoot
        val fab = composeRule.onNodeWithText("頁面動作").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Snackbar $message overlaps navigation $navigation", message.bottom < navigation.top)
        org.junit.Assert.assertTrue("FAB $fab overlaps navigation $navigation", fab.bottom < navigation.top)
        // Keep device-rendered evidence for reviewing the shared overlay spacing.
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val evidence = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "floating-snackbar-fab.png")
        evidence.outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun commonChildRouteHasIndependentSaveableStateInEachStack() {
        lateinit var navigator: AppNavigator
        composeRule.setContent {
            ScoreTheme {
                val state = rememberAppNavigationState()
                navigator = remember(state) { AppNavigator(state) }
                val provider = entryProvider<NavKey> {
                    entry<OverviewRoute> {
                        Button(onClick = { navigator.navigate(PersonalRoute) }) { Text("首頁設定") }
                    }
                    entry<CampusRoute> {
                        Button(onClick = { navigator.navigate(PersonalRoute) }) { Text("校園設定") }
                    }
                    entry<ScheduleRoute> { Text("課表首頁") }
                    entry<GradesRoute> { Text("成績首頁") }
                    entry<PersonalRoute> {
                        var value by androidx.compose.runtime.saveable.rememberSaveable {
                            androidx.compose.runtime.mutableIntStateOf(0)
                        }
                        Button(onClick = { value++ }) { Text("設定值 $value") }
                    }
                }
                AuthenticatedAppShell(state, navigator) {
                    NavDisplay(entries = state.toEntries(provider), onBack = { navigator.goBack() })
                }
            }
        }
        composeRule.onNodeWithText("首頁設定").performClick()
        composeRule.onNodeWithText("設定值 0").performClick()
        composeRule.onNodeWithContentDescription("首頁").assertDoesNotExist()
        composeRule.runOnIdle { navigator.selectTopLevel(TopLevelDestination.Campus) }
        composeRule.onNodeWithText("校園設定").performClick()
        composeRule.onNodeWithText("設定值 0").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("校園").assertDoesNotExist()
        composeRule.runOnIdle { navigator.selectTopLevel(TopLevelDestination.Overview) }
        composeRule.onNodeWithText("設定值 1").assertIsDisplayed()
    }
}
