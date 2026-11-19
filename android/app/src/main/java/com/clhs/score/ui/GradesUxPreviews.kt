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
import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.ui.theme.ScoreTheme

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
