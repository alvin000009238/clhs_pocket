package com.clhs.score.ui.overview

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

class OverviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersOnlyInformationPresentInStateAndRoutesItsCard() {
        var opened: OverviewDestination? = null
        composeRule.setContent {
            ScoreTheme {
                OverviewScreen(
                    state = OverviewState(
                        context = OverviewContext.InClass,
                        isInitialLoading = false,
                        hero = OverviewHero(
                            date = LocalDateTime.of(2026, 8, 24, 0, 0),
                            headline = "正在上課",
                            courseLabel = "第 1 節",
                            courseName = "國文",
                            trailingText = "08:10–09:00",
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
        composeRule.onNodeWithText("現在").assertIsDisplayed()
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
    }}
