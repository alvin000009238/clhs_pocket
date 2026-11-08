@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.clhs.score.data.AttendanceDetail
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordCategoryCount
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.ui.navigation.navigationContentPadding
import com.clhs.score.viewmodel.RecordLoadState
import com.clhs.score.viewmodel.StudentRecordsState

@Composable
fun AttendanceRecordsScreen(
    state: StudentRecordsState,
    onSelectSemester: (RecordSemester) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var showAllCategories by remember(state.selectedSemester) { mutableStateOf(false) }
    val dateGroups = remember(state.attendance.data) { attendanceDateGroups(state.attendance.data?.details.orEmpty()) }
    if (showAllCategories) {
        RecordCategoriesSheet(state.attendance.data?.summary.orEmpty(), onDismiss = { showAllCategories = false })
    }
    StudentRecordsPage(
        state = state,
        refreshing = state.semestersLoading || state.attendance.loading || state.attendanceHistory.loading,
        onSelectSemester = onSelectSemester,
        onRefresh = onRefresh,
        onBack = onBack,
    ) {
        item {
            RecordSummary {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("學期摘要", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(
                        onClick = hapticClick { showAllCategories = true },
                        enabled = !state.attendance.data?.summary.isNullOrEmpty(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    ) {
                        Text("全部分類")
                        OutlinedRoundedSymbol("keyboard_arrow_right", size = 20.dp, contentDescription = null)
                    }
                }
                RecordLoadContent(state.attendance, onRefresh) { record ->
                    val highlights = attendanceHighlights(record.summary)
                    if (record.summary.isEmpty()) RecordEmpty("所選學期沒有缺曠統計")
                    else if (highlights.isEmpty()) RecordSupportingText("查看全部分類，核對校方統計數量")
                    else {
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            highlights.forEach { count ->
                                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(count.category, style = MaterialTheme.typography.bodyMedium)
                                    Text(count.count.toString(), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            RecordSection("近期紀錄") {
                if (state.attendance.data?.details?.isEmpty() == true) RecordEmpty("所選學期沒有缺曠紀錄")
            }
        }
        dateGroups.forEach { group ->
            item {
                Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    Text(group.date.ifBlank { "日期未提供" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).semantics { heading() })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                }
            }
            items(group.records) { detail -> AttendanceDetailRow(detail) }
        }
        item { RecordHistorySection(state.attendanceHistory, "", onRefresh) }
    }
}

@Composable
fun MeritRecordsScreen(
    state: StudentRecordsState,
    onSelectSemester: (RecordSemester) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedGroup by remember(state.selectedSemester) { mutableStateOf(MeritFilter.ALL) }
    val records = state.merits.data.orEmpty()
    val visibleRecords = remember(records, selectedGroup) { records.filter(selectedGroup::includes).sortedWith(compareByDescending { recordDate(it.date) }) }
    StudentRecordsPage(
        state = state,
        refreshing = state.semestersLoading || state.merits.loading || state.meritHistory.loading,
        onSelectSemester = onSelectSemester,
        onRefresh = onRefresh,
        onBack = onBack,
    ) {
        item {
            RecordSummary {
                Text("學期摘要", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text("依校方分類查看獎懲紀錄", style = MaterialTheme.typography.bodyMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeritFilter.entries.forEach { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = hapticClick { selectedGroup = group },
                            label = { Text(group.label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                RecordLoadContent(state.merits, onRefresh) { loaded ->
                    if (loaded.isEmpty()) RecordEmpty("所選學期沒有獎懲紀錄")
                    else if (visibleRecords.isEmpty()) RecordEmpty("這個分類沒有紀錄")
                }
            }
        }
        item { RecordSection("近期紀錄") {} }
        items(visibleRecords) { record -> MeritDetailRow(record) }
        item { RecordHistorySection(state.meritHistory, "次", onRefresh) }
    }
}

@Composable
private fun StudentRecordsPage(
    state: StudentRecordsState,
    refreshing: Boolean,
    onSelectSemester: (RecordSemester) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    FloatingSubpageLayout(
        onBack = onBack,
        actions = { RecordSemesterSelector(state, onSelectSemester) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            state = pullState,
            onRefresh = { if (!refreshing) onRefresh() },
            modifier = Modifier.fillMaxSize().semantics {
                customActions = listOf(CustomAccessibilityAction("重新整理") {
                    if (refreshing) false else { onRefresh(); true }
                })
            },
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp).zIndex(2f),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = navigationContentPadding(PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.semestersError != null || (!state.semestersLoading && state.semesters.isEmpty())) item {
                    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.semestersError?.let { RecordError(it, onRefresh) }
                        if (!state.semestersLoading && state.semestersError == null && state.semesters.isEmpty()) RecordEmpty("查無可查詢學期")
                    }
                }
                content()
                item {
                    RecordSupportingText("校務資料非即時更新，請以學校正式紀錄為準。", modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp, bottom = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordSemesterSelector(
    state: StudentRecordsState,
    onSelectSemester: (RecordSemester) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = hapticClick { expanded = true },
            enabled = state.semesters.isNotEmpty(),
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "選擇學期，${state.selectedSemester?.label.orEmpty()}" },
        ) {
            Text(state.selectedSemester?.let { "${it.year} ${if (it.term == 1) "上" else "下"}學期" } ?: "選擇學期", modifier = Modifier.weight(1f, fill = false))
            OutlinedRoundedSymbol("keyboard_arrow_down", modifier = Modifier.padding(start = 12.dp), contentDescription = null)
        }
        DropdownMenu(offset = androidx.compose.ui.unit.DpOffset(0.dp, 10.dp), expanded = expanded, onDismissRequest = { expanded = false }) {
            state.semesters.forEach { semester ->
                DropdownMenuItem(
                    text = { Text(semester.label) },
                    onClick = hapticClick { expanded = false; onSelectSemester(semester) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordSummary(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun RecordCategoriesSheet(summary: List<RecordCategoryCount>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("全部分類", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                RecordSupportingText("校方統計數量")
            }
            items(summary) { count ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(count.category, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(count.count.toString(), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            item { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("關閉") } }
        }
    }
}

@Composable
private fun AttendanceDetailRow(detail: AttendanceDetail) {
    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(detail.category, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                    RecordSupportingText("節數 ${detail.count}")
                }
                if (detail.sections.isNotBlank()) Text(detail.sections, modifier = Modifier.padding(start = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun MeritDetailRow(record: MeritRecord) {
    var expanded by rememberSaveable(record) { mutableStateOf(false) }
    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text("${record.category} ${record.count} 次", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
        }
        if (record.reason.isNotBlank()) Text(record.reason, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 22.dp))
        if (record.date.isNotBlank()) RecordSupportingText("發生日期：${record.date}", modifier = Modifier.padding(start = 22.dp))
        TextButton(
            onClick = hapticClick { expanded = !expanded },
            modifier = Modifier.padding(start = 10.dp).heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "已展開" else "已收合" },
        ) {
            Text(if (expanded) "收合核定資訊" else "查看核定資訊")
            OutlinedRoundedSymbol(if (expanded) "keyboard_arrow_up" else "keyboard_arrow_down", contentDescription = null)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 22.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RecordSupportingText("校方分類：${record.group.ifBlank { "未分類" }}")
                if (record.yearTerm.isNotBlank()) RecordSupportingText("學期：${record.yearTerm}")
                if (record.checkDate.isNotBlank()) RecordSupportingText("核定日期：${record.checkDate}")
                if (record.cleanDate.isNotBlank()) RecordSupportingText("銷過日期：${record.cleanDate}")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun RecordSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun <T> RecordLoadContent(state: RecordLoadState<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    if (state.loading) RecordLoading(if (state.data == null) "正在查詢" else "正在更新")
    state.error?.let {
        if (state.data != null) RecordSupportingText("更新未完成，以下顯示上次成功資料")
        RecordError(it, onRetry)
    }
    state.data?.let { content(it) }
    if (state.data == null && !state.loading && state.error == null) RecordSupportingText("選擇學期後查詢紀錄")
}

@Composable
private fun RecordHistorySection(state: RecordLoadState<List<RecordHistoryStatistic>>, unit: String, onRetry: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = hapticClick { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().semantics {
                role = Role.Button
                stateDescription = if (expanded) "已展開" else "已收合"
            },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PocketOptionIcon("bar_chart")
                Text("查看歷年統計", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                if (state.error != null) Text("待重試", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                else if (state.loading) LoadingIndicator(modifier = Modifier.size(24.dp))
                OutlinedRoundedSymbol(if (expanded) "keyboard_arrow_up" else "keyboard_arrow_right", contentDescription = null)
            }
        }
        if (expanded) RecordLoadContent(state, onRetry) { history ->
            if (history.isEmpty()) RecordEmpty("沒有歷年統計資料")
            else {
                RecordSupportingText("依年級與上下學期顯示，${if (unit.isEmpty()) "校方統計數量" else "單位：$unit"}。橫向滑動查看；— 表示未提供。")
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.width(64.dp)) {
                        Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.CenterStart) {
                            Text("類別", style = MaterialTheme.typography.labelLarge)
                        }
                        history.forEach { statistic ->
                            HorizontalDivider()
                            Box(modifier = Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                                Text(statistic.category, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).width((56 * recordHistoryTerms.size).dp)) {
                        Row(modifier = Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            recordHistoryTerms.forEach { term -> Text(term, modifier = Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge) }
                        }
                        history.forEach { statistic ->
                            HorizontalDivider()
                            Row(modifier = Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                                recordHistoryTerms.forEachIndexed { index, term ->
                                    val count = statistic.semesterCounts.getOrNull(index)
                                    Text(
                                        count?.toString() ?: "—",
                                        modifier = Modifier.width(56.dp).semantics {
                                            contentDescription = "${statistic.category} $term ${count?.let { if (unit.isEmpty()) it.toString() else "$it $unit" } ?: "未提供"}"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val recordHistoryTerms = listOf("一上", "一下", "二上", "二下", "三上", "三下")

@Composable
private fun RecordLoading(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadingIndicator(modifier = Modifier.size(28.dp))
        RecordSupportingText(label)
    }
}

@Composable
private fun RecordError(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = hapticClick(onRetry)) { Text("重試") }
    }
}

@Composable
private fun RecordEmpty(message: String) { RecordSupportingText(message) }

@Composable
private fun RecordSupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
