package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.ui.OutlinedRoundedSymbol
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
internal fun OverviewCustomizationDialog(
    state: OverviewState,
    onSave: suspend (OverviewPreferences) -> Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(state.preferences) }
    var selectingEvent by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    if (selectingEvent) {
        var query by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(draft.countdownEventIds) }
        val events = state.calendarEvents.filter {
            (it.endExclusive.isAfter(state.now) || it.id in selected) && it.title.contains(query.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { selectingEvent = false },
            title = { Text("選擇事件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it.take(100) },
                        label = { Text("搜尋行事曆") }, singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已選 ${selected.size} 個 · 可選多個事件", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                            OutlinedRoundedSymbol("refresh", contentDescription = "重新載入行事曆")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (events.isEmpty()) item {
                            Text(if (state.isRefreshing) "行事曆載入中…" else "沒有符合的事件；可稍後再試或前往行事曆更新。")
                        }
                        items(events, key = { it.id }) { event ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(
                                    value = event.id in selected, role = Role.Checkbox,
                                    onValueChange = { checked ->
                                        selected = if (checked) selected + event.id else selected - event.id
                                    },
                                ).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Checkbox(checked = event.id in selected, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(event.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(event.start.format(DateTimeFormatter.ofPattern(if (event.isAllDay) "yyyy/M/d" else "yyyy/M/d HH:mm")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                draft = draft.copy(countdownEventIds = selected,
                    hiddenSections = if (selected.isNotEmpty()) draft.hiddenSections - "countdown" else draft.hiddenSections)
                selectingEvent = false
            }) { Text("完成 (${selected.size})") } },
            dismissButton = { TextButton(onClick = { selectingEvent = false }) { Text("取消") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("自訂總覽") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("拖曳把手調整區塊順序", style = MaterialTheme.typography.bodySmall)
                    OverviewSectionOrderEditor(draft, !saving) { draft = it }
                    HorizontalDivider()
                    Text("倒數 (${draft.countdownEventIds.size})", style = MaterialTheme.typography.titleSmall)
                    if (draft.countdownEventIds.isEmpty()) {
                        Text("選擇想關注的活動，總覽會顯示剩餘時間。", style = MaterialTheme.typography.bodySmall)
                    }
                    val eventsById = state.calendarEvents.associateBy { it.id }
                    draft.countdownEventIds.sortedBy { eventsById[it]?.start ?: java.time.LocalDateTime.MAX }.forEach { id ->
                        val event = eventsById[id]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(event?.title ?: "事件暫時無法取得", style = MaterialTheme.typography.bodyMedium)
                                event?.let {
                                    Text(it.start.format(DateTimeFormatter.ofPattern("yyyy/M/d")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { draft = draft.copy(countdownEventIds = draft.countdownEventIds - id) }, enabled = !saving) {
                                OutlinedRoundedSymbol("close", contentDescription = "移除${event?.title ?: "無法取得的事件"}")
                            }
                        }
                    }
                    TextButton(onClick = { selectingEvent = true }, enabled = !saving) { Text("選擇事件") }
                    OverviewLimit("近期行程", draft.calendarLimit, !saving && draft.shows("calendar")) {
                        draft = draft.copy(calendarLimit = it)
                    }
                    OverviewLimit("重要公告", draft.announcementLimit, !saving && draft.shows("announcements")) {
                        draft = draft.copy(announcementLimit = it)
                    }
                    Text("各區獨立計算數量；近期行程顯示未來 14 天內的事件，重要公告顯示已載入的置頂公告。",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val previousDraft = draft
                        draft = OverviewPreferences()
                        snackbarHostState.currentSnackbarData?.dismiss()
                        scope.launch {
                            if (snackbarHostState.showSnackbar(
                                    message = "已恢復預設",
                                    actionLabel = "復原",
                                    duration = SnackbarDuration.Short,
                                ) == SnackbarResult.ActionPerformed && !saving
                            ) {
                                draft = previousDraft
                            }
                        }
                    }, enabled = !saving && draft != OverviewPreferences()) { Text("恢復預設") }
                    if (saveFailed) Text("設定儲存失敗，請重試。", color = MaterialTheme.colorScheme.error)
                }
                SnackbarHost(snackbarHostState)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                snackbarHostState.currentSnackbarData?.dismiss()
                saving = true
                scope.launch {
                    try {
                        if (onSave(draft)) onDismiss() else saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) "儲存中…" else "儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

@Composable
private fun OverviewLimit(title: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Text("$title：最多 $value 筆")
    val sliderState = remember { SliderState(value = value.toFloat(), steps = 18, trackRange = 1f..20f) }
    sliderState.value = value.toFloat()
    Slider(
        state = sliderState, onValueChange = { onChange(it.roundToInt()) },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "${title}顯示數量" },
    )
}
