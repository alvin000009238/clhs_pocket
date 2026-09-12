package com.clhs.score.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.notifications.hasPostNotificationsPermission
import com.clhs.score.notifications.openAppNotificationSettings
import com.clhs.score.notifications.shouldShowPostNotificationsRationale
import com.clhs.score.viewmodel.AnnouncementReminderCheckResult
import com.clhs.score.viewmodel.AnnouncementReminderUiState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnnouncementReminderSettingsScreen(
    uiState: AnnouncementReminderUiState,
    onBack: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onSetIntervalMinutes: (Int) -> Unit,
    onOpenUnits: () -> Unit,
    onCheckResultConsumed: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var awaitingNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val currentOnSetEnabled by rememberUpdatedState(onSetEnabled)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        currentOnSetEnabled(granted)
        permissionDenied = !granted
        if (!granted) Toast.makeText(context, "未取得通知權限", Toast.LENGTH_SHORT).show()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingNotificationSettings) {
                awaitingNotificationSettings = false
                currentOnSetEnabled(context.canPostNotifications())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun setEnabled(enabled: Boolean) {
        when {
            !enabled -> onSetEnabled(false)
            context.canPostNotifications() -> onSetEnabled(true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.hasPostNotificationsPermission() &&
                (!permissionDenied || context.shouldShowPostNotificationsRationale()) ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> {
                awaitingNotificationSettings = true
                if (context.openAppNotificationSettings()) {
                    Toast.makeText(context, "請在系統設定中開啟通知", Toast.LENGTH_SHORT).show()
                } else {
                    awaitingNotificationSettings = false
                    Toast.makeText(context, "請手動到系統設定開啟通知", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val reminder = uiState.reminder
    val units = rememberReminderUnits(uiState)
    val unitSummary = announcementUnitSummary(units, reminder.selectedUnitIds)
    val statusSummary = if (!uiState.isStateAvailable) {
        if (uiState.stateLoadFailed) "無法讀取設定，正在重試" else "正在讀取設定"
    } else buildString {
        if (!reminder.enabled) append("開啟後")
        append("每 ${announcementIntervalLabel(reminder.intervalMinutes)} · $unitSummary")
    }
    val checkResult = uiState.checkResult

    LaunchedEffect(checkResult) {
        val result = checkResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(announcementCheckMessage(result))
        onCheckResultConsumed()
    }

    SubpageLayout(
        title = "公告更新提醒",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                val contentColor = if (reminder.enabled) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (reminder.enabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = contentColor,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = reminder.enabled,
                                enabled = !uiState.isChecking && uiState.isStateAvailable,
                                role = Role.Switch,
                                onValueChange = ::setEnabled,
                            )
                            .semantics {
                                stateDescription = when {
                                    !uiState.isStateAvailable -> "設定狀態未知"
                                    uiState.isChecking -> "正在開啟"
                                    reminder.enabled -> "已開啟"
                                    else -> "已關閉"
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (reminder.enabled) {
                            FilledRoundedSymbol(
                                "notifications_active",
                                size = 32.dp,
                                tint = contentColor,
                            )
                        } else {
                            OutlinedRoundedSymbol(
                                "notifications",
                                size = 32.dp,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                when {
                                    !uiState.isStateAvailable -> "提醒狀態暫時無法確認"
                                    uiState.isChecking -> "正在開啟提醒"
                                    reminder.enabled -> "提醒已開啟"
                                    else -> "提醒已暫停"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            AnimatedContent(
                                targetState = statusSummary,
                                label = "公告更新提醒狀態",
                            ) { summary ->
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (reminder.enabled) {
                                        contentColor.copy(alpha = 0.76f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (uiState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Switch(checked = reminder.enabled, onCheckedChange = null)
                        }
                    }
                }
            }
            item {
                Text(
                    "提醒設定",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("檢查頻率", style = MaterialTheme.typography.titleMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        row.forEach { minutes ->
                                            val selected = reminder.intervalMinutes == minutes
                                            FilterChip(
                                                enabled = uiState.isStateAvailable,
                                                selected = selected,
                                                onClick = { onSetIntervalMinutes(minutes) },
                                                modifier = Modifier.weight(1f),
                                                label = {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                        Text(
                                                            announcementIntervalLabel(minutes),
                                                            fontWeight = if (selected) {
                                                                FontWeight.SemiBold
                                                            } else {
                                                                FontWeight.Normal
                                                            },
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                "建議選擇 1–3 小時，兼顧即時性與耗電量",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                        Surface(onClick = onOpenUnits, color = Color.Transparent) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                OutlinedRoundedSymbol("campaign", tint = MaterialTheme.colorScheme.primary)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text("接收單位", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        unitSummary,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedRoundedSymbol(
                                    "keyboard_arrow_right",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("最近檢查時間", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    announcementLastCheckedLabel(reminder.lastCheckedAtMillis),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnnouncementReminderUnitsScreen(
    uiState: AnnouncementReminderUiState,
    onBack: () -> Unit,
    onApplyUnits: (Set<String>) -> Unit,
    onApplyResultConsumed: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pendingUnitIds by rememberSaveable {
        mutableStateOf(uiState.reminder.selectedUnitIds.toList())
    }
    var awaitingApply by rememberSaveable { mutableStateOf(false) }
    val selectedUnitIds = pendingUnitIds.toSet()
    val units = rememberReminderUnits(uiState)
    val sourceUnits = units.filterNot { it.id == ALL_UNITS_ID }
    val filteredUnits = remember(sourceUnits, query) {
        val normalizedQuery = query.trim()
        orderAnnouncementUnits(
            if (normalizedQuery.isEmpty()) sourceUnits
            else sourceUnits.filter { it.name.contains(normalizedQuery, ignoreCase = true) },
        )
    }
    val selectedCount = selectedUnitIds.count { it != ALL_UNITS_ID }
    val allSelected = ALL_UNITS_ID in selectedUnitIds
    val hasChanges = selectedUnitIds != uiState.reminder.selectedUnitIds
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clearFocusAndHideKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val interactionSource = remember { MutableInteractionSource() }

    fun toggleUnit(unitId: String) {
        clearFocusAndHideKeyboard()
        pendingUnitIds = toggleAnnouncementUnitSelection(selectedUnitIds, unitId).toList()
    }

    LaunchedEffect(uiState.unitApplySucceeded) {
        when (uiState.unitApplySucceeded) {
            true -> if (awaitingApply) {
                onApplyResultConsumed()
                onBack()
            } else {
                onApplyResultConsumed()
            }
            false -> if (awaitingApply) {
                snackbarHostState.showSnackbar("套用失敗，請稍後再試")
                awaitingApply = false
                onApplyResultConsumed()
            } else {
                onApplyResultConsumed()
            }
            null -> Unit
        }
    }

    SubpageLayout(
        title = "選擇接收單位",
        onBack = onBack,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = clearFocusAndHideKeyboard,
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            TextButton(
                onClick = {
                    awaitingApply = true
                    onApplyUnits(selectedUnitIds)
                },
                enabled = hasChanges && !uiState.isApplyingUnits,
                modifier = Modifier.semantics {
                    if (uiState.isApplyingUnits) contentDescription = "正在套用接收單位"
                },
            ) {
                if (uiState.isApplyingUnits) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("套用")
                }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isApplyingUnits,
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    leadingIcon = {
                        OutlinedRoundedSymbol("search", contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = { query = "" },
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                OutlinedRoundedSymbol("close", contentDescription = "清除搜尋")
                            }
                        }
                    },
                    placeholder = { Text("搜尋單位") },
                )
            }
            if (uiState.unitLoadFailed) {
                item {
                    Text(
                        "暫時無法更新單位清單，已顯示上次成功取得的內容。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (allSelected) "已選擇全部" else "已選擇 $selectedCount 個",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${sourceUnits.size} 個來源",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                UnitSelectionRow(
                    unit = AnnouncementUnit(ALL_UNITS_ID, "全部"),
                    selected = allSelected,
                    enabled = !uiState.isApplyingUnits,
                    onToggle = ::toggleUnit,
                )
            }
            if (filteredUnits.isNotEmpty()) {
                item(key = "unit-list") {
                    Column {
                        HorizontalDivider()
                        filteredUnits.forEachIndexed { index, unit ->
                            UnitSelectionRow(
                                unit = unit,
                                selected = unit.id in selectedUnitIds,
                                recommended = unit.name in RECOMMENDED_ANNOUNCEMENT_UNIT_NAMES,
                                enabled = !uiState.isApplyingUnits,
                                onToggle = ::toggleUnit,
                            )
                            if (index != filteredUnits.lastIndex) HorizontalDivider()
                        }
                    }
                }
            } else {
                item {
                    Text(
                        if (sourceUnits.isEmpty()) "尚未取得來源" else "找不到符合的單位",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitSelectionRow(
    unit: AnnouncementUnit,
    selected: Boolean,
    recommended: Boolean = false,
    enabled: Boolean = true,
    onToggle: (String) -> Unit,
) {
    ListItem(
        leadingContent = { Checkbox(checked = selected, enabled = enabled, onCheckedChange = null) },
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(unit.id) },
            )
            .semantics {
                stateDescription = if (selected) "已選取" else "未選取"
        },
        trailingContent = if (recommended) {
            {
                Text(
                    "推薦",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            unit.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val RECOMMENDED_ANNOUNCEMENT_UNIT_NAMES = listOf(
    "實驗研究組官網",
    "教學組官網",
    "學務處官網",
)

internal fun orderAnnouncementUnits(units: List<AnnouncementUnit>): List<AnnouncementUnit> =
    units.sortedBy { unit ->
        RECOMMENDED_ANNOUNCEMENT_UNIT_NAMES.indexOf(unit.name).takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

internal fun toggleAnnouncementUnitSelection(selected: Set<String>, unitId: String): Set<String> = when (unitId) {
    ALL_UNITS_ID -> setOf(ALL_UNITS_ID)
    in selected -> if (selected.size == 1) selected else selected - unitId
    else -> (selected - ALL_UNITS_ID) + unitId
}

@Composable
private fun rememberReminderUnits(uiState: AnnouncementReminderUiState): List<AnnouncementUnit> =
    remember(uiState.units, uiState.reminder.selectedUnitIds) {
        val knownIds = uiState.units.mapTo(mutableSetOf(), AnnouncementUnit::id)
        uiState.units + uiState.reminder.selectedUnitIds
            .filter { it != ALL_UNITS_ID && it !in knownIds }
            .map { AnnouncementUnit(it, "已選來源") }
    }

internal fun announcementUnitSummary(
    units: List<AnnouncementUnit>,
    selectedUnitIds: Set<String>,
): String {
    val sourceUnits = units.filterNot { it.id == ALL_UNITS_ID }
    if (selectedUnitIds.isEmpty() || ALL_UNITS_ID in selectedUnitIds) {
        return if (sourceUnits.isEmpty()) "全部單位" else "全部單位 · ${sourceUnits.size} 個來源"
    }

    val selectedIds = selectedUnitIds.filterNot { it == ALL_UNITS_ID }
    val selectedUnits = sourceUnits.filter { it.id in selectedUnitIds }
    val missingCount = selectedIds.count { id -> sourceUnits.none { it.id == id } }
    val names = selectedUnits.map(AnnouncementUnit::name) + List(missingCount) { "已選來源" }
    val count = selectedIds.size
    val nameSummary = names.take(2).joinToString("、")
    return if (count > 2) "${nameSummary}等 $count 個來源" else "$nameSummary · $count 個來源"
}

internal fun announcementLastCheckedLabel(
    lastCheckedAtMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): String {
    if (lastCheckedAtMillis <= 0L) return "尚未檢查"
    val checkedAt = runCatching { Instant.ofEpochMilli(lastCheckedAtMillis).atZone(zoneId) }
        .getOrNull() ?: return "尚未檢查"
    val time = checkedAt.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (checkedAt.toLocalDate()) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> checkedAt.format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    }
}

private fun announcementCheckMessage(result: AnnouncementReminderCheckResult): String =
    result.newAnnouncementCount?.let { count ->
        if (count == 0) "已完成檢查，沒有新公告" else "找到 $count 則新公告"
    } ?: "檢查公告失敗，請稍後再試"

internal fun announcementIntervalLabel(minutes: Int): String = when (minutes) {
    30 -> "30 分鐘"
    60 -> "1 小時"
    180 -> "3 小時"
    360 -> "6 小時"
    720 -> "12 小時"
    1_440 -> "24 小時"
    else -> "$minutes 分鐘"
}
