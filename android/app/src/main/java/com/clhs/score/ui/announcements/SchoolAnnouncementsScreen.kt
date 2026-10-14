package com.clhs.score.ui.announcements

import com.clhs.score.ui.navigation.navigationContentPadding

import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementAttachment
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementImage
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import com.clhs.score.data.safeAnnouncementWebUrl
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.SubpageLayout
import com.clhs.score.ui.CountBadge
import com.clhs.score.ui.highlightedSearchText
import com.clhs.score.ui.rememberLoadingShimmerProgress
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.viewmodel.AnnouncementBookmarksUiState
import com.clhs.score.viewmodel.SchoolAnnouncementDetailUiState
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SchoolAnnouncementsScreen(
    uiState: SchoolAnnouncementsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onOpenOfficialWebsite: () -> Unit,
    onOpenAnnouncementReminder: () -> Unit,
    onNoticeShown: () -> Unit,
    onSetFilters: (String, String) -> Unit = { _, _ -> },
    onRefreshFilters: () -> Unit = {},
    unreadIds: Set<String> = emptySet(),
    isReadingAvailable: Boolean = true,
    isMarkingAllRead: Boolean = false,
    onMarkAllRead: () -> Unit = {},
    bookmarksUiState: AnnouncementBookmarksUiState = AnnouncementBookmarksUiState(),
    onToggleBookmark: (SchoolAnnouncement) -> Unit = {},
    onBookmarkMessageConsumed: () -> Unit = {},
    onRetryBookmarks: () -> Unit = {},
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
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf(uiState.searchQuery) }
    val bookmarkCount = bookmarksUiState.bookmarks.size
    val bookmarkedIds = remember(bookmarksUiState.bookmarks) {
        bookmarksUiState.bookmarks.mapTo(mutableSetOf(), AnnouncementBookmark::id)
    }
    val clearSearch: () -> Unit = {
        searchText = ""
        if (uiState.searchQuery.isNotEmpty()) onClearSearch()
    }
    val clearSearchAndHideKeyboard: () -> Unit = {
        clearSearch()
        clearFocusAndHideKeyboard()
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        AnnouncementSearchField(
            value = searchText,
            onValueChange = { value ->
                if (value.length <= SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH) {
                    searchText = value
                    onSearch(value)
                }
            },
            onClear = clearSearch,
            onSearch = { keyboardController?.hide() },
            hasFilters = uiState.hasFilters,
            filterSummary = listOfNotNull(
                uiState.filterOptions.units.find { it.id == uiState.unitId && it.id != ALL_UNITS_ID }?.name,
                uiState.filterOptions.categories.find { it.id == uiState.categoryId && it.id != ALL_UNITS_ID }?.name,
            ).joinToString(" · "),
            onFilter = {
                clearFocusAndHideKeyboard()
                showFilters = true
                onRefreshFilters()
            },
            modifier = modifier,
        )
    }
    LaunchedEffect(uiState.noticeMessage, uiState.lastUpdatedAt) {
        uiState.noticeMessage?.let { message ->
            val fetchedAt = uiState.lastUpdatedAt?.let(announcementCacheTimeFormatter::format)
            val displayMessage = fetchedAt?.let { "$message（上次取得：$it）" } ?: message
            snackbarHostState.showSnackbar(displayMessage)
            onNoticeShown()
        }
    }
    LaunchedEffect(bookmarksUiState.message) {
        bookmarksUiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            onBookmarkMessageConsumed()
        }
    }

    if (showFilters) {
        var unitId by rememberSaveable { mutableStateOf(uiState.unitId) }
        var categoryId by rememberSaveable { mutableStateOf(uiState.categoryId) }
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("篩選公告", style = MaterialTheme.typography.titleLarge)
                if (uiState.isLoadingFilters) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).semantics { contentDescription = "正在載入篩選選項" },
                    )
                }
                if (uiState.filterLoadFailed) {
                    Text("無法更新篩選選項，請稍後再試。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefreshFilters) { Text("重試") }
                }
                AnnouncementFilterSelector(
                    label = "單位", selectedId = unitId,
                    options = uiState.filterOptions.units.map { it.id to it.name },
                    onSelect = { unitId = it },
                )
                AnnouncementFilterSelector(
                    label = "類別", selectedId = categoryId,
                    options = uiState.filterOptions.categories.map { it.id to it.name },
                    onSelect = { categoryId = it },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { unitId = ALL_UNITS_ID; categoryId = ALL_UNITS_ID }) { Text("重設") }
                    Button(
                        onClick = { onSetFilters(unitId, categoryId); showFilters = false },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text("套用") }
                }
            }
        }
    }

    if (showBookmarks) {
        ModalBottomSheet(
            onDismissRequest = { showBookmarks = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            AnnouncementBookmarksSheet(
                uiState = bookmarksUiState,
                unreadIds = unreadIds,
                onOpenAnnouncement = { announcement ->
                    showBookmarks = false
                    onOpenAnnouncement(announcement)
                },
                onToggleBookmark = onToggleBookmark,
                onRetry = onRetryBookmarks,
            )
        }
    }

    SubpageLayout(
        onBack = onBack,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = clearFocusAndHideKeyboard,
        ),
        title = "學校公告",
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            IconButton(
                onClick = onMarkAllRead,
                enabled = isReadingAvailable && !isMarkingAllRead && unreadIds.isNotEmpty(),
                shapes = IconButtonDefaults.shapes(),
            ) {
                OutlinedRoundedSymbol(
                    icon = "done_all",
                    contentDescription = if (isMarkingAllRead) "正在標記全部已讀" else "全部已讀",
                )
            }
            Box(Modifier.size(48.dp)) {
                IconButton(
                    onClick = {
                        clearFocusAndHideKeyboard()
                        showBookmarks = true
                    },
                    modifier = Modifier.fillMaxSize(),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    OutlinedRoundedSymbol(
                        icon = "bookmark",
                        contentDescription = if (bookmarkCount > 0) {
                            "已收藏公告，$bookmarkCount 則"
                        } else {
                            "已收藏公告"
                        },
                    )
                }
                if (bookmarkCount > 0) {
                    CountBadge(bookmarkCount.toString(), Modifier.align(Alignment.TopEnd).clearAndSetSemantics {})
                }
            }
            IconButton(
                onClick = { showMoreMenu = true },
                shapes = IconButtonDefaults.shapes(),
            ) {
                OutlinedRoundedSymbol(icon = "more_vert", contentDescription = "更多選項")
            }
            DropdownMenu(
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
            ) {
                DropdownMenuItem(
                    text = { Text("公告更新提醒") },
                    onClick = {
                        showMoreMenu = false
                        clearFocusAndHideKeyboard()
                        onOpenAnnouncementReminder()
                    },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isReadingAvailable) {
                Text(
                    "公告閱讀狀態暫時無法取得",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = hapticClick {
                    clearFocusAndHideKeyboard()
                    onRefresh()
                },
                state = pullToRefreshState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                indicator = {},
            ) {
                AnnouncementList(
                    uiState = uiState,
                    unreadIds = unreadIds,
                    bookmarkedIds = bookmarkedIds,
                    searchField = searchField,
                    onRefresh = onRefresh,
                    onClearSearch = clearSearchAndHideKeyboard,
                    onClearFilters = { onSetFilters(ALL_UNITS_ID, ALL_UNITS_ID) },
                    onLoadMore = {
                        clearFocusAndHideKeyboard()
                        onLoadMore()
                    },
                    onOpenAnnouncement = { announcement ->
                        clearFocusAndHideKeyboard()
                        onOpenAnnouncement(announcement)
                    },
                    onToggleBookmark = onToggleBookmark,
                    onOpenOfficialWebsite = {
                        clearFocusAndHideKeyboard()
                        onOpenOfficialWebsite()
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
}

@Composable
private fun AnnouncementSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    hasFilters: Boolean,
    filterSummary: String,
    onFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        placeholder = { Text("輸入公告關鍵字") },
        leadingIcon = {
            OutlinedRoundedSymbol(icon = "search", contentDescription = null)
        },
        trailingIcon = {
            Row {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        OutlinedRoundedSymbol(icon = "close", contentDescription = "清除搜尋")
                    }
                }
                IconButton(onClick = onFilter) {
                    BadgedBox(badge = { if (hasFilters) Badge() }) {
                        OutlinedRoundedSymbol(
                            icon = "filter_list",
                            contentDescription = if (hasFilters) "篩選公告，已套用" else "篩選公告",
                        )
                    }
                }
            }
        },
        supportingText = if (hasFilters) ({ Text(filterSummary) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnnouncementFilterSelector(
    label: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(options.find { it.first == selectedId }?.second ?: selectedId, modifier = Modifier.weight(1f))
                OutlinedRoundedSymbol(icon = "keyboard_arrow_down", contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        modifier = Modifier.semantics { selected = id == selectedId },
                        trailingIcon = {
                            if (id == selectedId) OutlinedRoundedSymbol(icon = "check", contentDescription = "已選取")
                        },
                        onClick = { onSelect(id); expanded = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnnouncementList(
    uiState: SchoolAnnouncementsUiState,
    unreadIds: Set<String>,
    bookmarkedIds: Set<String>,
    searchField: @Composable (Modifier) -> Unit,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onToggleBookmark: (SchoolAnnouncement) -> Unit,
    onOpenOfficialWebsite: () -> Unit,
) {
    val listState = rememberLazyListState()
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    val announcements = remember(uiState.announcements, unreadIds) {
        orderAnnouncements(uiState.announcements, unreadIds)
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(uiState.searchQuery, uiState.unitId, uiState.categoryId) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(
        listState,
        uiState.searchQuery,
        uiState.unitId,
        uiState.categoryId,
        uiState.pageIndex,
        uiState.announcements.size,
        uiState.hasMore,
        uiState.isInitialLoading,
        uiState.isRefreshing,
        uiState.errorMessage,
        uiState.loadMoreError,
    ) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            layoutInfo.visibleItemsInfo.lastOrNull()?.index to layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collectLatest { (lastVisibleItemIndex, totalItemsCount) ->
                if (shouldPrefetchAnnouncements(
                        uiState = uiState,
                        lastVisibleItemIndex = lastVisibleItemIndex ?: -1,
                        totalItemsCount = totalItemsCount,
                    )
                ) {
                    latestOnLoadMore()
                }
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .semantics {
                    if (uiState.isInitialLoading) contentDescription = "正在載入學校消息"
                },
            contentPadding = navigationContentPadding(PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "announcement-search") { searchField(Modifier) }
            when {
                uiState.isInitialLoading -> item(key = "announcement-loading") {
                    AnnouncementsLoadingList()
                }
                uiState.errorMessage != null -> item(key = "announcement-message") {
                    AnnouncementMessageCard(
                        title = if (uiState.searchQuery.isEmpty()) {
                            "暫時無法取得學校消息"
                        } else {
                            "暫時無法搜尋公告"
                        },
                        message = uiState.errorMessage,
                        actionLabel = "重新整理",
                        onAction = onRefresh,
                        onOpenOfficialWebsite = onOpenOfficialWebsite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                uiState.announcements.isEmpty() && (uiState.searchQuery.isNotEmpty() || uiState.hasFilters) -> item(key = "announcement-message") {
                    AnnouncementMessageCard(
                        title = if (uiState.searchQuery.isEmpty()) "找不到符合篩選條件的公告" else "找不到符合「${uiState.searchQuery}」的公告",
                        message = "請嘗試其他關鍵字或篩選條件。",
                        actionLabel = if (uiState.hasFilters) "清除篩選" else "清除搜尋",
                        onAction = if (uiState.hasFilters) onClearFilters else onClearSearch,
                        onOpenOfficialWebsite = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                uiState.announcements.isEmpty() -> item(key = "announcement-message") {
                    AnnouncementMessageCard(
                        title = "目前沒有最新消息",
                        message = "可以稍後重新整理，或前往學校網站查看。",
                        actionLabel = "重新整理",
                        onAction = onRefresh,
                        onOpenOfficialWebsite = onOpenOfficialWebsite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
                    )
                }
                else -> {
                    itemsIndexed(announcements, key = { _, item -> item.id }) { index, announcement ->
                        AnnouncementCard(
                            announcement = announcement, index = index, count = announcements.size,
                            isUnread = announcement.id in unreadIds,
                            isBookmarked = announcement.id in bookmarkedIds,
                            onClick = { onOpenAnnouncement(announcement) },
                            onBookmarkClick = { onToggleBookmark(announcement) },
                        )
                    }
                    item(key = "announcement-footer") {
                        AnnouncementListFooter(
                            hasMore = uiState.hasMore,
                            isLoadingMore = uiState.isLoadingMore,
                            errorMessage = uiState.loadMoreError,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = listState.canScrollBackward && !listState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationContentPadding()
                .navigationBarsPadding()
                .padding(4.dp),
            enter = fadeIn(PocketMotion.stateChange()) +
                slideInVertically(
                    animationSpec = PocketMotion.contentReflow(),
                    initialOffsetY = { height -> height / 3 },
                ),
            exit = fadeOut(PocketMotion.stateChange()) +
                slideOutVertically(
                    animationSpec = PocketMotion.contentReflow(),
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

internal fun orderAnnouncements(
    announcements: List<SchoolAnnouncement>,
    unreadIds: Set<String>,
): List<SchoolAnnouncement> = announcements.sortedWith(
    compareByDescending<SchoolAnnouncement> { it.id in unreadIds }.thenByDescending { it.isPinned },
)

private const val ANNOUNCEMENT_PREFETCH_DISTANCE = 3

internal fun shouldPrefetchAnnouncements(
    uiState: SchoolAnnouncementsUiState,
    lastVisibleItemIndex: Int,
    totalItemsCount: Int,
): Boolean {
    if (!uiState.hasMore ||
        uiState.isInitialLoading ||
        uiState.isRefreshing ||
        uiState.isLoadingMore ||
        uiState.errorMessage != null ||
        uiState.loadMoreError != null
    ) {
        return false
    }
    val prefetchStartIndex = (totalItemsCount - 1 - ANNOUNCEMENT_PREFETCH_DISTANCE).coerceAtLeast(0)
    return lastVisibleItemIndex >= prefetchStartIndex
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AnnouncementCard(
    announcement: SchoolAnnouncement,
    isUnread: Boolean,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onBookmarkClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    searchQuery: String = "",
) {
    SegmentedListItem(
        onClick = hapticClick(onClick),
        modifier = modifier.fillMaxWidth(),
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        overlineContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isUnread) AnnouncementBadge("未讀", unread = true)
                if (announcement.isPinned) AnnouncementBadge("置頂", emphasized = true)
                if (searchQuery.isEmpty()) {
                    announcement.category.takeIf(String::isNotBlank)?.let { AnnouncementBadge(it) }
                }
                Text(if (searchQuery.isEmpty()) announcement.date else
                    listOf(announcement.date, announcement.unit).filter(String::isNotBlank).joinToString(" · "),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = onBookmarkClick?.let { bookmarkClick -> {
            IconButton(onClick = hapticClick(bookmarkClick), shapes = IconButtonDefaults.shapes()) {
                OutlinedRoundedSymbol(if (isBookmarked) "bookmark_added" else "bookmark_add",
                    contentDescription = if (isBookmarked) "取消收藏公告" else "收藏公告")
            }
        } },
        supportingContent = announcement.unit.takeIf { it.isNotBlank() && searchQuery.isEmpty() }?.let { unit -> {
            Text(unit, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } },
    ) {
        Text(highlightedSearchText(announcement.title, searchQuery),
            style = if (searchQuery.isEmpty()) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnnouncementBookmarksSheet(
    uiState: AnnouncementBookmarksUiState,
    unreadIds: Set<String>,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onToggleBookmark: (SchoolAnnouncement) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("已收藏公告", style = MaterialTheme.typography.titleLarge)
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "正在載入收藏公告" },
                )
            }
            uiState.loadFailed -> Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = uiState.message ?: "無法讀取收藏，請稍後再試。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text("重試") }
            }
            uiState.bookmarks.isEmpty() -> Text(
                text = "目前沒有收藏。可在公告卡片按書籤，稍後再回來查看。",
                modifier = Modifier.padding(bottom = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(uiState.bookmarks, key = { _, item -> item.id }) { index, bookmark ->
                    val announcement = bookmark.toAnnouncement()
                    AnnouncementCard(
                        announcement = announcement, index = index, count = uiState.bookmarks.size,
                        isUnread = announcement.id in unreadIds,
                        isBookmarked = true,
                        onClick = { onOpenAnnouncement(announcement) },
                        onBookmarkClick = { onToggleBookmark(announcement) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AnnouncementBadge(text: String, emphasized: Boolean = false, unread: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (unread) {
            MaterialTheme.colorScheme.errorContainer
        } else if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (unread) {
            MaterialTheme.colorScheme.onErrorContainer
        } else if (emphasized) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun AnnouncementListFooter(
    hasMore: Boolean,
    isLoadingMore: Boolean,
    errorMessage: String?,
    onLoadMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            errorMessage != null -> {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onLoadMore, shapes = ButtonDefaults.shapes()) {
                    Text("再試一次")
                }
            }
            isLoadingMore -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "正在載入更多消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !hasMore -> Text(
                text = "已載入全部消息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SchoolAnnouncementDetailScreen(
    uiState: SchoolAnnouncementDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit,
    officialUrl: String,
) {
    SubpageLayout(
        onBack = onBack,
        title = "公告內容",
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        when {
            uiState.isLoading -> AnnouncementDetailLoading()
            uiState.detail != null -> AnnouncementDetailContent(uiState.detail, onOpenUrl)
            else -> AnnouncementMessageState(
                message = uiState.errorMessage ?: "請稍後再試一次。",
                onAction = onRetry,
                onOpenOfficialWebsite = { onOpenUrl(officialUrl) },
            )
        }
    }
}

@Composable
private fun AnnouncementDetailContent(
    detail: SchoolAnnouncementDetail,
    onOpenUrl: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = navigationContentPadding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "detail-header") { AnnouncementDetailHeader(detail) }
        if (detail.htmlContent.isNotBlank()) {
            item(key = "detail-article") { AnnouncementHtml(detail.htmlContent, onOpenUrl) }
        }
        items(detail.images, key = SchoolAnnouncementImage::url) { image ->
            AnnouncementImageCard(image, onOpenUrl)
        }
        if (detail.attachments.isNotEmpty()) {
            item(key = "attachment-heading") {
                Text(
                    text = "附件",
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(detail.attachments, key = SchoolAnnouncementAttachment::url) { attachment ->
                AnnouncementAttachmentButton(attachment, onOpenUrl)
            }
        }
        item(key = "official-original") {
            FilledTonalButton(
                onClick = { onOpenUrl(detail.officialUrl) },
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("查看原文")
            }
        }
    }
}

@Composable
private fun AnnouncementDetailHeader(detail: SchoolAnnouncementDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.largeIncreased,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val metadata = listOf(detail.category, detail.date, detail.unit, detail.issuer)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun AnnouncementHtml(html: String, onOpenUrl: (String) -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        AndroidView(
            factory = { context ->
                TextView(context).apply {
                    movementMethod = LinkMovementMethod.getInstance()
                    linksClickable = true
                    textSize = 16f
                    setLineSpacing(0f, 1.25f)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            update = { textView ->
                textView.setTextColor(textColor)
                textView.setLinkTextColor(linkColor)
                textView.text = linkedHtml(html, linkColor, onOpenUrl)
            },
        )
    }
}

@Composable
private fun AnnouncementImageCard(image: SchoolAnnouncementImage, onOpenUrl: (String) -> Unit) {
    var failed by remember(image.url) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        if (!image.canPreview) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "外部圖片未自動載入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { onOpenUrl(image.url) },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("開啟圖片")
                }
            }
        } else if (failed) {
            Text(
                text = "圖片暫時無法載入",
                modifier = Modifier.padding(20.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = image.url,
                contentDescription = image.description,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 480.dp)
                    .clickable(onClickLabel = "開啟原圖") { onOpenUrl(image.url) },
                contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        }
    }
}

@Composable
private fun AnnouncementAttachmentButton(
    attachment: SchoolAnnouncementAttachment,
    onOpenUrl: (String) -> Unit,
) {
    FilledTonalButton(
        onClick = { onOpenUrl(attachment.url) },
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        shapes = ButtonDefaults.shapes(),
    ) {
        OutlinedRoundedSymbol(icon = "arrow_outward", size = 24.dp, contentDescription = null)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = attachment.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            attachment.sizeBytes?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementMessageState(
    message: String,
    onAction: () -> Unit,
    onOpenOfficialWebsite: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
                .navigationContentPadding()
        .navigationBarsPadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        AnnouncementMessageCard(
            title = "暫時無法取得公告內容",
            message = message,
            actionLabel = "重試",
            onAction = onAction,
            onOpenOfficialWebsite = onOpenOfficialWebsite,
            officialLabel = "查看原文",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        )
    }
}

@Composable
private fun AnnouncementMessageCard(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onOpenOfficialWebsite: (() -> Unit)?,
    modifier: Modifier = Modifier,
    officialLabel: String = "學校網站",
) {
    Card(
        modifier = modifier,
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
                ) { Text(actionLabel) }
                onOpenOfficialWebsite?.let { openOfficialWebsite ->
                    FilledTonalButton(
                        onClick = openOfficialWebsite,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(officialLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementsLoadingList() {
    val shimmerProgress = rememberLoadingShimmerProgress("announcement-loading-shimmer")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "正在載入學校消息" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    )
    {
        repeat(5) {
            AnnouncementLoadingBlock(112.dp, shimmerProgress = shimmerProgress)
        }
    }
}

@Composable
private fun AnnouncementDetailLoading() {
    val shimmerProgress = rememberLoadingShimmerProgress("announcement-detail-loading-shimmer")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(16.dp)
            .semantics { contentDescription = "正在載入公告內容" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnnouncementLoadingBlock(
            height = 180.dp,
            color = MaterialTheme.colorScheme.primaryContainer,
            shimmerProgress = shimmerProgress,
            shape = MaterialTheme.shapes.largeIncreased,
        )
        AnnouncementLoadingBlock(260.dp, shimmerProgress = shimmerProgress)
    }
}

@Composable
private fun AnnouncementLoadingBlock(
    height: Dp,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shimmerProgress: State<Float>,
    shape: Shape = MaterialTheme.shapes.large,
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
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

private fun linkedHtml(html: String, linkColor: Int, onOpenUrl: (String) -> Unit): SpannableString {
    val source = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
    val result = SpannableString(source)
    source.getSpans(0, source.length, URLSpan::class.java).forEach { span ->
        val start = source.getSpanStart(span)
        val end = source.getSpanEnd(span)
        val flags = source.getSpanFlags(span)
        result.removeSpan(span)
        val safeUrl = safeAnnouncementWebUrl(span.url) ?: return@forEach
        result.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) = onOpenUrl(safeUrl)

                override fun updateDrawState(drawState: android.text.TextPaint) {
                    drawState.color = linkColor
                    drawState.isUnderlineText = true
                }
            },
            start,
            end,
            flags.takeIf { it != 0 } ?: Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return result
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val announcementCacheTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN)
        .withZone(ZoneId.of("Asia/Taipei"))
