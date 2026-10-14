@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import com.clhs.score.data.SCHOOL_CALENDAR_SEARCH_MAX_LENGTH
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.ui.announcements.AnnouncementCard
import com.clhs.score.ui.announcements.AnnouncementListFooter
import com.clhs.score.ui.announcements.orderAnnouncements
import com.clhs.score.ui.announcements.shouldPrefetchAnnouncements
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import com.clhs.score.viewmodel.SchoolCalendarUiState
import com.clhs.score.ui.navigation.PocketScaffold
import com.clhs.score.ui.theme.PocketMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class CampusSearchFilter(val label: String) {
    All("全部"), Calendar("行事曆"), Announcements("學校公告"),
}

@Composable
fun CampusSearchScreen(
    announcements: SchoolAnnouncementsUiState,
    calendar: SchoolCalendarUiState,
    unreadIds: Set<String>,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRetryAnnouncements: () -> Unit,
    onRetryCalendar: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onOpenCalendarEvent: (SchoolCalendarEvent) -> Unit,
) {
    val searchField = rememberTextFieldState(initialText = announcements.searchQuery)
    val searchBarState = rememberSearchBarState()
    val searchText = searchField.text.toString()
    var focusRequested by rememberSaveable { mutableStateOf(false) }
    var previousQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(CampusSearchFilter.All) }
    var previousFilter by rememberSaveable { mutableStateOf(filter) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val query = searchText.trim()
    val events = remember(calendar.events, calendar.searchQuery) { calendar.visibleEvents }
    val messages = remember(announcements.announcements, unreadIds) {
        orderAnnouncements(announcements.announcements, unreadIds)
    }
    val currentSearch by rememberUpdatedState(onSearch)
    val currentAnnouncements by rememberUpdatedState(announcements)
    val currentQuery by rememberUpdatedState(query)
    val currentFilter by rememberUpdatedState(filter)
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    val placementMotion = PocketMotion.contentReflow<IntOffset>()
    val effectsMotion = PocketMotion.stateChange<Float>()

    LaunchedEffect(Unit) {
        if (!focusRequested) {
            focusRequester.requestFocus()
            focusRequested = true
        }
    }
    LaunchedEffect(query) {
        if (previousQuery != query) listState.scrollToItem(0)
        previousQuery = query
        if (query.isNotEmpty()) delay(250)
        currentSearch(query)
    }
    LaunchedEffect(filter) {
        if (previousFilter != filter) listState.scrollToItem(0)
        previousFilter = filter
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val state = currentAnnouncements
            currentFilter != CampusSearchFilter.Calendar &&
                currentQuery.isNotEmpty() && currentQuery == state.searchQuery &&
                shouldPrefetchAnnouncements(
                    state,
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1,
                    listState.layoutInfo.totalItemsCount,
                )
        }.distinctUntilChanged().collect { shouldLoad ->
            if (shouldLoad) currentLoadMore()
        }
    }

    PocketScaffold(
        topBar = {
            RootTopAppBar(title = "校園搜尋", navigationIcon = {
                IconButton(onClick = onBack) {
                    OutlinedRoundedSymbol("arrow_back", contentDescription = "返回")
                }
            })
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()) {
            SearchBarDefaults.InputField(
                textFieldState = searchField,
                searchBarState = searchBarState,
                inputTransformation = InputTransformation.maxLength(
                    minOf(SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH, SCHOOL_CALENDAR_SEARCH_MAX_LENGTH)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester).semantics { contentDescription = "搜尋公告與行事曆" },
                onSearch = { keyboard?.hide() },
                shape = SearchBarDefaults.inputFieldShape,
                placeholder = { Text("搜尋公告、行事曆…") },
                colors = SearchBarDefaults.inputFieldColors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                leadingIcon = { OutlinedRoundedSymbol("search", contentDescription = null) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchField.edit { replace(0, length, "") } }) {
                            OutlinedRoundedSymbol("close", contentDescription = "清除搜尋")
                        }
                    }
                },
            )
            if (query.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampusSearchFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = hapticClick { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = if (query.isEmpty()) 8.dp else 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (query.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("快速搜尋", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("期中考", "期末考", "校慶", "補考").forEach { suggestion ->
                                    SuggestionChip(onClick = hapticClick {
                                        searchField.edit { replace(0, length, suggestion); selection = TextRange(length) }
                                        keyboard?.hide()
                                    }, label = { Text(suggestion) })
                                }
                            }
                            Text("搜尋學校公告與尚未結束的行程", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    if (filter != CampusSearchFilter.Announcements) {
                        item(key = "calendar-heading") {
                            Text("行事曆", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() })
                        }
                        val calendarLoading = calendar.isInitialLoading || query != calendar.searchQuery
                        item(key = "calendar-status") {
                            SearchSourceStatus(calendarLoading || calendar.isRefreshing, calendar.errorMessage, calendar.noticeMessage,
                                events.isEmpty(), "沒有符合的行程", onRetryCalendar)
                            if (calendar.skippedRecurringEvents > 0) {
                                Text("重複活動請至 Google 行事曆查看。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!calendarLoading) {
                            itemsIndexed(events, key = { _, event -> "event-${event.id}" }) { index, event ->
                                SegmentedListItem(
                                    onClick = hapticClick { keyboard?.hide(); onOpenCalendarEvent(event) },
                                    modifier = Modifier.fillMaxWidth().animateItem(
                                        fadeInSpec = effectsMotion, placementSpec = placementMotion, fadeOutSpec = effectsMotion),
                                    shapes = ListItemDefaults.segmentedShapes(index, events.size),
                                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    leadingContent = {
                                        Surface(shape = MaterialTheme.shapes.medium,
                                            color = MaterialTheme.colorScheme.primaryContainer) {
                                            Column(Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 10.dp)
                                                .clearAndSetSemantics { contentDescription = event.start.format(campusEventAccessibleDateFormatter) },
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center) {
                                                Text(event.start.format(campusEventDateFormatter),
                                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                Text(event.start.format(campusEventWeekdayFormatter),
                                                    style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    },
                                    supportingContent = event.location?.let { location -> { Text(location) } },
                                ) { Text(highlightedSearchText(event.title, query),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                    if (filter != CampusSearchFilter.Calendar) {
                        item(key = "announcements-heading") {
                            Text("學校公告", style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() })
                        }
                        val announcementsLoading = announcements.isInitialLoading || query != announcements.searchQuery
                        item(key = "announcements-status") {
                            SearchSourceStatus(announcementsLoading || announcements.isRefreshing, announcements.errorMessage, announcements.noticeMessage,
                                announcements.announcements.isEmpty(), "沒有符合的公告", onRetryAnnouncements)
                        }
                        if (!announcementsLoading) {
                            itemsIndexed(messages, key = { _, announcement -> "announcement-${announcement.id}" }) { index, announcement ->
                                AnnouncementCard(
                                    announcement = announcement,
                                    isUnread = announcement.id in unreadIds,
                                    isBookmarked = false,
                                    onClick = { keyboard?.hide(); onOpenAnnouncement(announcement) },
                                    onBookmarkClick = null,
                                    searchQuery = query,
                                    modifier = Modifier.animateItem(fadeInSpec = effectsMotion,
                                        placementSpec = placementMotion, fadeOutSpec = effectsMotion),
                                    index = index,
                                    count = messages.size,
                                )
                            }
                            if (messages.isNotEmpty()) {
                                item(key = "announcements-footer") {
                                    AnnouncementListFooter(announcements.hasMore, announcements.isLoadingMore,
                                        announcements.loadMoreError, onLoadMore)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSourceStatus(
    isLoading: Boolean,
    error: String?,
    notice: String?,
    isEmpty: Boolean,
    emptyMessage: String,
    onRetry: () -> Unit,
) {
    when {
        isLoading -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "正在搜尋" })
        error != null -> {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("再試一次") }
        }
        isEmpty -> Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private val campusEventDateFormatter = DateTimeFormatter.ofPattern("MM/dd")
private val campusEventWeekdayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.TAIWAN)
private val campusEventAccessibleDateFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd EEEE", Locale.TAIWAN)

internal fun highlightedSearchText(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), match.range.first, match.range.last + 1)
        }
    }
}
