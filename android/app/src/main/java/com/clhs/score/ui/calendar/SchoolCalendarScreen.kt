package com.clhs.score.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SCHOOL_CALENDAR_SEARCH_MAX_LENGTH
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.SubpageLayout
import com.clhs.score.viewmodel.SchoolCalendarUiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SchoolCalendarScreen(
    uiState: SchoolCalendarUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenGoogleCalendar: () -> Unit,
    onNoticeShown: () -> Unit,
    targetEventId: String? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val clearFocusAndHideKeyboard: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val interactionSource = remember { MutableInteractionSource() }
    var searchText by rememberSaveable { mutableStateOf(uiState.searchQuery) }
    val clearSearch: () -> Unit = {
        searchText = ""
        if (uiState.searchQuery.isNotEmpty()) onClearSearch()
    }
    val clearSearchAndHideKeyboard: () -> Unit = {
        clearSearch()
        clearFocusAndHideKeyboard()
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        CalendarSearchField(
            value = searchText,
            onValueChange = { value ->
                if (value.length <= SCHOOL_CALENDAR_SEARCH_MAX_LENGTH) {
                    searchText = value
                    onSearch(value)
                }
            },
            onClear = clearSearch,
            onSearch = { keyboardController?.hide() },
            modifier = modifier,
        )
    }
    LaunchedEffect(uiState.noticeMessage) {
        uiState.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onNoticeShown()
        }
    }

    SubpageLayout(
        onBack = onBack,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = clearFocusAndHideKeyboard,
        ),
        title = "行事曆",
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) {
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = {
                clearFocusAndHideKeyboard()
                onRefresh()
            },
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {},
        ) {
            CalendarAgenda(
                uiState = uiState,
                targetEventId = targetEventId,
                onTargetMissing = { snackbarHostState.showSnackbar("找不到這個事件，可能已從行事曆移除。") },
                searchField = searchField,
                onRefresh = {
                    clearFocusAndHideKeyboard()
                    onRefresh()
                },
                onClearSearch = clearSearchAndHideKeyboard,
                onOpenGoogleCalendar = {
                    clearFocusAndHideKeyboard()
                    onOpenGoogleCalendar()
                },
            )
            PullToRefreshDefaults.LoadingIndicator(
                state = pullToRefreshState,
                isRefreshing = uiState.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(2f),
            )
        }
    }
}

@Composable
private fun CalendarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        placeholder = { Text("輸入行事曆關鍵字") },
        leadingIcon = {
            OutlinedRoundedSymbol(icon = "search", contentDescription = null)
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = onClear) {
                    OutlinedRoundedSymbol(icon = "close", contentDescription = "清除搜尋")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable
private fun CalendarAgenda(
    uiState: SchoolCalendarUiState,
    targetEventId: String?,
    onTargetMissing: suspend () -> Unit,
    searchField: @Composable (Modifier) -> Unit,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenGoogleCalendar: () -> Unit,
) {
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val shimmerProgress = rememberInfiniteTransition(label = "calendar-loading-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearEasing),
        ),
        label = "calendar-loading-shimmer-progress",
    )
    val visibleEvents = remember(uiState.events, uiState.searchQuery) {
        uiState.visibleEvents
    }
    val groupedEvents = remember(visibleEvents, today) {
        visibleEvents.groupBy { event ->
            if (event.endExclusive.isAfter(today.atStartOfDay())) maxOf(event.start.toLocalDate(), today)
            else event.start.toLocalDate()
        }.toSortedMap()
    }

    var targetHandled by rememberSaveable(targetEventId) { mutableStateOf(false) }
    LaunchedEffect(targetEventId, groupedEvents, uiState.isInitialLoading, uiState.isRefreshing, uiState.errorMessage, uiState.searchQuery) {
        if (targetEventId == null || targetHandled || uiState.isInitialLoading ||
            uiState.errorMessage != null || uiState.searchQuery.isNotEmpty()) return@LaunchedEffect
        val index = calendarEventListIndex(groupedEvents, targetEventId, uiState.skippedRecurringEvents > 0)
        if (index != null) {
            listState.scrollToItem(index)
            targetHandled = true
        } else if (!uiState.isRefreshing) {
            targetHandled = true
            onTargetMissing()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .semantics {
                    if (uiState.isInitialLoading) contentDescription = "正在載入行事曆"
                },
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "calendar-search") { searchField(Modifier) }

            if (uiState.skippedRecurringEvents > 0) {
                item(key = "recurring-warning") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            text = "部分重複活動請前往 Google 行事曆查看。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            when {
                uiState.isInitialLoading -> item(key = "calendar-loading") {
                    CalendarLoadingList(shimmerProgress)
                }
                uiState.errorMessage != null -> item(key = "calendar-message") {
                    CalendarMessageState(
                        title = "暫時無法取得行事曆",
                        message = uiState.errorMessage,
                        actionLabel = "重新整理",
                        onAction = onRefresh,
                        onOpenGoogleCalendar = onOpenGoogleCalendar,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                visibleEvents.isEmpty() && uiState.searchQuery.isNotEmpty() -> item(key = "calendar-message") {
                    CalendarMessageState(
                        title = "找不到符合「${uiState.searchQuery}」的活動",
                        message = "請嘗試其他關鍵字，或清除搜尋查看接下來的活動。",
                        actionLabel = "清除搜尋",
                        onAction = onClearSearch,
                        onOpenGoogleCalendar = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                visibleEvents.isEmpty() -> item(key = "calendar-message") {
                    CalendarMessageState(
                        title = if (uiState.skippedRecurringEvents > 0) {
                            "目前沒有可直接顯示的活動"
                        } else {
                            "目前沒有接下來的活動"
                        },
                        message = if (uiState.skippedRecurringEvents > 0) {
                            "部分重複活動請前往 Google 行事曆查看。"
                        } else {
                            "可以稍後重新整理，或前往 Google 行事曆查看完整內容。"
                        },
                        actionLabel = "重新整理",
                        onAction = onRefresh,
                        onOpenGoogleCalendar = onOpenGoogleCalendar,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                else -> groupedEvents.forEach { (date, events) ->
                    item(key = "date-$date") {
                        Text(
                            text = dateHeading(date, today),
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(events, key = SchoolCalendarEvent::id) { event ->
                        CalendarEventCard(event, highlighted = event.id == targetEventId)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = listState.canScrollBackward && !listState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(4.dp),
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                slideInVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    initialOffsetY = { height -> height / 3 },
                ),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                slideOutVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    targetOffsetY = { height -> height / 3 },
                ),
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                FloatingActionButton(
                    onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    OutlinedRoundedSymbol(
                        icon = "keyboard_arrow_up",
                        size = 28.dp,
                        contentDescription = "回到頂端",
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(event: SchoolCalendarEvent, highlighted: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = eventTimeText(event),
                modifier = Modifier.fillMaxWidth(0.25f),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                event.location?.let { location ->
                    Text(
                        text = "地點：$location",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarMessageState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onOpenGoogleCalendar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(actionLabel)
                }
                onOpenGoogleCalendar?.let { openGoogleCalendar ->
                    FilledTonalButton(
                        onClick = openGoogleCalendar,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("Google 行事曆")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarLoadingList(shimmerProgress: State<Float>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingBlock(height = 24.dp, widthFraction = 0.42f, shimmerProgress = shimmerProgress)
        repeat(4) { LoadingBlock(height = 84.dp, shimmerProgress = shimmerProgress) }
    }
}

@Composable
private fun LoadingBlock(
    height: Dp,
    widthFraction: Float = 1f,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shimmerProgress: State<Float>,
) {
    val shape = MaterialTheme.shapes.large
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .background(color)
            .drawWithCache {
                val shimmerWidth = size.width * 0.55f
                val startX = (size.width + shimmerWidth) * shimmerProgress.value - shimmerWidth
                val shimmerBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.20f),
                        Color.Transparent,
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(startX + shimmerWidth, size.height),
                )
                onDrawBehind { drawRect(shimmerBrush) }
            },
    )
}

private fun dateHeading(date: LocalDate, today: LocalDate): String {
    val formatted = date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN))
    return if (date == today) "今天 · $formatted" else formatted
}

private fun eventTimeText(event: SchoolCalendarEvent): String {
    if (event.isAllDay) {
        val lastDate = event.endExclusive.toLocalDate().minusDays(1)
        return if (lastDate > event.start.toLocalDate()) {
            "全天\n${event.start.monthValue}/${event.start.dayOfMonth}－${lastDate.monthValue}/${lastDate.dayOfMonth}"
        } else {
            "全天"
        }
    }

    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val startDate = event.start.toLocalDate()
    val endDate = event.endExclusive.toLocalDate()
    return if (startDate == endDate) {
        "${event.start.format(timeFormatter)}\n${event.endExclusive.format(timeFormatter)}"
    } else {
        "${startDate.monthValue}/${startDate.dayOfMonth} ${event.start.format(timeFormatter)}\n" +
            "${endDate.monthValue}/${endDate.dayOfMonth} ${event.endExclusive.format(timeFormatter)}"
    }
}

/** Includes the search row, optional recurrence warning, and each date heading. */
internal fun calendarEventListIndex(
    groups: Map<LocalDate, List<SchoolCalendarEvent>>,
    eventId: String,
    hasRecurringWarning: Boolean,
): Int? {
    var index = if (hasRecurringWarning) 2 else 1
    for (events in groups.values) {
        index += 1
        val eventIndex = events.indexOfFirst { it.id == eventId }
        if (eventIndex >= 0) return index + eventIndex
        index += events.size
    }
    return null
}
