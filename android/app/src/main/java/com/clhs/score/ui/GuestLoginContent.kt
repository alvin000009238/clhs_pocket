package com.clhs.score.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clhs.score.viewmodel.AuthState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GuestLoginContent(
    authState: AuthState,
    title: String,
    description: String,
    icon: String,
    onRequestLogin: () -> Unit,
) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PocketOptionIcon(icon)
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMediumEmphasized)
        }
        Text(description, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (authState is AuthState.Authenticating) {
            LoadingIndicator(Modifier.size(40.dp).align(Alignment.CenterHorizontally))
        } else {
            Button(onClick = onRequestLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("使用學校帳號登入")
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(widthDp = 390, heightDp = 260)
@androidx.compose.ui.tooling.preview.Preview(widthDp = 360, heightDp = 360, fontScale = 1.5f)
@Composable
private fun GuestLoginCardPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(GuestCardKindProvider::class) profile: Boolean,
) {
    com.clhs.score.ui.theme.ScoreTheme {
        Surface(shape = MaterialTheme.shapes.extraLarge,
            color = if (profile) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            GuestLoginContent(AuthState.Guest, if (profile) "登入學校帳號" else "今日課表",
                if (profile) "查看個人資料、缺曠與獎懲紀錄。" else "登入後查看現在與下一節課，掌握今天的課程。",
                if (profile) "account_circle" else "calendar_view_week", {})
        }
    }
}

class GuestCardKindProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@androidx.compose.ui.tooling.preview.Preview(widthDp = 390, heightDp = 844)
@Composable
private fun GuestRecordsPreview() {
    com.clhs.score.ui.theme.ScoreTheme {
        AuthGate(AuthState.Guest, com.clhs.score.ui.navigation.AuthRequirement.Session, {},
            feature = "缺曠統計", description = "查看學期缺曠統計、請假與缺席明細。", icon = "calendar_view_week", onBack = {}) {}
    }
}
