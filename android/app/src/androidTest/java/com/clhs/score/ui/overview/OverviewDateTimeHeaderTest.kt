package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetConfig
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class OverviewDateTimeHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun restoredScrollStartsAtTheDockWithoutAnEntranceMorph() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30),
                        with(rule.density) { 200.dp.toPx() },
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val bounds = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 128.dp.toPx() }, bounds.width, 1f)
        assertEquals(with(rule.density) { 32.dp.toPx() }, bounds.top, 1f)
    }

    @Test fun editModeUsesTheCompactClockWithoutScrolling() {
        val editing = mutableStateOf(false)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), 0f,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp,
                        forceCompact = editing.value)
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { editing.value = true }
        rule.mainClock.advanceTimeByFrame()
        val bounds = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 128.dp.toPx() }, bounds.width, 1f)
        assertEquals(with(rule.density) { 32.dp.toPx() }, bounds.top, 1f)
    }

    @Test fun normalLayoutChangesNeverAnimateWidgetsThroughTheHeader() {
        val headerHeight = mutableStateOf(96.dp)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewWidgetGrid(listOf(OverviewWidgetInstance.create("schedule")), false, true,
                        {}, { _, _ -> }, {}, {}, {}, {}, header = { Spacer(Modifier.height(headerHeight.value)) }) {
                        Box(Modifier.fillMaxWidth().height(112.dp).testTag("first"))
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { headerHeight.value = 204.dp }
        repeat(20) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            assertTrue(rule.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot.top >=
                with(rule.density) { 204.dp.toPx() } - 1f)
        }
    }

    @Test fun swipingTheDateCardScrollsTheGrid() {
        val offset = mutableFloatStateOf(0f)
        val scroll = mutableStateOf<ScrollableState?>(null)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewWidgetGrid(emptyList(), false, true, {}, { _, _ -> }, {}, {}, {}, {},
                        header = { Spacer(Modifier.height(1000.dp)) },
                        onScrollOffsetChanged = { offset.floatValue = it },
                        onScrollStateReady = { scroll.value = it }) {}
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp,
                        modifier = scroll.value?.let { Modifier.scrollable(it, Orientation.Vertical) } ?: Modifier)
                }
            }
        }
        rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30").performTouchInput { swipeUp() }
        rule.runOnIdle { assertTrue(offset.floatValue > 0f) }
    }

    @Test fun cardScrollsBeforeDockingAndSettlesWithoutOscillatingAroundTheDock() {
        val offset = mutableFloatStateOf(0f)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val card = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
        val expanded = card.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { offset.floatValue = with(rule.density) { 12.dp.toPx() } }
        val scrolling = card.fetchSemanticsNode().boundsInRoot
        assertEquals(expanded.width, scrolling.width, 1f)
        assertEquals(expanded.top - with(rule.density) { 12.dp.toPx() }, scrolling.top, 1f)
        rule.runOnIdle { offset.floatValue = with(rule.density) { 72.dp.toPx() } }
        val beforeDock = card.fetchSemanticsNode().boundsInRoot
        assertEquals(expanded.width, beforeDock.width, 1f)
        assertEquals(expanded.top - with(rule.density) { 72.dp.toPx() }, beforeDock.top, 1f)
        rule.runOnIdle { offset.floatValue = with(rule.density) { 112.dp.toPx() } }
        val docked = card.fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 128.dp.toPx() }, docked.width, 1f)
        assertEquals(with(rule.density) { 32.dp.toPx() }, docked.top, 1f)
        assertEquals(with(rule.density) { 48.dp.toPx() }, docked.height, 1f)
        rule.runOnIdle { offset.floatValue = with(rule.density) { 104.dp.toPx() } }
        assertEquals(docked, card.fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { offset.floatValue = 0f }
        assertEquals(expanded, card.fetchSemanticsNode().boundsInRoot)
    }
    @Test fun headerMorphDoesNotMoveWidgetsOrChangeScrollRange() {
        val offset = mutableFloatStateOf(0f)
        val scroll = mutableStateOf<ScrollableState?>(null)
        val widgets = listOf(OverviewWidgetInstance.create("schedule").copy(id = "first")) +
            (1..8).map { OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(id = "widget-$it") }
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewWidgetGrid(widgets, false, true, {}, { _, _ -> }, {}, {}, {}, {},
                        modifier = Modifier.testTag("grid"),
                        header = { Spacer(Modifier.height(204.dp)) },
                        onScrollOffsetChanged = { offset.floatValue = it },
                        onScrollStateReady = { scroll.value = it }) {
                        Box(Modifier.fillMaxWidth().height(112.dp).testTag(it.id))
                    }
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val rangeKey = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        fun range() = rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(rangeKey), useUnmergedTree = true)
            .fetchSemanticsNode().config[rangeKey].maxValue()
        val initialRange = range()
        rule.mainClock.autoAdvance = false
        for (target in listOf(112.dp, 80.dp, 112.dp, 0.dp)) {
            rule.runOnIdle { scroll.value!!.dispatchRawDelta(offset.floatValue - with(rule.density) { target.toPx() }) }
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val expectedTop = with(rule.density) { (204.dp - target).toPx() }
            repeat(30) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                assertEquals(expectedTop, rule.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot.top, 1f)
                assertEquals(initialRange, range(), 1f)
            }
            if (target == 112.dp) {
                val headerBottom = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
                    .fetchSemanticsNode().boundsInRoot.bottom
                assertEquals(with(rule.density) { 12.dp.toPx() }, expectedTop - headerBottom, 1f)
            }
        }
    }
    @Test fun fastScrollKeepsTheMorphAtTheDockInsteadOfPullingItOffscreen() {
        val offset = mutableFloatStateOf(0f)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val card = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { offset.floatValue = with(rule.density) { 600.dp.toPx() } }
        repeat(30) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue("Fast scroll pulled the clock above its docking trajectory: $bounds",
                bounds.top >= with(rule.density) { (-16).dp.toPx() } - 1f)
            assertTrue("Clock bottom left the dock: $bounds",
                bounds.bottom >= with(rule.density) { 80.dp.toPx() } - 1f)
        }
    }
    @Test fun clockRemainsVisibleThroughEveryFrameOfTheMorph() {
        val offset = mutableFloatStateOf(0f)
        var clockColor = Color.Unspecified
        rule.setContent {
            ScoreTheme {
                clockColor = MaterialTheme.colorScheme.primary
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val card = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
        rule.mainClock.autoAdvance = false
        for (target in listOf(112.dp, 0.dp)) {
            rule.runOnIdle { offset.floatValue = with(rule.density) { target.toPx() } }
            repeat(30) { frame ->
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                val pixels = card.captureToImage().toPixelMap()
                var clockPixels = 0
                for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                    val color = pixels[x, y]
                    if (abs(color.red - clockColor.red) < .02f &&
                        abs(color.green - clockColor.green) < .02f &&
                        abs(color.blue - clockColor.blue) < .02f) clockPixels++
                }
                assertTrue("The clock disappeared at frame $frame toward $target", clockPixels > 5)
            }
        }
    }
    @Test fun headerWidthSettlesWithoutStretchingBackAfterReachingThePill() {
        val offset = mutableFloatStateOf(0f)
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    OverviewDateTimeHeader(LocalDateTime.of(2026, 10, 8, 8, 30), offset.floatValue,
                        expandedWidth = 328.dp, expandedLeft = 16.dp, expandedTop = 96.dp,
                        expandedHeight = 96.dp, dockTop = 32.dp, dockWidth = 128.dp)
                }
            }
        }
        val card = rule.onNodeWithContentDescription("10 月 8 日，星期四，08:30")
        rule.mainClock.autoAdvance = false
        for (target in listOf(112.dp, 0.dp)) {
            var previous = card.fetchSemanticsNode().boundsInRoot.width
            rule.runOnIdle { offset.floatValue = with(rule.density) { target.toPx() } }
            repeat(60) { frame ->
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
                val width = card.fetchSemanticsNode().boundsInRoot.width
                if (target > 0.dp) assertTrue("Pill stretched back at frame $frame: $previous -> $width", width <= previous + 1f)
                else assertTrue("Expanded card bounced back at frame $frame: $previous -> $width", width >= previous - 1f)
                previous = width
            }
            assertEquals(with(rule.density) { (if (target > 0.dp) 128.dp else 328.dp).toPx() }, previous, 1f)
        }
    }
}
