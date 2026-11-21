package com.clhs.score.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clhs.score.data.AppSettings
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.SettingsUiState

@Preview(name = "共用選項狀態 · Light", widthDp = 390, heightDp = 900)
@Preview(name = "共用選項狀態 · Dark", widthDp = 390, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "共用選項狀態 · Large text", widthDp = 320, heightDp = 1600, fontScale = 2f)
@Preview(name = "共用選項狀態 · Wide", widthDp = 840, heightDp = 900)
@Composable
private fun SharedOptionStatesPreview() {
    ScoreTheme {
        Surface {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SettingsSection("外觀與狀態") {
                    SettingsGroup {
                        SettingsRow("system_update", "App 更新", {}, value = "有新版本 2026.11.19", iconBadge = true)
                        SettingsRow("license", "開源授權", {}, value = "MIT License")
                        SettingsRow("refresh", "檢查更新", {}, value = "正在檢查最新版本", loading = true, enabled = false)
                        SettingsRow("science", "開發者選項", {}, supportingText = "目前無法使用此功能", enabled = false)
                    }
                }
                SettingsSection("通知與背景設定") {
                    SettingsGroup {
                        SettingsSwitchRow("notifications", "通知", "接收課程與成績更新提醒", true, {})
                        SettingsSwitchRow("dark_mode", "AMOLED 純黑", "深色模式使用純黑背景", false, {}, enabled = false)
                        SettingsSwitchRow("schedule", "背景排程", "暫停期間保留原本設定，結束後自動恢復。", true, {}, enabled = false)
                    }
                }
            }
        }
    }
}

@Preview(name = "設定選項 · Light", widthDp = 390, heightDp = 1100)
@Preview(name = "設定選項 · Dark", widthDp = 390, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "設定選項 · Large text", widthDp = 360, heightDp = 1200, fontScale = 1.5f)
@Composable
private fun GeneralOptionsPreview() {
    ScoreTheme {
        GeneralSettingsScreen(
            settings = AppSettings(),
            uiState = SettingsUiState(),
            onBack = {},
            onSetThemeMode = {},
            onSetStartOnSchedule = {},
            onSetDynamicColor = {},
            onSetAmoledBlack = {},
            onSetNotificationsEnabled = {},
            onCheckUpdate = {},
            onVersionTap = {},
            onDismissDeveloperToast = {},
            onOpenUsageStatistics = {},
            onOpenSourceLicenses = {},
            onOpenDeveloperSettings = {},
            onOpenWorkManagerInfo = {},
        )
    }
}

@Preview(name = "紀錄入口", widthDp = 390, heightDp = 844)
@Composable
private fun RecordOptionsPreview() {
    ScoreTheme {
        AttendanceRecordsScreen(com.clhs.score.viewmodel.StudentRecordsState(), {}, {}, {})
    }
}

@Preview(name = "背景暫停", widthDp = 390, heightDp = 844)
@Preview(name = "背景暫停 · 大字", widthDp = 360, heightDp = 1000, fontScale = 1.5f)
@Composable
private fun BackgroundPauseOptionsPreview() {
    ScoreTheme {
        BackgroundPauseSettingsScreen(com.clhs.score.data.BackgroundPause(), {}, { true })
    }
}

@Preview(name = "紀錄下拉更新", widthDp = 390, heightDp = 844)
@Composable
private fun RecordRefreshPreview() {
    ScoreTheme {
        AttendanceRecordsScreen(com.clhs.score.viewmodel.StudentRecordsState(semestersLoading = true), {}, {}, {})
    }
}
