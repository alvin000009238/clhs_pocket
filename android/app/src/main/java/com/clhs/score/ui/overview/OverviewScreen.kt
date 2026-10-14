package com.clhs.score.ui.overview

import com.clhs.score.ui.navigation.navigationContentPadding

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.clhs.score.ui.navigation.PocketScaffold as Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.WeatherSource
import java.time.ZoneId
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.domain.overview.OverviewContext
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewHero
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWeatherState
import com.clhs.score.domain.overview.SCHOOL_ZONE
import com.clhs.score.domain.overview.isPrivate
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.RootTopAppBar
import com.clhs.score.ui.AccountIconButton
import com.clhs.score.ui.AuthRestoringPlaceholder
import com.clhs.score.ui.AuthGate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import com.clhs.score.ui.navigation.AuthRequirement
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.ui.theme.PocketVisual
import com.clhs.score.viewmodel.AuthState
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OverviewScreen(
    state: OverviewState,
    onOpenPersonal: () -> Unit,
    onOpenDestination: (OverviewDestination) -> Unit,
    showUpdateBadge: Boolean = false,
    authState: AuthState = AuthState.Authenticated(0L),
    onRequestLogin: () -> Unit = {},
    onSavePreferences: suspend (OverviewPreferences) -> Boolean = { false },
    onRefresh: () -> Unit = {},
    onRefreshYouBike: () -> Unit = {},
    unreadAnnouncementIds: Set<String> = emptySet(),
    isReadingAvailable: Boolean = true,
) {
    var customize by rememberSaveable { mutableStateOf(false) }
    if (customize) {
        OverviewCustomizationDialog(state, onSavePreferences, onRefresh) { customize = false }
    }
    Scaffold(
        topBar = {
            RootTopAppBar(
                title = "首頁",
                actions = {
                    IconButton(onClick = { customize = true }, enabled = !state.isInitialLoading) {
                        OutlinedRoundedSymbol("dashboard_customize", contentDescription = "自訂首頁")
                    }
                    AccountIconButton(onClick = onOpenPersonal, showUpdateBadge = showUpdateBadge)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                .navigationContentPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isInitialLoading) {
                        OverviewSkeleton()
                    } else {
                        OverviewContent(
                            state = state,
                            unreadAnnouncementIds = unreadAnnouncementIds,
                            isReadingAvailable = isReadingAvailable,
                            authState = authState,
                            onOpenDestination = onOpenDestination,
                            onRequestLogin = onRequestLogin,
                            onRefreshYouBike = onRefreshYouBike,
                        )
                    }
                    if (state.hasPartialFailure) {
                        Text(
                            "部分資訊暫時無法更新，已顯示可用內容",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun OverviewContent(
    state: OverviewState,
    unreadAnnouncementIds: Set<String>,
    isReadingAvailable: Boolean,
    authState: AuthState,
    onOpenDestination: (OverviewDestination) -> Unit,
    onRequestLogin: () -> Unit,
    onRefreshYouBike: () -> Unit,
) {
    val visibleItems = remember(state.items, authState) {
        state.items.filter { it.isVisibleForAuth(authState) }
    }
    val preferences = state.preferences
    val calendar = remember(visibleItems) { visibleItems.filter { it.kind == OverviewItemKind.CalendarEvent } }
    val announcements = remember(visibleItems) { visibleItems.filter { it.kind == OverviewItemKind.Announcement } }
    val scheduleChanges = remember(visibleItems) { visibleItems.filter { it.kind == OverviewItemKind.ScheduleChange } }
    val alerts = remember(visibleItems) {
        visibleItems.filter { it.kind == OverviewItemKind.GradeUpdate }
    }

    val sections = remember(preferences, scheduleChanges.isNotEmpty(), alerts.isNotEmpty()) {
        preferences.orderedSections().filter { section ->
            preferences.shows(section) && when (section) {
                "alerts" -> scheduleChanges.isNotEmpty() || alerts.isNotEmpty()
                "countdown" -> preferences.countdownEventIds.isNotEmpty()
                else -> true
            }
        }
    }
    val sectionContent: @Composable (String) -> Unit = { section ->
        when (section) {
            "schedule" -> AuthGate(
                authState = authState,
                requirement = AuthRequirement.Session,
                onRequestLogin = onRequestLogin,
                restoring = { AuthRestoringPlaceholder() },
                fallback = { OverviewLoginPrompt(authState, onRequestLogin) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isScheduleRefreshing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LoadingIndicator(Modifier.size(24.dp))
                            Text("課表更新中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HeroCard(
                        hero = state.hero,
                        now = state.now,
                        hasSchedule = state.hasSchedule,
                        needsSetup = visibleItems.any { it.kind == OverviewItemKind.ScheduleSetup },
                        nextClass = visibleItems.firstOrNull {
                            state.context == OverviewContext.InClass && it.kind == OverviewItemKind.UpcomingClass
                        },
                    ) { onOpenDestination(OverviewDestination.Schedule) }
                }
            }
            "weather" -> Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { WeatherStrip(state.weather, state.now) }
            }
            "youbike" -> YouBikeCard(
                state.youBike,
                state.now.atZone(SCHOOL_ZONE).toInstant(),
                onRefreshYouBike,
            )
            "alerts" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scheduleChanges.isNotEmpty()) {
                    AlertGroup(scheduleChanges, onOpenDestination)
                }
                if (alerts.isNotEmpty()) AlertGroup(alerts, onOpenDestination)
            }
            "countdown" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CountdownCards(state.countdowns, onOpenDestination)
                val missing = preferences.countdownEventIds.size - state.countdowns.size
                if (missing > 0) Text(
                    "$missing 個事件暫時無法取得，可在自訂首頁調整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            "calendar" -> OverviewSection(
                title = "近期行程",
                onViewAll = { onOpenDestination(OverviewDestination.Calendar) },
            ) {
                EventGroup(calendar, state.calendarEvents, state.now.toLocalDate(), state.calendarLoading, state.calendarFailed, onOpenDestination)
            }
            "announcements" -> OverviewSection(
                title = "重要公告",
                onViewAll = { onOpenDestination(OverviewDestination.Announcements) },
            ) {
                if (!isReadingAvailable) {
                    Text("公告閱讀狀態暫時無法取得", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                AnnouncementGroup(announcements, state.now.toLocalDate(), onOpenDestination, unreadAnnouncementIds,
                    state.announcementsLoading, state.announcementsFailed)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                state.now.toLocalDate().let { "${it.monthValue} 月 ${it.dayOfMonth} 日 · ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TAIWAN)}" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            var index = 0
            while (index < sections.size) {
                val section = sections[index]
                val next = sections.getOrNull(index + 1)
                if (wide && setOf(section, next) == setOf("calendar", "announcements")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top) {
                        Box(Modifier.weight(1f)) { sectionContent(section) }
                        Box(Modifier.weight(1f)) { sectionContent(requireNotNull(next)) }
                    }
                    index += 2
                } else {
                    androidx.compose.runtime.key(section) { sectionContent(section) }
                    index++
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverviewLoginPrompt(authState: AuthState, onRequestLogin: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedRoundedSymbol(
                    "calendar_view_week",
                    size = 32.dp,
                    tint = MaterialTheme.colorScheme.primary,
                    contentDescription = null,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("今日課表", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "登入後，在這裡查看現在與下一節課。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (authState is AuthState.Authenticating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("正在確認登入狀態…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Button(
                    onClick = onRequestLogin,
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("使用學校帳號登入")
                }
            }
        }
    }
}
@Composable
internal fun OverviewSection(
    title: String,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title, onViewAll, action)
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroCard(
    hero: OverviewHero?, now: LocalDateTime, hasSchedule: Boolean,
    needsSetup: Boolean, nextClass: OverviewItem?, onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = PocketVisual.heroShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                hero?.statusText(now) ?: when {
                    needsSetup -> "課表尚未設定"
                    hasSchedule -> "目前沒有待上課程"
                    else -> "暫無可用課表"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (hero == null) {
                Text(
                    when {
                        needsSetup -> "選擇學期與班級後，就能查看現在與下一節課。"
                        hasSchedule -> "目前課表範圍內沒有下一節課。"
                        else -> "前往課表重新查詢，取得最新課程資訊。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Crossfade(targetState = hero.courseName, animationSpec = PocketMotion.stateChange(), label = "課程名稱") { name ->
                    Text(name, style = MaterialTheme.typography.headlineLargeEmphasized,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(listOf(hero.courseLabel, hero.supportingText).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            hero?.courseProgress?.let { progress ->
                LinearWavyProgressIndicator(progress = { progress },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "課程進度" })
            }
            nextClass?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Text("下一節課 · ${it.title.removePrefix("下一節 ")}", style = MaterialTheme.typography.titleSmall)
                Text(listOf(it.label, it.supportingText.replace(Regex("^\\d{2}:\\d{2}[-–]\\d{2}:\\d{2}( · )?"), ""))
                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (needsSetup && hero == null) Text("設定課表", Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WeatherStrip(state: OverviewWeatherState, now: LocalDateTime, modifier: Modifier = Modifier,
    compact: Boolean = false, expanded: Boolean = false, editing: Boolean = false,
    refreshing: Boolean = false, wide: Boolean = true) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(
        if (expanded) 12.dp else if (compact) 8.dp else 4.dp,
        alignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    )) {
        when (state) {
            OverviewWeatherState.Loading -> WeatherLoadingSkeleton(compact, expanded)
            is OverviewWeatherState.Unavailable -> Text(
                if (state.needsCwaApiKey) "點擊設定 CWA 授權碼" else "天氣暫時無法取得",
                style = MaterialTheme.typography.bodyMedium)
            is OverviewWeatherState.Available -> {
                val weather = state.snapshot
                val showRange = weather.source == WeatherSource.CWA ||
                    weather.fetchedAt.atZone(ZoneId.of(weather.timezoneId)).toLocalDate() ==
                    now.atZone(SCHOOL_ZONE).withZoneSameInstant(ZoneId.of(weather.timezoneId)).toLocalDate()
                Row(Modifier.fillMaxWidth().padding(end = if (editing) 40.dp else if (refreshing) 28.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(weather.locationLabel, Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (compact && state.failed) Text("更新失敗", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (compact) {
                    WeatherCurrent(weather, compact = true)
                    WeatherRain(weather)
                } else if (wide) {
                    Row(if (expanded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) { WeatherCurrent(weather, compact = false) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (showRange) WeatherRange(weather)
                            WeatherRain(weather)
                            weather.apparentTemperatureCelsius?.let { Text("體感 $it°", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedRoundedSymbol(weatherSymbol(weather.condition), contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary, size = 40.dp)
                            Text("${weather.temperatureCelsius}°", style = MaterialTheme.typography.displayMediumEmphasized,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = "攝氏 ${weather.temperatureCelsius} 度" })
                        }
                        Text(weather.condition, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (showRange) WeatherRange(weather)
                        WeatherRain(weather)
                        weather.apparentTemperatureCelsius?.let { Text("體感 $it°", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val updateLabel = if (state.failed) "更新失敗，顯示上次資料" else if (!state.isRefreshing &&
                    Duration.between(weather.fetchedAt, now.atZone(SCHOOL_ZONE).toInstant()).toMinutes() > 30
                ) "上次更新 ${weather.fetchedAt.atZone(SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))}" else null
                updateLabel?.takeIf { !compact }?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WeatherCurrent(weather: com.clhs.score.domain.overview.WeatherSnapshot, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            OutlinedRoundedSymbol(weatherSymbol(weather.condition), contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary, size = if (compact) 20.dp else 32.dp)
            Text("${weather.temperatureCelsius}°", style = if (compact) MaterialTheme.typography.headlineSmallEmphasized
                else MaterialTheme.typography.displaySmallEmphasized, softWrap = false,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "攝氏 ${weather.temperatureCelsius} 度" })
            if (compact) Text(weather.condition, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!compact) Text(weather.condition, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WeatherRain(weather: com.clhs.score.domain.overview.WeatherSnapshot) {
    weather.precipitationProbability?.let {
        Row(Modifier.clearAndSetSemantics { contentDescription = "降雨機率 $it%" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedRoundedSymbol("water_drop", size = 16.dp, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$it%",
                style = MaterialTheme.typography.labelSmall, softWrap = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WeatherRange(weather: com.clhs.score.domain.overview.WeatherSnapshot) {
    if (weather.maximumTemperatureCelsius == null && weather.minimumTemperatureCelsius == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        weather.maximumTemperatureCelsius?.let { WeatherRangeValue("keyboard_arrow_up", "最高溫", it) }
        weather.minimumTemperatureCelsius?.let { WeatherRangeValue("keyboard_arrow_down", "最低溫", it) }
    }
}

@Composable
private fun WeatherRangeValue(symbol: String, label: String, temperature: Int) {
    Row(Modifier.clearAndSetSemantics { contentDescription = "$label，攝氏 $temperature 度" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedRoundedSymbol(symbol, size = 16.dp, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$temperature°", style = MaterialTheme.typography.labelSmall, softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLargeEmphasized)
        if (action != null) action() else onViewAll?.let {
            TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("查看全部")
                Spacer(Modifier.width(4.dp))
                OutlinedRoundedSymbol("keyboard_arrow_right", size = 18.dp, contentDescription = null)
            }
        }
    }
}

@Composable
internal fun EventGroup(
    items: List<OverviewItem>, events: List<SchoolCalendarEvent>, today: LocalDate, loading: Boolean, failed: Boolean,
    onOpenDestination: (OverviewDestination) -> Unit,
    skeletonRows: Int = 3,
) {
    if (items.isEmpty()) {
        if (loading && !failed) {
            OverviewListSkeleton(skeletonRows, "近期行程載入中")
            return
        }
        SectionMessage(when {
            loading -> "行程載入中…"
            failed -> "行程暫時無法取得，請稍後重試"
            else -> "目前沒有可顯示的近期行程"
        })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            val eventId = (item.destination as? OverviewDestination.CalendarEvent)?.id
            val event = events.firstOrNull { it.id == eventId }
            OverviewListRow(item, today, item.eventSupportingText(), false,
                dateLabel = event?.overviewDateRange(today), index = index, count = items.size) { onOpenDestination(item.destination) }
        }
        if (failed) SectionMessage("行程更新失敗，顯示上次資料")
    }
}

@Composable
internal fun AnnouncementGroup(
    items: List<OverviewItem>, today: LocalDate, onOpenDestination: (OverviewDestination) -> Unit,
    unreadIds: Set<String>, loading: Boolean, failed: Boolean,
    skeletonRows: Int = 2,
) {
    if (items.isEmpty()) {
        if (loading && !failed) {
            OverviewListSkeleton(skeletonRows, "重要公告載入中")
            return
        }
        SectionMessage(when {
            loading -> "公告載入中…"
            failed -> "公告暫時無法取得，請稍後重試"
            else -> "目前沒有可顯示的重要公告"
        })
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, item ->
            OverviewListRow(item, today, announcementSupportingText(item.supportingText),
                (item.destination as? OverviewDestination.Announcement)?.id in unreadIds, index = index, count = items.size) {
                onOpenDestination(item.destination)
            }
        }
        if (failed) SectionMessage("公告更新失敗，顯示上次資料")
    }
}

@Composable
private fun SectionMessage(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OverviewListRow(item: OverviewItem, today: LocalDate, supporting: String, unread: Boolean,
    dateLabel: String? = null, index: Int, count: Int, onClick: () -> Unit) {
    val date = item.displayDate ?: if (item.kind == OverviewItemKind.Announcement) {
        runCatching { LocalDate.parse(item.supportingText.substringAfterLast(" · ").take(10).replace('/', '-')) }.getOrNull()
    } else null
    val dateText = dateLabel ?: date?.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MM/dd" else "yyyy/MM/dd"))
    SegmentedListItem(
        onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = if (dateText != null) { {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Box(Modifier.widthIn(min = 52.dp).width(IntrinsicSize.Max).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(dateText, style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } } else null,
        supportingContent = if (supporting.isNotBlank() || unread) { {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (unread) com.clhs.score.ui.announcements.AnnouncementBadge("未讀", unread = true)
            }
        } } else null,
        trailingContent = { OutlinedRoundedSymbol("keyboard_arrow_right", size = 20.dp, contentDescription = null) },
    ) { Text(item.title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}
@Composable
internal fun AlertGroup(items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            items.forEachIndexed { index, item ->
                Surface(
                    onClick = { onOpenDestination(item.destination) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedRoundedSymbol(
                            item.icon,
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription = null,
                        )
                        ItemText(item, modifier = Modifier.weight(1f))
                        OutlinedRoundedSymbol("keyboard_arrow_right", size = 18.dp, contentDescription = null)
                    }
                }
                if (index < items.lastIndex) ListDivider(start = 48.dp)
            }
        }
    }
}

@Composable
private fun ItemText(
    item: OverviewItem,
    modifier: Modifier = Modifier,
    supportingText: String = item.supportingText,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (supportingText.isNotBlank()) {
            Text(
                supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListDivider(start: androidx.compose.ui.unit.Dp = 44.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start, end = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun OverviewSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeletonBlock(
            modifier = Modifier.width(168.dp).height(24.dp),
            shape = MaterialTheme.shapes.extraSmall,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(Modifier.width(72.dp).height(16.dp))
                    Spacer(Modifier.weight(1f))
                    SkeletonBlock(Modifier.width(64.dp).height(16.dp))
                }
                SkeletonBlock(Modifier.fillMaxWidth(0.62f).height(34.dp), shape = MaterialTheme.shapes.extraSmall)
                SkeletonBlock(Modifier.fillMaxWidth(0.82f).height(18.dp))
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(Modifier.size(24.dp), shape = CircleShape)
                    SkeletonBlock(Modifier.width(72.dp).height(20.dp))
                    Spacer(Modifier.weight(1f))
                    SkeletonBlock(Modifier.width(56.dp).height(20.dp))
                }
                SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(16.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.34f).height(14.dp))
            }
        }
        SkeletonSection(rowCount = 3)
        SkeletonSection(rowCount = 2)
    }
}

@Composable
private fun SkeletonSection(rowCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonBlock(Modifier.width(96.dp).height(20.dp))
            Spacer(Modifier.width(8.dp))
            SkeletonBlock(Modifier.width(20.dp).height(16.dp))
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                repeat(rowCount) { index ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SkeletonBlock(Modifier.size(24.dp), shape = CircleShape)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(16.dp))
                                SkeletonBlock(Modifier.fillMaxWidth(0.56f).height(14.dp))
                            }
                        }
                    }
                    if (index < rowCount - 1) ListDivider()
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {}
}

internal fun OverviewHero.statusText(now: LocalDateTime): String {
    val start = startsAt ?: return when (headline) {
        "正在上課" -> "上課中"
        "接下來" -> "下一節課"
        else -> "下一節課 · $headline"
    }
    val end = endsAt
    if (end != null && !now.isBefore(start) && now.isBefore(end)) {
        val minutes = (Duration.between(now, end).seconds + 59) / 60
        return "上課中 · 還有 $minutes 分鐘"
    }
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), start.toLocalDate())
    return "下一節課 · " + when {
        days > 1 -> "$days 天後"
        days == 1L -> "明天"
        now.isBefore(start) -> "${((Duration.between(now, start).seconds + 59) / 60).coerceAtLeast(1)} 分鐘後"
        else -> "即將開始"
    }
}

internal fun OverviewItem.eventSupportingText(): String = when (label) {
    "進行中" -> supportingText.replace("進行中 · 至 ", "至 ")
    else -> supportingText.removePrefix("全天 · 開始 · ").removePrefix("全天 · 開始")
}
private fun weatherSymbol(condition: String): String = when {
    "雷" in condition -> "thunderstorm"
    "雨" in condition -> "rainy"
    "雪" in condition -> "cloudy_snowing"
    "霧" in condition -> "foggy"
    "晴" in condition && "雲" in condition -> "partly_cloudy_day"
    "晴" in condition -> "sunny"
    else -> "cloud"
}

internal fun OverviewItem.isVisibleForAuth(authState: AuthState): Boolean =
    authState is AuthState.Authenticated || !kind.isPrivate()

internal fun announcementSupportingText(text: String): String {
    val rawDate = text.substringAfterLast(" · ")
    if (!Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*").matches(rawDate)) return text
    return text.removeSuffix(rawDate).removeSuffix(" · ")
}

internal fun OverviewItem.countdownText(): String =
    if (label in setOf("今天", "已結束", "已到期", "進行中")) label else "${label}後"

private fun SchoolCalendarEvent.overviewDateRange(today: LocalDate): String {
    val startDate = start.toLocalDate()
    // All-day DTEND is exclusive; timed events retain their actual end date.
    val endDate = (if (isAllDay) endExclusive.minusNanos(1) else endExclusive).toLocalDate()
    val formatter = DateTimeFormatter.ofPattern(
        if (startDate.year == today.year && endDate.year == today.year) "MM/dd" else "yyyy/MM/dd",
    )
    return if (endDate.isAfter(startDate)) "${startDate.format(formatter)}\n～${endDate.format(formatter)}"
        else startDate.format(formatter)
}

@Composable
internal fun CountdownCards(
    items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit,
    modifier: Modifier = Modifier, singleInstance: Boolean = false, showDate: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val columns = if (!singleInstance && maxWidth >= 320.dp && fontScale < 1.8f) 2 else 1
        Column(Modifier.then(if (singleInstance) Modifier.fillMaxSize() else Modifier.widthIn(max = 480.dp).fillMaxWidth()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { rowItems ->
                Row(
                    Modifier.fillMaxWidth().then(if (singleInstance) Modifier.weight(1f) else Modifier.height(IntrinsicSize.Min)),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { item ->
                        val countdown = item.countdownText()
                        Surface(
                            onClick = { onOpenDestination(item.destination) },
                            modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = listOf(item.title, countdown)
                                        .filter(String::isNotBlank).joinToString("，")
                                },
                            shape = PocketVisual.metricShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(countdown, style = if (showDate) MaterialTheme.typography.displaySmallEmphasized
                                    else MaterialTheme.typography.headlineSmallEmphasized,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.title, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (showDate) item.displayDate?.let {
                                    Spacer(Modifier.weight(1f))
                                    Text(it.format(DateTimeFormatter.ofPattern("M 月 d 日")), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    if (rowItems.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
