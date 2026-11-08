package com.clhs.score.ui

import com.clhs.score.ui.navigation.navigationContentPadding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.clhs.score.ui.navigation.AuthRequirement
import com.clhs.score.viewmodel.AuthState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun AuthGate(
    authState: AuthState,
    requirement: AuthRequirement,
    onRequestLogin: () -> Unit,
    modifier: Modifier = Modifier,
    feature: String = "\u500b\u4eba\u8cc7\u8a0a",
    description: String = "\u767b\u5165\u5f8c\u67e5\u770b\u500b\u4eba\u6821\u52d9\u8cc7\u6599\u3002",
    icon: String = "account_circle",
    onBack: (() -> Unit)? = null,
    fallback: @Composable () -> Unit = {
        AuthRequiredState(
            authState = authState,
            onRequestLogin = onRequestLogin,
            modifier = modifier,
            feature = feature, description = description, icon = icon, onBack = onBack,
        )
    },
    restoring: @Composable () -> Unit = { AuthRestoringPlaceholder(modifier) },
    content: @Composable () -> Unit,
) {
    if (requirement == AuthRequirement.Public || authState is AuthState.Authenticated) {
        content()
    } else if (authState is AuthState.Restoring) {
        restoring()
    } else {
        fallback()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthRequiredState(
    authState: AuthState,
    onRequestLogin: () -> Unit,
    modifier: Modifier,
    feature: String,
    description: String,
    icon: String,
    onBack: (() -> Unit)?,
) {
    if (onBack != null) {
        FloatingSubpageLayout(onBack = onBack) { padding ->
            AuthRequiredContent(authState, onRequestLogin, modifier.padding(padding), feature, description, icon)
        }
    } else {
        AuthRequiredContent(authState, onRequestLogin, modifier, feature, description, icon)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AuthRequiredContent(
    authState: AuthState,
    onRequestLogin: () -> Unit,
    modifier: Modifier,
    feature: String,
    description: String,
    icon: String,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .navigationContentPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    OutlinedRoundedSymbol(icon, size = 28.dp, contentDescription = null)
                }
            }
            Text(
                "\u767b\u5165\u67e5\u770b$feature",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                if (authState is AuthState.Authenticating) {
                    "正在確認登入狀態…"
                } else {
                    description
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (authState is AuthState.Authenticating) {
                LoadingIndicator(modifier = Modifier.size(56.dp))
            } else {
                Button(
                    onClick = onRequestLogin,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("使用學校帳號登入", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
@Composable
fun AuthRestoringPlaceholder(modifier: Modifier = Modifier) {
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300.milliseconds)
        showLoading = true
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.height(160.dp).padding(20.dp), contentAlignment = Alignment.Center) {
            if (showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}
