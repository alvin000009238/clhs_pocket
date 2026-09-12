package com.clhs.score.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.clhs.score.data.SubjectAnalysis
import com.clhs.score.data.cleanSubjectName

@Composable
internal fun SubjectsTab(
    analyses: List<SubjectAnalysis>,
    expandedSubjectKeys: Set<String>,
    onToggleSubject: (String) -> Unit,
) {
    var pendingBringIntoViewKey by remember { mutableStateOf<String?>(null) }

    Column {
        analyses.forEach { analysis ->
            val subjectKey = cleanSubjectName(analysis.subject.subjectName)
            val expanded = subjectKey in expandedSubjectKeys
            SubjectCard(
                analysis = analysis,
                expanded = expanded,
                bringIntoViewOnExpand = pendingBringIntoViewKey == subjectKey,
                onBringIntoViewHandled = {
                    if (pendingBringIntoViewKey == subjectKey) {
                        pendingBringIntoViewKey = null
                    }
                },
                onToggle = {
                    pendingBringIntoViewKey = if (expanded) null else subjectKey
                    onToggleSubject(analysis.subject.subjectName)
                },
            )
        }
    }
}
