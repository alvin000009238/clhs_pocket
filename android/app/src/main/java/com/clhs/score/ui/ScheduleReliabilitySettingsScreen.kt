package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.data.AppSettings
import com.clhs.score.reminders.BatteryOptimizationHelper
import com.clhs.score.reminders.exactAlarmAccessAvailable

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ScheduleReliabilitySettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onSetPreferExactAlarms: (Boolean) -> Unit,
    onRefreshSystemState: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactAccess by remember(context) { mutableStateOf(exactAlarmAccessAvailable(context)) }
    var batteryExempt by remember(context) {
        mutableStateOf(runCatching { BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }.getOrDefault(false))
    }
    var exactSettingsUnavailable by rememberSaveable { mutableStateOf(false) }
    var batterySettingsUnavailable by rememberSaveable { mutableStateOf(false) }
    var showFurtherInfo by rememberSaveable { mutableStateOf(false) }
    val needsExactSpecialAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val currentOnRefreshSystemState by rememberUpdatedState(onRefreshSystemState)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val resumedExact = exactAlarmAccessAvailable(context)
                val resumedBattery = runCatching {
                    BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                }.getOrDefault(false)
                val changed = exactAccess != resumedExact || batteryExempt != resumedBattery
                exactAccess = resumedExact
                batteryExempt = resumedBattery
                if (resumedExact) exactSettingsUnavailable = false
                if (resumedBattery) batterySettingsUnavailable = false
                if (changed) currentOnRefreshSystemState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openExactAlarmSettings() {
        if (!needsExactSpecialAccess) return
        exactSettingsUnavailable = false
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData("package:${context.packageName}".toUri()),
            )
        }.onFailure {
            exactSettingsUnavailable = true
        }
    }

    ScheduleReliabilitySettingsContent(
        exactOptIn = settings.preferExactCourseAlarms,
        exactAccess = exactAccess,
        needsExactSpecialAccess = needsExactSpecialAccess,
        batteryExempt = batteryExempt,
        onBack = onBack,
        onToggleExact = onSetPreferExactAlarms,
        onManageExact = ::openExactAlarmSettings,
        onManageBattery = {
            batterySettingsUnavailable = false
            if (batteryExempt) {
                runCatching {
                    context.startActivity(BatteryOptimizationHelper.ignoreBatteryOptimizationSettingsIntent())
                }.onFailure {
                    batterySettingsUnavailable = true
                }
            } else if (!BatteryOptimizationHelper.openBatteryOptimizationRequest(context)) {
                batterySettingsUnavailable = true
            }
        },
        exactSettingsUnavailable = exactSettingsUnavailable,
        batterySettingsUnavailable = batterySettingsUnavailable,
        onOpenFurtherInfo = { showFurtherInfo = true },
    )

    if (showFurtherInfo) {
        ModalBottomSheet(
            onDismissRequest = { showFurtherInfo = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("了解更多", style = MaterialTheme.typography.headlineSmall)
                InfoSection(
                    title = "為什麼通知可能延遲？",
                    body = "省電模式與手機廠商的限制可能延後通知和小工具更新。",
                )
                InfoSection(
                    title = "精確鬧鐘",
                    body = "讓課程通知更接近預定時間。",
                )
                InfoSection(
                    title = "電池最佳化",
                    body = "一般不必調整。通知持續延遲時再考慮排除，可能增加耗電。",
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun ScheduleReliabilitySettingsContent(
    exactOptIn: Boolean,
    exactAccess: Boolean,
    needsExactSpecialAccess: Boolean,
    batteryExempt: Boolean,
    onBack: () -> Unit,
    onToggleExact: (Boolean) -> Unit,
    onManageBattery: () -> Unit,
    onManageExact: () -> Unit = {},
    onOpenFurtherInfo: () -> Unit = {},
    exactSettingsUnavailable: Boolean = false,
    batterySettingsUnavailable: Boolean = false,
) {
    val needsPermission = exactOptIn && needsExactSpecialAccess && !exactAccess
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    SubpageLayout(title = "排程可靠性", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = navigationContentPadding(PaddingValues(16.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingIcon(if (exactOptIn && !needsPermission) "check_circle" else "alarm")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            when {
                                needsPermission -> "還差一步"
                                exactOptIn -> "設定就緒"
                                else -> "使用一般排程"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (!exactOptIn) {
                            Text(
                                "課程通知與小工具更新可能延遲。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .toggleable(value = exactOptIn, role = Role.Switch, onValueChange = onToggleExact)
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("精確鬧鐘", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = exactOptIn, onCheckedChange = null)
                        }
                        if (exactOptIn && needsExactSpecialAccess) {
                            ReliabilitySettingsRow(
                                title = if (needsPermission) "允許鬧鐘與提醒" else "系統授權",
                                status = when {
                                    exactSettingsUnavailable -> "無法開啟"
                                    needsPermission -> "需設定"
                                    else -> "已允許"
                                },
                                onClick = onManageExact,
                                emphasized = needsPermission,
                                supportingText = when {
                                    exactSettingsUnavailable -> "請至 Android 設定搜尋「鬧鐘與提醒」。"
                                    needsPermission -> "在系統設定中允許後返回。"
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
            item {
                ReliabilitySettingsRow(
                    title = "進階選項",
                    status = "選用",
                    onClick = { showAdvanced = !showAdvanced },
                    expanded = showAdvanced,
                )
                if (showAdvanced) {
                    Text(
                        "通知仍延遲時再調整，可能增加耗電。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReliabilitySettingsRow(
                        title = "電池最佳化",
                        status = when {
                            batterySettingsUnavailable -> "無法開啟"
                            batteryExempt -> "已排除"
                            else -> "系統預設"
                        },
                        onClick = onManageBattery,
                        supportingText = if (batterySettingsUnavailable) "請至 Android 設定搜尋「電池最佳化」。" else null,
                    )
                }
            }
            item {
                TextButton(onClick = onOpenFurtherInfo) {
                    OutlinedRoundedSymbol("info", contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("了解更多")
                }
            }
        }
    }
}

@Composable
private fun ReliabilitySettingsRow(
    title: String,
    status: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    supportingText: String? = null,
    expanded: Boolean? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (expanded != null) stateDescription = if (expanded) "已展開" else "已收合" }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (supportingText != null) {
                Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedRoundedSymbol(
            if (expanded == true) "keyboard_arrow_up" else if (expanded == false) "keyboard_arrow_down" else "keyboard_arrow_right",
            contentDescription = null,
        )
    }
}

@Composable
private fun SettingIcon(icon: String) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            OutlinedRoundedSymbol(icon, contentDescription = null)
        }
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
