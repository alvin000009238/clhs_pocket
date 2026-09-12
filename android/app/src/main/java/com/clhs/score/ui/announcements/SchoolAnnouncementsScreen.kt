package com.clhs.score.ui.announcements

import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementAttachment
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementImage
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import com.clhs.score.data.safeAnnouncementWebUrl
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.SubpageLayout
import com.clhs.score.viewmodel.SchoolAnnouncementDetailUiState
import com.clhs.score.viewmodel.SchoolAnnouncementsUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    unreadIds: Set<String> = emptySet(),
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
    var showMoreMenu by remember { mutableStateOf(false) }
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
        title = "學校公告",
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
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
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
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
                    searchField = searchField,
                    onRefresh = onRefresh,
                    onClearSearch = clearSearchAndHideKeyboard,
                    onLoadMore = {
                        clearFocusAndHideKeyboard()
                        onLoadMore()
                    },
                    onOpenAnnouncement = { announcement ->
                        clearFocusAndHideKeyboard()
                        onOpenAnnouncement(announcement)
                    },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnnouncementList(
    uiState: SchoolAnnouncementsUiState,
    unreadIds: Set<String>,
    searchField: @Composable (Modifier) -> Unit,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenAnnouncement: (SchoolAnnouncement) -> Unit,
    onOpenOfficialWebsite: () -> Unit,
) {
    val listState = rememberLazyListState()
    val announcements = remember(uiState.announcements, unreadIds) {
        orderAnnouncements(uiState.announcements, unreadIds)
    }
    val coroutineScope = rememberCoroutineScope()
    val shimmerProgress = rememberInfiniteTransition(label = "announcement-loading-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearEasing),
        ),
        label = "announcement-loading-shimmer-progress",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .semantics {
                    if (uiState.isInitialLoading) contentDescription = "正在載入學校消息"
                },
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "announcement-search") { searchField(Modifier) }
            when {
                uiState.isInitialLoading -> item(key = "announcement-loading") {
                    AnnouncementsLoadingList(shimmerProgress)
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
                uiState.announcements.isEmpty() && uiState.searchQuery.isNotEmpty() -> item(key = "announcement-message") {
                    AnnouncementMessageCard(
                        title = "找不到符合「${uiState.searchQuery}」的公告",
                        message = "請嘗試其他關鍵字，或清除搜尋查看最新公告。",
                        actionLabel = "清除搜尋",
                        onAction = onClearSearch,
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
                    items(announcements, key = SchoolAnnouncement::id) { announcement ->
                        AnnouncementCard(
                            announcement = announcement,
                            isUnread = announcement.id in unreadIds,
                            onClick = { onOpenAnnouncement(announcement) },
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

internal fun orderAnnouncements(
    announcements: List<SchoolAnnouncement>,
    unreadIds: Set<String>,
): List<SchoolAnnouncement> = announcements.sortedWith(
    compareByDescending<SchoolAnnouncement> { it.id in unreadIds }.thenByDescending { it.isPinned },
)

@Composable
private fun AnnouncementCard(announcement: SchoolAnnouncement, isUnread: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isUnread) {
                    AnnouncementBadge("未讀", unread = true)
                }
                if (announcement.isPinned) AnnouncementBadge("置頂", emphasized = true)
                announcement.category.takeIf(String::isNotBlank)?.let { AnnouncementBadge(it) }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = announcement.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            announcement.unit.takeIf(String::isNotBlank)?.let { unit ->
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementBadge(text: String, emphasized: Boolean = false, unread: Boolean = false) {
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
private fun AnnouncementListFooter(
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
            hasMore -> Button(
                onClick = onLoadMore,
                enabled = !isLoadingMore,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(if (isLoadingMore) "載入中…" else "載入更多")
            }
            else -> Text(
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
        contentPadding = PaddingValues(16.dp),
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
private fun AnnouncementsLoadingList(shimmerProgress: State<Float>) {
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
    val shimmerProgress = rememberInfiniteTransition(label = "announcement-detail-loading-shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300, easing = LinearEasing),
        ),
        label = "announcement-detail-loading-shimmer-progress",
    )
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
