package com.clhs.score.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clhs.score.data.GradeReport
import com.clhs.score.data.SimulatorCredits
import com.clhs.score.viewmodel.SimulatorCreditUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimulatorCreditSheet(
    report: GradeReport,
    state: SimulatorCreditUiState,
    onEdit: (String, String) -> Unit,
    onToggleFixed: (String) -> Unit,
    onInfer: () -> Unit,
    onCandidate: (Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val saving by rememberUpdatedState(state.isSaving)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { it != SheetValue.Hidden || !saving },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !saving,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("學分設定", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, enabled = !saving) {
                    OutlinedRoundedSymbol("close", contentDescription = "關閉學分設定")
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp)) {
                SimulatorCreditPanel(report, state, onEdit, onToggleFixed, onInfer, onCandidate, onSave, onReset, onRetry)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SimulatorCreditPanel(
    report: GradeReport,
    state: SimulatorCreditUiState,
    onEdit: (String, String) -> Unit,
    onToggleFixed: (String) -> Unit,
    onInfer: () -> Unit,
    onCandidate: (Int) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
) {
    var showInference by rememberSaveable(state.identity) { mutableStateOf(false) }
    val credits = state.credits
    val verification = remember(report, credits) {
        credits?.let { SimulatorCredits.verify(report.subjects, report.examSummary, it) }
    }
    val busy = !state.isLoaded || state.isSaving
    val source = when {
        state.isLoading -> "載入中…"
        state.isInferring -> "推算中…"
        state.confirmed && state.source == "inferred" -> "推算・已確認"
        state.confirmed && state.source == "default" -> "預設・已儲存"
        state.confirmed -> "自訂・已儲存"
        state.source == "inferred" -> "推算・待確認"
        state.source == "manual" -> "自訂・未儲存"
        else -> "預設"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本次學分", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(source, credits?.values?.sum()?.let { "共 $it 學分" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!state.isLoading) {
            Text(
                when {
                    credits == null -> "學分需為 1～${SimulatorCredits.MAX_CREDITS} 的整數"
                    verification?.status == SimulatorCredits.VerificationStatus.UNAVAILABLE -> "暫無法比對官方成績"
                    verification?.status == SimulatorCredits.VerificationStatus.MISMATCH -> "與官方成績不符"
                    else -> "與官方成績相符"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!state.isLoaded) {
                TextButton(onClick = onRetry, enabled = !state.isLoading && !state.isSaving, shapes = ButtonDefaults.shapes()) { Text("重新載入") }
            }
        }
        if (!state.confirmed) state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        CreditComparisonRow("", "加權總分", "平均")
        CreditComparisonRow(
            "官方",
            report.examSummary?.totalScoreDisplay?.ifBlank { "--" } ?: "--",
            report.examSummary?.averageScoreDisplay?.ifBlank { "--" } ?: "--",
        )
        CreditComparisonRow(
            "試算",
            verification?.calculation?.weightedTotal?.stripTrailingZeros()?.toPlainString() ?: "--",
            verification?.calculation?.weightedAverage?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "--",
        )
        HorizontalDivider()
        TextButton(onClick = { showInference = !showInference }, shapes = ButtonDefaults.shapes()) {
            Text(when {
                showInference -> "收合推算"
                state.candidates.size > 1 -> "比較方案（${state.candidates.size}）"
                else -> "推算學分"
            })
        }
        if (showInference) {
            TextButton(onClick = onInfer, enabled = !busy && !state.isInferring, shapes = ButtonDefaults.shapes()) {
                Text(if (state.isInferring) "推算中…" else if (state.fixedSubjects.isEmpty()) "重新推算" else "依固定學分推算")
            }
            state.inferenceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.candidates.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.candidates.forEachIndexed { index, candidate ->
                        FilterChip(
                            selected = candidate == credits,
                            onClick = { if (candidate != credits) onCandidate(index) },
                            enabled = !busy && !state.isInferring,
                            label = { Text("方案 ${index + 1}") },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("科目", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("合計學分", Modifier.width(88.dp), style = MaterialTheme.typography.labelMedium)
            if (showInference) Text("固定", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
        }
        state.inputs.forEach { (subject, value) ->
            val invalid = value.toIntOrNull()?.let { it !in 1..SimulatorCredits.MAX_CREDITS } ?: true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(subject, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 2) onEdit(subject, it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !busy,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text("1～${SimulatorCredits.MAX_CREDITS}") }) else null,
                    modifier = Modifier.width(88.dp).semantics { contentDescription = "$subject 合計學分" },
                )
                if (showInference) {
                    IconToggleButton(
                        checked = subject in state.fixedSubjects,
                        onCheckedChange = { onToggleFixed(subject) },
                        enabled = !busy,
                    ) {
                        OutlinedRoundedSymbol(
                            icon = if (subject in state.fixedSubjects) "lock" else "lock_open",
                            contentDescription = if (subject in state.fixedSubjects) "解除固定${subject}學分" else "固定${subject}學分",
                        )
                    }
                }
            }
        }
        Button(
            onClick = onSave,
            enabled = !busy && !state.isInferring && credits != null && SimulatorCredits.defaultCredits(report.subjects) != null && report.subjects.size <= 64,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSaving) "儲存中…" else "儲存")
        }
        TextButton(
            onClick = onReset,
            enabled = !state.isLoading && !state.isSaving && (state.isLoaded || state.canRestoreDefaults),
            shapes = ButtonDefaults.shapes(),
        ) { Text("恢復預設") }
    }
}

@Composable
private fun CreditComparisonRow(label: String, total: String, average: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
        Text(total, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
        Text(average, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
