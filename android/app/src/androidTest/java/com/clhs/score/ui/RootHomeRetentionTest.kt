package com.clhs.score.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.clhs.score.ui.navigation.*
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RootHomeRetentionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rapidRootSwitchesKeepTheRetainedLazyHomePainted() {
        var selected: NavKey by mutableStateOf(OverviewRoute)
        val widget = com.clhs.score.domain.overview.OverviewWidgetInstance.create("weather")
        rule.setContent {
            ScoreTheme {
                CompositionLocalProvider(LocalRootBodyMotionEnabled provides true) {
                    val keys = if (selected == OverviewRoute) listOf(OverviewRoute) else listOf(OverviewRoute, selected)
                    NavDisplay(entries = keys.map { key ->
                        NavEntry(key, metadata = topLevelNavigationMetadata() + mapOf(RootSceneDestinationKey to key)) {
                            if (key == OverviewRoute) com.clhs.score.ui.overview.OverviewWidgetGrid(
                                widgets = listOf(widget), editing = false, enabled = true,
                                onBeginEdit = {}, onMove = { _, _ -> }, onChange = {}, onConfigure = {},
                                onRemove = {}, onDuplicate = {},
                            ) {
                                Box(Modifier.rootBodyMotion().fillMaxSize()
                                    .background(androidx.compose.ui.graphics.Color.Red).testTag("home-cell"))
                            } else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Blue))
                        }
                    }, onBack = {}, sceneStrategies = remember { listOf(RootSceneStrategy()) })
                }
            }
        }
        val bounds = rule.onNodeWithTag("home-cell").fetchSemanticsNode().boundsInRoot
        rule.mainClock.autoAdvance = false
        for (destination in listOf(ScheduleRoute, GradesRoute, CampusRoute, ScheduleRoute, GradesRoute, CampusRoute)) {
            rule.runOnIdle { selected = destination }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            rule.runOnIdle { selected = OverviewRoute }
            rule.mainClock.advanceTimeByFrame()
            repeat(3) { frame ->
                rule.mainClock.advanceTimeByFrame()
                val pixels = rule.onRoot().captureToImage().asAndroidBitmap()
                assertEquals("Home lazy cell disappeared returning from $destination at frame $frame",
                    android.graphics.Color.RED, pixels.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
            }
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun returningFromEachRootReusesHomeAndHidesItsInactiveContent() {
        var selected: NavKey by mutableStateOf(OverviewRoute)
        var homeStarts = 0
        var homeStops = 0
        var homeBacks = 0
        var navigationBacks = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        var homeLifecycle: Lifecycle? = null
        rule.setContent {
            ScoreTheme {
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val keys = if (selected == OverviewRoute) listOf(OverviewRoute) else listOf(OverviewRoute, selected)
                NavDisplay(entries = keys.map { key ->
                    NavEntry(key, metadata = topLevelNavigationMetadata() + mapOf(RootSceneDestinationKey to key)) {
                        Box(Modifier.fillMaxSize()) {
                            if (key == OverviewRoute) {
                                homeLifecycle = LocalLifecycleOwner.current.lifecycle
                                BackHandler { homeBacks++ }
                                DisposableEffect(Unit) {
                                    homeStarts++
                                    onDispose { homeStops++ }
                                }
                                Text("首頁內容")
                            } else Text("其他內容")
                        }
                    }
                }, onBack = { navigationBacks++; selected = OverviewRoute }, sceneStrategies = remember { listOf(RootSceneStrategy()) })
            }
        }
        rule.onNodeWithText("首頁內容").assertIsDisplayed()
        for (destination in listOf(ScheduleRoute, GradesRoute, CampusRoute)) {
            rule.runOnIdle { selected = destination }
            rule.waitForIdle()
            rule.onNodeWithText("其他內容").assertIsDisplayed()
            rule.runOnIdle {
                assertEquals("Home was disposed on root switch", 0, homeStops)
                assertEquals(Lifecycle.State.CREATED, homeLifecycle?.currentState)
            }
            rule.onNodeWithText("首頁內容").assertDoesNotExist()
            rule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals("Hidden home intercepted system back", 0, homeBacks) }
            rule.waitForIdle()
            rule.onNodeWithText("首頁內容").assertIsDisplayed()
            rule.runOnIdle { assertEquals("Home was rebuilt on return", 1, homeStarts) }
        }
        rule.runOnIdle { assertEquals(3, navigationBacks) }
    }
}
