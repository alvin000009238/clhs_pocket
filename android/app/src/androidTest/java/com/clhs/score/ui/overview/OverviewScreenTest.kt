package com.clhs.score.ui.overview

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.domain.overview.OverviewContext
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewHero
import com.clhs.score.domain.overview.OverviewPriority
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.ui.theme.ScoreTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime
import com.clhs.score.data.SchoolCalendarEvent
import org.junit.Assert.assertTrue
import com.clhs.score.data.YouBikeState
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import androidx.compose.ui.test.hasProgressBarRangeInfo

class OverviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun publicWidgetsReloadFromTheCardAndShowStatusAtTheTopRight() {
        val weather = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
        val refreshing = mutableStateOf(false)
        var bikeRequests = 0
        var weatherRequests = 0
        composeRule.setContent {
            ScoreTheme {
                Column(Modifier.width(328.dp)) {
                    YouBikeCard(YouBikeState(isRefreshing = refreshing.value), java.time.Instant.now(),
                        onRefresh = { bikeRequests++; refreshing.value = true },
                        modifier = Modifier.height(112.dp), expanded = false)
                    OverviewWidgetContent(weather, OverviewState(weatherLocations = emptyMap()),
                        isLoggedIn = false, editing = false, onLogin = {}, onOpenDestination = {},
                        onWeatherSettings = {}, onRetry = {}, onRetryWeather = { weatherRequests++ },
                        modifier = Modifier.height(112.dp))
                }
            }
        }
        composeRule.onNodeWithText("中壢高中 YouBike").performClick()
        composeRule.onNodeWithText("天氣").performClick()
        composeRule.runOnIdle {
            assertEquals(1, bikeRequests)
            assertEquals(1, weatherRequests)
        }
        val title = composeRule.onNodeWithText("中壢高中 YouBike", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = composeRule.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        assertTrue(status.left >= title.right && kotlin.math.abs(status.top - title.top) < 2f)
        composeRule.onNodeWithContentDescription("重新載入 YouBike").assertDoesNotExist()
    }

    @Test
    fun enlargedPhoneKeepsDateBesideTitle() {
        val today = LocalDateTime.of(2026, 10, 5, 0, 0)
        val event = SchoolCalendarEvent("event", "中學生網站閱讀心得比賽",
            LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 10, 10, 0, 0), true)
        val item = OverviewItem(id = event.id, priority = OverviewPriority.P1,
            kind = OverviewItemKind.CalendarEvent, title = event.title, supportingText = "進行中",
            label = "", icon = "event", destination = OverviewDestination.CalendarEvent(event.id))
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                ScoreTheme {
                    Box(Modifier.width(379.dp)) {
                        EventGroup(listOf(item), listOf(event), today.toLocalDate(), false, false, onOpenDestination = {})
                    }
                }
            }
        }
        val date = composeRule.onNodeWithText("09/01\n～10/09", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText(event.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(date.right <= title.left)
    }

    @Test
    fun rendersOnlyInformationPresentInStateAndRoutesItsCard() {
        var opened: OverviewDestination? = null
        composeRule.setContent {
            ScoreTheme {
                OverviewScreen(
                    state = OverviewState(
                        now = LocalDateTime.of(2026, 8, 24, 8, 30),
                        context = OverviewContext.InClass,
                        isInitialLoading = false,
                        hero = OverviewHero(
                            date = LocalDateTime.of(2026, 8, 24, 0, 0),
                            headline = "正在上課",
                            courseLabel = "第 1 節",
                            courseName = "國文",
                            supportingText = "201",
                        ),
                        items = listOf(
                            OverviewItem(
                                id = "current-class",
                                priority = OverviewPriority.P0,
                                kind = OverviewItemKind.CurrentClass,
                                title = "正在上 國文",
                                supportingText = "08:10-09:00 · 201",
                                label = "第 1 節",
                                icon = "school",
                                destination = OverviewDestination.Schedule,
                            ),
                        ),
                    ),
                    onOpenPersonal = {},
                    onOpenDestination = { opened = it },
                )
            }
        }

        composeRule.onNodeWithText("8 月 24 日 · 星期一").assertIsDisplayed()
        composeRule.onNodeWithText("上課中").assertIsDisplayed()
        composeRule.onNodeWithText("國文").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText("置頂公告").assertCountEquals(0)
        composeRule.runOnIdle { assertEquals(OverviewDestination.Schedule, opened) }
    }

    @Test
    fun guestCanSignInAndOpenCalendarWithoutOverviewItems() {
        var loginRequests = 0
        var opened: OverviewDestination? = null
        composeRule.setContent {
            ScoreTheme {
                OverviewScreen(
                    state = OverviewState(isInitialLoading = false),
                    authState = AuthState.Guest,
                    onRequestLogin = { loginRequests++ },
                    onOpenPersonal = {},
                    onOpenDestination = { opened = it },
                )
            }
        }
        composeRule.onNodeWithText("使用學校帳號登入").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, loginRequests) }
        composeRule.onNodeWithText("目前沒有可顯示的近期行程").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("查看全部")[0].performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(OverviewDestination.Calendar, opened) }
    }

    @Test
    fun quickConsecutiveSectionDragsKeepTheFinalOrder() {
        val preferences = mutableStateOf(OverviewPreferences())
        composeRule.setContent {
            ScoreTheme {
                OverviewSectionOrderEditor(
                    preferences = preferences.value,
                    enabled = true,
                    onChange = { preferences.value = it },
                )
            }
        }
        val dragDistance = with(composeRule.density) { 64.dp.toPx() }

        repeat(2) {
            composeRule.onNodeWithContentDescription("拖曳調整課表摘要 順序").performTouchInput {
                down(center)
                moveBy(Offset(0f, dragDistance), delayMillis = 100)
                up()
            }
        }

        composeRule.runOnIdle {
            assertEquals(
                listOf("weather", "alerts", "schedule"),
                preferences.value.orderedSections().take(3),
            )
        }
    }
}
