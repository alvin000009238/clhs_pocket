package com.clhs.score.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.navigation.navigationContentPadding
import com.clhs.score.viewmodel.SettingsTransferUiState
import com.clhs.score.viewmodel.SettingsTransferViewModel
import java.time.LocalDate

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsTransferScreen(state: SettingsTransferUiState, owner: SettingsTransferViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var showContents by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), owner::writeFile)
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) owner.readFile(uri)
    }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); owner.consumeMessage() }
    }
    val enabled = !state.loading && !state.busy
    BackHandler(state.busy) {}
    SubpageLayout(title = "備份與還原", onBack = { if (!state.busy) onBack() }, snackbarHost = { SnackbarHost(snackbar) }) {
        Column(Modifier.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp)
            .fillMaxSize().align(Alignment.TopCenter).imePadding().verticalScroll(rememberScrollState())
            .padding(navigationContentPadding(PaddingValues(horizontal = 20.dp, vertical = 12.dp))),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledRoundedSymbol("settings", size = 36.dp, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Text("設定、首頁與自訂科目", style = MaterialTheme.typography.titleLarge)
                    Text("保存目前配置，或從備份檔還原。", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = hapticClick {
                        owner.exportCurrent { export.launch("clhs-pocket-settings-" + LocalDate.now() + ".json") }
                    }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
                        OutlinedRoundedSymbol("download", contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("儲存備份")
                    }
                    OutlinedButton(onClick = hapticClick { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
                        OutlinedRoundedSymbol("refresh", contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("從檔案還原")
                    }
                }
            }
            if (state.busy || state.loading) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoadingIndicator(Modifier.size(32.dp))
                Text(if (state.loading) "讀取設定中" else "處理中", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.preview == null) state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(onClick = hapticClick { showContents = true }, modifier = Modifier.fillMaxWidth(), shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = { OutlinedRoundedSymbol("info", contentDescription = null) },
                    trailingContent = { OutlinedRoundedSymbol("keyboard_arrow_right", contentDescription = null) }) { Text("備份範圍") }
                SegmentedListItem(onClick = hapticClick { advanced = !advanced }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (advanced) "已展開" else "已收合" },
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    leadingContent = { OutlinedRoundedSymbol("code", contentDescription = null) },
                    trailingContent = { OutlinedRoundedSymbol(if (advanced) "keyboard_arrow_up" else "keyboard_arrow_down", contentDescription = null) }) {
                    Text("進階：JSON 編輯")
                }
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = state.text, onValueChange = owner::edit, enabled = enabled,
                        label = { Text("設定 JSON") }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = state.error != null)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CLHS Pocket 設定", state.text))
                        }, enabled = enabled && state.text.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("複製") }
                        TextButton(onClick = owner::loadCurrent, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text("重設草稿") }
                    }
                    FilledTonalButton(onClick = owner::previewImport, enabled = enabled && state.text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text("套用 JSON") }
                }
            }
        }
    }
    if (showContents) AlertDialog(onDismissRequest = { showContents = false }, title = { Text("備份範圍") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("包含外觀、啟動與通知偏好、背景暫停、公告提醒、首頁元件排列與內容，以及目前解鎖帳號的自訂科目。")
            Text("不含登入資料、API 授權碼、生物識別、桌面 Widget 與課程／成績提醒啟用狀態。系統權限需在裝置上另行設定。")
            Text("自訂文字可能含個人資訊，請妥善保管備份檔。")
        } }, confirmButton = { TextButton(onClick = { showContents = false }) { Text("知道了") } })
    state.preview?.let { document ->
        var includeSubjects by remember(document) { mutableStateOf(state.canImportSubjects) }
        AlertDialog(onDismissRequest = { if (!state.busy) owner.dismissPreview() }, title = { Text("還原備份？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("目前的設定與首頁配置將被取代。")
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("首頁配置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(document.overview.size.toString() + " 個元件", style = MaterialTheme.typography.titleMedium)
                            if (document.overview.any { !it.enabled }) Text("含 " + document.overview.count { !it.enabled } + " 個停用元件",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (document.subjects != null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = includeSubjects,
                            enabled = state.canImportSubjects && !state.busy, role = Role.Checkbox, onValueChange = { includeSubjects = it }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = includeSubjects, onCheckedChange = null, enabled = state.canImportSubjects && !state.busy)
                            Text("一併還原自訂科目（" + document.subjects.size + " 筆）", Modifier.weight(1f))
                        }
                        Text(if (state.canImportSubjects) "套用至目前登入的帳號。" else "尚未登入，這次保留現有自訂科目。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("備份未包含自訂科目，現有設定會保留。", style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { owner.importSettings(includeSubjects) }, enabled = !state.busy,
                shapes = ButtonDefaults.shapes()) { Text(if (state.busy) "還原中" else "確認還原") } },
            dismissButton = { TextButton(onClick = owner::dismissPreview, enabled = !state.busy,
                shapes = ButtonDefaults.shapes()) { Text("取消") } })
    }
}
