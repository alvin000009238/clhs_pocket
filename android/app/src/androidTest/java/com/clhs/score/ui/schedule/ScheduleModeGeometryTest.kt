package com.clhs.score.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleDisplayMode
import com.clhs.score.data.ScheduleScope
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.ScheduleUiState
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScheduleModeGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun scopeSwitchKeepsToggleAndFirstPeriodStationary() = verifyGeometry(360, 1f)
    @Test fun narrowLargeTextKeepsSameGeometryAcrossScopes() = verifyGeometry(280, 1.6f)
    @Test fun dayViewKeepsToggleAndFirstPeriodStationary() = verifyGeometry(360, 1f, ScheduleDisplayMode.DAY)
    @Test fun narrowDayViewKeepsSameGeometryAcrossScopes() = verifyGeometry(280, 1.6f, ScheduleDisplayMode.DAY)

    private fun verifyGeometry(width: Int, fontScale: Float, mode: ScheduleDisplayMode = ScheduleDisplayMode.WEEK) {
        val date = LocalDate.of(2026, 11, 9)
        var scope by mutableStateOf(ScheduleScope.CURRENT_WEEK)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ScoreTheme {
                    Box(Modifier.size(width.dp, 780.dp)) {
                        val report = FakeScheduleData.report("115_1", "230", scope, date)
                        ScheduleScreen(ScheduleUiState(isInitialLoading = false, report = report, selectedScope = scope, displayMode = mode),
                            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = { scope = it },
                            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {})
                    }
                }
            }
        }
        val toggle = rule.onNodeWithContentDescription(if (mode == ScheduleDisplayMode.WEEK) "目前整週顯示，切換為單日顯示" else "目前單日顯示，切換為整週顯示")
        val firstPeriod = rule.onAllNodes(hasContentDescription("第 1 節", substring = true)).onFirst()
        val originalToggle = toggle.fetchSemanticsNode().boundsInRoot
        val originalTop = firstPeriod.fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithContentDescription("選擇課表類型").performClick()
        rule.onNodeWithText("顯示學期固定排課基準").performClick()
        rule.waitForIdle()
        assertEquals(originalToggle, toggle.fetchSemanticsNode().boundsInRoot)
        assertEquals(originalTop, firstPeriod.fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithText("08:10\n09:00", useUnmergedTree = true).assertExists()
        rule.onNodeWithContentDescription("選擇課表類型").performClick()
        rule.onNodeWithText("顯示當週調課、加課與停課異動").performClick()
        rule.waitForIdle()
        assertEquals(originalToggle, toggle.fetchSemanticsNode().boundsInRoot)
        assertEquals(originalTop, firstPeriod.fetchSemanticsNode().boundsInRoot.top)
    }
}
