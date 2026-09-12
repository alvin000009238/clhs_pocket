package com.clhs.score.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.clhs.score.data.ExamOption
import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.StudentScenario
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.data.buildGradeTrend
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.GradesUiState
import org.junit.Rule
import org.junit.Test

class ScoreUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun welcomeScreenExposesPrimaryEntryPoint() {
        var clicked = false
        composeRule.setContent {
            ScoreTheme {
                OnboardingWelcomeScreen(onContinue = { clicked = true })
            }
        }

        composeRule.onNodeWithText("壢中 Pocket").assertIsDisplayed()
        composeRule.onNodeWithText("開始使用").performClick()
        composeRule.runOnIdle {
            assert(clicked)
        }
    }

    @Test
    fun accountScreenExposesBackEntryPointWhenProvided() {
        var backClicked = false
        composeRule.setContent {
            ScoreTheme {
                OnboardingAccountScreen(
                    onBack = { backClicked = true },
                    onLogin = {},
                    onSkip = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("返回")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assert(backClicked)
        }
    }

    @Test
    fun accountLoginRemainsReachableInSmallWindowWithLargeText() {
        var clicked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ScoreTheme {
                    Box(Modifier.size(320.dp)) {
                        OnboardingAccountScreen(
                            onBack = {},
                            onLogin = { clicked = true },
                            onSkip = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("使用學校帳號登入")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assert(clicked)
        }
    }

    @Test
    fun webViewProcessingOverlayKeepsNavigationTouch() {
        var backClicked = false
        composeRule.setContent {
            ScoreTheme {
                WebViewLoginScreen(
                    isProcessingLogin = true,
                    errorMessage = null,
                    onLoginSuccess = { _, _ -> },
                    onBack = { backClicked = true },
                    onDismissError = {},
                )
            }
        }
        settleUi()

        composeRule.onNodeWithContentDescription("返回").performTouchInput { click() }
        composeRule.runOnIdle {
            assert(backClicked)
        }
    }

    @Test
    fun biometricUnlockOptionsRemainReachableInSmallWindowWithLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ScoreTheme {
                    Box(Modifier.size(320.dp)) {
                        BiometricLockScreen(
                            isBiometricInvalidated = false,
                            onTriggerBiometric = {},
                            onUnlockWithPin = {},
                            onLogout = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("生物識別解鎖").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("使用備用密碼解鎖").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("登出").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun gradesScreenUsesAdaptiveNavigationAndSegmentedOverview() {
        composeRule.setContent {
            ScoreTheme {
                TestGradesScreen()
            }
        }
        settleUi()

        composeRule.onNodeWithText("總覽").assertIsDisplayed()
        composeRule.onNodeWithText("科目").assertIsDisplayed()
        composeRule.onNodeWithText("分析").assertIsDisplayed()
        composeRule.onNodeWithText("114-上").assertIsDisplayed()
        composeRule.onNodeWithText("期末考").assertIsDisplayed()
        composeRule.onAllNodesWithText("全部科目").assertCountEquals(0)
        composeRule.onAllNodesWithText("圖表").assertCountEquals(0)
        composeRule.onAllNodesWithText("加權平均").assertCountEquals(1)
        composeRule.onAllNodesWithText("班排 15/38 ・ 類排 88/226").assertCountEquals(1)
        composeRule.onNodeWithText("表現亮點").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("近期變化").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("ROI").assertCountEquals(0)
        composeRule.onAllNodesWithText("科目分析").assertCountEquals(0)
        composeRule.onAllNodesWithText("圖表分析").assertCountEquals(0)
        composeRule.onAllNodesWithText("更多資料").assertCountEquals(0)
    }

    @Test
    fun overviewRemainsReachableInSmallWindowWithLargeText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ScoreTheme {
                    Box(Modifier.size(width = 320.dp, height = 640.dp)) {
                        TestGradesScreen()
                    }
                }
            }
        }
        settleUi()

        composeRule.onNodeWithText("期末考").assertIsDisplayed()
        composeRule.onAllNodesWithText("班級前", substring = true)[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("類組前", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("表現亮點").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("近期變化").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("段考更新提醒").assertIsDisplayed()
        composeRule.onNodeWithText("匯出成績").assertIsDisplayed()
    }

    @Test
    fun subjectCardExpandsDetails() {
        composeRule.setContent {
            ScoreTheme {
                TestGradesScreen()
            }
        }

        composeRule.onNodeWithText("科目").performClick()
        settleUi()
        composeRule.onAllNodesWithText("班級五標").assertCountEquals(0)
        composeRule.onNodeWithText("國文").performScrollTo().performClick()
        settleUi()
        composeRule.onNodeWithText("班級五標").assertIsDisplayed()
        composeRule.onNodeWithText("分數分布").assertIsDisplayed()
        composeRule.onNodeWithText("上次成績").assertIsDisplayed()

        composeRule.onNodeWithText("數學").performScrollTo().performClick()
        settleUi()
        composeRule.onAllNodesWithText("班級五標").assertCountEquals(1)

        composeRule.onNodeWithText("數學").performScrollTo().performClick()
        settleUi()
        composeRule.onAllNodesWithText("班級五標").assertCountEquals(0)
    }

    @Test
    fun navigationSwitchesBetweenSubjectsAndAdvanced() {
        composeRule.setContent {
            ScoreTheme {
                TestGradesScreen()
            }
        }
        settleUi()

        composeRule.onNodeWithText("科目").performClick()
        settleUi()
        composeRule.onNodeWithText("國文").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("分析").performClick()
        settleUi()
        composeRule.onNodeWithText("成績模擬器").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun overviewShowsTrendLoadingAndNoHistoryStates() {
        var isLoadingTrend by mutableStateOf(true)
        var showTrend by mutableStateOf(true)
        var trendError by mutableStateOf<String?>(null)
        composeRule.setContent {
            ScoreTheme {
                TestGradesScreen(
                    isLoadingTrend = isLoadingTrend,
                    showTrend = showTrend,
                    trendError = trendError,
                )
            }
        }
        settleUi()
        composeRule.onNodeWithText("正在載入歷次趨勢...").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle {
            isLoadingTrend = false
            showTrend = false
            trendError = "尚無歷次趨勢可比較"
        }
        settleUi()
        composeRule.onNodeWithText("尚無歷次趨勢可比較").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun analysisSectionShowsChartsAndStandards() {
        composeRule.setContent {
            ScoreTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val report = MockGradeSystem.generateReport()
                    AnalysisSection(report = report, analysis = buildGradeAnalysis(report))
                }
            }
        }

        composeRule.onNodeWithText("雷達分析").assertIsDisplayed()
        composeRule.onNodeWithText("成績比較").assertIsDisplayed()
        composeRule.onNodeWithText("五標分析").performScrollTo().assertIsDisplayed()
    }

    private fun settleUi() {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    @Composable
    private fun TestGradesScreen(
        isLoadingTrend: Boolean = false,
        showTrend: Boolean = true,
        trendError: String? = null,
    ) {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val analysis = buildGradeAnalysis(report)
        val trend = if (showTrend) buildGradeTrend(
            currentExamName = "期末考",
            currentReport = report,
            previousReports = listOf("期中考" to MockGradeSystem.generateReport(
                StudentScenario.NORMAL,
                customScores = listOf(78.0, 80.0, 61.0, 70.0, 60.0, 60.0, 60.0)
            )),
        ) else null
        var expanded by remember { mutableStateOf(emptySet<String>()) }
        GradesScreen(
            state = GradesUiState(
                studentNo = "DEMO-000",
                structure = listOf(
                    YearTermOption(
                        text = "114學年度 上學期",
                        value = "114_1",
                        exams = listOf(ExamOption("期中考", "E1"), ExamOption("期末考", "E2")),
                    ),
                ),
                selectedYearValue = "114_1",
                selectedExamValue = "E2",
                report = report,
                analysis = analysis,
                isLoadingTrend = isLoadingTrend,
                trendError = trendError,
                trend = trend,
                expandedSubjectKeys = expanded,
            ),
            snackbarHost = {},
            onSelectYear = {},
            onSelectExam = {},
            onReload = {},
            onToggleSubject = { subjectName ->
                val key = cleanSubjectName(subjectName)
                expanded = if (key in expanded) emptySet() else setOf(key)
            },
            onStartGradeReminder = {},
            onStopGradeReminder = {},
            onSetNotificationsEnabled = {},
            onGradeReminderPrerequisiteFailed = {},
            onDismissGradeReminderChanges = {},
            onOpenPersonal = {},
            onOpenScoreSimulator = {},
            onOpenSubjectTrend = {},
            onExportGrades = {},
        )
    }
}
