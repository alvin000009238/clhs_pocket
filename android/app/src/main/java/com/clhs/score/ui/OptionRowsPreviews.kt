package com.clhs.score.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.clhs.score.data.AppSettings
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.SettingsUiState

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
