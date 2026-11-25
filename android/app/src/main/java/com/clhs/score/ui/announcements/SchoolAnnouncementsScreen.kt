package com.clhs.score.ui.announcements

import com.clhs.score.ui.rememberPocketOverflowMenuState
import com.clhs.score.ui.PocketOverflowDropdownMenu
import com.clhs.score.ui.pocketOverflowMenuAnchor
import com.clhs.score.ui.PocketOverflowMenuItem

import com.clhs.score.ui.navigation.navigationContentPadding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.clhs.score.R
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
import com.clhs.score.ui.PocketOptionIcon
import com.clhs.score.ui.LocalReadingGutter
import com.clhs.score.ui.readingContentPadding
import com.clhs.score.ui.FloatingSubpageLayout
import com.clhs.score.ui.highlightedSearchText
import com.clhs.score.ui.rememberLoadingShimmerProgress
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.viewmodel.AnnouncementBookmarksUiState
import com.clhs.score.viewmodel.SchoolAnnouncementDetailUiState
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

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
    markedReadSnapshot: Set<String>? = null,
    onMarkAllRead: () -> Unit = {},
    onMarkedReadSnapshotConsumed: () -> Unit = {},
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
    val overflowMenu = rememberPocketOverflowMenuState { showMoreMenu = it }
    var searchText by rememberSaveable { mutableStateOf(uiState.searchQuery) }
    LaunchedEffect(uiState.searchQuery) {
        if (uiState.searchQuery.isEmpty()) searchText = ""
    }
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
    var showSearch by rememberSaveable { mutableStateOf(false) }

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
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = LocalReadingGutter.current)
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

    FloatingSubpageLayout(
        onBack = onBack,
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = clearFocusAndHideKeyboard,
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        toolbar = {
            com.clhs.score.ui.PocketSearchToolbar(
                expanded = showSearch, onExpandedChange = { showSearch = it; showMoreMenu = false },
                query = searchText, onQueryChange = { searchText = it; onSearch(it) },
                maxLength = SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH,
                searchLabel = "搜尋公告", collapsedWidth = 96.dp,
            ) {
            IconButton(
                modifier = Modifier.pocketOverflowMenuAnchor(overflowMenu),
                onClick = { showMoreMenu = true },
                shapes = IconButtonDefaults.shapes(pressedShape = IconButtonDefaults.standardShape),
            ) {
                OutlinedRoundedSymbol(icon = "more_vert", contentDescription = "更多選項")
            }
            PocketOverflowDropdownMenu(state = overflowMenu, offset = androidx.compose.ui.unit.DpOffset(0.dp, 10.dp),
                expanded = showMoreMenu,
                onDismissRequest = { showMoreMenu = false },
            ) {
                PocketOverflowMenuItem(
                    text = { Text(if (isMarkingAllRead) "正在標記全部已讀" else "全部已讀") },
                    enabled = isReadingAvailable && !isMarkingAllRead && unreadIds.isNotEmpty(),
                    leadingIcon = { OutlinedRoundedSymbol("done_all", contentDescription = null) },
                    onClick = {
                        showMoreMenu = false
                        onMarkAllRead()
                    },
                )
                PocketOverflowMenuItem(
                    text = { Text(if (bookmarkCount > 0) "已收藏公告（$bookmarkCount）" else "已收藏公告") },
                    leadingIcon = { OutlinedRoundedSymbol("bookmark", contentDescription = null) },
                    onClick = { showMoreMenu = false; clearFocusAndHideKeyboard(); showBookmarks = true },
                )
                PocketOverflowMenuItem(
                    text = { Text(if (uiState.hasFilters) "篩選公告（已套用）" else "篩選公告") },
                    leadingIcon = { OutlinedRoundedSymbol("filter_list", contentDescription = null) },
                    onClick = { showMoreMenu = false; clearFocusAndHideKeyboard(); showFilters = true; onRefreshFilters() },
                )
                PocketOverflowMenuItem(
                    text = { Text("公告更新提醒") },
                    onClick = {
                        showMoreMenu = false
                        clearFocusAndHideKeyboard()
                        onOpenAnnouncementReminder()
                    },
                )
            }
            }
        },
    ) { pagePadding ->
        Column(modifier = Modifier.fillMaxSize()) {
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
                    markedReadSnapshot = markedReadSnapshot,
                    onMarkAllReadPositionRestored = onMarkedReadSnapshotConsumed,
                    isReadingAvailable = isReadingAvailable,
                    topPadding = pagePadding.calculateTopPadding(),
                    uiState = uiState,
                    unreadIds = unreadIds,
                    bookmarkedIds = bookmarkedIds,
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
                        .padding(top = pagePadding.calculateTopPadding() - 56.dp)
                        .zIndex(2f),
                )
            }
        }
    }
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
            DropdownMenu(offset = androidx.compose.ui.unit.DpOffset(0.dp, 10.dp), expanded = expanded, onDismissRequest = { expanded = false }) {
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
    markedReadSnapshot: Set<String>?,
    onMarkAllReadPositionRestored: () -> Unit,
    isReadingAvailable: Boolean,
    topPadding: androidx.compose.ui.unit.Dp,
    uiState: SchoolAnnouncementsUiState,
    unreadIds: Set<String>,
    bookmarkedIds: Set<String>,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onClearFilters: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onToggleBookmark: (SchoolAnnouncement) -> Unit,
    onOpenOfficialWebsite: () -> Unit,
) {
    val listState = rememberLazyListState()
    val announcements = remember(uiState.announcements, unreadIds) {
        orderAnnouncements(uiState.announcements, unreadIds)
    }
    val topAnchor = remember { AnnouncementTopAnchor() }
    val loadedIds = remember(uiState.announcements) {
        uiState.announcements.mapTo(HashSet(), SchoolAnnouncement::id)
    }
    val firstUnreadId = announcements.firstOrNull()?.id?.takeIf { it in unreadIds }
    SideEffect {
        if (topAnchor.shouldKeepAtTop(
                loadedIds, firstUnreadId,
                isAtTop = !listState.canScrollBackward,
                isScrollInProgress = listState.isScrollInProgress,
            )
        ) {
            listState.requestScrollToItem(0)
        }
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(markedReadSnapshot, unreadIds) {
        if (markedReadSnapshot != null && markedReadSnapshot.none { it in unreadIds }) {
            listState.scrollToItem(0)
            onMarkAllReadPositionRestored()
        }
    }

    var previousSearchQuery by rememberSaveable { mutableStateOf(uiState.searchQuery) }
    var previousUnitId by rememberSaveable { mutableStateOf(uiState.unitId) }
    var previousCategoryId by rememberSaveable { mutableStateOf(uiState.categoryId) }
    LaunchedEffect(uiState.searchQuery, uiState.unitId, uiState.categoryId) {
        if (previousSearchQuery != uiState.searchQuery ||
            previousUnitId != uiState.unitId || previousCategoryId != uiState.categoryId
        ) {
            listState.scrollToItem(0)
            previousSearchQuery = uiState.searchQuery
            previousUnitId = uiState.unitId
            previousCategoryId = uiState.categoryId
        }
    }
    PrefetchAnnouncementPages(listState, uiState, onLoadMore = onLoadMore)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .semantics {
                    if (uiState.isInitialLoading) contentDescription = "正在載入學校消息"
                },
            contentPadding = readingContentPadding(navigationContentPadding(PaddingValues(start = 16.dp, top = topPadding + 8.dp, end = 16.dp, bottom = 88.dp))),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isReadingAvailable) {
                item(key = "announcement-reading-unavailable") {
                    Text(
                        "公告閱讀狀態暫時無法取得",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (uiState.searchQuery.isNotEmpty()) {
                item(key = "announcement-search-status") {
                    InputChip(
                        selected = false,
                        onClick = onClearSearch,
                        label = { Text("搜尋：${uiState.searchQuery}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { OutlinedRoundedSymbol("close", contentDescription = null) },
                        modifier = Modifier.semantics { contentDescription = "清除搜尋：${uiState.searchQuery}" },
                    )
                }
            }
            if (uiState.hasFilters) {
                item(key = "announcement-filter-status") {
                    TextButton(onClick = onClearFilters) { Text("已套用公告篩選 · 清除") }
                }
            }
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
                    itemsIndexed(announcements, key = { _, item -> announcementItemKey(item.id) }) { index, announcement ->
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

private const val ANNOUNCEMENT_ITEM_PREFIX = "school-announcement:"
internal fun announcementItemKey(id: String): String = ANNOUNCEMENT_ITEM_PREFIX + id

internal class AnnouncementTopAnchor {
    private var previousIds = emptySet<String>()

    fun shouldKeepAtTop(
        loadedIds: Set<String>,
        firstUnreadId: String?,
        isAtTop: Boolean,
        isScrollInProgress: Boolean,
    ): Boolean {
        val newUnreadAtTop = firstUnreadId != null && firstUnreadId !in previousIds &&
            previousIds.isNotEmpty() && loadedIds.containsAll(previousIds)
        previousIds = loadedIds
        return newUnreadAtTop && isAtTop && !isScrollInProgress
    }
}

internal class AnnouncementPrefetchViewport {
    private var requestedKeys = emptySet<String>()
    private var requestedShortPage: Int? = null

    fun shouldRequest(visibleKeys: List<String>, nearEnd: Boolean, shortPage: Int? = null): Boolean {
        if (visibleKeys.isEmpty()) return false
        val sameRows = visibleKeys.all(requestedKeys::contains)
        if (!nearEnd) {
            if (!sameRows) requestedKeys = emptySet()
            return false
        }
        if (sameRows && (shortPage == null || shortPage == requestedShortPage)) return false
        requestedKeys = visibleKeys.toSet()
        requestedShortPage = shortPage
        return true
    }
}

@Composable
internal fun PrefetchAnnouncementPages(
    listState: androidx.compose.foundation.lazy.LazyListState,
    uiState: SchoolAnnouncementsUiState,
    enabled: Boolean = true,
    onLoadMore: () -> Unit,
) {
    val currentState by rememberUpdatedState(uiState)
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, enabled, uiState.searchQuery, uiState.unitId, uiState.categoryId) {
        if (!enabled) return@LaunchedEffect
        val viewport = AnnouncementPrefetchViewport()
        snapshotFlow {
            val state = currentState
            if (!state.canPrefetchAnnouncements()) return@snapshotFlow null
            val layout = listState.layoutInfo
            val visibleKeys = layout.visibleItemsInfo.mapNotNull {
                (it.key as? String)?.takeIf { key -> key.startsWith(ANNOUNCEMENT_ITEM_PREFIX) }
            }
            val nearEnd = shouldPrefetchAnnouncements(
                state, layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount,
            )
            val shortPage = state.pageIndex.takeIf { !listState.canScrollBackward && !listState.canScrollForward }
            Triple(visibleKeys, nearEnd, shortPage)
        }.collect { candidate ->
            if (candidate != null && viewport.shouldRequest(candidate.first, candidate.second, candidate.third)) {
                currentLoadMore()
            }
        }
    }
}

private fun SchoolAnnouncementsUiState.canPrefetchAnnouncements(): Boolean =
    hasMore && !isInitialLoading && !isRefreshing && !isLoadingMore &&
        errorMessage == null && loadMoreError == null

private const val ANNOUNCEMENT_PREFETCH_DISTANCE = 3

internal fun shouldPrefetchAnnouncements(
    uiState: SchoolAnnouncementsUiState,
    lastVisibleItemIndex: Int,
    totalItemsCount: Int,
): Boolean {
    if (!uiState.canPrefetchAnnouncements()) return false
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
        trailingContent = onBookmarkClick?.let { toggle -> {
            IconButton(onClick = hapticClick(toggle)) {
                OutlinedRoundedSymbol(
                    if (isBookmarked) "bookmark_added" else "bookmark_add",
                    contentDescription = if (isBookmarked) "取消收藏" else "收藏公告",
                )
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
                text = "目前沒有收藏。可在公告詳情按書籤，稍後再回來查看。",
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
    onOpenExternal: (SchoolAnnouncementDetail) -> Unit = {},
    isBookmarked: Boolean = false,
    onToggleBookmark: () -> Unit = {},
) {
    val context = LocalContext.current
    val announcementUrl = uiState.detail?.officialUrl ?: officialUrl
    val shareUrl = safeAnnouncementWebUrl(announcementUrl)
    FloatingSubpageLayout(
        onBack = onBack,
        actions = {
            IconButton(onClick = onToggleBookmark, enabled = uiState.detail != null) {
                OutlinedRoundedSymbol(if (isBookmarked) "bookmark_added" else "bookmark_add",
                    contentDescription = if (isBookmarked) "取消收藏公告" else "收藏公告")
            }
            IconButton(
                enabled = shareUrl != null,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareUrl)
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "暫時無法分享公告", Toast.LENGTH_SHORT).show()
                    }
                },
            ) {
                OutlinedRoundedSymbol(
                    icon = "share",
                    contentDescription = "分享公告連結",
                    size = 22.dp,
                )
            }
            IconButton(onClick = { onOpenUrl(announcementUrl) }) {
                Icon(
                    painter = painterResource(R.drawable.ic_open_original),
                    contentDescription = "查看原文",
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    ) { pagePadding ->
        val contentPadding = readingContentPadding(
            navigationContentPadding(
                PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = pagePadding.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
            ),
        )
        when {
            uiState.isLoading -> AnnouncementDetailLoading(contentPadding)
            uiState.detail != null -> AnnouncementDetailContent(uiState.detail, onOpenUrl, onOpenExternal, contentPadding)
            else -> AnnouncementMessageState(
                message = uiState.errorMessage ?: "請稍後再試一次。",
                onAction = onRetry,
                onOpenOfficialWebsite = { onOpenUrl(announcementUrl) },
            )
        }
    }
}

@Composable
private fun AnnouncementDetailContent(
    detail: SchoolAnnouncementDetail,
    onOpenUrl: (String) -> Unit,
    onOpenExternal: (SchoolAnnouncementDetail) -> Unit,
    contentPadding: PaddingValues,
) {
    SelectionContainer(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AnnouncementDetailHeader(detail)
            if (detail.isExternal) {
                DisableSelection {
                    FilledTonalButton(onClick = { onOpenExternal(detail) }) {
                        Text("開啟公告連結")
                    }
                }
            }
            if (detail.containsTables || detail.htmlContent.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (detail.containsTables) {
                DisableSelection {
                    AnnouncementTableNotice(onOpenOriginal = { onOpenUrl(detail.officialUrl) })
                }
            }
            if (detail.htmlContent.isNotBlank()) {
                AnnouncementHtml(detail.htmlContent, onOpenUrl)
            }
            detail.images.forEach { image ->
                key(image.url) { AnnouncementImage(image, onOpenUrl) }
            }
            if (detail.attachments.isNotEmpty()) {
                DisableSelection { AnnouncementAttachments(detail.attachments, onOpenUrl) }
            }
        }
    }
}

@Composable
private fun AnnouncementDetailHeader(detail: SchoolAnnouncementDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = detail.title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (detail.category.isNotBlank()) {
                Box(Modifier.align(Alignment.CenterVertically)) {
                    AnnouncementBadge(detail.category)
                }
            }
            val date = detail.date.replace(announcementDateTimePattern, "$1/$2/$3 $4")
            val metadata = (listOf(detail.issuer, detail.unit).distinct() + date)
                .filter(String::isNotBlank)
                .joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(
                    text = metadata,
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementTableNotice(onOpenOriginal: () -> Unit) {
    Surface(
        onClick = onOpenOriginal,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedRoundedSymbol("info")
            Column(modifier = Modifier.weight(1f)) {
                Text("查看完整表格", style = MaterialTheme.typography.titleSmall)
                Text("在學校網站開啟公告原文", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedRoundedSymbol("arrow_outward", size = 20.dp)
        }
    }
}

@Composable
private fun AnnouncementHtml(html: String, onOpenUrl: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val openUrl by rememberUpdatedState(onOpenUrl)
    val article = remember(html, linkColor) {
        val formatted = AnnotatedString.fromHtml(
            htmlString = announcementHtmlWithReadableSpacing(html),
            linkStyles = TextLinkStyles(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ),
            linkInteractionListener = { link ->
                if (link is LinkAnnotation.Url) openUrl(link.url)
            },
        ).flatMapAnnotations { range ->
            val link = range.item as? LinkAnnotation.Url ?: return@flatMapAnnotations listOf(range)
            val url = safeAnnouncementWebUrl(link.url) ?: return@flatMapAnnotations emptyList()
            listOf(AnnotatedString.Range(link.copy(url = url), range.start, range.end, range.tag))
        }
        formatted.subSequence(0, formatted.text.trimEnd('\n').length)
    }
    Text(
        text = article,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
    )
}

private fun announcementHtmlWithReadableSpacing(html: String): String {
    val document = Jsoup.parseBodyFragment(html)
    document.outputSettings().prettyPrint(false)
    document.select("p + p, p + div, div + p, div + div").forEach { paragraph ->
        val previous = generateSequence(paragraph.previousSibling()) { it.previousSibling() }
            .firstOrNull { it !is TextNode || !it.isBlank }
        if (previous == paragraph.previousElementSibling()) {
            paragraph.before("<br>")
        }
    }
    // Compose renders bullets with paragraph annotations. The clipboard needs real newlines.
    document.select("li + li").forEach { item ->
        val previousItem = item.previousElementSibling() ?: return@forEach
        val lastChild = previousItem.childNodes().lastOrNull { it !is TextNode || !it.isBlank }
        if (lastChild?.nodeName() != "br") {
            previousItem.appendElement("br")
        }
    }
    document.select("li > ul, li > ol").forEach { list ->
        val previous = generateSequence(list.previousSibling()) { it.previousSibling() }
            .firstOrNull { it !is TextNode || !it.isBlank }
        if (previous != null && previous.nodeName() != "br") {
            list.before("<br>")
        }
    }
    return document.body().html()
}

@Composable
private fun AnnouncementImage(image: SchoolAnnouncementImage, onOpenUrl: (String) -> Unit) {
    var failed by remember(image.url) { mutableStateOf(false) }
    var hasEnteredViewport by remember(image.url) { mutableStateOf(false) }
    if (!image.canPreview) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "外部圖片未自動載入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onOpenUrl(image.url) }) { Text("開啟圖片") }
        }
    } else if (!hasEnteredViewport) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .onVisibilityChanged(minFractionVisible = 0f) { visible ->
                    if (visible) hasEnteredViewport = true
                },
        )
    } else if (failed) {
        Text(
            text = "圖片暫時無法載入",
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
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = "開啟原圖") { onOpenUrl(image.url) },
            contentScale = ContentScale.Fit,
            onError = { failed = true },
        )
    }
}

@Composable
private fun AnnouncementAttachments(
    attachments: List<SchoolAnnouncementAttachment>,
    onOpenUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "附件",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                attachments.forEachIndexed { index, attachment ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp, end = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    AnnouncementAttachmentRow(attachment, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun AnnouncementAttachmentRow(
    attachment: SchoolAnnouncementAttachment,
    onOpenUrl: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "開啟附件", role = Role.Button) { onOpenUrl(attachment.url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PocketOptionIcon("description", modifier = Modifier.size(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            attachment.sizeBytes?.let { size ->
                Text(
                    text = formatFileSize(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedRoundedSymbol(
            icon = "arrow_outward",
            size = 20.dp,
            tint = MaterialTheme.colorScheme.primary,
        )
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
            .verticalScroll(rememberScrollState()).padding(horizontal = LocalReadingGutter.current)
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
            AnnouncementLoadingBlock(
                height = 112.dp,
                shimmerProgress = shimmerProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AnnouncementDetailLoading(contentPadding: PaddingValues) {
    val shimmerProgress = rememberLoadingShimmerProgress("announcement-detail-loading-shimmer")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(contentPadding)
            .semantics { contentDescription = "正在載入公告內容" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnnouncementLoadingBlock(
                height = 28.dp,
                shimmerProgress = shimmerProgress,
                modifier = Modifier.fillMaxWidth(0.86f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnnouncementLoadingBlock(
                    height = 26.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shimmerProgress = shimmerProgress,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.width(42.dp),
                )
                AnnouncementLoadingBlock(
                    height = 12.dp,
                    shimmerProgress = shimmerProgress,
                    modifier = Modifier.fillMaxWidth(0.55f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (widthFraction in listOf(1f, 0.94f, 0.68f, 0.98f, 0.77f)) {
                AnnouncementLoadingBlock(
                    height = 12.dp,
                    shimmerProgress = shimmerProgress,
                    modifier = Modifier.fillMaxWidth(widthFraction),
                )
            }
        }
    }
}

@Composable
private fun AnnouncementLoadingBlock(
    height: Dp,
    shimmerProgress: State<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: Shape = MaterialTheme.shapes.large,
) {
    Spacer(
        modifier = modifier
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

private val announcementDateTimePattern = Regex("""^(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2}):\d{2}$""")

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val announcementCacheTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.TAIWAN)
        .withZone(ZoneId.of("Asia/Taipei"))
