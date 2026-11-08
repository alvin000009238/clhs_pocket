@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding

import com.clhs.score.ui.theme.PocketVisual
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clhs.score.data.AppSettings
import com.clhs.score.data.BiometricHelper
import com.clhs.score.ui.components.PinSetupDialog
import com.clhs.score.viewmodel.GradesUiState
import com.clhs.score.viewmodel.AuthState

private val ProfileMaxWidth = 720.dp

@Composable
fun PersonalScreen(
    state: GradesUiState,
    authState: AuthState = AuthState.Authenticated(0L),
    onRequestLogin: () -> Unit = {},
    settings: AppSettings,
    onBack: () -> Unit,
    onCycleProfileAvatarShape: () -> Unit,
    onSetBiometricEnabled: (Boolean, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAttendance: () -> Unit,
    onOpenMerits: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showPinSetupDialog by remember { mutableStateOf(false) }
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
    FloatingSubpageLayout(
        actionsPadding = PaddingValues(0.dp),
        onBack = onBack,
        actions = {
            PocketCircleIconButton("settings", "設定", hapticClick(onOpenSettings))
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = navigationContentPadding(PaddingValues(start = 16.dp, top = scaffoldPadding.calculateTopPadding() + 8.dp, end = 16.dp, bottom = scaffoldPadding.calculateBottomPadding() + 40.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                ProfileHero(state, authState, onRequestLogin, settings.profileAvatarShapeIndex, onCycleProfileAvatarShape)
            }
            item {
                SettingsSection("在校紀錄") {
                    SettingsGroup {
                        ProfileRecordDestination("event", "缺曠統計", "學期分類、日期與節次", onOpenAttendance)
                        ProfileRecordDestination("school", "獎懲紀錄", "獎勵、懲處與核定資訊", onOpenMerits)
                    }
                }
            }
            if (BiometricHelper.canAuthenticate(context)) {
                item {
                    SettingsSection("帳號安全") {
                        SettingsGroup {
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
                    }
                }
            }
            if (authState is AuthState.Authenticated) {
                item {
                    TextButton(
                        onClick = hapticClick { showLogoutDialog = true },
                        modifier = Modifier.widthIn(max = ProfileMaxWidth).fillMaxWidth().heightIn(min = 56.dp),
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedRoundedSymbol("meeting_room", contentDescription = null)
                            Text("登出帳號", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "個人 · Compact", widthDp = 390, heightDp = 900)
@Preview(name = "個人 · Dark", widthDp = 390, heightDp = 900, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "個人 · Expanded", widthDp = 1100, heightDp = 1000)
@Composable
private fun PersonalScreenPreview() {
    com.clhs.score.ui.theme.ScoreTheme {
        PersonalScreen(
            state = GradesUiState(studentNo = com.clhs.score.data.FakeData.session.studentNo),
            settings = AppSettings(),
            onBack = {},
            onCycleProfileAvatarShape = {},
            onSetBiometricEnabled = { _, _ -> },
            onOpenSettings = {},
            onOpenAttendance = {},
            onOpenMerits = {},
            onLogout = {},
        )
    }
}

@Composable
private fun ProfileRecordDestination(icon: String, title: String, supportingText: String, onClick: () -> Unit) {
    Surface(onClick = hapticClick(onClick), modifier = Modifier.semantics { role = Role.Button }, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PocketOptionIcon(icon)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                Text(supportingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedRoundedSymbol("keyboard_arrow_right", contentDescription = null)
        }
    }
}

@Composable
private fun ProfileHero(
    state: GradesUiState,
    authState: AuthState,
    onRequestLogin: () -> Unit,
    avatarShapeIndex: Int,
    onCycleAvatarShape: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .widthIn(max = ProfileMaxWidth)
            .fillMaxWidth(),
        shape = PocketVisual.heroShape,
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
                ProfileAvatar(avatarShapeIndex, onCycleAvatarShape)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        student?.studentName?.takeIf(String::isNotBlank) ?: "學生帳號",
                        style = MaterialTheme.typography.headlineSmallEmphasized,
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
private fun ProfileAvatar(shapeIndex: Int, onCycleShape: () -> Unit) {
    val shapes = listOf(
        MaterialShapes.Cookie9Sided.toShape(),
        MaterialShapes.Clover4Leaf.toShape(),
        MaterialShapes.Sunny.toShape(),
        MaterialShapes.SoftBurst.toShape(),
    )
    val clockwise = shapeIndex % 2 == 0
    val rotation = remember { Animatable(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, clockwise) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            rotation.animateTo(
                targetValue = rotation.value + if (clockwise) 360f else -360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 20_000, easing = LinearEasing),
                ),
            )
        }
    }
    Surface(
        onClick = hapticClick(onCycleShape),
        modifier = Modifier
            .size(64.dp)
            .semantics {
                role = Role.Button
                contentDescription = "更換個人圖示形狀並反轉旋轉方向"
            }
            .graphicsLayer { rotationZ = rotation.value },
        shape = shapes[shapeIndex],
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    ) {
        Box(
            modifier = Modifier.graphicsLayer { rotationZ = -rotation.value },
            contentAlignment = Alignment.Center,
        ) {
            OutlinedRoundedSymbol("account_circle", size = 38.dp, contentDescription = null)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ProfileGuestHero(authState: AuthState, onRequestLogin: () -> Unit) {
    GuestLoginContent(authState, "\u767b\u5165\u5b78\u6821\u5e33\u865f",
        "\u67e5\u770b\u500b\u4eba\u8cc7\u6599\u3001\u7f3a\u66e0\u8207\u734e\u61f2\u7d00\u9304\u3002", "account_circle", onRequestLogin)
}

@Composable
internal fun ProfileCwaApiKeyDialog(
    saving: Boolean,
    configured: Boolean,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
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
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
