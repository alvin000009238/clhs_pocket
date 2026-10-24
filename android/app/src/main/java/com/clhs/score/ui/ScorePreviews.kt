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
import com.clhs.score.domain.overview.withPreferences

@Preview(name = "首頁 · Compact", widthDp = 360, heightDp = 900, showBackground = true)
@Preview(name = "首頁 · Dark", widthDp = 390, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "首頁 · Medium", widthDp = 700, heightDp = 1100)
@Preview(name = "首頁 · Expanded", widthDp = 1100, heightDp = 1100)
@Preview(name = "首頁 · 1.3x", widthDp = 360, heightDp = 1000, fontScale = 1.3f)
@Preview(name = "首頁 · 1.5x", widthDp = 360, heightDp = 1000, fontScale = 1.5f)
@Preview(name = "首頁 · 2.0x", widthDp = 320, heightDp = 1000, fontScale = 2f)
@Composable
private fun OverviewPreview() {
    val now = java.time.LocalDateTime.of(2026, 10, 2, 22, 14)
    val instant = now.atZone(com.clhs.score.domain.overview.SCHOOL_ZONE).toInstant()
    val events = listOf(
        com.clhs.score.data.SchoolCalendarEvent("reading", "中學生網站閱讀心得寫作比賽",
            now.minusDays(7).toLocalDate().atStartOfDay(), now.plusDays(8).toLocalDate().atStartOfDay(), true),
        com.clhs.score.data.SchoolCalendarEvent("books", "好書換換愛",
            now.plusDays(26).toLocalDate().atStartOfDay(), now.plusDays(27).toLocalDate().atStartOfDay(), true),
    )
    val report = com.clhs.score.data.FakeScheduleData.report("114_2", "230", today = now.toLocalDate())
    val announcements = FakeData.announcementPage
    val state = com.clhs.score.domain.overview.OverviewState(
        now = now, isInitialLoading = false, hasSchedule = true,
        hero = com.clhs.score.domain.overview.scheduleOverviewHero(report, now),
        calendarEvents = events,
        items = com.clhs.score.domain.overview.calendarOverviewItems(
            com.clhs.score.data.SchoolCalendarSnapshot(com.clhs.score.data.SchoolCalendarFeed(events), instant), now,
        ) + com.clhs.score.domain.overview.announcementOverviewItems(announcements),
        weather = com.clhs.score.domain.overview.OverviewWeatherState.Available(
            com.clhs.score.domain.overview.WeatherSnapshot(com.clhs.score.data.WeatherSource.OPEN_METEO,
                "中壢區", 24, 27, "晴時多雲", 58, now, now.plusHours(1), instant,
                maximumTemperatureCelsius = 29, minimumTemperatureCelsius = 22)),
        youBike = com.clhs.score.data.YouBikeState(com.clhs.score.data.fakeYouBikeSnapshot(instant)),
    ).withPreferences(com.clhs.score.data.OverviewPreferences(countdownEventIds = events.map { it.id }.toSet()))
    ScoreTheme {
        com.clhs.score.ui.overview.OverviewScreen(state, {}, {},
            unreadAnnouncementIds = announcements.announcements.map { it.id }.toSet())
    }
}

@Preview(name = "首頁 · 無課表／來源失敗", widthDp = 360, heightDp = 900)
@Composable
private fun OverviewEmptyPreview() {
    ScoreTheme {
        com.clhs.score.ui.overview.OverviewScreen(
            com.clhs.score.domain.overview.OverviewState(isInitialLoading = false, calendarFailed = true,
                announcementsFailed = true, weather = com.clhs.score.domain.overview.OverviewWeatherState.Unavailable(),
                youBike = com.clhs.score.data.YouBikeState(failed = true)), {}, {})
    }
}

@Preview(name = "YouBike", showBackground = true, widthDp = 390)
@Composable
private fun YouBikePreview() {
    val now = java.time.Instant.parse("2026-10-02T04:43:03Z")
    ScoreTheme {
        com.clhs.score.ui.overview.YouBikeCard(
            com.clhs.score.data.YouBikeState(com.clhs.score.data.fakeYouBikeSnapshot(now)), now, {},
        )
    }
}

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
            onSetStartOnSchedule = {},
            onCycleProfileAvatarShape = {},
            onSetDynamicColor = {},
            onSetAmoledBlack = {},
            onSetNotificationsEnabled = {},
            onSaveBackgroundPause = { true },
            onSetPreferExactCourseAlarms = {},
            onRefreshAlarmSystemState = {},
            onCheckUpdate = {},
            onDownloadUpdate = {},
            onDismissUpdateDownloadResult = {},
            onDismissUpdateResult = {},
            onVersionTap = {},
            onDismissDeveloperToast = {},
            onDismissRestartDialog = {},
            onExportGrades = {},
            onDismissExportResult = {},
            onSetBiometricEnabled = { _, _ -> },
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
            creditState = com.clhs.score.viewmodel.SimulatorCreditUiState(
                identity = com.clhs.score.viewmodel.simulatorCreditIdentity(state.studentNo, state.selectedYearValue, state.selectedExamValue, state.report!!),
                appliedCredits = com.clhs.score.data.SimulatorCredits.defaultCredits(state.report.subjects).orEmpty(),
                isAppliedStateReliable = true,
            ),
            state = state.copy(
                expandedSubjectKeys = state.report.subjects
                    .take(1)
                    .map { cleanSubjectName(it.subjectName) }
                    .toSet()
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
            onOpenCreditSettings = {},
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
        val state = fakeGradesState(scenario = scenario)
        ScoreSimulatorScreen(
            state = state,
            snackbarHostState = androidx.compose.material3.SnackbarHostState(),
            onBack = {},
            creditState = com.clhs.score.viewmodel.SimulatorCreditUiState(
                identity = com.clhs.score.viewmodel.simulatorCreditIdentity(state.studentNo, state.selectedYearValue, state.selectedExamValue, state.report!!),
                inputs = com.clhs.score.data.SimulatorCredits.defaultCredits(state.report.subjects).orEmpty().mapValues { it.value.toString() },
                appliedCredits = com.clhs.score.data.SimulatorCredits.defaultCredits(state.report.subjects).orEmpty(),
                isLoaded = true,
            ),
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
