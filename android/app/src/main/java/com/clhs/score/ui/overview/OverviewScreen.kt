package com.clhs.score.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.clhs.score.data.OverviewPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.data.WeatherSource
import com.clhs.score.domain.overview.OverviewContext
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewHero
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWeatherState
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.RootTopAppBar
import com.clhs.score.ui.AccountIconButton
import com.clhs.score.ui.AuthRestoringPlaceholder
import com.clhs.score.ui.AuthGate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import com.clhs.score.ui.navigation.AuthRequirement
import com.clhs.score.viewmodel.AuthState
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val HeroShape = RoundedCornerShape(32.dp)
private val GroupShape = RoundedCornerShape(24.dp)
private val ItemShape = RoundedCornerShape(16.dp)
private val DateBadgeShape = RoundedCornerShape(12.dp)

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
) {
    var customize by rememberSaveable { mutableStateOf(false) }
    if (customize) {
        OverviewCustomizationDialog(state, onSavePreferences, onRefresh) { customize = false }
    }
    Scaffold(
        topBar = {
            RootTopAppBar(
                title = "總覽",
                actions = {
                    IconButton(onClick = { customize = true }, enabled = !state.isInitialLoading) {
                        OutlinedRoundedSymbol("dashboard_customize", contentDescription = "自訂總覽")
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (state.isInitialLoading) {
                        OverviewSkeleton()
                    } else {
                        OverviewContent(
                            state = state,
                            authState = authState,
                            onOpenDestination = onOpenDestination,
                            onRequestLogin = onRequestLogin,
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
    authState: AuthState,
    onOpenDestination: (OverviewDestination) -> Unit,
    onRequestLogin: () -> Unit,
) {
    val visibleItems = state.items.filter { it.isVisibleForAuth(authState) }
    val preferences = state.preferences
    val calendar = visibleItems.filter { it.kind == OverviewItemKind.CalendarEvent }
    val announcements = visibleItems.filter { it.kind == OverviewItemKind.Announcement }
    val alerts = visibleItems.filter {
        it.kind !in setOf(
            OverviewItemKind.ScheduleSetup,
            OverviewItemKind.CurrentClass,
            OverviewItemKind.UpcomingClass,
            OverviewItemKind.CalendarEvent,
            OverviewItemKind.Announcement,
        )
    }

    val sections = preferences.orderedSections().filter { section ->
        preferences.shows(section) && when (section) {
            "alerts" -> alerts.isNotEmpty()
            "countdown" -> preferences.countdownEventIds.isNotEmpty()
            else -> true
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
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { WeatherStrip(state.weather) }
            }
            "alerts" -> AlertGroup(alerts, onOpenDestination)
            "countdown" -> OverviewSection(title = "倒數") {
                CountdownCards(state.countdowns, onOpenDestination)
                val missing = preferences.countdownEventIds.size - state.countdowns.size
                if (missing > 0) Text(
                    "$missing 個事件暫時無法取得，可在自訂總覽調整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            "calendar" -> OverviewSection(
                title = "近期行程",
                onViewAll = { onOpenDestination(OverviewDestination.Calendar) },
            ) { EventGroup(calendar, onOpenDestination) }
            "announcements" -> OverviewSection(
                title = "重要公告",
                onViewAll = { onOpenDestination(OverviewDestination.Announcements) },
            ) { AnnouncementGroup(announcements, onOpenDestination) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                state.hero?.dateLabel() ?: LocalDate.now().let { "${it.monthValue} 月 ${it.dayOfMonth} 日 · ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TAIWAN)}" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            var index = 0
            while (index < sections.size) {
                val section = sections[index]
                val next = sections.getOrNull(index + 1)
                if (wide && setOf(section, next) == setOf("calendar", "announcements")) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
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
private fun OverviewLoginPrompt(authState: AuthState, onRequestLogin: () -> Unit) {
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
private fun OverviewSection(
    title: String,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, onViewAll)
        content()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(hero: OverviewHero?, needsSetup: Boolean, nextClass: OverviewItem?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = HeroShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hero?.displayHeadline() ?: if (needsSetup) "課表尚未設定" else "暫無可用課表",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                hero?.trailingText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hero == null) {
                Text(
                    if (needsSetup) "選擇學期與班級後，就能查看現在與下一節課。" else "前往課表重新查詢，取得最新課程資訊。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    hero.courseName,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                hero.detailText()?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            hero?.courseProgress?.let { progress ->
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "課程進度" },
                )
            }
            nextClass?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(it.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    it.supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (needsSetup && hero == null) {
                Text(
                    "設定課表",
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WeatherStrip(state: OverviewWeatherState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            OverviewWeatherState.Loading -> Text("天氣載入中…", style = MaterialTheme.typography.bodyMedium)
            is OverviewWeatherState.Unavailable -> Text(
                if (state.needsCwaApiKey) "請先在設定輸入 CWA 授權碼" else "天氣暫時無法取得",
                style = MaterialTheme.typography.bodyMedium,
            )
            is OverviewWeatherState.Available -> {
                val weather = state.snapshot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        weatherEmoji(weather.condition),
                        modifier = Modifier.clearAndSetSemantics {},
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        weather.condition,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${weather.temperatureCelsius}°",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val details = listOfNotNull(
                    weather.apparentTemperatureCelsius?.let { "體感 $it°" },
                    weather.precipitationProbability?.let { "${weather.precipitationLabel()} $it%" },
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${weather.locationLabel} · ${if (weather.source == WeatherSource.CWA) "中央氣象署" else "Open-Meteo"}",
                    modifier = Modifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        onViewAll?.let {
            TextButton(
                onClick = it,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Text("查看全部")
                Spacer(Modifier.width(2.dp))
                OutlinedRoundedSymbol("keyboard_arrow_right", size = 18.dp, contentDescription = null)
            }
        }
    }
}

@Composable
private fun EventGroup(items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit) {
    if (items.isEmpty()) {
        Text(
            "目前沒有可顯示的近期行程",
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = GroupShape, color = Color.Transparent) {
        Column(modifier = Modifier.padding(8.dp)) {
            items.forEachIndexed { index, item ->
                Surface(
                    onClick = { onOpenDestination(item.destination) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = ItemShape,
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EventLeading(item)
                        ItemText(item, modifier = Modifier.weight(1f), supportingText = item.eventSupportingText())
                        OutlinedRoundedSymbol("keyboard_arrow_right", size = 18.dp, contentDescription = null)
                    }
                }
                if (index < items.lastIndex) ListDivider()
            }
        }
    }
}

@Composable
private fun EventLeading(item: OverviewItem) {
    if (item.eventTime == null || item.label == "進行中" || item.label == "今天" || item.label == "明天") {
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(eventIndicatorColor(item), CircleShape),
            )
        }
        return
    }
    val eventTime = item.eventTime
    Surface(
        modifier = Modifier.widthIn(min = 40.dp).heightIn(min = 44.dp),
        shape = DateBadgeShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(item.dateBadgeTop(), style = MaterialTheme.typography.labelSmall)
            Text(
                eventTime.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AnnouncementGroup(items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit) {
    if (items.isEmpty()) {
        Text(
            "目前沒有可顯示的重要公告",
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = GroupShape, color = Color.Transparent) {
        Column(modifier = Modifier.padding(8.dp)) {
            items.forEachIndexed { index, item ->
                Surface(
                    onClick = { onOpenDestination(item.destination) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = ItemShape,
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ItemText(item, modifier = Modifier.weight(1f), supportingText = compactAnnouncementDate(item.supportingText))
                        OutlinedRoundedSymbol("keyboard_arrow_right", size = 18.dp, contentDescription = null)
                    }
                }
                if (index < items.lastIndex) ListDivider(start = 8.dp)
            }
        }
    }
}

@Composable
private fun AlertGroup(items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = GroupShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            items.forEachIndexed { index, item ->
                Surface(
                    onClick = { onOpenDestination(item.destination) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = ItemShape,
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
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun OverviewSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SkeletonBlock(
            modifier = Modifier.width(168.dp).height(24.dp),
            shape = RoundedCornerShape(8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = HeroShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(Modifier.width(72.dp).height(16.dp))
                    Spacer(Modifier.weight(1f))
                    SkeletonBlock(Modifier.width(64.dp).height(16.dp))
                }
                SkeletonBlock(Modifier.fillMaxWidth(0.62f).height(34.dp), shape = RoundedCornerShape(8.dp))
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
            shape = GroupShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                repeat(rowCount) { index ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        shape = ItemShape,
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
    shape: RoundedCornerShape = RoundedCornerShape(6.dp),
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {}
}

private fun OverviewHero.displayHeadline(): String = when (headline) {
    "正在上課" -> "現在"
    "接下來" -> "下一節"
    else -> headline
}

private fun OverviewHero.detailText(): String? = listOfNotNull(
    courseLabel.takeIf(String::isNotBlank),
    supportingText.takeIf(String::isNotBlank),
).joinToString(" · ").takeIf(String::isNotBlank)

internal fun OverviewItem.eventSupportingText(): String = when (label) {
    "進行中" -> supportingText.replace("進行中 · 至 ", "至 ")
    "今天", "明天" -> listOf(label, supportingText).filter(String::isNotBlank).joinToString(" · ")
    else -> supportingText
}

@Composable
private fun eventIndicatorColor(item: OverviewItem): Color = when (item.label) {
    "進行中" -> MaterialTheme.colorScheme.primary
    "今天" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}

private fun OverviewItem.dateBadgeTop(): String = eventTime?.let { "${it.monthValue}月" } ?: label

private fun com.clhs.score.domain.overview.WeatherSnapshot.precipitationLabel(): String {
    val hours = if (precipitationStart != null && precipitationEndExclusive != null) {
        Duration.between(precipitationStart, precipitationEndExclusive).toHours().takeIf { it > 0 }
    } else null
    return hours?.let { "未來 $it 小時降雨機率" } ?: "短時降雨機率"
}

private fun weatherEmoji(condition: String): String = when {
    "雷" in condition -> "⛈️"
    "雨" in condition -> "🌧️"
    "雪" in condition -> "🌨️"
    "霧" in condition -> "🌫️"
    "晴" in condition -> "☀️"
    else -> "☁️"
}

internal fun OverviewItem.isVisibleForAuth(authState: AuthState): Boolean =
    authState is AuthState.Authenticated || !kind.isPrivate()

private fun OverviewItemKind.isPrivate(): Boolean = when (this) {
    OverviewItemKind.CurrentClass,
    OverviewItemKind.UpcomingClass,
    OverviewItemKind.ScheduleSetup,
    OverviewItemKind.ScheduleChange,
    OverviewItemKind.GradeUpdate,
    -> true
    OverviewItemKind.CalendarEvent,
    OverviewItemKind.Announcement,
    -> false
}

private fun OverviewHero.dateLabel(): String =
    "${date.monthValue} 月 ${date.dayOfMonth} 日 · ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TAIWAN)}"

internal fun compactAnnouncementDate(text: String, today: LocalDate = LocalDate.now()): String {
    val rawDate = text.substringAfterLast(" · ")
    val date = runCatching { LocalDate.parse(rawDate.replace('/', '-')) }.getOrNull() ?: return text
    val label = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> if (date.year == today.year) "${date.monthValue}/${date.dayOfMonth}" else rawDate
    }
    return text.removeSuffix(rawDate) + label
}

@Composable
private fun CountdownCards(items: List<OverviewItem>, onOpenDestination: (OverviewDestination) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = if (maxWidth >= 320.dp) ((maxWidth - 12.dp) / 2).coerceAtMost(220.dp)
            else maxWidth.coerceAtMost(220.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item ->
                Surface(
                    onClick = { onOpenDestination(item.destination) },
                    modifier = Modifier.width(cardWidth).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.label, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
