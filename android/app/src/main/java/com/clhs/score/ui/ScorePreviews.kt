package com.clhs.score.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.clhs.score.data.AppSettings
import com.clhs.score.data.FakeData
import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.StudentScenario
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.data.buildGradeTrend
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.SchoolAnnouncementDetailUiState
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.viewmodel.LoginUiState
import com.clhs.score.viewmodel.SettingsUiState

class ScenarioProvider : PreviewParameterProvider<StudentScenario> {
    override val values = sequenceOf(
        StudentScenario.NORMAL,
        StudentScenario.EXCELLENT,
        StudentScenario.STRUGGLING,
        StudentScenario.SPECIAL
    )
}

class AnnouncementStateProvider : PreviewParameterProvider<SchoolAnnouncementsUiState> {
    override val values = sequenceOf(
        SchoolAnnouncementsUiState(),
        SchoolAnnouncementsUiState(
            isInitialLoading = false,
            announcements = FakeData.announcementPage.announcements,
            pageIndex = 0,
            hasMore = true,
            lastUpdatedAt = FakeData.announcementPage.fetchedAt,
        ),
        SchoolAnnouncementsUiState(isInitialLoading = false),
        SchoolAnnouncementsUiState(
            isInitialLoading = false,
            errorMessage = "暫時無法取得學校消息，請檢查網路後再試一次。",
        ),
    )
}

@Preview(name = "School Announcements", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SchoolAnnouncementsPreview(
    @PreviewParameter(AnnouncementStateProvider::class) state: SchoolAnnouncementsUiState,
) {
    ScoreTheme {
        com.clhs.score.ui.announcements.SchoolAnnouncementsScreen(
            uiState = state,
            onBack = {},
            onRefresh = {},
            onLoadMore = {},
            onSearch = {},
            onClearSearch = {},
            onOpenAnnouncement = {},
            onOpenOfficialWebsite = {},
            onOpenAnnouncementReminder = {},
            onNoticeShown = {},
        )
    }
}

@Preview(name = "School Announcement Detail", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun SchoolAnnouncementDetailPreview() {
    ScoreTheme {
        com.clhs.score.ui.announcements.SchoolAnnouncementDetailScreen(
            uiState = SchoolAnnouncementDetailUiState(
                isLoading = false,
                detail = FakeData.announcementDetail,
            ),
            onBack = {},
            onRetry = {},
            onOpenUrl = {},
            officialUrl = FakeData.announcementDetail.officialUrl,
        )
    }
}

@Preview(name = "Score App - Fake Data", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun ScoreAppFakePreview(
    @PreviewParameter(ScenarioProvider::class) scenario: StudentScenario
) {
    ScoreTheme {
        val context = androidx.compose.ui.platform.LocalContext.current
        val scoreViewModel: com.clhs.score.viewmodel.ScoreViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            factory = com.clhs.score.viewmodel.ScoreViewModel.factory(context, useFakeData = true)
        )
        ScoreApp(
            scoreViewModel = scoreViewModel,
            loginState = LoginUiState(),
            gradesState = fakeGradesState(scenario = scenario),
            authState = AuthState.Authenticated(1L),
            settings = AppSettings(hasCompletedOnboarding = true),
            settingsUiState = SettingsUiState(),
            launchTarget = null,
            onLaunchTargetHandled = {},
            onGradeTargetOpened = { _, _ -> },
            onWebViewLoginSuccess = { _, _ -> },
            onCompleteOnboarding = {},
            onSelectYear = {},
            onSelectExam = {},
            onReload = {},
            onLogout = {},
            onRestartOnboarding = {},
            onToggleSubject = {},
            onDismissLoginError = {},
            onDismissGradesError = {},
            onStartGradeReminder = {},
            onStopGradeReminder = {},
            onGradeReminderPrerequisiteFailed = {},
            onDismissGradeReminderError = {},
            onDismissGradeReminderChanges = {},
            onSetThemeMode = {},
            onSetDynamicColor = {},
            onSetAmoledBlack = {},
            onSetNotificationsEnabled = {},
            onCheckUpdate = {},
            onDownloadUpdate = {},
            onDismissUpdateDownloadResult = {},
            onDismissUpdateResult = {},
            onVersionTap = {},
            onDismissDeveloperToast = {},
            onSetDemoMode = {},
            onDismissRestartDialog = {},
            onExportGrades = {},
            onDismissExportResult = {},
            onSetBiometricEnabled = { _, _ -> },
            onSetWeatherSource = {},
            onSaveCwaApiKey = {},
            onClearCwaApiKey = {},
            onDismissCwaKeyMessage = {},
        )
    }
}

@Preview(name = "Grades Screen - Fake Data", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "Grades Screen - Large Text", showBackground = true, widthDp = 320, heightDp = 900, fontScale = 2f)
@Preview(name = "Grades Screen - Medium Window", showBackground = true, widthDp = 700, heightDp = 600)
@Preview(name = "Grades Screen - Expanded Window", showBackground = true, widthDp = 1200, heightDp = 800)
@Composable
private fun GradesScreenFakePreview(
    @PreviewParameter(ScenarioProvider::class) scenario: StudentScenario
) {
    val state = fakeGradesState(scenario = scenario)
    ScoreTheme {
        GradesScreen(
            state = state.copy(
                expandedSubjectKeys = state.report?.subjects
                    ?.take(1)
                    ?.map { cleanSubjectName(it.subjectName) }
                    ?.toSet() ?: emptySet()
            ),
            snackbarHost = {},
            onSelectYear = {},
            onSelectExam = {},
            onReload = {},
            onToggleSubject = {},
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

@Preview(name = "Score Simulator - Fake Data", showBackground = true, widthDp = 390, heightDp = 844)
@Preview(name = "Score Simulator - Landscape", showBackground = true, widthDp = 844, heightDp = 390)
@Preview(name = "Score Simulator - Tablet", showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun ScoreSimulatorFakePreview(
    @PreviewParameter(ScenarioProvider::class) scenario: StudentScenario
) {
    ScoreTheme {
        ScoreSimulatorScreen(
            state = fakeGradesState(scenario = scenario),
            snackbarHostState = androidx.compose.material3.SnackbarHostState(),
            onBack = {},
        )
    }
}

@Preview(name = "Subject Card - Fake Data", showBackground = true, widthDp = 390)
@Composable
private fun SubjectCardFakePreview(
    @PreviewParameter(ScenarioProvider::class) scenario: StudentScenario
) {
    val report = MockGradeSystem.generateReport(scenario)
    val analysis = buildGradeAnalysis(report)
    val subjectAnalysis = analysis.subjects.firstOrNull() ?: return
    
    ScoreTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            SubjectCard(
                analysis = subjectAnalysis,
                expanded = true,
                onToggle = {}
            )
        }
    }
}

private fun fakeGradesState(
    scenario: StudentScenario = StudentScenario.NORMAL,
    expandedSubjectKeys: Set<String> = emptySet(),
): GradesUiState {
    val report = MockGradeSystem.generateReport(scenario)
    val previous = FakeData.previousReport()
    val trend = buildGradeTrend(
        currentExamName = report.examSummary?.examName.orEmpty().ifBlank { "期末考" },
        currentReport = report,
        previousReports = FakeData.trendReports(),
    )
    val analysis = buildGradeAnalysis(
        report = report,
        comparisonReport = previous,
        previousExamName = previous.examSummary?.examName,
    )
    return GradesUiState(
        studentNo = FakeData.session.studentNo,
        structure = FakeData.structure,
        selectedYearValue = FakeData.CURRENT_YEAR_VALUE,
        selectedExamValue = FakeData.CURRENT_EXAM_VALUE,
        report = report,
        comparisonReport = previous,
        comparisonExamName = previous.examSummary?.examName,
        trendReports = FakeData.trendReports().map { it.second },
        trendHistoryLabel = "近 3 次段考",
        trend = trend,
        simulatorHistoryReports = FakeData.simulatorHistoryReports(),
        simulatorHistoryLabel = "近 3 次段考",
        analysis = analysis,
        expandedSubjectKeys = expandedSubjectKeys,
    )
}
