package com.clhs.score.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clhs.score.data.FakeData
import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.SimulatorCreditUiState

@Preview(name = "Grade load failure after Snackbar", widthDp = 390, heightDp = 844)
@Preview(name = "Grade load failure dark", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Grade load failure large text", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
private fun GradesLoadFailurePreview() {
    GradeLoadFailurePreviewContent(
        GradesUiState(
            structure = FakeData.structure,
            selectedYearValue = FakeData.CURRENT_YEAR_VALUE,
            selectedExamValue = FakeData.CURRENT_EXAM_VALUE,
            loadErrorMessage = "成績載入失敗，請稍後再試。",
        ),
    )
}

@Preview(name = "Exam list load failure", widthDp = 390, heightDp = 844)
@Composable
private fun GradesStructureFailurePreview() {
    GradeLoadFailurePreviewContent(
        GradesUiState(loadErrorMessage = "考試清單載入失敗，請稍後再試。"),
    )
}

@Composable
private fun GradeLoadFailurePreviewContent(state: GradesUiState) {
    ScoreTheme {
        GradesScreen(
            state = state,
            creditState = SimulatorCreditUiState(),
            snackbarHost = {},
            onSelectYear = {}, onSelectExam = {}, onReload = {}, onToggleSubject = {},
            onStartGradeReminder = {}, onStopGradeReminder = {}, onSetNotificationsEnabled = {},
            onGradeReminderPrerequisiteFailed = {}, onDismissGradeReminderChanges = {},
            onOpenPersonal = {}, onOpenScoreSimulator = {}, onOpenCreditSettings = {},
            onOpenSubjectTrend = {}, onExportGrades = {},
        )
    }
}

@Preview(name = "Grade subjects", widthDp = 390, heightDp = 844)
@Preview(name = "Grade subjects dark", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Grade subjects large text", widthDp = 320, heightDp = 900, fontScale = 2f)
@Composable
private fun GradeSubjectsBrowsingPreview() {
    ScoreTheme {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SubjectsTab(
                    analyses = buildGradeAnalysis(MockGradeSystem.generateReport()).subjects,
                    expandedSubjectKeys = emptySet(),
                    onToggleSubject = {},
                )
            }
        }
    }
}

@Preview(name = "Subject ranks large text", widthDp = 320, heightDp = 600, fontScale = 2f)
@Preview(name = "Subject ranks large text dark", widthDp = 320, heightDp = 600, fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SubjectRanksLargeTextPreview() {
    val analysis = buildGradeAnalysis(MockGradeSystem.generateReport()).subjects.first()
    ScoreTheme {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                SubjectCard(
                    analysis = analysis.copy(subject = analysis.subject.copy(
                        classRank = 31,
                        classRankCount = 38,
                        yearRank = 184,
                        yearRankCount = 226,
                    )),
                    expanded = false,
                    onToggle = {},
                )
            }
        }
    }
}
