package com.clhs.score.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.BuildConfig
import com.clhs.score.R
import com.clhs.score.data.AppSettings
import com.clhs.score.data.BiometricHelper
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.WeatherSource
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.notifications.hasPostNotificationsPermission
import com.clhs.score.notifications.openAppNotificationSettings
import com.clhs.score.notifications.shouldShowPostNotificationsRationale
import com.clhs.score.ui.components.PinSetupDialog
import com.clhs.score.ui.theme.OutfitFontFamily
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.viewmodel.SettingsUiState
import com.clhs.score.viewmodel.UpdateState
import com.google.firebase.remoteconfig.FirebaseRemoteConfig

private const val ProfileSourceCodeUrl = "https://github.com/alvin000009238/clhs_pocket"
private const val ProfileFeedbackFormUrlKey = "feedback_form_url"
private val ProfileMaxWidth = 720.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PersonalScreen(
    state: GradesUiState,
    authState: AuthState = AuthState.Authenticated(0L),
    onRequestLogin: () -> Unit = {},
    settings: AppSettings,
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetBiometricEnabled: (Boolean, String?) -> Unit,
    onSetWeatherSource: (WeatherSource) -> Unit,
    onSaveCwaApiKey: (String) -> Unit,
    onClearCwaApiKey: () -> Unit,
    onDismissCwaKeyMessage: () -> Unit,
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    onDismissDeveloperToast: () -> Unit,
    onOpenUsageStatistics: () -> Unit,
    onOpenSourceLicenses: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onOpenWorkManagerInfo: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val utilityMotion = remember { MotionScheme.standard() }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var showCwaKeyDialog by remember { mutableStateOf(false) }
    var awaitingNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val currentOnSetNotificationsEnabled by rememberUpdatedState(onSetNotificationsEnabled)
    val currentOnDismissDeveloperToast by rememberUpdatedState(onDismissDeveloperToast)
    val remoteConfig = remember {
        FirebaseRemoteConfig.getInstance().also {
            it.setDefaultsAsync(mapOf(ProfileFeedbackFormUrlKey to ""))
        }
    }
    var isFetchingFeedback by remember { mutableStateOf(false) }

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

    LaunchedEffect(uiState.cwaKeyMessage) {
        uiState.cwaKeyMessage?.let {
            snackbarHostState.showSnackbar(it)
            onDismissCwaKeyMessage()
        }
    }
    LaunchedEffect(uiState.cwaKeyConfigured) {
        if (uiState.cwaKeyConfigured) showCwaKeyDialog = false
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

    if (showLogoutDialog) {
        LogoutConfirmDialog(
            onDismiss = { showLogoutDialog = false },
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
        )
    }
    if (showPinSetupDialog) {
        PinSetupDialog(
            onConfirm = {
                showPinSetupDialog = false
                onSetBiometricEnabled(true, it)
            },
            onDismiss = { showPinSetupDialog = false },
        )
    }
    if (showCwaKeyDialog) {
        ProfileCwaApiKeyDialog(
            saving = uiState.isSavingCwaKey,
            configured = uiState.cwaKeyConfigured,
            onConfirm = onSaveCwaApiKey,
            onRemove = {
                showCwaKeyDialog = false
                onClearCwaApiKey()
            },
            onDismiss = { if (!uiState.isSavingCwaKey) showCwaKeyDialog = false },
        )
    }

    MaterialTheme(motionScheme = utilityMotion) {
        Scaffold(
            topBar = {
                RootTopAppBar(
                    title = "個人",
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            OutlinedRoundedSymbol("arrow_back", contentDescription = "返回")
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { scaffoldPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
                contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item { ProfileHero(state, authState, onRequestLogin) }

                item {
                    SettingsSection("外觀") {
                        SettingsGroup {
                            ThemeSelector(settings.themeMode, onSetThemeMode)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                SettingsSwitchRow(
                                    icon = "palette",
                                    title = "動態色彩",
                                    supportingText = "依照桌布色彩調整",
                                    checked = settings.dynamicColor,
                                    onCheckedChange = onSetDynamicColor,
                                )
                            }
                            SettingsSwitchRow(
                                icon = "dark_mode",
                                title = "AMOLED 純黑",
                                supportingText = "深色模式使用純黑背景",
                                checked = settings.amoledBlack,
                                onCheckedChange = onSetAmoledBlack,
                                enabled = settings.themeMode == ThemeMode.DARK ||
                                    (settings.themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme()),
                            )
                        }
                    }
                }

                item {
                    SettingsSection("天氣") {
                        SettingsGroup {
                            WeatherSourceSelector(
                                selected = settings.weatherSource,
                                onSelected = { source ->
                                    if (source == WeatherSource.CWA && !uiState.cwaKeyConfigured) {
                                        showCwaKeyDialog = true
                                    } else {
                                        onSetWeatherSource(source)
                                    }
                                },
                            )
                            AnimatedVisibility(
                                visible = settings.weatherSource == WeatherSource.CWA,
                                enter = fadeIn(utilityMotion.defaultEffectsSpec()) +
                                    expandVertically(animationSpec = utilityMotion.defaultSpatialSpec()),
                                exit = fadeOut(utilityMotion.fastEffectsSpec()) +
                                    shrinkVertically(animationSpec = utilityMotion.defaultSpatialSpec()),
                            ) {
                                SettingsRow(
                                    icon = "lock",
                                    title = "授權碼",
                                    value = if (uiState.cwaKeyConfigured) "已設定" else "未設定",
                                    onClick = { showCwaKeyDialog = true },
                                )
                            }
                        }
                        if (settings.weatherSource == WeatherSource.CWA) {
                            Text(
                                "提供機關／交通部中央氣象署\n" +
                                    "資料名稱／鄉鎮天氣預報－桃園市未來3天天氣預報（F-D0047-005）\n" +
                                    "此開放資料依政府資料開放授權條款 (Open Government Data License) 進行公眾釋出。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        } else {
                            Text(
                                "資料來源／Open-Meteo.com\n" +
                                    "API 資料依 CC BY 4.0 提供。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
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
                            )
                            SettingsRow(
                                icon = "info",
                                title = "執行資訊",
                                supportingText = "查看所有背景工作的即時狀態",
                                onClick = onOpenWorkManagerInfo,
                            )
                        }
                    }
                }

                item {
                    SettingsSection("資料與隱私") {
                        SettingsGroup {
                            if (BiometricHelper.canAuthenticate(context)) {
                                AuthGate(
                                    authState = authState,
                                    requirement = com.clhs.score.ui.navigation.AuthRequirement.Session,
                                    onRequestLogin = onRequestLogin,
                                    fallback = {
                                        SettingsRow(
                                            icon = "fingerprint",
                                            title = "生物識別解鎖",
                                            supportingText = "登入後可設定生物識別與備用密碼",
                                            value = if (authState is AuthState.Authenticating) "確認中" else "登入",
                                            enabled = authState is AuthState.Guest,
                                            onClick = onRequestLogin,
                                        )
                                    },
                                ) {
                                    SettingsSwitchRow(
                                        icon = "fingerprint",
                                        title = "生物識別解鎖",
                                        supportingText = "啟動時驗證，並設定 4–6 位備用密碼",
                                        checked = settings.biometricEnabled,
                                        onCheckedChange = { enabled ->
                                            if (enabled) showPinSetupDialog = true
                                            else onSetBiometricEnabled(false, null)
                                        },
                                    )
                                }
                            }
                            SettingsRow(
                                icon = "newsstand",
                                title = "使用統計",
                                supportingText = "查看各項功能的使用次數",
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

                if (settings.developerEnabled) {
                    item {
                        SettingsSection("開發者選項", subdued = true) {
                            SettingsGroup {
                                SettingsRow(
                                    icon = "science",
                                    title = "開發者選項",
                                    supportingText = "Demo 模式與診斷工具",
                                    onClick = onOpenDeveloperSettings,
                                )
                            }
                        }
                    }
                }

                if (authState is AuthState.Authenticated) {
                    item {
                        OutlinedButton(
                            onClick = { showLogoutDialog = true },
                            modifier = Modifier
                                .widthIn(max = ProfileMaxWidth)
                                .fillMaxWidth()
                                .heightIn(min = 52.dp),
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush = androidx.compose.ui.graphics.SolidColor(
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
                                ),
                            ),
                        ) {
                            Text("登出帳號")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    state: GradesUiState,
    authState: AuthState,
    onRequestLogin: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .widthIn(max = ProfileMaxWidth)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        AuthGate(
            authState = authState,
            requirement = com.clhs.score.ui.navigation.AuthRequirement.Session,
            onRequestLogin = onRequestLogin,
            fallback = {
                ProfileGuestHero(authState, onRequestLogin)
            },
        ) {
            val student = state.studentInfo
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        OutlinedRoundedSymbol("account_circle", size = 38.dp, contentDescription = null)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        student?.studentName?.takeIf(String::isNotBlank) ?: "學生帳號",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            student?.className?.takeIf(String::isNotBlank),
                            student?.seatNo?.takeIf(String::isNotBlank)?.let { "$it 號" },
                        ).joinToString(" · ").ifBlank { "已登入" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    )
                    Text(
                        student?.studentNo?.takeIf(String::isNotBlank)
                            ?: state.studentNo.takeIf(String::isNotBlank)
                            ?: "學號讀取中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ProfileGuestHero(authState: AuthState, onRequestLogin: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    OutlinedRoundedSymbol("account_circle", size = 34.dp, contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("使用學校帳號登入", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "登入後查看成績、排名、課表與個人資料",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (authState is AuthState.Authenticating) {
            LoadingIndicator(modifier = Modifier.align(Alignment.End))
        } else {
            Button(
                onClick = onRequestLogin,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("登入")
            }
        }
    }
}

@Composable
private fun SettingsSection(
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
            style = MaterialTheme.typography.titleSmall,
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
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
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
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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

@Composable
private fun WeatherSourceSelector(selected: WeatherSource, onSelected: (WeatherSource) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("資料來源", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WeatherSource.entries.forEachIndexed { index, source ->
                SegmentedButton(
                    selected = selected == source,
                    onClick = { onSelected(source) },
                    shape = SegmentedButtonDefaults.itemShape(index, WeatherSource.entries.size),
                ) { Text(if (source == WeatherSource.OPEN_METEO) "Open-Meteo" else "中央氣象署") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
    supportingText: String? = null,
    value: String? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    iconBadge: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (supportingText == null) 64.dp else 76.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box {
                OutlinedRoundedSymbol(
                    icon,
                    size = 24.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
                if (iconBadge) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                supportingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            value?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (loading) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
            } else {
                OutlinedRoundedSymbol(
                    "keyboard_arrow_right",
                    size = 24.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .semantics { stateDescription = if (checked) "已開啟" else "已關閉" }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedRoundedSymbol(
            icon,
            size = 24.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = null,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun ProfileCwaApiKeyDialog(
    saving: Boolean,
    configured: Boolean,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("中央氣象署授權碼") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (configured) "授權碼已安全儲存在此裝置。輸入新授權碼即可更換。"
                    else "輸入氣象資料開放平臺授權碼，驗證成功後即可使用。",
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { if (it.length <= 256) key = it },
                    label = { Text("授權碼") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (configured) {
                    TextButton(
                        onClick = onRemove,
                        enabled = !saving,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("點此移除授權碼") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key) }, enabled = !saving && key.isNotBlank()) {
                Text(if (saving) "驗證中…" else "驗證並儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}

private fun openProfileUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: Exception) {
        Toast.makeText(context, "無法開啟連結", Toast.LENGTH_SHORT).show()
    }
}
