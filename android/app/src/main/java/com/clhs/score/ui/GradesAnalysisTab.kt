package com.clhs.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clhs.score.data.GradeAnalysis
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeTrend

@Composable
internal fun AdvancedTab(
    report: GradeReport,
    analysis: GradeAnalysis,
    isLoadingTrend: Boolean,
    trendError: String?,
    trend: GradeTrend?,
    onOpenScoreSimulator: () -> Unit,
    onOpenSubjectTrend: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TrendChart(
            isLoadingTrend = isLoadingTrend,
            trendError = trendError,
            trend = trend,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "工具",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ScoreSimulatorEntryCard(
                report = report,
                analysis = analysis,
                onOpen = onOpenScoreSimulator,
            )
            SubjectTrendEntryCard(onOpen = onOpenSubjectTrend)
        }
    }
}
