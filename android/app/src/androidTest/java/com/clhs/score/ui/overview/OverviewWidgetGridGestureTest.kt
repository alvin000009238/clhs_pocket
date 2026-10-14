package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.clhs.score.domain.overview.OverviewLayoutEngine
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.height
import org.junit.Rule
import org.junit.Test

class OverviewWidgetGridGestureTest {
    @get:Rule val rule = createComposeRule()

    private fun grid(initial: List<OverviewWidgetInstance>, editing: Boolean = true, onBeginEdit: () -> Unit = {},
        onClick: () -> Unit = {}, reveal: MutableState<String?> = mutableStateOf(null),
        headerHeight: Dp = 0.dp, width: Dp = 360.dp,
        naturalHeight: Dp = 112.dp) = mutableStateOf(initial).also { widgets ->
        rule.setContent {
            ScoreTheme {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(width, 640.dp)) {
                    OverviewWidgetGrid(widgets.value, editing = editing, enabled = true,
                        onBeginEdit = onBeginEdit,
                        onMove = { id, index -> widgets.value = OverviewLayoutEngine.move(widgets.value, id, index) },
                        onChange = { changed -> widgets.value = widgets.value.map { if (it.id == changed.id) changed else it } },
                        onConfigure = {}, onRemove = {}, onDuplicate = {},
                        modifier = Modifier.testTag("grid"), revealWidgetId = reveal.value,
                        onWidgetRevealed = { reveal.value = null },
                        header = { Box(Modifier.height(headerHeight).testTag("grid-header")) }) {
                        Surface(onClick = onClick, modifier = Modifier.testTag("content-${it.id}").then(
                            if (com.clhs.score.domain.overview.OverviewWidgetDefinitions[it.type]?.multiple == false)
                                Modifier.height(naturalHeight) else Modifier.fillMaxSize(),
                        )) { Text(it.id) }
                    }
                }
            }
        }
    }

    private fun point(x: Int, y: Int) = with(rule.density) { Offset(x.dp.toPx(), y.dp.toPx()) }

    @Test fun wideningYouBikeKeepsAdjacentCountdownBeforeFollowingSections() {
        val widgets = grid(listOf(
            OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike(com.clhs.score.data.YouBikeStation.SCHOOL))
                .copy(id = "bike", size = OverviewWidgetSize(2, 1)),
            OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("Exam", "2026-10-14"))
                .copy(id = "countdown", size = OverviewWidgetSize(2, 1)),
            OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems()).copy(id = "calendar"),
            OverviewWidgetInstance.create("announcements", OverviewWidgetConfig.ListItems()).copy(id = "announcements"),
        ), headerHeight = 96.dp, naturalHeight = 400.dp)
        val target = rule.onNodeWithTag("grid")
        val originalTop = rule.onNodeWithTag("content-countdown", useUnmergedTree = true).getUnclippedBoundsInRoot().top
        target.performTouchInput { down(point(165, 199)); moveTo(point(350, 199), delayMillis = 200) }
        rule.runOnIdle {
            assertEquals(OverviewWidgetSize(4, 1), widgets.value.first().size)
            assertEquals(listOf("bike", "countdown", "calendar", "announcements"), widgets.value.map { it.id })
        }
        val countdown = rule.onNodeWithTag("content-countdown", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val calendar = rule.onNodeWithTag("content-calendar", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(124f, (countdown.top - originalTop).value, 1f)
        assertTrue(countdown.bottom <= calendar.top)
        target.performTouchInput { moveTo(point(165, 199), delayMillis = 200); up() }
        rule.runOnIdle { assertEquals(OverviewWidgetSize(2, 1), widgets.value.first().size) }
        assertEquals(originalTop, rule.onNodeWithTag("content-countdown", useUnmergedTree = true).getUnclippedBoundsInRoot().top)
    }

    @Test fun compactWidgetKeepsItsHeightBesideNaturalContentOnWideGrid() {
        grid(listOf(
            OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems()).copy(id = "calendar"),
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = "compact", size = OverviewWidgetSize(2, 1)),
        ), width = 640.dp, naturalHeight = 400.dp)
        val compact = rule.onNodeWithTag("content-compact", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(112f, (compact.bottom - compact.top).value, 0.1f)
    }

    @Test fun movingHeldPreviewWithinItsSlotDoesNotRecomposeDashboard() {
        grid(listOf(
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = "compact", size = OverviewWidgetSize(2, 1)),
            OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems()).copy(id = "calendar"),
        ), width = 640.dp, naturalHeight = 400.dp, headerHeight = 96.dp)
        val target = rule.onNodeWithTag("grid")
        target.performTouchInput { down(point(60, 140)); advanceEventTime(700); moveTo(point(60, 140)) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.onNodeWithTag("overview-drag-preview", useUnmergedTree = true).assertExists()
        val before = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        repeat(8) { index ->
            target.performTouchInput { moveTo(point(60 + index, 140 + index)) }
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
        }
        val after = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        target.performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        assertEquals("Moving the overlay must not recompose the dashboard", before, after)
    }

    @Test fun newlyAddedWidgetIsRevealedWithoutManualScrolling() {
        val reveal = mutableStateOf<String?>(null)
        val widgets = grid((1..8).map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = "existing-$it", size = OverviewWidgetSize(4, 1))
        }, reveal = reveal, headerHeight = 96.dp)
        repeat(2) { index ->
            val id = "added-$index"
            rule.runOnIdle {
                widgets.value += OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                    .copy(id = id, size = OverviewWidgetSize(4, 2))
                reveal.value = id
            }
            rule.onNodeWithText(id, useUnmergedTree = true).assertIsDisplayed()
            rule.runOnIdle { assertEquals(null, reveal.value) }
        }
    }

    @Test fun headerScrollsAwayAndDragUsesItsMeasuredOffset() {
        val widgets = grid((1..8).map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = "widget-$it", size = OverviewWidgetSize(4, 1))
        }, headerHeight = 96.dp)
        val target = rule.onNodeWithTag("grid")
        val firstTop = rule.onNodeWithText("widget-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertEquals(with(rule.density) { 96.dp.toPx() }, firstTop - target.fetchSemanticsNode().boundsInRoot.top, 1f)
        target.performTouchInput {
            down(point(100, 152)); advanceEventTime(700); moveTo(point(100, 296)); up()
        }
        rule.runOnIdle { assertEquals(listOf("widget-2", "widget-1"), widgets.value.take(2).map { it.id }) }
        target.performTouchInput {
            down(point(280, 500)); moveTo(point(280, 200), delayMillis = 200); up()
        }
        rule.onNodeWithTag("grid-header", useUnmergedTree = true).assertIsNotDisplayed()
        val scrollRange = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val offset = rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(scrollRange), useUnmergedTree = true)
            .fetchSemanticsNode().config[scrollRange].value()
        assertTrue(offset > with(rule.density) { 96.dp.toPx() })
    }

    @Test fun listCardLongPressDoesNotEnterEditMode() {
        var editRequests = 0
        var opened = 0
        grid(listOf("calendar", "announcements").map {
            OverviewWidgetInstance.create(it, OverviewWidgetConfig.ListItems())
        } + OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(size = OverviewWidgetSize(4, 1)),
            editing = false, onBeginEdit = { editRequests++ }, onClick = { opened++ })
        val target = rule.onNodeWithTag("grid")
        for (y in listOf(56, 180)) target.performTouchInput { down(point(100, y)); advanceEventTime(700); up() }
        rule.runOnIdle { assertEquals(0, editRequests); assertEquals(0, opened) }
        target.performTouchInput { down(point(100, 56)); advanceEventTime(100); up() }
        rule.runOnIdle { assertEquals(1, opened) }
        target.performTouchInput { down(point(100, 304)); advanceEventTime(700); up() }
        rule.runOnIdle { assertEquals(1, editRequests) }
    }

    @Test fun oneHeldDragCanCrossTwoWidgets() {
        val widgets = grid(listOf("a", "b", "c").map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(id = it, size = OverviewWidgetSize(4, 1))
        })
        val target = rule.onNodeWithTag("grid")
        target.performTouchInput {
            down(point(100, 56)); advanceEventTime(700); moveTo(point(100, 200))
        }
        // The held drag runs an edge-scroll frame loop. Freeze its clock while
        // checking the intermediate layout, then resume after releasing.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("b", "a", "c"), widgets.value.map { it.id }) }
        target.performTouchInput { moveTo(point(100, 340)); up() }
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { assertEquals(listOf("b", "c", "a"), widgets.value.map { it.id }) }
    }

    @Test fun hoveringAfterReorderDoesNotOscillate() {
        val widgets = grid(listOf("a", "b", "c").map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = it, size = if (it == "a") OverviewWidgetSize(2, 2) else OverviewWidgetSize(4, 1))
        })
        val target = rule.onNodeWithTag("grid")
        target.performTouchInput { down(point(70, 80)); advanceEventTime(700); moveTo(point(70, 290)) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val order = widgets.value.map { it.id }
        repeat(12) {
            target.performTouchInput { moveTo(point(70, 290 + it % 2)) }
            rule.mainClock.advanceTimeBy(32)
            rule.runOnIdle { assertEquals(order, widgets.value.map { it.id }) }
        }
        target.performTouchInput { up() }
        rule.mainClock.autoAdvance = true
    }

    @Test fun droppingBetweenRowsAutomaticallyReservesARow() {
        val widgets = grid(listOf("a", "b", "c").map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = it, size = if (it == "a") OverviewWidgetSize(2, 1) else OverviewWidgetSize(4, 1))
        })
        rule.onNodeWithTag("grid").performTouchInput {
            down(point(70, 56)); advanceEventTime(700); moveTo(point(70, 242)); up()
        }
        rule.runOnIdle {
            assertEquals(listOf("b", "a", "c"), widgets.value.map { it.id })
            assertTrue(widgets.value.first { it.id == "a" }.ownRow)
            val placements = OverviewLayoutEngine.pack(widgets.value, 4)
            assertTrue(placements.first { it.id == "c" }.row > placements.first { it.id == "a" }.row)
        }
    }

    @Test fun secondFingerScrollsWhileFirstFingerKeepsHolding() {
        grid((1..10).map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(id = "widget-$it", size = OverviewWidgetSize(4, 1))
        })
        val target = rule.onNodeWithTag("grid")
        target.performTouchInput { down(0, point(70, 56)); advanceEventTime(700); moveTo(0, point(70, 180)) }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val before = rule.onNodeWithTag("overview-drag-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(1, point(280, 500)); moveTo(1, point(280, 250), delayMillis = 200) }
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        val key = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val offset = rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true).fetchSemanticsNode().config[key].value()
        assertTrue(offset > with(rule.density) { 200.dp.toPx() })
        val after = rule.onNodeWithTag("overview-drag-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.left, after.left, 1f)
        target.performTouchInput { up(1); moveTo(0, point(70, 300)); up(0) }
        rule.mainClock.autoAdvance = true
    }

    @Test fun cornerResizeCanGrowAndShrinkWithoutLifting() {
        val widgets = grid(listOf("a", "b").map {
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
                .copy(id = it, size = if (it == "a") OverviewWidgetSize(2, 1) else OverviewWidgetSize(4, 1))
        })
        val target = rule.onNodeWithTag("grid")
        val originalTop = rule.onNodeWithText("b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        target.performTouchInput { down(point(165, 103)); moveTo(point(245, 150), delayMillis = 200) }
        val preview = rule.onNodeWithTag("overview-resize-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val width = with(rule.density) { preview.width.toDp().value }
        assertTrue("Preview width was $width dp", width > 250f && width < 260f)
        rule.runOnIdle { assertEquals(OverviewWidgetSize(2, 1), widgets.value.first().size) }
        target.performTouchInput { moveTo(point(350, 240), delayMillis = 200) }
        rule.runOnIdle { assertEquals(OverviewWidgetSize(4, 2), widgets.value.first().size) }
        val expandedTop = rule.onNodeWithText("b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertEquals(with(rule.density) { 124.dp.toPx() }, expandedTop - originalTop, 2f)
        target.performTouchInput { moveTo(point(165, 103), delayMillis = 200) }
        rule.runOnIdle { assertEquals(OverviewWidgetSize(2, 1), widgets.value.first().size) }
        assertEquals(originalTop, rule.onNodeWithText("b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top, 2f)
        val returned = rule.onNodeWithTag("overview-resize-preview", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(174f, with(rule.density) { returned.width.toDp().value }, 1f)
        target.performTouchInput { moveTo(point(350, 240), delayMillis = 200); up() }
        rule.runOnIdle { assertEquals(OverviewWidgetSize(4, 2), widgets.value.first().size) }
        target.performTouchInput { down(point(350, 230)); moveTo(point(165, 103), delayMillis = 200); up() }
        rule.runOnIdle { assertEquals(OverviewWidgetSize(2, 1), widgets.value.first().size) }
    }
}
