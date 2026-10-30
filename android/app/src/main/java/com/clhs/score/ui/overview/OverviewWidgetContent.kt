package com.clhs.score.ui.overview

import com.clhs.score.ui.theme.pocketMeshBackground

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.data.YouBikeState
import com.clhs.score.domain.overview.OverviewContext
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWeatherState
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.SCHOOL_ZONE
import com.clhs.score.domain.overview.WeatherForecastPeriod
import com.clhs.score.domain.overview.countdownItem
import com.clhs.score.domain.overview.isPrivate
import com.clhs.score.domain.overview.resourceKey
import com.clhs.score.domain.overview.importantAnnouncements
import com.clhs.score.domain.overview.recentEvents
import com.clhs.score.viewmodel.AnnouncementBookmarksUiState
import com.clhs.score.ui.AuthGate
import com.clhs.score.ui.AuthRestoringPlaceholder
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.navigation.AuthRequirement
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.ui.theme.PocketVisual
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Instances reuse the original Overview components; only repeatable types resize. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverviewWidgetContent(
    widget: OverviewWidgetInstance,
    state: OverviewState,
    isLoggedIn: Boolean,
    editing: Boolean,
    onLogin: () -> Unit,
    onOpenDestination: (OverviewDestination) -> Unit,
    onWeatherSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    unreadAnnouncementIds: Set<String> = emptySet(),
    isReadingAvailable: Boolean = true,
    authState: AuthState? = null,
    onRetryYouBike: () -> Unit = onRetry,
    onRetryWeather: () -> Unit = onRetry,
    bookmarksUiState: AnnouncementBookmarksUiState = AnnouncementBookmarksUiState(isLoading = false),
    onRetryBookmarks: () -> Unit = {},
) {
    val config = remember(widget.configuration, widget.configVersion, widget.type) { widget.config() }
    val visibleItems = remember(state.items, isLoggedIn) {
        state.items.filter { isLoggedIn || !it.kind.isPrivate() }
    }
    val open: (OverviewDestination) -> Unit = { if (!editing) onOpenDestination(it) }
    val login = { if (!editing) onLogin() }
    if (config == null) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(OverviewWidgetDefinitions[widget.type]?.title ?: "未支援的元件", style = MaterialTheme.typography.titleMedium)
            Text("此元件的設定暫時無法讀取，可在編輯模式移除。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    when (widget.type) {
        "schedule" -> Box(modifier.fillMaxWidth()) {
            val currentAuth = authState ?: if (isLoggedIn) AuthState.Authenticated(0L) else AuthState.Guest
            AuthGate(currentAuth, AuthRequirement.Session, login,
                restoring = { AuthRestoringPlaceholder() },
                fallback = { OverviewLoginPrompt(currentAuth, login) },
            ) {
                if (state.isScheduleRefreshing && !state.hasSchedule) OverviewHeroSkeleton()
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isScheduleRefreshing) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Text("課表更新中…", style = MaterialTheme.typography.bodySmall)
                    }
                    else if (state.scheduleFailed) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.hasSchedule) "更新失敗，顯示上次課表" else "課表載入失敗",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        IconButton(onClick = hapticClick { if (!editing) onRetry() }, enabled = !editing,
                            shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                            FilledRoundedSymbol("refresh", size = 28.dp, contentDescription = "課表更新失敗，點擊重試")
                        }
                    }
                    HeroCard(state.hero, state.now, state.hasSchedule,
                        needsSetup = visibleItems.any { it.kind == OverviewItemKind.ScheduleSetup },
                        nextClass = visibleItems.firstOrNull {
                            state.context == OverviewContext.InClass && it.kind == OverviewItemKind.UpcomingClass
                        },
                    ) { open(OverviewDestination.Schedule) }
                }
            }
        }
        "weather" -> {
            val weatherConfig = config as OverviewWidgetConfig.Weather
            val weather = state.weatherLocations[weatherConfig.resourceKey()] ?: OverviewWeatherState.Loading
            val onClick = {
                if (!editing) {
                    if (weather is OverviewWeatherState.Unavailable && weather.needsCwaApiKey) onWeatherSettings()
                    else onRetryWeather()
                }
            }
            Surface(onClick = hapticClick(onClick), enabled = !editing, modifier = modifier.fillMaxSize(), shape = PocketVisual.metricShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.fillMaxSize().pocketMeshBackground()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (widget.size.rows == 1) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (weatherConfig.location.countryCode != "TW") {
                            Text("請重新選擇台灣地點", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            if (weather == OverviewWeatherState.Loading) WeatherLoadingSkeleton(
                                compact = widget.size.columns == 2 && widget.size.rows == 1,
                                expanded = widget.size.rows > 1,
                                showForecast = widget.size.columns == 4 && widget.size.rows == 2,
                                modifier = Modifier.fillMaxSize(),
                            ) else WeatherStrip(weather, state.now, compact = widget.size.columns == 2 && widget.size.rows == 1,
                                expanded = widget.size.rows > 1, editing = editing, wide = widget.size.columns == 4,
                                refreshing = weatherConfig.resourceKey() in state.manuallyRefreshingWeatherKeys,
                                modifier = if (widget.size.columns == 4 && widget.size.rows == 2) Modifier.weight(1f) else Modifier.fillMaxSize())
                            val available = weather as? OverviewWeatherState.Available
                            if (widget.size.columns == 4 && widget.size.rows == 2 && available != null) {
                                val periods = available.snapshot.forecast.filter { it.endsAtExclusive.isAfter(state.now.atZone(SCHOOL_ZONE).toInstant()) }
                                if (periods.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    ForecastRow(periods, available.snapshot.timezoneId, editing)
                                }
                            }
                        }
                    }
                    if (!editing && weatherConfig.resourceKey() in state.manuallyRefreshingWeatherKeys) {
                        LoadingIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp)
                            .semantics { contentDescription = "正在更新天氣" })
                    }
                }
            }
        }
        "youbike" -> {
            val station = (config as OverviewWidgetConfig.YouBike).station
            YouBikeCard(state.youBikeStations[station.id] ?: YouBikeState(), state.now.atZone(SCHOOL_ZONE).toInstant(),
                onRefresh = { if (!editing) onRetryYouBike() }, stationName = station.name,
                compact = widget.size.columns == 2 && widget.size.rows == 1, expanded = widget.size.rows > 1,
                modifier = modifier.fillMaxSize(), editing = editing)
        }
        "countdown" -> {
            val item = widget.countdownItem(state)
            if (item != null) CountdownCards(listOf(item), open, modifier = modifier, singleInstance = true,
                showDate = widget.size.rows > 1)
            else if (config is OverviewWidgetConfig.SchoolEvent && state.calendarLoading && !state.calendarFailed)
                CountdownLoadingSkeleton(modifier, showDate = widget.size.rows > 1)
            else Box(modifier.fillMaxWidth()) {
                Text("事件暫時無法取得，可在自訂首頁重新選擇。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "calendar" -> {
            val listConfig = config as OverviewWidgetConfig.ListItems
            val limit = listConfig.limit
            val items = remember(state.items, state.calendarEvents, listConfig) {
                state.recentEvents(listConfig)
            }
            OverviewSection("近期行程", modifier.fillMaxWidth(), onViewAll = if (editing) null else { { open(OverviewDestination.Calendar) } }) {
                EventGroup(items, state.calendarEvents, state.now.toLocalDate(), state.calendarLoading, state.calendarFailed, open,
                    skeletonRows = limit.coerceAtMost(3))
            }
        }
        "announcements" -> {
            val listConfig = config as OverviewWidgetConfig.ListItems
            val limit = listConfig.limit
            val items = remember(state.announcements, listConfig, unreadAnnouncementIds, isReadingAvailable, bookmarksUiState.bookmarks) {
                state.importantAnnouncements(listConfig, unreadAnnouncementIds.takeIf { isReadingAvailable }.orEmpty(), bookmarksUiState.bookmarks)
            }
            OverviewSection("重要公告", modifier.fillMaxWidth(), onViewAll = if (editing) null else { { open(OverviewDestination.Announcements) } }) {
                if (!isReadingAvailable) Text("公告閱讀狀態暫時無法取得", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                if (bookmarksUiState.loadFailed) TextButton(onClick = hapticClick(onRetryBookmarks)) { Text("收藏讀取失敗，重試") }
                AnnouncementGroup(items, state.now.toLocalDate(), open, unreadAnnouncementIds,
                    state.announcementsLoading || bookmarksUiState.isLoading, state.announcementsFailed, skeletonRows = limit.coerceAtMost(3))
            }
        }
        "alerts" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val scheduleChanges = remember(visibleItems) { visibleItems.filter { it.kind == OverviewItemKind.ScheduleChange } }
            val grades = remember(visibleItems) { visibleItems.filter { it.kind == OverviewItemKind.GradeUpdate } }
            if (scheduleChanges.isNotEmpty()) AlertGroup(scheduleChanges, open)
            if (grades.isNotEmpty()) AlertGroup(grades, open)
            if (editing && scheduleChanges.isEmpty() && grades.isEmpty()) Text("目前沒有課程或成績異動",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ForecastRow(periods: List<WeatherForecastPeriod>, timezoneId: String, editing: Boolean) {
    val zone = remember(timezoneId) { runCatching { ZoneId.of(timezoneId) }.getOrDefault(SCHOOL_ZONE) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val periodWidth = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = !editing) {
        items(periods) { period ->
            val nativeHours = Duration.between(period.startsAt, period.endsAtExclusive).toHours()
            val label = if (nativeHours > 1) "${period.startsAt.atZone(zone).format(DateTimeFormatter.ofPattern("HH"))}–${period.endsAtExclusive.atZone(zone).format(DateTimeFormatter.ofPattern("HH"))}"
                else period.startsAt.atZone(zone).format(formatter)
            Column(Modifier.width(periodWidth).semantics(mergeDescendants = true) {
                contentDescription = listOf(label, period.temperatureCelsius?.let { "攝氏 $it 度" }, period.condition,
                    period.precipitationProbability?.let { "降雨機率 $it%" }).filterNotNull().joinToString("，")
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    period.condition?.let { OutlinedRoundedSymbol(widgetWeatherSymbol(it), contentDescription = null, size = 14.dp) }
                    Text(period.temperatureCelsius?.let { "$it°" } ?: "—", style = MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (period.precipitationProbability != null) OutlinedRoundedSymbol("water_drop", size = 14.dp,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(period.precipitationProbability?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun widgetWeatherSymbol(condition: String): String = when {
    "雷" in condition -> "thunderstorm"
    "雨" in condition -> "rainy"
    "雪" in condition -> "cloudy_snowing"
    "霧" in condition -> "foggy"
    "晴" in condition && "雲" in condition -> "partly_cloudy_day"
    "晴" in condition -> "sunny"
    else -> "cloud"
}
