package com.clhs.score.ui.schedule

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

import com.clhs.score.data.ScheduleChange
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleDisplayMode
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.ScheduleUiState
import java.time.LocalDateTime

@Preview(name = "自訂科目 · 浮動操作", widthDp = 360, heightDp = 800)
@Preview(name = "自訂科目 · 深色", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleCustomizationsPreview() {
    com.clhs.score.ui.theme.ScoreTheme {
        ScheduleCustomizationsScreen(
            state = com.clhs.score.viewmodel.ScheduleCustomizationsUiState(
                isLoading = false,
                overrides = listOf("國語文", "英語文", "數學", "物理", "化學", "地理", "歷史", "公民").map {
                    com.clhs.score.data.ScheduleSubjectOverride(it, customSubjectName = "$it 自訂")
                },
            ),
            onBack = {}, onRetry = {}, onSaveOverride = { _, _ -> },
            onRemoveOverride = { _, _ -> }, onReplaceOverrides = { _, _ -> }, onNoticeShown = {},
        )
    }
}
@Preview(name = "日期與週次對齊", widthDp = 360, heightDp = 800)
@Composable
private fun ScheduleWeekHeaderAlignmentPreview() = ScheduleWeekDensityPreview()

@Preview(name = "課程詳情 · 自訂名稱", widthDp = 390, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ScheduleDetailContentPreview() {
    ScoreTheme {
        androidx.compose.material3.Surface {
            ScheduleSubjectOverrideEditor(
                originalSubjectName = "英語文",
                originalItems = listOf(ScheduleItem(1, 3, "英語文", "王五", "高二30")),
                existingOverride = com.clhs.score.data.ScheduleSubjectOverride("英語文", customSubjectName = "英文"),
                isEditing = false,
                isSaving = false,
                onCancel = {},
                onSave = {},
            )
        }
    }
}

private fun previewSchedule() = ScheduleReport(
    yearTermValue = "115_1", classNo = "230", scope = ScheduleScope.CURRENT_WEEK,
    weekStartDate = "2026-11-02", weekEndDate = "2026-11-08",
    items = listOf(
        ScheduleItem(1, 1, "專題實作與跨領域探索", "王老師", "專題教室"),
        ScheduleItem(1, 2, "專題實作與跨領域探索", "王老師", "專題教室"),
        ScheduleItem(1, 3, "國語文", "李老師", "230"),
        ScheduleItem(1, 5, "數學", "陳老師", "230"),
        ScheduleItem(2, 1, "英文閱讀與寫作", "林老師", "語言教室"),
        ScheduleItem(3, 3, "物理"), ScheduleItem(4, 4, "體育", classroom = "操場"),
        ScheduleItem(5, 6, "資訊科技與人工智慧"),
        ScheduleItem(4, 5, "體育", classroom = "操場"),
        ScheduleItem(5, 8, "生涯規劃"),
    ),
    changes = listOf(
        ScheduleChange(ScheduleChangeType.REMOVED, 1, 4, semesterItem = ScheduleItem(1, 4, "公民與社會", "張老師", "230")),
        ScheduleChange(ScheduleChangeType.ADDED, 1, 5, weekItem = ScheduleItem(1, 5, "數學", "陳老師", "230")),
    ),
)

@Preview(name = "課表整頁 · Compact", widthDp = 360, heightDp = 900)
@Preview(name = "課表整頁 · 2x", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Composable
private fun SchedulePagePreview() {
    ScoreTheme {
        PreviewSchedulePage(
            uiState = ScheduleUiState(isInitialLoading = false, report = previewSchedule(), selectedScope = ScheduleScope.CURRENT_WEEK),
            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
        )
    }
}

@Preview(name = "課表單日 · Light", widthDp = 360, heightDp = 900)
@Preview(name = "課表單日 · Dark", widthDp = 390, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "課表單日 · 2x", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Composable
private fun ScheduleDayPreview() {
    ScoreTheme {
        val report = previewSchedule()
        ScheduleGrid(items = report.items, changes = report.changes.orEmpty(), report = report,
            displayMode = ScheduleDisplayMode.DAY, now = LocalDateTime.of(2026, 11, 2, 9, 20))
    }
}

@Preview(name = "課表整週 · Dark", widthDp = 390, heightDp = 950, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleWeekPreview() {
    ScoreTheme {
        val report = previewSchedule()
        ScheduleGrid(items = report.items, changes = report.changes.orEmpty(), report = report,
            currentDayOfWeek = 1, now = LocalDateTime.of(2026, 11, 2, 9, 20))
    }
}

@Preview(name = "課表日頁 · Narrow 1:2", widthDp = 320, heightDp = 640)
@Preview(name = "課表日頁 · Short 9:16", widthDp = 360, heightDp = 640)
@Preview(name = "課表日頁 · Tall", widthDp = 412, heightDp = 915)
@Preview(name = "課表日頁 · Landscape", widthDp = 740, heightDp = 360)
@Composable
private fun ScheduleAdaptiveDayPreview() = ScheduleAdaptivePage(ScheduleDisplayMode.DAY)

@Preview(name = "課表週頁 · Narrow 1:2", widthDp = 320, heightDp = 640)
@Preview(name = "課表週頁 · Short 9:16", widthDp = 360, heightDp = 640)
@Preview(name = "課表週頁 · Tall", widthDp = 412, heightDp = 915)
@Preview(name = "課表週頁 · Landscape", widthDp = 740, heightDp = 360)
@Composable
private fun ScheduleAdaptiveWeekPreview() = ScheduleAdaptivePage(ScheduleDisplayMode.WEEK)

@Composable
private fun ScheduleAdaptivePage(mode: ScheduleDisplayMode) {
    ScoreTheme {
        PreviewSchedulePage(
            uiState = ScheduleUiState(isInitialLoading = false, report = com.clhs.score.data.FakeScheduleData.report(
                "115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 9)),
                selectedScope = ScheduleScope.CURRENT_WEEK, displayMode = mode),
            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
        )
    }
}

@Preview(name = "日課程狀態 · Compact", widthDp = 360, heightDp = 1100)
@Preview(name = "日課程狀態 · Narrow 2x", widthDp = 320, heightDp = 1400, fontScale = 2f)
@Composable
private fun ScheduleDayStatusPreview() {
    ScoreTheme {
        val report = com.clhs.score.data.FakeScheduleData.report(
            "115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 10))
        ScheduleGrid(
            items = report.items, changes = report.changes.orEmpty(), report = report,
            subjectOverrides = listOf(com.clhs.score.data.ScheduleSubjectOverride("數學", customSubjectName = "數學探究與實作")),
            displayMode = ScheduleDisplayMode.DAY, now = LocalDateTime.of(2026, 11, 10, 10, 30),
        )
    }
}

@Preview(name = "Phone day · Narrow large text", widthDp = 280, heightDp = 430, fontScale = 1.6f)
@Preview(name = "Phone day · 16:9", widthDp = 360, heightDp = 640)
@Preview(name = "Phone day · Tall", widthDp = 412, heightDp = 915)
@Preview(name = "Phone day · Landscape", widthDp = 740, heightDp = 360)
@Preview(name = "Phone day · Landscape large text", widthDp = 740, heightDp = 360, fontScale = 1.6f)
@Composable
private fun SchedulePhoneDayPreview() = ScheduleAdaptivePage(ScheduleDisplayMode.DAY)

@Preview(name = "Phone week · Narrow large text", widthDp = 280, heightDp = 430, fontScale = 1.6f)
@Preview(name = "Phone week · 16:9", widthDp = 360, heightDp = 640)
@Preview(name = "Phone week · Tall", widthDp = 412, heightDp = 915)
@Preview(name = "Phone week · Landscape", widthDp = 740, heightDp = 360)
@Composable
private fun SchedulePhoneWeekPreview() = ScheduleAdaptivePage(ScheduleDisplayMode.WEEK)

@Preview(name = "八節整週 · 手機", widthDp = 360, heightDp = 750, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "八節整週 · 放大字體", widthDp = 360, heightDp = 750, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "八節整週 · 短手機", widthDp = 360, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "八節整週 · 412", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "八節整週 · 高手機上限", widthDp = 412, heightDp = 1100)
@Preview(name = "八節整週 · 2x", widthDp = 360, heightDp = 800, fontScale = 2f)
@Preview(name = "八節整週 · 橫向", widthDp = 740, heightDp = 360)
@Composable
private fun ScheduleWeekDensityPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report(
        "115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 9),
    )
    ScoreTheme {
        androidx.compose.runtime.CompositionLocalProvider(
            com.clhs.score.ui.navigation.LocalNavigationClearance provides androidx.compose.ui.unit.Dp(96f),
        ) {
            PreviewSchedulePage(
                uiState = ScheduleUiState(isInitialLoading = false, report = report, selectedScope = ScheduleScope.CURRENT_WEEK),
                onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
                onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
            )
        }
    }
}

@Composable
private fun PreviewSchedulePage(
    uiState: ScheduleUiState,
    onRefresh: () -> Unit,
    onYearSelected: (String) -> Unit,
    onClassSelected: (String) -> Unit,
    onScopeSelected: (ScheduleScope) -> Unit,
    onConfirmSelection: () -> Unit,
    onClearSelection: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.clhs.score.ui.navigation.LocalNavigationClearance provides androidx.compose.ui.unit.Dp(96f),
    ) {
        ScheduleScreen(uiState = uiState, onRefresh = onRefresh, onYearSelected = onYearSelected,
            onClassSelected = onClassSelected, onScopeSelected = onScopeSelected,
            onConfirmSelection = onConfirmSelection, onClearSelection = onClearSelection, onNoticeShown = onNoticeShown)
    }
}

@Preview(name = "學期週板 · 360", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "學期週板 · 412", widthDp = 412, heightDp = 915)
@Composable
private fun ScheduleSemesterBoardPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report("115_1", "230", ScheduleScope.SEMESTER)
    ScoreTheme {
        PreviewSchedulePage(
            uiState = ScheduleUiState(isInitialLoading = false, report = report, selectedScope = ScheduleScope.SEMESTER),
            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
        )
    }
}

@Preview(name = "學期日視圖 · 星期置中", widthDp = 360, heightDp = 800)
@Preview(name = "學期日視圖 · 橫向", widthDp = 740, heightDp = 360)
@Composable
private fun ScheduleSemesterDayPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report("115_1", "230", ScheduleScope.SEMESTER)
    ScoreTheme {
        ScheduleGrid(items = report.items, report = report, displayMode = ScheduleDisplayMode.DAY)
    }
}

@Preview(name = "週板狀態 · 異動上課中", widthDp = 360, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleWeekStatusPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report(
        "115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 10))
    ScoreTheme {
        ScheduleGrid(items = report.items, changes = report.changes.orEmpty(), report = report,
            currentDayOfWeek = 2, now = LocalDateTime.of(2026, 11, 10, 10, 30))
    }
}

@Preview(name = "異動比較 · 精簡", widthDp = 360, heightDp = 300, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleChangeDetailPreview() {
    ScoreTheme {
        androidx.compose.material3.Surface {
            ScheduleChangeDetailContent(ScheduleChange(
                ScheduleChangeType.ADDED, 3, 8,
                weekItem = ScheduleItem(3, 8, "生涯規劃", "代理教師", "專題教室"),
            ))
        }
    }
}

@Preview(name = "課表更新中 · 單一載入提示", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleRefreshingPreview() {
    ScoreTheme {
        ScheduleScreen(
            uiState = ScheduleUiState(isInitialLoading = false, isLoading = true,
                report = previewSchedule(), selectedScope = ScheduleScope.CURRENT_WEEK),
            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
        )
    }
}

@Preview(name = "學校週次選擇", widthDp = 360, heightDp = 750, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleWeekPickerPreview() {
    ScoreTheme {
        ScheduleScreen(
            uiState = ScheduleUiState(isInitialLoading = false, report = previewSchedule(),
                isWeekPickerOpen = true, weekOptions = listOf(
                    com.clhs.score.data.ScheduleWeekOption("第 16 週", "16", "2026-11-09", "2026-11-15"),
                    com.clhs.score.data.ScheduleWeekOption("第 17 週", "17", "2026-11-16", "2026-11-22"))),
            onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
            onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
        )
    }
}

@Preview(name = "右下角上課中", widthDp = 360, heightDp = 800)
@Composable
private fun ScheduleCornerBorderPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 9))
    ScoreTheme {
        ScheduleGrid(items = report.items, changes = report.changes.orEmpty(), report = report,
            now = LocalDateTime.of(2026, 11, 13, 16, 30))
    }
}
@Preview(name = "週五自動切下週一", widthDp = 360, heightDp = 800)
@Composable
private fun ScheduleAutomaticMondayPreview() {
    val report = com.clhs.score.data.FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, java.time.LocalDate.of(2026, 11, 16))
    ScoreTheme {
        ScheduleGrid(items = report.items, report = report, displayMode = ScheduleDisplayMode.DAY,
            now = LocalDateTime.of(2026, 11, 13, 17, 0))
    }
}