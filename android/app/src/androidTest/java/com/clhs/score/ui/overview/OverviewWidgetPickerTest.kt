package com.clhs.score.ui.overview

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class OverviewWidgetPickerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun singletonStaysDisabledWhileWeatherCanBeAddedAgain() {
        val existing = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
        var chosen: OverviewWidgetInstance? = null
        rule.setContent {
            ScoreTheme {
                OverviewWidgetPicker(listOf(OverviewWidgetInstance.create("schedule"), existing),
                    onChoose = { chosen = it }, onDismiss = {})
            }
        }
        rule.onNodeWithText("課表摘要").assertIsNotEnabled()
        rule.onNodeWithText("天氣").performClick()
        rule.runOnIdle {
            assertEquals("weather", chosen?.type)
            assertNotEquals(existing.id, chosen?.id)
            assertNull(chosen?.config())
        }
    }

    @Test fun hiddenWidgetIsReusedWithItsConfigurationIntact() {
        val existing = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(enabled = false)
        var chosen: OverviewWidgetInstance? = null
        rule.setContent {
            ScoreTheme {
                OverviewWidgetPicker(listOf(existing), onChoose = { chosen = it }, onDismiss = {})
            }
        }
        rule.onNodeWithText("天氣").performClick()
        rule.runOnIdle { assertEquals(existing, chosen) }
    }
}
