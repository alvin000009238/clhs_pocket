package com.clhs.score.ui.overview

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolCalendarFeed
import com.clhs.score.data.SchoolCalendarSnapshot
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeState
import com.clhs.score.data.YouBikeStation
import com.clhs.score.data.fakeYouBikeSnapshot
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWeatherState
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.SCHOOL_ZONE
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.domain.overview.announcementOverviewItems
import com.clhs.score.domain.overview.calendarOverviewItems
import com.clhs.score.domain.overview.resourceKey
import com.clhs.score.domain.overview.scheduleOverviewHero
import com.clhs.score.ui.theme.ScoreTheme
import java.time.LocalDateTime

@Preview(name = "首頁元件 · 數值", widthDp = 360, heightDp = 550)
@Preview(name = "首頁元件 · 數值深色", widthDp = 360, heightDp = 550, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "首頁元件 · 數值大字", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
private fun OverviewMetricCardsPreview() {
    val state = cardPreviewState()
    val rowHeight = 40.dp + 72.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    ScoreTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(OverviewSpacing.page), verticalArrangement = Arrangement.spacedBy(OverviewSpacing.cardGap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OverviewSpacing.cardGap)) {
                    PreviewWidget("weather", OverviewWidgetConfig.Weather(), OverviewWidgetSize(2, 1), state,
                        Modifier.weight(1f).height(rowHeight))
                    PreviewWidget("countdown", OverviewWidgetConfig.CustomCountdown("第一次期中考", "2026-11-13"), OverviewWidgetSize(2, 1), state,
                        Modifier.weight(1f).height(rowHeight))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(OverviewSpacing.cardGap)) {
                    PreviewWidget("weather", OverviewWidgetConfig.Weather(), OverviewWidgetSize(2, 2), state,
                        Modifier.weight(1f).height(rowHeight * 2 + OverviewSpacing.cardGap))
                    PreviewWidget("youbike", OverviewWidgetConfig.YouBike(), OverviewWidgetSize(2, 2), state,
                        Modifier.weight(1f).height(rowHeight * 2 + OverviewSpacing.cardGap))
                }
                PreviewWidget("youbike", OverviewWidgetConfig.YouBike(), OverviewWidgetSize(4, 1), state,
                    Modifier.fillMaxWidth().height(rowHeight))
            }
        }
    }
}

@Preview(name = "首頁元件 · 課程與列表", widthDp = 360, heightDp = 1200)
@Preview(name = "首頁元件 · 列表大字", widthDp = 360, heightDp = 1800, fontScale = 2f)
@Composable
private fun OverviewCourseAndListsPreview() {
    val state = cardPreviewState()
    ScoreTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(OverviewSpacing.page), verticalArrangement = Arrangement.spacedBy(OverviewSpacing.section)) {
                PreviewWidget("schedule", OverviewWidgetConfig.None, OverviewWidgetSize(4, 1), state)
                PreviewWidget("calendar", OverviewWidgetConfig.ListItems(limit = 2), OverviewWidgetSize(4, 1), state)
                PreviewWidget("announcements", OverviewWidgetConfig.ListItems(limit = 2), OverviewWidgetSize(4, 1), state)
            }
        }
    }
}

@Composable
private fun PreviewWidget(type: String, config: OverviewWidgetConfig, size: OverviewWidgetSize, state: OverviewState,
    modifier: Modifier = Modifier) {
    Box(modifier) {
        OverviewWidgetContent(OverviewWidgetInstance("preview-$type", type, size).withConfig(config), state,
            isLoggedIn = true, editing = false, onLogin = {}, onOpenDestination = {}, onWeatherSettings = {}, onRetry = {},
            unreadAnnouncementIds = state.announcements.map { it.id }.toSet())
    }
}

private fun cardPreviewState(): OverviewState {
    val now = LocalDateTime.of(2026, 11, 9, 9, 22)
    val instant = now.atZone(SCHOOL_ZONE).toInstant()
    val report = FakeScheduleData.report("115_1", "230", scope = com.clhs.score.data.ScheduleScope.CURRENT_WEEK, today = now.toLocalDate())
    val events = listOf(
        SchoolCalendarEvent("reading", "中學生網站閱讀心得寫作比賽", now.minusDays(7), now.plusDays(8), true),
        SchoolCalendarEvent("books", "好書換換愛：校園圖書交換活動", now.plusDays(2), now.plusDays(3), true),
    )
    val weather = OverviewWeatherState.Available(WeatherSnapshot(WeatherSource.OPEN_METEO, "中壢區", 24, 26,
        "晴時多雲", 20, now, now.plusHours(1), instant, maximumTemperatureCelsius = 28, minimumTemperatureCelsius = 21))
    return OverviewState(now = now, isInitialLoading = false, hasSchedule = true,
        hero = scheduleOverviewHero(report, now), calendarEvents = events,
        items = calendarOverviewItems(SchoolCalendarSnapshot(SchoolCalendarFeed(events), instant), now) +
            announcementOverviewItems(FakeData.announcementPage),
        announcements = FakeData.announcementPage.announcements,
        weatherLocations = mapOf(OverviewWidgetConfig.Weather().resourceKey() to weather),
        youBikeStations = mapOf(YouBikeStation.SCHOOL.id to YouBikeState(fakeYouBikeSnapshot(instant))))
}

@Preview(name = "異動顯示分工", widthDp = 390, heightDp = 1100)
@Preview(name = "異動顯示分工深色", widthDp = 390, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OverviewChangeOwnershipPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(ChangeOwnershipPreviewProvider::class) separateAlerts: Boolean,
) {
    val now = LocalDateTime.of(2026, 11, 11, 14, 51)
    val report = FakeScheduleData.report("115_1", "230", scope = com.clhs.score.data.ScheduleScope.CURRENT_WEEK, today = now.toLocalDate())
    val widgets = listOf(OverviewWidgetInstance.create("schedule")) +
        if (separateAlerts) listOf(OverviewWidgetInstance.create("alerts")) else emptyList()
    val state = cardPreviewState().copy(now = now, widgets = widgets,
        hero = scheduleOverviewHero(report, now),
        items = com.clhs.score.domain.overview.scheduleOverviewItems(report, now) +
            com.clhs.score.domain.overview.OverviewItem("preview-grade", com.clhs.score.domain.overview.OverviewPriority.P1,
                com.clhs.score.domain.overview.OverviewItemKind.GradeUpdate, "第一次段考成績更新", "有新的成績可查看", "成績更新", "notifications",
                com.clhs.score.domain.overview.OverviewDestination.GradeExam("115_1", "1")))
    ScoreTheme {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(OverviewSpacing.page),
                verticalArrangement = Arrangement.spacedBy(OverviewSpacing.cardGap)) {
                PreviewWidget("schedule", OverviewWidgetConfig.None, OverviewWidgetSize(4, 1), state)
                PreviewWidget("weather", OverviewWidgetConfig.Weather(), OverviewWidgetSize(4, 1), state,
                    Modifier.fillMaxWidth().height(160.dp))
                if (separateAlerts) state.items.filter { it.kind.isOverviewAlert() }.forEach { item ->
                    OverviewAlertCard(item) {}
                }
            }
        }
    }
}

class ChangeOwnershipPreviewProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}
