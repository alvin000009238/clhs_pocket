package com.clhs.score.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleScope
import com.clhs.score.ui.schedule.ScheduleScreen
import com.clhs.score.viewmodel.ScheduleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class RootFloatingScaffoldTest {
    @get:Rule val rule = createComposeRule()
    private val now = LocalDateTime.of(2026, 10, 9, 8, 30)
    private val brandDescription = "壢中 Pocket"

    @Test fun brandScrollsWithContentWhileActionsStayInPlace() {
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    RootFloatingScaffold(actions = { AccountIconButton({}) }) { padding, header ->
                        Column(Modifier.fillMaxSize().testTag("scroll")
                            .verticalScroll(rememberScrollState()).padding(padding)) {
                            header()
                            Spacer(Modifier.height(16.dp))
                            Text("內容起點", Modifier.testTag("first"))
                            Spacer(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        val before = rule.onNodeWithContentDescription(brandDescription).fetchSemanticsNode().boundsInRoot
        val actionsBefore = rule.onNodeWithContentDescription("個人").fetchSemanticsNode().boundsInRoot
        assertTrue(rule.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot.top > before.bottom)
        rule.onNodeWithTag("scroll").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val after = rule.onNodeWithContentDescription(brandDescription).fetchSemanticsNode().boundsInRoot
        assertTrue(after.top < before.top)
        assertEquals(actionsBefore, rule.onNodeWithContentDescription("個人").fetchSemanticsNode().boundsInRoot)
        assertTrue(rule.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot.top < before.top)
    }

    @Test fun narrowPhoneWithLargeTextKeepsThreeActionsClearOfTheBrand() {
        rule.setContent {
            ScoreTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        RootFloatingScaffold(actions = {
                            repeat(3) { index ->
                                IconButton({}, Modifier.testTag("action$index")) { Text("$index") }
                            }
                        }) { padding, header -> Column(Modifier.padding(padding).padding(horizontal = 16.dp)) { header() } }
                    }
                }
            }
        }
        val brand = rule.onNodeWithContentDescription(brandDescription).fetchSemanticsNode().boundsInRoot
        val action = rule.onNodeWithTag("action0").fetchSemanticsNode().boundsInRoot
        assertTrue(brand.right <= action.left)
        assertTrue(brand.height >= with(rule.density) { 48.dp.toPx() })
        repeat(3) { rule.onNodeWithTag("action$it").assertHasClickAction() }
    }

    @Test fun embeddedScheduleKeepsDetailsAndScrollingAvailable() {
        val report = FakeScheduleData.report("114_1", "230", ScheduleScope.CURRENT_WEEK, now.toLocalDate())
        rule.setContent {
            ScoreTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ScheduleScreen(ScheduleUiState(isInitialLoading = false, report = report),
                        onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
                        onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {})
                }
            }
        }
        val course = rule.onNode(hasContentDescription("週一，第 1 節", substring = true))
        course.assertIsDisplayed().performClick()
        rule.onNodeWithText("張三", substring = true).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        val before = course.fetchSemanticsNode().boundsInRoot
        val scroll = rule.onNode(hasScrollAction())
        val range = scroll.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        if (range.maxValue() > 0f) {
            scroll.performTouchInput { swipeUp() }
            rule.waitForIdle()
            assertTrue(course.fetchSemanticsNode().boundsInRoot.top < before.top)
        } else {
            // A tall native viewport can show the whole timetable without scrolling.
            rule.onAllNodes(hasContentDescription("第 8 節", substring = true))[0].assertIsDisplayed()
        }
        rule.onNodeWithContentDescription("個人").assertHasClickAction()
    }
}
