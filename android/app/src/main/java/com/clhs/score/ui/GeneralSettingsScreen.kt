@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding


import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.BuildConfig
import com.clhs.score.R
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ThemeMode
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.notifications.hasPostNotificationsPermission
import com.clhs.score.notifications.openAppNotificationSettings
import com.clhs.score.notifications.shouldShowPostNotificationsRationale
import com.clhs.score.ui.theme.OutfitFontFamily
import com.clhs.score.viewmodel.SettingsUiState
import com.clhs.score.viewmodel.UpdateState
import com.google.firebase.remoteconfig.FirebaseRemoteConfig

private const val ProfileSourceCodeUrl = "https://github.com/alvin000009238/clhs_pocket"
private const val ProfileFeedbackFormUrlKey = "feedback_form_url"
private val ProfileMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GeneralSettingsScreen(
    settings: AppSettings,
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetStartOnSchedule: (Boolean) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    onDismissDeveloperToast: () -> Unit,
    onOpenUsageStatistics: () -> Unit,
    onOpenSourceLicenses: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenWorkManagerInfo: () -> Unit,
    onOpenBackgroundPauseSettings: () -> Unit = {},
    onOpenScheduleReliabilitySettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val rootMotionScheme = MaterialTheme.motionScheme
    var awaitingNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val currentOnSetNotificationsEnabled by rememberUpdatedState(onSetNotificationsEnabled)
    val currentOnDismissDeveloperToast by rememberUpdatedState(onDismissDeveloperToast)
    val remoteConfig = remember {
        if (BuildConfig.USE_FAKE_DATA) null else FirebaseRemoteConfig.getInstance().also {
            it.setDefaultsAsync(mapOf(ProfileFeedbackFormUrlKey to ""))
        }
    }
    var isFetchingFeedback by remember { mutableStateOf(false) }
    var advancedBackgroundExpanded by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        currentOnSetNotificationsEnabled(granted)
        notificationPermissionDenied = !granted
        Toast.makeText(
            context,
            if (granted) "已開啟推播通知" else "未取得通知權限，可再次嘗試開啟",
            Toast.LENGTH_SHORT,
        ).show()
    }

    DisposableEffect(lifecycleOwner, settings.notificationsEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingNotificationSettings) {
                awaitingNotificationSettings = false
                val enabled = context.canPostNotifications()
                currentOnSetNotificationsEnabled(enabled)
                notificationPermissionDenied = !enabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.showDeveloperUnlockedToast) {
        if (uiState.showDeveloperUnlockedToast) {
            Toast.makeText(context, "已開啟開發者選項", Toast.LENGTH_SHORT).show()
            currentOnDismissDeveloperToast()
        }
    }

    fun setNotifications(enabled: Boolean) {
        if (!enabled) {
            onSetNotificationsEnabled(false)
        } else if (context.canPostNotifications()) {
            onSetNotificationsEnabled(true)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPostNotificationsPermission() &&
            (!notificationPermissionDenied || context.shouldShowPostNotificationsRationale())
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            awaitingNotificationSettings = true
            if (context.openAppNotificationSettings()) {
                Toast.makeText(context, "請在系統設定中開啟通知", Toast.LENGTH_SHORT).show()
            } else {
                awaitingNotificationSettings = false
                Toast.makeText(context, "無法開啟通知設定，請手動到系統設定開啟", Toast.LENGTH_SHORT).show()
            }
        }
    }

    MaterialTheme(motionScheme = rootMotionScheme) {
        FloatingSubpageLayout(
            onBack = onBack,
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { pagePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                contentPadding = navigationContentPadding(PaddingValues(start = 16.dp, top = pagePadding.calculateTopPadding() + 8.dp, end = 16.dp, bottom = pagePadding.calculateBottomPadding() + 40.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    SettingsSection("外觀") {
                        SettingsGroup {
                            ThemeSelector(settings.themeMode, onSetThemeMode, compact = true)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SettingsSwitchRow(
                                    icon = "palette",
                                    title = "動態色彩",
                                    supportingText = "依照桌布色彩調整",
                                    checked = settings.dynamicColor,
                                    onCheckedChange = onSetDynamicColor,
                                    compact = true,
                                )
                            }
                            SettingsSwitchRow(
                                icon = "dark_mode",
                                title = "AMOLED 純黑",
                                supportingText = "深色模式使用純黑背景",
                                checked = settings.amoledBlack,
                                onCheckedChange = onSetAmoledBlack,
                                compact = true,
                                enabled = settings.themeMode == ThemeMode.DARK ||
                                    (settings.themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme()),
                            )
                        }
                    }
                }

                item {
                    SettingsSection("啟動與導覽") {
                        SettingsGroup {
                            SettingsSwitchRow(
                                icon = "calendar_view_week",
                                title = "啟動時開啟課表",
                                supportingText = "從桌面啟動時直接顯示課表",
                                checked = settings.startOnSchedule,
                                onCheckedChange = onSetStartOnSchedule,
                                compact = true,
                            )
                        }
                    }
                }

                item {
                    SettingsSection("通知與提醒") {
                        SettingsGroup {
                            SettingsSwitchRow(
                                icon = "notifications",
                                title = "通知",
                                supportingText = "接收 App 更新與公告推播",
                                checked = settings.notificationsEnabled,
                                onCheckedChange = ::setNotifications,
                                compact = true,
                            )
                            SettingsRow(
                                icon = "schedule",
                                title = "排程暫停時段",
                                supportingText = if (settings.backgroundPause.enabled) {
                                    "每天 ${formatPauseTime(settings.backgroundPause.startMinute)}–${formatPauseTime(settings.backgroundPause.endMinute)}"
                                } else "未啟用",
                                onClick = onOpenBackgroundPauseSettings,
                                compact = true,
                            )
                            Surface(
                                onClick = hapticClick { advancedBackgroundExpanded = !advancedBackgroundExpanded },
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().semantics {
                                    role = Role.Button
                                    stateDescription = if (advancedBackgroundExpanded) "已展開" else "已收合"
                                },
                            ) {
                                Row(
                                    modifier = Modifier.heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    PocketOptionIcon("settings")
                                    Text("進階背景設定", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
                                    OutlinedRoundedSymbol(if (advancedBackgroundExpanded) "keyboard_arrow_up" else "keyboard_arrow_down", tint = MaterialTheme.colorScheme.onSurfaceVariant, contentDescription = null)
                                }
                            }
                            if (advancedBackgroundExpanded) {
                                SettingsRow(
                                    icon = "info",
                                    title = "執行資訊",
                                    supportingText = "背景工作狀態",
                                    compact = true,
                                    onClick = onOpenWorkManagerInfo,
                                )
                                SettingsRow(
                                    icon = "alarm",
                                    title = "排程可靠性",
                                    supportingText = "課程通知與小工具排程",
                                    compact = true,
                                    onClick = onOpenScheduleReliabilitySettings,
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsSection("資料與隱私") {
                        SettingsGroup {
                            SettingsRow(
                                icon = "data_usage",
                                title = "使用統計",
                                supportingText = "各功能使用次數",
                                compact = true,
                                onClick = onOpenUsageStatistics,
                            )
                        }
                    }
                }

                item {
                    val updateState = uiState.updateState
                    val updateValue = when (updateState) {
                        is UpdateState.UpdateAvailable -> "v${updateState.release.versionName} 可用"
                        is UpdateState.Checking -> "正在檢查"
                        is UpdateState.UpToDate -> "已是最新版本"
                        is UpdateState.Error -> "檢查失敗"
                        is UpdateState.Idle -> null
                    }
                    SettingsSection("關於") {
                        SettingsGroup {
                            AppIdentityRow(
                                onVersionTap = onVersionTap,
                            )
                            SettingsRow(
                                icon = "system_update",
                                title = "App 更新",
                                value = updateValue,
                                loading = uiState.isCheckingUpdate,
                                enabled = !uiState.isCheckingUpdate,
                                iconBadge = updateState is UpdateState.UpdateAvailable,
                                onClick = onCheckUpdate,
                            )
                            SettingsRow(
                                icon = "code",
                                title = "原始碼",
                                value = "GitHub",
                                onClick = { openProfileUrl(context, ProfileSourceCodeUrl) },
                            )
                            SettingsRow(
                                icon = "license",
                                title = "開源授權",
                                value = "MIT License",
                                onClick = onOpenSourceLicenses,
                            )
                            if (remoteConfig != null) {
                                SettingsRow(
                                    icon = "thumbs_up_down",
                                    title = "意見回饋",
                                    loading = isFetchingFeedback,
                                    enabled = !isFetchingFeedback,
                                    onClick = {
                                        isFetchingFeedback = true
                                        remoteConfig.fetchAndActivate().addOnCompleteListener {
                                            isFetchingFeedback = false
                                            val url = remoteConfig.getString(ProfileFeedbackFormUrlKey)
                                            if (isAllowedFeedbackFormUrl(url)) {
                                                openProfileUrl(context, url)
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "暫時無法取得回饋表單，請稍後再試",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (settings.developerEnabled) {
                    item {
                        SettingsSection("開發者選項", subdued = true) {
                            SettingsGroup {
                                SettingsRow(
                                    icon = "science",
                                    title = "開發者選項",
                                    supportingText = "更多進階設定",
                                    onClick = onOpenDeveloperSettings,
                                )
                            }
                        }
                    }
                }

            }
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    subdued: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = ProfileMaxWidth)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = if (subdued) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        content()
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun ThemeSelector(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
    systemLabel: String = "系統",
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Text("主題", style = MaterialTheme.typography.bodyLarge)
        val options = listOf(systemLabel to ThemeMode.SYSTEM, "淺色" to ThemeMode.LIGHT, "深色" to ThemeMode.DARK)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (label, mode) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelected(mode) },
                    icon = {
                        if (selected == mode && mode != ThemeMode.SYSTEM) {
                            OutlinedRoundedSymbol(
                                icon = if (mode == ThemeMode.LIGHT) "light_mode" else "dark_mode",
                                size = 18.dp,
                                contentDescription = null,
                            )
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
    supportingText: String? = null,
    value: String? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    iconBadge: Boolean = false,
    compact: Boolean = false,
) {
    Surface(
        onClick = hapticClick(onClick),
        enabled = enabled,
        modifier = Modifier.semantics { role = Role.Button },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = if (compact) 80.dp else 84.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box {
                PocketOptionIcon(icon, enabled = enabled)
                if (iconBadge) Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                supportingText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                value?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                if (loading) LoadingIndicator(Modifier.size(28.dp))
                else OutlinedRoundedSymbol("keyboard_arrow_right", size = 24.dp, contentDescription = null)
            }
        }
    }
}

@Composable
internal fun SettingsSwitchRow(
    icon: String,
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 80.dp else 84.dp)
            .semantics { stateDescription = if (checked) "已開啟" else "已關閉" }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = hapticToggle(onCheckedChange),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PocketOptionIcon(icon, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            if (supportingText.isNotBlank()) Text(
                supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun AppIdentityRow(onVersionTap: () -> Unit) {
    Surface(
        onClick = onVersionTap,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = Color.White,
            ) {
                Image(
                    painter = painterResource(R.drawable.clhs_pocket_foreground),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.5f),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "CLHS Pocket",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun openProfileUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, "無法開啟連結", Toast.LENGTH_SHORT).show()
    }
}
