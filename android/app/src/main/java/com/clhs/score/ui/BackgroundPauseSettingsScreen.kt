package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clhs.score.data.BackgroundPause
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal fun formatPauseTime(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofPattern("HH:mm"))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BackgroundPauseSettingsScreen(
    settings: BackgroundPause,
    onBack: () -> Unit,
    onSave: suspend (BackgroundPause) -> Boolean,
) {
    var current by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { current = settings }
    var editingStart by remember { mutableStateOf<Boolean?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    SubpageLayout(title = "排程暫停時段", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = navigationContentPadding(PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsSwitchRow(
                        icon = "schedule",
                        title = "每天暫停背景檢查",
                        supportingText = "讓背景資料檢查在休息時間暫停",
                        checked = current.enabled,
                        onCheckedChange = { enabled ->
                            saving = true
                            message = null
                            scope.launch {
                                try {
                                    val updated = current.copy(enabled = enabled)
                                    if (onSave(updated)) current = updated else message = "儲存失敗，請重試"
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Surface(onClick = { editingStart = true }, enabled = !saving, color = Color.Transparent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("開始時間", style = MaterialTheme.typography.bodyLarge)
                            Text(formatPauseTime(current.startMinute), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(onClick = { editingStart = false }, enabled = !saving, color = Color.Transparent) {
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("結束時間", style = MaterialTheme.typography.bodyLarge)
                            Text(formatPauseTime(current.endMinute), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text(
                    "每天 ${formatPauseTime(current.startMinute)}–${formatPauseTime(current.endMinute)}${if (current.startMinute > current.endMinute) "（隔天）" else ""}，" +
                        "暫停已啟用的成績與學校公告的背景定期檢查，結束後於下一次原定檢查恢復。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            message?.let { text -> item { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) } }
        }
    }
    editingStart?.let { isStart ->
        val minute = if (isStart) current.startMinute else current.endMinute
        val time = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
        val selected = time.hour * 60 + time.minute
        val valid = selected != (if (isStart) current.endMinute else current.startMinute)
        AlertDialog(
            onDismissRequest = { if (!saving) editingStart = null },
            title = { Text(if (isStart) "開始時間" else "結束時間") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeInput(state = time, modifier = Modifier.padding(top = 8.dp))
                    if (!valid) Text("開始與結束時間不能相同", color = MaterialTheme.colorScheme.error)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = valid && !saving, shapes = ButtonDefaults.shapes(), onClick = {
                    val updated = if (isStart) current.copy(startMinute = selected) else current.copy(endMinute = selected)
                    saving = true
                    message = null
                    scope.launch {
                        try {
                            if (onSave(updated)) {
                                current = updated
                                editingStart = null
                            } else message = "儲存失敗，請重試"
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("確定") }
            },
            dismissButton = {
                TextButton(enabled = !saving, shapes = ButtonDefaults.shapes(), onClick = {
                    editingStart = null
                    message = null
                }) { Text("取消") }
            },
        )
    }
}
