package com.clhs.score.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.clhs.score.ui.navigation.topLevelNavigationMetadata
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RootHeaderTransitionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun returningToScheduleDrawsItsEmbeddedGridDuringTheTransition() {
        var page by mutableIntStateOf(1)
        val report = com.clhs.score.data.FakeScheduleData.report("114_1", "230",
            com.clhs.score.data.ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 10, 9))
        rule.setContent {
            ScoreTheme {
                CompositionLocalProvider(LocalRootBodyMotionEnabled provides true) {
                    NavDisplay(backStack = listOf(page), onBack = {}, entryProvider = { key ->
                        NavEntry(key, metadata = topLevelNavigationMetadata()) {
                            if (key == 1) com.clhs.score.ui.schedule.ScheduleScreen(
                                com.clhs.score.viewmodel.ScheduleUiState(isInitialLoading = false, report = report),
                                onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
                                onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {})
                            else RootFloatingScaffold(actions = {}) { padding, header ->
                                Column(Modifier.padding(padding)) { header(); Text("其他頁面") }
                            }
                        }
                    })
                }
            }
        }
        val cell = rule.onNode(hasContentDescription("週一，第 1 節", substring = true))
            .fetchSemanticsNode().boundsInRoot
        val x = cell.left.toInt() + 8
        val y = cell.top.toInt() + 8
        val expected = rule.onRoot().captureToImage().asAndroidBitmap().getPixel(x, y)
        rule.runOnIdle { page = 0 }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { page = 1 }
        rule.mainClock.advanceTimeByFrame()
        repeat(12) { frame ->
            rule.mainClock.advanceTimeByFrame()
            assertEquals("Embedded timetable is hidden at frame $frame", expected,
                rule.onRoot().captureToImage().asAndroidBitmap().getPixel(x, y))
        }
        rule.mainClock.autoAdvance = true
    }

    @Test fun rootSwitchKeepsBrandAndActionsOpaqueAndStationary() {
        var page by mutableIntStateOf(0)
        rule.setContent {
            ScoreTheme {
                val metadata = topLevelNavigationMetadata()
                CompositionLocalProvider(LocalRootBodyMotionEnabled provides true) {
                    Box(Modifier.size(360.dp, 640.dp)) {
                        NavDisplay(backStack = listOf(page), onBack = {}, entryProvider = { key ->
                            NavEntry(key, metadata = metadata) {
                                RootFloatingScaffold(actions = { AccountIconButton({}, animateShape = false) }) { padding, header ->
                                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                                        header()
                                        Box(Modifier.rootBodyMotion().fillMaxWidth().height(160.dp)
                                            .background(if (key == 0) Color.Blue else Color.Red)
                                            .testTag("內容 $key")) { Text("內容 $key") }
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        val bounds = rule.onNodeWithContentDescription("壢中 Pocket").fetchSemanticsNode().boundsInRoot
        fun headerPixels(): Bitmap {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            return Bitmap.createBitmap(bitmap, 0, bounds.top.toInt(), bitmap.width, bounds.height.toInt())
        }
        val before = headerPixels()
        val body = rule.onNodeWithTag("內容 0").fetchSemanticsNode().boundsInRoot
        val bodyX = body.center.x.toInt()
        val bodyY = body.center.y.toInt()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { page = 1 }
        rule.mainClock.advanceTimeByFrame()
        repeat(12) { frame ->
            rule.mainClock.advanceTimeByFrame()
            assertTrue("Header changed during root transition at frame $frame", before.sameAs(headerPixels()))
            val pixels = rule.onRoot().captureToImage().asAndroidBitmap()
            assertEquals("Incoming body is hidden at frame $frame",
                android.graphics.Color.RED, pixels.getPixel(bodyX, bodyY))
        }
        rule.mainClock.autoAdvance = true
    }
}
