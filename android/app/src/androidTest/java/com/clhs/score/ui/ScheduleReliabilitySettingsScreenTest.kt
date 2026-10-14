package com.clhs.score.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScheduleReliabilitySettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun readyStateDoesNotRequireBatteryExemptionOrShowProgress() {
        content()
        composeRule.onNodeWithText("設定就緒").assertExists()
        composeRule.onNodeWithText("已允許").assertExists()
        composeRule.onNodeWithText("電池最佳化").assertDoesNotExist()
        composeRule.onNodeWithText("進度", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("2 / 2", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("還差一步").assertDoesNotExist()
    }

    @Test
    fun disabledPreferenceShowsFallbackWithoutPermissionPrompt() {
        content(exact = false, access = false)
        composeRule.onNodeWithText("使用一般排程").assertExists()
        composeRule.onNodeWithText("課程通知與小工具更新可能延遲。").assertExists()
        composeRule.onNodeWithText("允許鬧鐘與提醒").assertDoesNotExist()
    }

    @Test
    fun pendingAccessHasAnActionAndBecomesQuietAfterGrant() {
        val access = mutableStateOf(false)
        var requests = 0
        var toggles = 0
        composeRule.setContent {
            ScoreTheme {
                ScheduleReliabilitySettingsContent(
                    exactOptIn = true, exactAccess = access.value,
                    needsExactSpecialAccess = true, batteryExempt = false,
                    onBack = {}, onToggleExact = { toggles++ }, onManageBattery = {},
                    onManageExact = { requests++ },
                )
            }
        }
        composeRule.onNodeWithText("還差一步").assertExists()
        scrollTo("允許鬧鐘與提醒")
        composeRule.onNodeWithText("允許鬧鐘與提醒").performClick()
        composeRule.runOnIdle {
            assertEquals(1, requests)
            assertEquals(0, toggles)
            access.value = true
        }
        composeRule.onNodeWithText("還差一步").assertDoesNotExist()
        composeRule.onNodeWithText("在系統設定中允許後返回。").assertDoesNotExist()
        scrollTo("設定就緒")
        composeRule.onNodeWithText("設定就緒").assertExists()
    }

    @Test
    fun switchOnlyChangesTheAppPreference() {
        var requested: Boolean? = null
        content(exact = false, access = false, onToggle = { requested = it })
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        composeRule.runOnIdle { assertEquals(true, requested) }
    }

    @Test
    fun batteryOptimizationIsOptionalAndCollapsed() {
        content()
        scrollTo("進階選項")
        composeRule.onNodeWithText("進階選項").performClick()
        scrollTo("電池最佳化")
        composeRule.onNodeWithText("系統預設").assertExists()
        composeRule.onNodeWithText("需設定").assertDoesNotExist()
        scrollTo("進階選項")
        composeRule.onNodeWithText("進階選項").performClick()
        composeRule.onNodeWithText("電池最佳化").assertDoesNotExist()
    }

    @Test
    fun unavailableSettingsShowManualAlternative() {
        content(access = false, unavailable = true)
        scrollTo("無法開啟")
        composeRule.onNodeWithText("請至 Android 設定搜尋「鬧鐘與提醒」。").assertExists()
        composeRule.onNodeWithText("設定就緒").assertDoesNotExist()
    }

    @Test
    fun olderAndroidOmitsUnneededPermissionRow() {
        content(access = false, specialAccess = false)
        composeRule.onNodeWithText("設定就緒").assertExists()
        composeRule.onNodeWithText("系統授權").assertDoesNotExist()
        composeRule.onNodeWithText("允許鬧鐘與提醒").assertDoesNotExist()
    }

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private fun content(
        exact: Boolean = true,
        access: Boolean = true,
        specialAccess: Boolean = true,
        unavailable: Boolean = false,
        onToggle: (Boolean) -> Unit = {},
    ) {
        composeRule.setContent {
            ScoreTheme {
                ScheduleReliabilitySettingsContent(
                    exactOptIn = exact, exactAccess = access,
                    needsExactSpecialAccess = specialAccess, batteryExempt = false,
                    onBack = {}, onToggleExact = onToggle, onManageBattery = {},
                    exactSettingsUnavailable = unavailable,
                )
            }
        }
    }
}
