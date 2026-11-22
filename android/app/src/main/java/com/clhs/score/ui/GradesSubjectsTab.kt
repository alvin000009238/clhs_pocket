package com.clhs.score.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.clhs.score.data.SubjectAnalysis
import com.clhs.score.data.cleanSubjectName

internal enum class GradeSubjectSort(val label: String) {
    SchoolOrder("預設"),
    ScoreDescending("分數高到低"),
    ScoreAscending("分數低到高"),
}

internal fun gradeSubjectsForDisplay(
    analyses: List<SubjectAnalysis>,
    sort: GradeSubjectSort,
    onlyFlunked: Boolean,
): List<SubjectAnalysis> {
    val filtered = if (onlyFlunked) analyses.filter { it.subject.flunk } else analyses
    if (sort == GradeSubjectSort.SchoolOrder) return filtered
    // Match the card's blank-display fallback, but never treat an unpublished label as a score.
    fun SubjectAnalysis.score() = if (subject.scoreDisplay.isBlank()) {
        subject.scoreValue
    } else {
        subject.scoreDisplay.toDoubleOrNull()
    }
    return filtered.sortedWith(compareBy<SubjectAnalysis> { it.score() == null }.thenBy {
        val score = it.score() ?: 0.0
        if (sort == GradeSubjectSort.ScoreDescending) -score else score
    })
}

@Composable
internal fun SubjectsTab(
    analyses: List<SubjectAnalysis>,
    expandedSubjectKeys: Set<String>,
    onToggleSubject: (String) -> Unit,
) {
    var pendingBringIntoViewKey by remember { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(GradeSubjectSort.SchoolOrder) }
    var onlyFlunked by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val visibleSubjects = remember(analyses, sort, onlyFlunked) {
        gradeSubjectsForDisplay(analyses, sort, onlyFlunked)
    }
    val flunkedCount = analyses.count { it.subject.flunk }

    Column {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !onlyFlunked, onClick = { onlyFlunked = false },
                label = { Text("全部 ${analyses.size}") })
            FilterChip(selected = onlyFlunked, onClick = { onlyFlunked = true },
                label = { Text("未達及格 $flunkedCount") })
            Box {
                TextButton(onClick = { showSortMenu = true }) {
                    Text(sort.label)
                    OutlinedRoundedSymbol(icon = "keyboard_arrow_down", contentDescription = "科目排序")
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    GradeSubjectSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            modifier = Modifier.semantics { selected = sort == option },
                            trailingIcon = {
                                if (sort == option) FilledRoundedSymbol(icon = "check", contentDescription = null)
                            },
                            onClick = {
                                sort = option
                                showSortMenu = false
                            },
                        )
                    }
                }
            }
        }
        if (visibleSubjects.isEmpty()) {
            Text(
                text = if (onlyFlunked && analyses.isNotEmpty()) "這次沒有未達及格的科目" else "此考試尚無科目成績",
                modifier = Modifier.padding(vertical = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visibleSubjects.forEach { analysis ->
            val subjectKey = cleanSubjectName(analysis.subject.subjectName)
            val expanded = subjectKey in expandedSubjectKeys
            key(subjectKey) {
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
}
