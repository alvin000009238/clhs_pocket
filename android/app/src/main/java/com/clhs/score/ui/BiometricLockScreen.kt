package com.clhs.score.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.components.PinInputDialog

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BiometricLockScreen(
    isBiometricInvalidated: Boolean,
    onTriggerBiometric: () -> Unit,
    onUnlockWithPin: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var showInvalidatedAlert by remember { mutableStateOf(isBiometricInvalidated) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isBiometricInvalidated) {
        if (isBiometricInvalidated) {
            showInvalidatedAlert = true
        }
    }

    if (showInvalidatedAlert) {
        AlertDialog(
            onDismissRequest = { /* Force action */ },
            title = { Text("安全防護提示") },
            text = { Text("系統生物特徵已變更，請輸入備用密碼重新註冊。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInvalidatedAlert = false
                        showPinDialog = true
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("確定")
                }
            }
        )
    }

    if (showPinDialog) {
        PinInputDialog(
            title = "備用密碼解鎖",
            subtitle = "請輸入您的 4–6 位數備用密碼：",
            onConfirm = { pin ->
                showPinDialog = false
                onUnlockWithPin(pin)
            },
            onDismiss = {
                showPinDialog = false
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("登出") },
            text = { Text("確定要登出嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("登出") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutDialog = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("取消") }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(112.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                OutlinedRoundedSymbol(
                    icon = "lock",
                    size = 64.dp,
                    contentDescription = "安全鎖定",
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "已鎖定",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "請進行生物識別來解鎖，或輸入備用密碼解鎖。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onTriggerBiometric,
            shapes = ButtonDefaults.shapesFor(52.dp),
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(52.dp),
            contentPadding = ButtonDefaults.contentPaddingFor(52.dp),
        ) {
            Text(
                text = "生物識別解鎖",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { showPinDialog = true },
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = "使用備用密碼解鎖",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = { showLogoutDialog = true },
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = "登出",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
