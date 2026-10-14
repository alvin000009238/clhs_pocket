package com.clhs.score.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Touch paths explored on Pixel_9_Pro_Visual; no coordinates or model latency in execution. */
class PocketNavigationDragTest {
    @get:Rule val rule = createComposeRule()
    private val selected = mutableStateOf(TopLevelDestination.Overview)
    private var commits = 0
    private var taps = 0
    private var ticks = 0

    private fun show(compact: Boolean = false) {
        rule.setContent {
            ScoreTheme {
                CompositionLocalProvider(LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks++ }
                }) {
                PocketFloatingNavigation(
                    destinations = TopLevelDestination.entries,
                    selected = selected.value, hasUnread = true, compact = compact,
                    onSelect = { taps++; selected.value = it },
                    onDragSelect = { commits++; selected.value = it },
                )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun previewDoesNotNavigateAndReleaseCommitsOnce() {
        show()
        val origin = rule.onNodeWithContentDescription("首頁")
        val width = origin.fetchSemanticsNode().boundsInRoot.width
        val start = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        origin.performTouchInput {
            down(center)
            moveTo(center + Offset(width, 0f), delayMillis = 150)
            moveTo(center + Offset(width * 2f, 0f), delayMillis = 150)
            moveTo(center + Offset(width * 3f, 0f), delayMillis = 150)
        }
        origin.assertIsSelected()
        rule.runOnIdle { assertEquals(0, commits) }
        val preview = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("start=$start preview=$preview slot=$width", preview > start + width * 2f)
        origin.performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("校園").assertIsSelected()
        rule.runOnIdle { assertEquals(1, commits); assertEquals(0, taps); assertEquals(3, ticks) }
    }

    @Test fun cancellationRestoresOriginalAnchorAndTapReselectSurvives() {
        show()
        val origin = rule.onNodeWithContentDescription("首頁")
        val width = origin.fetchSemanticsNode().boundsInRoot.width
        val start = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        origin.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 2f, 0f), delayMillis = 200)
            cancel()
        }
        rule.waitForIdle()
        origin.assertIsSelected()
        val restored = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(start, restored, 1f)
        origin.performClick()
        rule.runOnIdle { assertEquals(0, commits); assertEquals(1, taps) }
    }

    @Test fun compactBadgeDragBackAndImmediateTap() {
        show(compact = true)
        rule.onNodeWithContentDescription("校園").performClick()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val campus = rule.onNodeWithContentDescription("校園")
        val width = campus.fetchSemanticsNode().boundsInRoot.width
        campus.performTouchInput {
            down(center)
            moveTo(center - Offset(width * 3f, 0f), delayMillis = 350)
            up()
        }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithContentDescription("首頁").assertIsSelected()
        rule.onNodeWithContentDescription("成績").performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("成績").assertIsSelected()
        rule.runOnIdle { assertEquals(1, commits); assertEquals(2, taps) }
    }

    @Test fun unselectedItemCannotStartNavigationDrag() {
        show()
        val campus = rule.onNodeWithContentDescription("校園")
        val width = campus.fetchSemanticsNode().boundsInRoot.width
        campus.performTouchInput {
            down(center)
            moveTo(center - Offset(width * 2f, 0f), delayMillis = 300)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("首頁").assertIsSelected()
        rule.runOnIdle { assertEquals(0, commits); assertEquals(0, taps) }
    }

    @Test fun shortDragAndReversalReturnWithoutCommit() {
        show()
        val origin = rule.onNodeWithContentDescription("首頁")
        val width = origin.fetchSemanticsNode().boundsInRoot.width
        origin.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.55f, 0f), delayMillis = 300)
            up()
        }
        rule.waitForIdle()
        origin.assertIsSelected()
        origin.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 2f, 0f), delayMillis = 300)
            moveTo(center, delayMillis = 300)
            up()
        }
        rule.waitForIdle()
        origin.assertIsSelected()
        rule.runOnIdle { assertEquals(0, commits) }
    }

    @Test fun dragInterruptsTapTransitionWithoutReturningToOldAnchor() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("課表").performClick()
        rule.mainClock.advanceTimeBy(64)
        val schedule = rule.onNodeWithContentDescription("課表")
        val width = schedule.fetchSemanticsNode().boundsInRoot.width
        val before = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.x
        schedule.performTouchInput {
            down(center)
            moveTo(center + Offset(width, 0f), delayMillis = 100)
        }
        rule.mainClock.advanceTimeBy(32)
        val during = rule.onNodeWithTag("PocketNavigationIndicator", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.center.x
        assertTrue("before=$before during=$during", during > before)
        schedule.performTouchInput { cancel() }
        rule.mainClock.advanceTimeBy(1500)
        rule.mainClock.autoAdvance = true
        schedule.assertIsSelected()
        rule.runOnIdle { assertEquals(0, commits) }
    }
}
