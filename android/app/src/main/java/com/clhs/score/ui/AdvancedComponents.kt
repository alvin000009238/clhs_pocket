package com.clhs.score.ui

import java.math.BigDecimal
import java.math.RoundingMode
import android.os.Bundle
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.data.GradeSummaryDisplay
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeTrend
import com.clhs.score.data.SubjectScore
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.SimulatorCredits
import com.clhs.score.data.requiredSimulatorAverage
import com.clhs.score.data.weightedAverageFor
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.SimulatorCreditUiState
import com.clhs.score.viewmodel.simulatorCreditIdentity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun scoreSimulatorUsesSplitLayout(width: Dp): Boolean =
    gradesAdaptiveLayoutForWidth(width) != GradesAdaptiveLayout.SingleColumn

internal fun scoreSimulatorSummaryOffsetPx(
    usesSplitLayout: Boolean,
    viewportHeightPx: Int,
    cardHeightPx: Int,
    statusBarHeightPx: Int,
    topSpacerHeightPx: Int,
    navigationBarHeightPx: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): Int {
    if (!usesSplitLayout) {
        return if (firstVisibleItemIndex == 0) {
            maxOf(statusBarHeightPx, topSpacerHeightPx - firstVisibleItemScrollOffset)
        } else {
            statusBarHeightPx
        }
    }

    val safeBottomPx = viewportHeightPx - navigationBarHeightPx
    val centeredOffsetPx = statusBarHeightPx +
        (safeBottomPx - statusBarHeightPx - cardHeightPx).coerceAtLeast(0) / 2
    return maxOf(topSpacerHeightPx, centeredOffsetPx)
}

internal data class ScoreSimulatorEditorState(
    val adjustedScores: Map<String, Double>,
    val isCustomGroupEnabled: Boolean = false,
    val isTargetReversalEnabled: Boolean = false,
    val checkedSubjects: Set<String>,
    val lockedSubjects: Set<String> = emptySet(),
    val targetAverage: String = "",
)

internal val ScoreSimulatorEditorStateSaver = Saver<ScoreSimulatorEditorState, Bundle>(
    save = { state ->
        Bundle().apply {
            putStringArrayList("score_keys", ArrayList(state.adjustedScores.keys))
            putDoubleArray("score_values", state.adjustedScores.values.toDoubleArray())
            putBoolean("custom_group", state.isCustomGroupEnabled)
            putBoolean("target_reversal", state.isTargetReversalEnabled)
            putStringArrayList("checked_subjects", ArrayList(state.checkedSubjects))
            putStringArrayList("locked_subjects", ArrayList(state.lockedSubjects))
            putString("target_average", state.targetAverage)
        }
    },
    restore = { saved ->
        val scoreKeys = saved.getStringArrayList("score_keys")
        val scoreValues = saved.getDoubleArray("score_values")
        if (scoreKeys == null || scoreValues == null || scoreKeys.size != scoreValues.size) {
            null
        } else {
            ScoreSimulatorEditorState(
                adjustedScores = scoreKeys.indices.associate { scoreKeys[it] to scoreValues[it] },
                isCustomGroupEnabled = saved.getBoolean("custom_group"),
                isTargetReversalEnabled = saved.getBoolean("target_reversal"),
                checkedSubjects = saved.getStringArrayList("checked_subjects").orEmpty().toSet(),
                lockedSubjects = saved.getStringArrayList("locked_subjects").orEmpty().toSet(),
                targetAverage = saved.getString("target_average").orEmpty(),
            )
        }
    },
)

@Composable
internal fun TrendChart(
    isLoadingTrend: Boolean,
    trendError: String?,
    trend: GradeTrend?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("歷次表現", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when {
                isLoadingTrend -> ChartLoadingPlaceholder()
                trend != null && trend.points.size >= 2 -> {
                    trend.points.forEachIndexed { index, point ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (index == trend.points.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    ),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(point.examName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "平均 ${"%.1f".format(point.weightedAverage)} ｜ 班排 ${point.classRank ?: "--"}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            point.highestScore?.let {
                                Text(
                                    text = "最高 ${"%.0f".format(it)}",
                                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                else -> EmptyAnalysisState(trendError ?: "目前沒有足夠歷次考試資料。")
            }
        }
    }
}

@Composable
internal fun ScoreSimulatorEntryCard(
    report: GradeReport,
    summaryDisplay: GradeSummaryDisplay,
    onOpen: () -> Unit,
) {
    val chipContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val chipValueColor = MaterialTheme.colorScheme.onSurface
    val chipLabelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("成績模擬器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = "自訂採計科目與目標分數，快速試算調整後的平均成績。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(
                    modifier = Modifier.weight(1f).semantics {
                        if (summaryDisplay.isAverageEstimated) stateDescription = "非官方試算平均"
                    },
                    label = "目前平均",
                    value = summaryDisplay.averageText,
                    containerColor = chipContainerColor,
                    labelColor = chipLabelColor,
                    valueColor = chipValueColor,
                )
                InfoChip(
                    modifier = Modifier.weight(1f),
                    label = "科目",
                    value = "${report.subjects.size} 科",
                    containerColor = chipContainerColor,
                    labelColor = chipLabelColor,
                    valueColor = chipValueColor,
                )
            }
            if (summaryDisplay.isAverageEstimated) {
                Text("* 非官方試算", style = MaterialTheme.typography.labelSmall, color = chipLabelColor)
            }
        }
    }
}

@Composable
internal fun SubjectTrendEntryCard(
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("成績折線圖", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = "以折線圖查看各科目歷次成績走勢。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScoreSimulatorScreen(
    state: GradesUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    creditState: SimulatorCreditUiState,
) {
    val report = state.report
    if (report == null) {
        SubpageLayout(
            onBack = onBack,
            snackbarHost = { SwipeDismissibleSnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                EmptyAnalysisState("尚未載入成績資料。")
            }
        }
        return
    }

    val coroutineScope = rememberCoroutineScope()

    val initialScores = remember(report) {
        report.subjects.associate { cleanSubjectName(it.subjectName) to it.scoreValue }
    }
    val reportSubjects = remember(report) {
        report.subjects.mapTo(mutableSetOf()) { cleanSubjectName(it.subjectName) }
    }
    val reportIdentity = remember(state.studentNo, state.selectedYearValue, state.selectedExamValue, report) {
        simulatorCreditIdentity(state.studentNo, state.selectedYearValue, state.selectedExamValue, report) +
            report.subjects.joinToString("\u0000") { "${it.scoreDisplay}:${it.absent}:${it.cheating}" }
    }
    val scopedCreditState = creditState.takeIf {
        it.identity == simulatorCreditIdentity(state.studentNo, state.selectedYearValue, state.selectedExamValue, report)
    } ?: SimulatorCreditUiState(isLoading = true)
    val credits = scopedCreditState.appliedCredits
    var editorState by rememberSaveable(
        reportIdentity,
        stateSaver = ScoreSimulatorEditorStateSaver,
    ) {
        mutableStateOf(
            ScoreSimulatorEditorState(
                adjustedScores = initialScores,
                checkedSubjects = reportSubjects,
            )
        )
    }
    val adjustedScores = editorState.adjustedScores
    val isCustomGroupEnabled = editorState.isCustomGroupEnabled
    val isTargetReversalEnabled = editorState.isTargetReversalEnabled
    val checkedSubjects = editorState.checkedSubjects
    val lockedSubjects = editorState.lockedSubjects
    val targetAverageStr = editorState.targetAverage
    val targetAverage = targetAverageStr.takeIf { it.matches(Regex("[0-9]{1,3}(\\.[0-9]{0,8})?")) }
        ?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(100) }

    val activeSubjects = if (isCustomGroupEnabled) checkedSubjects else reportSubjects

    val currentAverage = remember(report, activeSubjects, credits) {
        weightedAverageFor(report.subjects, emptyMap(), activeSubjects, credits)
    }
    val adjustedAverage = remember(report, adjustedScores, activeSubjects, credits) {
        weightedAverageFor(report.subjects, adjustedScores, activeSubjects, credits)
    }
    val includedSubjects = report.subjects.filter { cleanSubjectName(it.subjectName) in activeSubjects }
    val activeCredits = credits.filterKeys { it in activeSubjects }
    val currentCalculation = remember(includedSubjects, activeCredits) {
        SimulatorCredits.calculate(includedSubjects, activeCredits)
    }
    val adjustedCalculation = remember(includedSubjects, adjustedScores, activeCredits) {
        val includedScores = adjustedScores.filterKeys { it in activeSubjects }
        if (includedScores.values.any { !it.isFinite() }) SimulatorCredits.Calculation(issue = SimulatorCredits.Issue.INVALID_SCORE)
        else SimulatorCredits.calculate(includedSubjects, activeCredits, includedScores.mapValues { BigDecimal.valueOf(it.value).setScale(8, RoundingMode.HALF_UP) })
    }
    val calculationAvailable = credits.isNotEmpty() &&
        currentCalculation.issue == null && adjustedCalculation.issue == null

    val historyMaxMin = remember(state.simulatorHistoryReports, report) {
        val reportSubjectKeys = report.subjects
            .mapTo(mutableSetOf()) { cleanSubjectName(it.subjectName) }
        val map = mutableMapOf<String, Pair<Double, Double>>()
        state.simulatorHistoryReports.forEach { historyReport ->
            historyReport.subjects.forEach { subject ->
                val key = cleanSubjectName(subject.subjectName)
                if (key in reportSubjectKeys) {
                    val score = subject.scoreValue
                    val range = map[key]
                    map[key] = if (range == null) {
                        score to score
                    } else {
                        minOf(range.first, score) to maxOf(range.second, score)
                    }
                }
            }
        }
        map
    }

    val density = LocalDensity.current
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val statusBarHeightPx = statusBars.getTop(density)
    val navigationBarHeightPx = navigationBars.getBottom(density)
    val topSpacerHeightPx = statusBarHeightPx + with(density) { 56.dp.toPx() }.roundToInt()

    val listState = rememberLazyListState()
    var cardHeightPx by remember { mutableIntStateOf(0) }
    var showAdvancedModes by rememberSaveable(reportIdentity) {
        mutableStateOf(isCustomGroupEnabled || isTargetReversalEnabled)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val usesSplitLayout = scoreSimulatorUsesSplitLayout(maxWidth)
        val summaryPaneWidth = (maxWidth * 0.4f).coerceIn(280.dp, 400.dp)
        val viewportHeightPx = with(density) { maxHeight.toPx().roundToInt() }

        SubpageLayout(
            onBack = onBack,
            snackbarHost = { SwipeDismissibleSnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
            summaryContent = {
                SimulatorSummaryCard(
                    available = calculationAvailable,
                    currentAverage = currentAverage,
                    currentTotal = currentCalculation.weightedTotal?.stripTrailingZeros()?.toPlainString() ?: "--",
                    adjustedAverage = adjustedAverage,
                    adjustedTotal = adjustedCalculation.weightedTotal?.stripTrailingZeros()?.toPlainString() ?: "--",
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = 0,
                                y = scoreSimulatorSummaryOffsetPx(
                                    usesSplitLayout = usesSplitLayout,
                                    viewportHeightPx = viewportHeightPx,
                                    cardHeightPx = cardHeightPx,
                                    statusBarHeightPx = statusBarHeightPx,
                                    topSpacerHeightPx = topSpacerHeightPx,
                                    navigationBarHeightPx = navigationBarHeightPx,
                                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                                ),
                            )
                        }
                        .then(
                            if (usesSplitLayout) {
                                Modifier
                                    .padding(start = 16.dp)
                                    .width(summaryPaneWidth)
                            } else {
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                            }
                        )
                        .onGloballyPositioned { coordinates ->
                            cardHeightPx = coordinates.size.height
                        }
                )
            }
        ) {
            LazyColumn(
                contentPadding = com.clhs.score.ui.navigation.navigationContentPadding(
                    androidx.compose.foundation.layout.PaddingValues(0.dp),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (usesSplitLayout) summaryPaneWidth + 32.dp else 16.dp,
                        end = 16.dp,
                    )
                    .navigationBarsPadding()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                state = listState,
            ) {
                item {
                    Spacer(
                        modifier = Modifier.height(
                            with(density) {
                                if (usesSplitLayout) {
                                    topSpacerHeightPx.toDp()
                                } else {
                                    (topSpacerHeightPx + cardHeightPx).toDp()
                                }
                            }
                        )
                    )
                }

                item {
                    if (!calculationAvailable) {
                        Text(
                            when {
                                scopedCreditState.isLoading -> "學分載入中…"
                                credits.isEmpty() -> "學分尚未載入，請至成績頁的學分設定重試"
                                else -> "尚無可採計的完整成績"
                            },
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                    FilledTonalButton(
                        onClick = { showAdvancedModes = !showAdvancedModes },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            when {
                                showAdvancedModes -> "收合進階模式"
                                isCustomGroupEnabled || isTargetReversalEnabled -> "進階模式（已啟用）"
                                else -> "進階模式"
                            },
                        )
                    }
                    Column(
                        modifier = Modifier.animateContentSize(
                            animationSpec = PocketMotion.contentReflow(),
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (showAdvancedModes) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = isCustomGroupEnabled,
                                    role = Role.Switch,
                                    onValueChange = {
                                        editorState = editorState.copy(isCustomGroupEnabled = it)
                                    },
                                )
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "自訂採計科目",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Switch(checked = isCustomGroupEnabled, onCheckedChange = null)
                            }
                        }
                    }
                }

                    if (showAdvancedModes) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = isTargetReversalEnabled,
                                        role = Role.Switch,
                                        onValueChange = {
                                            editorState = editorState.copy(
                                                isTargetReversalEnabled = it,
                                                lockedSubjects = if (it) lockedSubjects else emptySet(),
                                            )
                                        },
                                    )
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "反推分數",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Switch(checked = isTargetReversalEnabled, onCheckedChange = null)
                            }
                            }

                            if (isTargetReversalEnabled) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                ) {
                                    OutlinedTextField(
                                        value = targetAverageStr,
                                        onValueChange = { if (it.length <= 12) editorState = editorState.copy(targetAverage = it) },
                                        label = { Text("目標平均") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                    )
                                    Button(
                                        onClick = {
                                            val targetAvg = targetAverage ?: return@Button
                                            val activeList = report.subjects.filter { cleanSubjectName(it.subjectName) in activeSubjects }
                                            val totalWeights = activeList.sumOf { credits[cleanSubjectName(it.subjectName)] ?: 1 }
                                            val targetTotalPoints = targetAvg * BigDecimal(totalWeights)

                                            val lockedList = activeList.filter { cleanSubjectName(it.subjectName) in lockedSubjects }
                                            val lockedPoints = lockedList.fold(BigDecimal.ZERO) { total, subject ->
                                                val name = cleanSubjectName(subject.subjectName)
                                                total + BigDecimal.valueOf(adjustedScores[name] ?: subject.scoreValue) * BigDecimal(credits[name] ?: 1)
                                            }

                                            val unlockedList = activeList.filter { cleanSubjectName(it.subjectName) !in lockedSubjects }
                                            val neededAvg = requiredSimulatorAverage(
                                                targetTotalPoints = targetTotalPoints,
                                                lockedPoints = lockedPoints,
                                                unlockedCredits = unlockedList.sumOf { credits[cleanSubjectName(it.subjectName)] ?: 1 },
                                            )?.toDouble()

                                            if (neededAvg != null) {
                                                if (neededAvg > 100.0) {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            "即使其他未鎖定科目皆為 100 分，也無法達到目標平均",
                                                        )
                                                    }
                                                    return@Button
                                                }
                                                val newScores = adjustedScores.toMutableMap()
                                                unlockedList.forEach { subject ->
                                                    newScores[cleanSubjectName(subject.subjectName)] = neededAvg.coerceIn(0.0, 100.0)
                                                }
                                                editorState = editorState.copy(adjustedScores = newScores)
                                            } else coroutineScope.launch {
                                                snackbarHostState.showSnackbar("沒有可調整且有採計學分的科目，請先解除分數鎖定。")
                                            }
                                        },
                                        enabled = calculationAvailable && targetAverage != null,
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text("反推")
                                    }
                                }
                            }
                        }
                    }
                    }
                    }
                    }
                }

            item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = "科目分數",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton(
                                    onClick = {
                                        val previousState = editorState
                                        editorState = editorState.copy(
                                            adjustedScores = initialScores,
                                            checkedSubjects = reportSubjects,
                                        )
                                        coroutineScope.launch {
                                            if (
                                                snackbarHostState.showSnackbar(
                                                    message = "已恢復所有科目",
                                                    actionLabel = "復原",
                                                    duration = SnackbarDuration.Short,
                                                ) == SnackbarResult.ActionPerformed
                                            ) {
                                                editorState = previousState
                                            }
                                        }
                                    },
                                    shapes = ButtonDefaults.shapes(),
                                ) {
                                    Text("恢復全部")
                                }
                            }
                            report.subjects.forEachIndexed { index, subject ->
                                val key = cleanSubjectName(subject.subjectName)
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                SubjectScoreSlider(
                                    subject = subject,
                                    credit = credits[key] ?: 1,
                                    value = adjustedScores[key] ?: subject.scoreValue,
                                    isChecked = checkedSubjects.contains(key),
                                    onCheckedChange = { checked ->
                                        editorState = editorState.copy(
                                            checkedSubjects = if (checked) checkedSubjects + key else checkedSubjects - key
                                        )
                                    },
                                    showCheckbox = isCustomGroupEnabled,
                                    isLocked = lockedSubjects.contains(key),
                                    onLockedChange = { locked ->
                                        editorState = editorState.copy(
                                            lockedSubjects = if (locked) lockedSubjects + key else lockedSubjects - key
                                        )
                                    },
                                    showLock = isTargetReversalEnabled,
                                    historyMax = historyMaxMin[cleanSubjectName(subject.subjectName)]?.second,
                                    onValueChange = { score ->
                                        editorState = editorState.copy(adjustedScores = adjustedScores + (key to score))
                                    },
                                    onResetSubject = {
                                        editorState = editorState.copy(
                                            adjustedScores = adjustedScores + (key to subject.scoreValue)
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SimulatorSummaryCard(
    available: Boolean,
    currentAverage: Double,
    currentTotal: String,
    adjustedAverage: Double,
    adjustedTotal: String,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val labelColor = contentColor.copy(alpha = 0.78f)
    val changedValueColor = MaterialTheme.colorScheme.tertiary
    val arrowContainerColor = contentColor.copy(alpha = 0.12f)
    val isAdjusted = abs(adjustedAverage - currentAverage) > 0.01 ||
        adjustedTotal != currentTotal

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryMetricColumn(
                available = available,
                modifier = Modifier.weight(1f),
                label = "原始平均",
                average = currentAverage,
                total = currentTotal,
                labelColor = labelColor,
                valueColor = contentColor,
                pillContainerColor = contentColor.copy(alpha = 0.10f),
                pillContentColor = contentColor.copy(alpha = 0.82f),
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(arrowContainerColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedRoundedSymbol(
                    icon = "arrow_forward",
                    tint = contentColor,
                    contentDescription = null,
                )
            }

            SummaryMetricColumn(
                available = available,
                modifier = Modifier.weight(1f),
                label = "調整後平均",
                average = adjustedAverage,
                total = adjustedTotal,
                labelColor = labelColor,
                valueColor = if (isAdjusted) changedValueColor else contentColor,
                pillContainerColor = if (isAdjusted) {
                    changedValueColor.copy(alpha = 0.16f)
                } else {
                    contentColor.copy(alpha = 0.10f)
                },
                pillContentColor = if (isAdjusted) {
                    changedValueColor
                } else {
                    contentColor.copy(alpha = 0.82f)
                },
            )
        }
    }
}

@Composable
private fun SummaryMetricColumn(
    available: Boolean,
    label: String,
    average: Double,
    total: String,
    labelColor: Color,
    valueColor: Color,
    pillContainerColor: Color,
    pillContentColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
        Text(
            text = if (available) "%.2f".format(average) else "--",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            modifier = Modifier
                .background(pillContainerColor, CircleShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            text = "總分 ${if (available) total else "--"}",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = pillContentColor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDismissibleSnackbarHost(snackbarHostState: SnackbarHostState) {
    SnackbarHost(snackbarHostState) { snackbarData ->
        key(snackbarData) {
            val dismissState = rememberSwipeToDismissBoxState()
            LaunchedEffect(dismissState.currentValue) {
                if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                    snackbarData.dismiss()
                }
            }
            SwipeToDismissBox(
                state = dismissState,
                backgroundContent = {},
            ) {
                Snackbar(snackbarData)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectScoreSlider(
    subject: SubjectScore,
    credit: Int,
    value: Double,
    isChecked: Boolean = true,
    onCheckedChange: (Boolean) -> Unit = {},
    showCheckbox: Boolean = false,
    isLocked: Boolean = false,
    onLockedChange: (Boolean) -> Unit = {},
    showLock: Boolean = false,
    historyMax: Double? = null,
    onValueChange: (Double) -> Unit,
    onResetSubject: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val diff = value - subject.scoreValue
    val isAboveMax = historyMax != null && value > historyMax
    var lastDispatchedScore by remember(subject.subjectName, value) {
        mutableDoubleStateOf(value)
    }
    fun updateScore(score: Double, haptic: Boolean = false) {
        val roundedScore = BigDecimal.valueOf(score.coerceIn(0.0, 100.0)).setScale(8, RoundingMode.HALF_UP).toDouble()
        if (roundedScore == lastDispatchedScore) {
            return
        }
        if (haptic) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lastDispatchedScore = roundedScore
        onValueChange(roundedScore)
    }

    val scoreColor = if (isAboveMax) Color(0xFFFF5722) else MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCheckbox) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "${cleanSubjectName(subject.subjectName)}納入試算" },
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            if (showLock) {
                IconToggleButton(
                    checked = isLocked,
                    onCheckedChange = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLockedChange(it)
                    },
                    modifier = Modifier.size(48.dp),
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    OutlinedRoundedSymbol(
                        icon = if (isLocked) "lock" else "lock_open",
                        size = 20.dp,
                        contentDescription = if (isLocked) {
                            "解除鎖定${cleanSubjectName(subject.subjectName)}分數"
                        } else {
                            "鎖定${cleanSubjectName(subject.subjectName)}分數"
                        },
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = cleanSubjectName(subject.subjectName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "$credit 學分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (abs(diff) < 0.000000005) {
                        "原始 ${subject.scoreDisplay.ifBlank { "--" }}｜班平均 ${subject.classAverageDisplay.ifBlank { "--" }}｜與原始相同"
                    } else {
                        "原始 ${subject.scoreDisplay.ifBlank { "--" }}｜班平均 ${subject.classAverageDisplay.ifBlank { "--" }}｜調整 ${if (diff >= 0) "+" else ""}${formatSimulatorScore(diff)} 分"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                modifier = Modifier.semantics {
                    contentDescription = "目前調整分數 ${formatSimulatorScore(value)}，重設此科目"
                },
                onClick = onResetSubject,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = formatSimulatorScore(value),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                    color = scoreColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreStepButton(
                    label = "-",
                    contentDescription = "${cleanSubjectName(subject.subjectName)}減少 1 分",
                    onClick = { updateScore(value - 1.0, haptic = true) },
                )
            }
            FlameSlider(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = "${cleanSubjectName(subject.subjectName)}分數，可調整 0 到 100"
                        stateDescription = "${formatSimulatorScore(value)} 分"
                    },
                value = value.toFloat(),
                onValueChange = { updateScore(it.roundToInt().toDouble(), haptic = true) },
                isFlameActive = isAboveMax,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScoreStepButton(
                    label = "+",
                    contentDescription = "${cleanSubjectName(subject.subjectName)}增加 1 分",
                    onClick = { updateScore(value + 1.0, haptic = true) },
                )
            }
        }
    }
}

private fun formatSimulatorScore(value: Double): String =
    BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FlameSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isFlameActive: Boolean = false,
) {
    val flameColor by animateColorAsState(
        targetValue = if (isFlameActive) Color(0xFFFF5722) else MaterialTheme.colorScheme.primary,
        animationSpec = PocketMotion.stateChange(),
        label = "score-simulator-flame-color",
    )

    val sliderState = remember { SliderState(value = value, trackRange = 0f..100f) }
    sliderState.value = value

    Slider(
        modifier = modifier,
        state = sliderState,
        onValueChange = onValueChange,
        colors = SliderDefaults.colors(
            thumbColor = flameColor,
            activeTrackColor = flameColor,
            inactiveTrackColor = flameColor.copy(alpha = 0.24f),
        ),
    )
}

@Composable
private fun ScoreStepButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapesFor(48.dp),
        modifier = Modifier
            .width(52.dp)
            .height(48.dp)
            .semantics { this.contentDescription = contentDescription },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
private fun ChartLoadingPlaceholder() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun EmptyAnalysisState(message: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
            .padding(12.dp),
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
