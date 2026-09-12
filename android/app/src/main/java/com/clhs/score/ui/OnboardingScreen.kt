package com.clhs.score.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clhs.score.R
import com.clhs.score.data.AppSettings
import com.clhs.score.data.ThemeMode
import com.clhs.score.notifications.areNotificationsEnabled
import com.clhs.score.notifications.canPostNotifications
import com.clhs.score.notifications.hasPostNotificationsPermission
import com.clhs.score.notifications.openAppNotificationSettings
import com.clhs.score.notifications.shouldShowPostNotificationsRationale
import com.clhs.score.ui.theme.OutfitFontFamily

private const val ONBOARDING_STEP_COUNT = 4

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingWelcomeScreen(onContinue: () -> Unit) {
    OnboardingScaffold(step = 0, actions = { PrimaryOnboardingButton("開始設定", onContinue) }) {
        Spacer(modifier = Modifier.height(28.dp))
        Surface(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(96.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Image(
                painter = painterResource(R.drawable.clhs_pocket_foreground),
                contentDescription = "壢中 Pocket 圖示",
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.45f),
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "壢中 Pocket",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "校園生活需要的資訊，都在這裡。",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "課表 · 成績 · 公告 · 行事曆 · 天氣",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingAppearanceScreen(
    settings: AppSettings,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
    onSetAmoledBlack: (Boolean) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(step = 1, onBack = onBack, actions = { PrimaryOnboardingButton("繼續", onContinue) }) {
        OnboardingTitle(
            title = "選擇喜歡的外觀",
            subtitle = "之後也可以隨時在設定中更改。",
        )
        Spacer(modifier = Modifier.height(28.dp))
        ThemeSelector(
            selected = settings.themeMode,
            onSelected = onSetThemeMode,
            systemLabel = "跟隨系統",
        )
        Text(
            "更多外觀選項",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsSwitchRow(
                        icon = "palette",
                        title = "動態色彩",
                        supportingText = "使用系統桌布的配色",
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingPermissionsScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationPermissionDenied by rememberSaveable { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(context.canPostNotifications()) }
    val currentOnSetNotificationsEnabled by rememberUpdatedState(onSetNotificationsEnabled)

    fun refreshNotificationState() {
        val enabled = context.canPostNotifications()
        notificationsAllowed = enabled
        if (enabled) currentOnSetNotificationsEnabled(true)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionDenied = !granted
        refreshNotificationState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshNotificationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestNotifications() {
        if (context.canPostNotifications()) {
            refreshNotificationState()
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPostNotificationsPermission() &&
            (!notificationPermissionDenied || context.shouldShowPostNotificationsRationale())
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (!context.openAppNotificationSettings()) {
                Toast.makeText(
                    context,
                    "無法開啟通知設定，請手動到系統設定開啟",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val needsNotificationSettings = !notificationsAllowed && if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.hasPostNotificationsPermission()
    ) {
        notificationPermissionDenied && !context.shouldShowPostNotificationsRationale()
    } else {
        !context.areNotificationsEnabled()
    }

    OnboardingScaffold(
        step = 2,
        onBack = onBack,
        actions = { PrimaryOnboardingButton(if (notificationsAllowed) "繼續" else "先略過", onContinue) },
    ) {
        OnboardingTitle(
            title = "不錯過重要消息",
            subtitle = "接收 App 更新與推播消息。你可以選擇開啟通知，也可以稍後再設定。",
        )
        Spacer(modifier = Modifier.height(28.dp))
        PermissionItem(
            enabled = notificationsAllowed,
            actionLabel = if (needsNotificationSettings) "前往設定" else "開啟通知",
            onAction = ::requestNotifications,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingAccountScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onSkip: () -> Unit,
) {
    OnboardingScaffold(
        step = 3,
        onBack = onBack,
        actions = {
            PrimaryOnboardingButton("使用學校帳號登入", onLogin)
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shapes = ButtonDefaults.shapes(),
            ) { Text("先逛逛") }
        },
    ) {
        OnboardingTitle(
            title = "連結學校帳號",
            subtitle = "登入後查看個人成績與課表。\n不登入也能使用公告、行事曆與天氣。",
        )
        Spacer(modifier = Modifier.height(28.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        OutlinedRoundedSymbol("lock", contentDescription = null)
                    }
                }
                Text(
                    text = "帳號密碼直接提交至學校校務系統，壢中 Pocket 不保存你的密碼。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnboardingTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OnboardingScaffold(
    step: Int,
    onBack: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            OutlinedRoundedSymbol("arrow_back", contentDescription = "返回")
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        OnboardingProgress(step)
                    }
                    Spacer(modifier = Modifier.size(48.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = 16.dp),
                    content = content,
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    actions()
                    if (step != ONBOARDING_STEP_COUNT - 1) Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun OnboardingProgress(step: Int) {
    val labels = listOf("歡迎", "外觀", "通知", "帳號")
    Row(
        modifier = Modifier.semantics {
            contentDescription = "首次設定進度"
            stateDescription = "第 ${step + 1} 步，共 $ONBOARDING_STEP_COUNT 步：${labels[step]}"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${step + 1} / $ONBOARDING_STEP_COUNT", style = MaterialTheme.typography.labelMedium)
        repeat(ONBOARDING_STEP_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == step) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        if (index == step) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        CircleShape,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryOnboardingButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        shapes = ButtonDefaults.shapesFor(64.dp),
        contentPadding = ButtonDefaults.contentPaddingFor(64.dp),
    ) {
        Text(
            text = text,
            style = ButtonDefaults.textStyleFor(64.dp),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun PermissionItem(
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (enabled) "已開啟" else "未開啟" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    OutlinedRoundedSymbol("notifications", contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("通知", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "學校公告追蹤與段考更新提醒，可在進入 App 後另行設定。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (enabled) "已開啟" else "未開啟",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (enabled) {
                            OutlinedRoundedSymbol(
                                icon = "check",
                                size = 18.dp,
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "已開啟",
                            )
                        }
                    }
                    if (!enabled) {
                        TextButton(onClick = onAction, shapes = ButtonDefaults.shapes()) {
                            Text(actionLabel)
                        }
                    }
                }
            }
        }
    }
}
