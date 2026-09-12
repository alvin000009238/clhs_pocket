package com.clhs.score.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.clhs.score.BuildConfig
import com.clhs.score.data.AppSettings
import com.clhs.score.data.DeveloperDiagnostics
import com.clhs.score.data.ErrorDiagnosticContext
import com.clhs.score.data.GradeChange
import com.clhs.score.data.GradeChangeField
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.LocalDataCategory
import com.clhs.score.data.LocalDataCleanupResult
import com.clhs.score.data.StorageDiagnostics
import com.clhs.score.data.StorageEntry
import com.clhs.score.data.defaultSelectedLocalDataCategories
import com.clhs.score.data.toReadableSize
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.reminders.GradeReminderNotifier
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeveloperSettingsScreen(
    settings: AppSettings,
    showRestartDialog: Boolean,
    isLoggedIn: Boolean,
    loginErrorMessage: String?,
    gradesErrorMessage: String?,
    onBack: () -> Unit,
    onSetDemoMode: (Boolean) -> Unit,
    onRestartOnboarding: () -> Unit,
    onDismissRestartDialog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember(context) { DeveloperDiagnostics(context.applicationContext) }

    var isBusy by remember { mutableStateOf(false) }
    var storageDiagnostics by remember { mutableStateOf<StorageDiagnostics?>(null) }
    var cleanupResult by remember { mutableStateOf<LocalDataCleanupResult?>(null) }
    var diagnosticReport by remember { mutableStateOf<String?>(null) }
    var selectedCleanupCategories by remember { mutableStateOf(defaultSelectedLocalDataCategories()) }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("需要重新啟動") },
            text = { Text("Demo 模式會在重新啟動 App 後完整套用。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissRestartDialog()
                        val activity = context as? Activity
                        activity?.finishAffinity()
                        kotlin.system.exitProcess(0)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("關閉 App")
                }
            },
        )
    }

    storageDiagnostics?.let { currentDiagnostics ->
        LocalDataManagementDialog(
            diagnostics = currentDiagnostics,
            cleanupResult = cleanupResult,
            selectedCategories = selectedCleanupCategories,
            isBusy = isBusy,
            onToggleCategory = { category ->
                selectedCleanupCategories = if (category in selectedCleanupCategories) {
                    selectedCleanupCategories - category
                } else {
                    selectedCleanupCategories + category
                }
            },
            onClearSelected = {
                val categories = selectedCleanupCategories
                if (categories.isEmpty()) return@LocalDataManagementDialog
                isBusy = true
                scope.launch {
                    runCatching {
                        diagnostics.clearLocalData(categories)
                    }.onSuccess { result ->
                        cleanupResult = result
                        storageDiagnostics = result.storageAfterCleanup
                        Toast.makeText(context, "已清除選取資料", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "清除資料失敗", Toast.LENGTH_SHORT).show()
                    }
                    isBusy = false
                }
            },
            onDismiss = { storageDiagnostics = null },
        )
    }

    diagnosticReport?.let { report ->
        DiagnosticReportDialog(
            report = report,
            onCopy = {
                context.copyText(report)
                Toast.makeText(context, "診斷包已複製", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { diagnosticReport = null },
        )
    }

    val developerItemCount = if (BuildConfig.DEBUG) 5 else 4
    SubpageLayout(
        onBack = onBack,
        title = "開發者選項",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            DeveloperSwitchItem(
                checked = settings.demoMode,
                onCheckedChange = onSetDemoMode,
                count = developerItemCount,
            )

            DeveloperActionItem(
                icon = "refresh",
                title = "重跑 onboarding",
                subtitle = "重新進行 onboarding 流程。",
                enabled = !isBusy,
                index = 1,
                count = developerItemCount,
                onClick = onRestartOnboarding,
            )

            if (BuildConfig.DEBUG) {
                DeveloperActionItem(
                    icon = "notifications_active",
                    title = "段考更新提醒測試通知",
                    subtitle = "發送一則模擬資訊更新通知，測試手機是否能收到提醒。",
                    enabled = !isBusy,
                    index = 2,
                    count = developerItemCount,
                    onClick = {
                        showGradeReminderTestNotification(context)
                    },
                )
            }

            DeveloperActionItem(
                icon = "settings",
                title = "本機資料與儲存空間",
                subtitle = "查看各項資料大小，並只清除選取的本機資料。",
                enabled = !isBusy,
                index = if (BuildConfig.DEBUG) 3 else 2,
                count = developerItemCount,
                onClick = {
                    isBusy = true
                    scope.launch {
                        cleanupResult = null
                        selectedCleanupCategories = defaultSelectedLocalDataCategories()
                        runCatching {
                            diagnostics.collectStorageDiagnostics()
                        }.onSuccess { result ->
                            storageDiagnostics = result
                        }.onFailure {
                            Toast.makeText(context, "讀取儲存空間失敗", Toast.LENGTH_SHORT).show()
                        }
                        isBusy = false
                    }
                },
            )

            DeveloperActionItem(
                icon = "science",
                title = "錯誤診斷包",
                subtitle = "產生偵錯用診斷文字。",
                enabled = !isBusy,
                index = developerItemCount - 1,
                count = developerItemCount,
                onClick = {
                    isBusy = true
                    scope.launch {
                        runCatching {
                            diagnostics.buildErrorReport(
                                ErrorDiagnosticContext(
                                    isLoggedIn = isLoggedIn,
                                    loginErrorMessage = loginErrorMessage,
                                    gradesErrorMessage = gradesErrorMessage,
                                ),
                            )
                        }.onSuccess { report ->
                            diagnosticReport = report
                        }.onFailure {
                            Toast.makeText(context, "產生診斷包失敗", Toast.LENGTH_SHORT).show()
                        }
                        isBusy = false
                    }
                },
            )

            if (isBusy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    LoadingIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "處理中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun showGradeReminderTestNotification(context: Context) {
    if (!context.canPostNotifications()) {
        Toast.makeText(context, "請先允許通知權限", Toast.LENGTH_SHORT).show()
        return
    }

    val changeSet = GradeChangeSet(
        studentNo = "debug",
        yearValue = "debug_year",
        examValue = "debug_exam",
        examName = "段考測試",
        checkedAtMillis = System.currentTimeMillis(),
        changes = listOf(
            GradeChange(
                targetName = "數學",
                subjectName = "數學",
                field = GradeChangeField.SUBJECT_SCORE,
                oldValue = "測試前",
                newValue = "測試後",
            ),
            GradeChange(
                targetName = "數學",
                subjectName = "數學",
                field = GradeChangeField.SUBJECT_CLASS_RANK,
                oldValue = "測試前",
                newValue = "測試後",
            ),
            GradeChange(
                targetName = "總覽",
                field = GradeChangeField.SUMMARY_AVERAGE,
                oldValue = "測試前",
                newValue = "測試後",
            ),
        ),
    )
    GradeReminderNotifier(context.applicationContext).showChangedNotification(changeSet)
    Toast.makeText(context, "已發送段考更新提醒測試通知", Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeveloperSwitchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    count: Int,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = count),
        leadingContent = {
            OutlinedRoundedSymbol(
                icon = "science",
                size = 24.dp,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = "使用假資料測試畫面，不依賴學校系統登入。",
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            leadingContentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = "Demo 模式")
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeveloperActionItem(
    icon: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = {
            OutlinedRoundedSymbol(
                icon = icon,
                size = 24.dp,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            leadingContentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = title)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LocalDataManagementDialog(
    diagnostics: StorageDiagnostics,
    cleanupResult: LocalDataCleanupResult?,
    selectedCategories: Set<LocalDataCategory>,
    isBusy: Boolean,
    onToggleCategory: (LocalDataCategory) -> Unit,
    onClearSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!isBusy) onDismiss()
        },
        title = { Text("本機資料與儲存空間") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cleanupResult?.let {
                    Text(
                        text = "已釋放 ${it.removedBytes.toReadableSize()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    diagnostics.entries.forEachIndexed { index, entry ->
                        StorageEntryRow(
                            entry = entry,
                            checked = entry.category in selectedCategories,
                            enabled = entry.isClearable && !isBusy,
                            onToggle = { onToggleCategory(entry.category) },
                            index = index,
                            count = diagnostics.entries.size,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "合計",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = diagnostics.totalBytes.toReadableSize(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onClearSelected,
                enabled = !isBusy && selectedCategories.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(if (selectedCategories.isEmpty()) "請先選擇" else "清除選取")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isBusy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("關閉")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageEntryRow(
    entry: StorageEntry,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    index: Int,
    count: Int,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val leadingContent: @Composable () -> Unit = {
        if (entry.isClearable) {
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                ),
            )
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
    val trailingContent: @Composable () -> Unit = {
        Text(
            text = entry.bytes.toReadableSize(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val content: @Composable () -> Unit = {
        Text(text = entry.label)
    }

    if (entry.isClearable) {
        SegmentedListItem(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            shapes = shapes,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            colors = colors,
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        SegmentedListItem(
            enabled = false,
            shapes = shapes,
            leadingContent = leadingContent,
            supportingContent = {
                Text(
                    text = "保留設定、Demo 模式與開發者選項",
                    modifier = Modifier.padding(top = 4.dp),
                )
            },
            trailingContent = trailingContent,
            colors = colors,
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiagnosticReportDialog(
    report: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("錯誤診斷包") },
        text = {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = onCopy,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("複製")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("關閉")
            }
        },
    )
}

private fun Context.copyText(text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CLHS Pocket 診斷包", text))
}
