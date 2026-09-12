package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.getSubjectBaseName
import com.clhs.score.data.shortenSubjectName
import com.clhs.score.viewmodel.ScoreViewModel

internal fun subjectTrendUsesSplitLayout(width: Dp): Boolean =
    gradesAdaptiveLayoutForWidth(width) != GradesAdaptiveLayout.SingleColumn

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun SubjectTrendScreen(
    viewModel: ScoreViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.subjectTrendState.collectAsStateWithLifecycle()
    val gradesState by viewModel.gradesState.collectAsStateWithLifecycle()
    val structure = gradesState.structure
    var showSubjectBottomSheet by remember { mutableStateOf(false) }
    var showYearTermBottomSheet by remember { mutableStateOf(false) }
    var selectedBaseName by remember { mutableStateOf<String?>(null) }

    // Extract all unique subjects from the fetched reports
    val availableSubjects = remember(state.reports) {
        state.reports.flatMap { report ->
            report.subjects.map { cleanSubjectName(it.subjectName).substringBefore("-") }
        }.toSet().sorted()
    }

    // Chart Colors mapped to subjects for legend
    val subjectColors = remember(state.selectedSubjectKeys) {
        val colors = listOf(
            Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5),
            Color(0xFF00BCD4), Color(0xFF4CAF50), Color(0xFFFF9800),
            Color(0xFF795548), Color(0xFF607D8B), Color(0xFFF44336),
            Color(0xFF009688), Color(0xFFCDDC39), Color(0xFFFFC107),
            Color(0xFFFF5722), Color(0xFF8BC34A), Color(0xFF673AB7),
            Color(0xFF03A9F4), Color(0xFFE040FB), Color(0xFF00E5FF)
        )
        val baseNameColors = mutableMapOf<String, Color>()
        var colorIndex = 0
        state.selectedSubjectKeys.toList().associateWith { key ->
            val baseName = getSubjectBaseName(key)
            baseNameColors.getOrPut(baseName) { colors[colorIndex++ % colors.size] }
        }
    }

    // Memoized legend groups to prevent recalculation during recomposition
    val groupedLegend = remember(state.selectedSubjectKeys, state.reports) {
        val baseGroup = state.selectedSubjectKeys.groupBy { getSubjectBaseName(it) }
        // Pre-extract all reported subject names to avoid flatMapping repeatedly
        val allReportedSubjects = state.reports.flatMap { report ->
            report.subjects.map { it.subjectName }
        }
        baseGroup.mapValues { (_, keys) ->
            val firstKey = allReportedSubjects.firstOrNull { it in keys } ?: keys.first()
            firstKey to keys
        }
    }

    val chartSection = @Composable {
        val hasNoYears = state.selectedYearValues.isEmpty()
        val hasNoSubjects = state.selectedSubjectKeys.isEmpty()
        val selectedPointCount = remember(state.reports, state.selectedSubjectKeys) {
            state.selectedSubjectKeys.maxOfOrNull { subjectKey ->
                state.reports.count { report ->
                    report.subjects.any {
                        cleanSubjectName(it.subjectName).substringBefore("-") == subjectKey && it.scoreValue > 0
                    }
                }
            } ?: 0
        }
        val hasInsufficientData = !hasNoSubjects && selectedPointCount < 2
        val showEmptyState = hasNoYears || hasNoSubjects || state.reports.isEmpty() || hasInsufficientData
        Card(
            modifier = if (showEmptyState) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 280.dp, max = 520.dp)
                    .aspectRatio(1.5f)
            },
            shape = if (showEmptyState) MaterialTheme.shapes.large else MaterialTheme.shapes.largeIncreased,
            colors = CardDefaults.cardColors(
                containerColor = if (showEmptyState) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            if (showEmptyState) {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = when {
                                hasNoYears -> "選擇要比較的學期"
                                hasNoSubjects -> "選擇要比較的科目"
                                hasInsufficientData -> "選取的科目只有 $selectedPointCount 次可比較成績"
                                else -> "暫時沒有可繪製的成績資料"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                hasNoYears -> "可一次選擇多個學期，再比較同一科目的變化。"
                                hasNoSubjects -> "選好後，就能比較各次考試的成績變化。"
                                hasInsufficientData -> "至少需要 2 次成績才能繪製趨勢，請調整學期或科目。"
                                else -> "請調整學期或科目後再試一次。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (hasNoYears) showYearTermBottomSheet = true
                                else showSubjectBottomSheet = true
                            },
                            enabled = !hasNoYears || structure.isNotEmpty(),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(if (hasNoYears) "選擇學期" else "選擇科目")
                        }
                    }
                }
            } else {
                SubjectTrendLineChart(
                    reports = state.reports,
                    selectedSubjectKeys = state.selectedSubjectKeys,
                    subjectColors = subjectColors,
                    selectedBaseName = selectedBaseName,
                    onBaseNameSelected = { selectedBaseName = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        }
    }

    val legendSection = @Composable {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groupedLegend.forEach { (baseName, pair) ->
                val (firstKey, keys) = pair
                val color = subjectColors[firstKey] ?: Color.Black
                val label = keys.map { shortenSubjectName(it) }.distinct().joinToString("/")
                val isSelected = baseName == selectedBaseName

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .semantics { selected = isSelected }
                        .toggleable(
                            value = isSelected,
                            role = Role.Button,
                            onValueChange = {
                                selectedBaseName = if (isSelected) null else baseName
                            },
                        )
                        .background(
                            color = if (isSelected) color.copy(alpha = 0.1f) else Color.Transparent,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    val filtersSection = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val selectionSummary = when (val selectedCount = state.selectedYearValues.size) {
                0 -> "尚未選擇學期"
                structure.size -> "已全選（共 $selectedCount 個學期）"
                else -> "已選 $selectedCount 個學期"
            }
            SegmentedListItem(
                onClick = { showYearTermBottomSheet = true },
                enabled = structure.isNotEmpty(),
                shapes = ListItemDefaults.segmentedShapes(
                    index = 0,
                    count = 1,
                    defaultShapes = ListItemDefaults.shapes(
                        shape = MaterialTheme.shapes.large,
                    ),
                ),
                supportingContent = {
                    Text(
                        text = selectionSummary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
                trailingContent = {
                    OutlinedRoundedSymbol(
                        icon = "keyboard_arrow_right",
                        size = 24.dp,
                        contentDescription = null,
                    )
                },
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("選擇學期")
            }
            if (state.selectedSubjectKeys.isNotEmpty()) {
                FilledTonalButton(
                    onClick = { showSubjectBottomSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("調整對比科目")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成績折線圖") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        OutlinedRoundedSymbol(icon = "arrow_back", contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (subjectTrendUsesSplitLayout(maxWidth)) {
                    val mainPaneWeight =
                        if (gradesAdaptiveLayoutForWidth(maxWidth) == GradesAdaptiveLayout.ListDetail) 2f else 1f
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 1200.dp)
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(mainPaneWeight)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            state.errorMessage?.let { message ->
                                item { Text(message, color = MaterialTheme.colorScheme.error) }
                            }
                            item { chartSection() }
                            if (state.selectedSubjectKeys.isNotEmpty()) {
                                item { legendSection() }
                            }
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                ) {
                                    Box(modifier = Modifier.padding(20.dp)) {
                                        filtersSection()
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(8.dp)) }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        state.errorMessage?.let { message ->
                            item { Text(message, color = MaterialTheme.colorScheme.error) }
                        }
                        item { chartSection() }
                        if (state.selectedSubjectKeys.isNotEmpty()) {
                            item { legendSection() }
                        }
                        item { filtersSection() }
                        item { Spacer(modifier = Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }

    if (showSubjectBottomSheet) {
        SubjectSelectionSheet(
            subjects = availableSubjects,
            selectedSubjectKeys = state.selectedSubjectKeys,
            onToggleSubject = viewModel::toggleSubjectTrendSubject,
            onDismiss = { showSubjectBottomSheet = false },
        )
    }

    if (showYearTermBottomSheet) {
        YearTermSelectionSheet(
            yearTerms = structure,
            selectedYearValues = state.selectedYearValues,
            onApply = viewModel::setSubjectTrendYears,
            onDismiss = { showYearTermBottomSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun YearTermSelectionSheet(
    yearTerms: List<YearTermOption>,
    selectedYearValues: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draftSelection by remember(yearTerms, selectedYearValues) {
        mutableStateOf(selectedYearValues)
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "選擇學期",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { draftSelection = yearTerms.mapTo(mutableSetOf()) { it.value } },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("全選")
                }
                TextButton(
                    onClick = { draftSelection = emptySet() },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("清除")
                }
            }
            Text(
                text = "選擇要納入折線圖比較的學期",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(yearTerms) { index, yearTerm ->
                    SegmentedListItem(
                        checked = yearTerm.value in draftSelection,
                        onCheckedChange = { checked ->
                            draftSelection = if (checked) {
                                draftSelection + yearTerm.value
                            } else {
                                draftSelection - yearTerm.value
                            }
                        },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = yearTerms.size),
                        leadingContent = {
                            Checkbox(
                                checked = yearTerm.value in draftSelection,
                                onCheckedChange = null,
                            )
                        },
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(yearTerm.text)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onApply(draftSelection)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("套用", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubjectSelectionSheet(
    subjects: List<String>,
    selectedSubjectKeys: Set<String>,
    onToggleSubject: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = "選擇要顯示的科目",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                itemsIndexed(subjects) { index, subjectKey ->
                    SubjectSelectionRow(
                        subjectKey = subjectKey,
                        selected = selectedSubjectKeys.contains(subjectKey),
                        onToggleSubject = onToggleSubject,
                        index = index,
                        count = subjects.size,
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("確認", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SubjectSelectionRow(
    subjectKey: String,
    selected: Boolean,
    onToggleSubject: (String) -> Unit,
    index: Int,
    count: Int,
) {
    SegmentedListItem(
        checked = selected,
        onCheckedChange = { onToggleSubject(subjectKey) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
        },
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = subjectKey)
    }
}
