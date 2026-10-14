@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui.overview

import com.clhs.score.ui.rootBodyMotion

import com.clhs.score.ui.navigation.PocketScaffold as Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.*
import com.clhs.score.ui.AccountIconButton
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.navigation.LocalNavigationClearance
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.viewmodel.AuthState
import com.clhs.score.viewmodel.AnnouncementBookmarksUiState
import com.clhs.score.viewmodel.OverviewEditorState
import kotlinx.coroutines.launch
import java.util.UUID

class OverviewEditorActions(
    val beginEdit: () -> Unit,
    val cancelEdit: () -> Unit,
    val saveLayout: suspend () -> Boolean,
    val putWidget: (OverviewWidgetInstance) -> Unit,
    val removeWidget: (String) -> Unit,
    val moveWidget: (String, Int) -> Unit,
    val resetLayout: () -> Unit,
    val undoReset: () -> Unit,
    val searchLocations: suspend (String) -> List<WeatherLocation>,
    val searchStations: suspend (String) -> List<YouBikeStation>,
    val retryLayout: () -> Unit,
    val markYouBikeWidgetSeen: suspend () -> Boolean,
    val prepareLayoutOrder: (Int, List<String>) -> Unit = { _, _ -> },
)

@Composable
internal fun OverviewDashboard(
    state: OverviewState, editor: OverviewEditorState, actions: OverviewEditorActions,
    showNewWidgetBadge: Boolean,
    authState: AuthState, onRequestLogin: () -> Unit, onOpenPersonal: () -> Unit,
    onOpenDestination: (OverviewDestination) -> Unit, showUpdateBadge: Boolean,
    onRefresh: () -> Unit,
    unreadAnnouncementIds: Set<String>, isReadingAvailable: Boolean,
    onRefreshYouBike: () -> Unit,
    cwaKeyConfigured: Boolean,
    cwaKeySaving: Boolean,
    cwaKeyMessage: String?,
    onSaveCwaApiKey: (String) -> Unit,
    onClearCwaApiKey: () -> Unit,
    onDismissCwaKeyMessage: () -> Unit,
    onRefreshWeather: () -> Unit,
    bookmarksUiState: AnnouncementBookmarksUiState = AnnouncementBookmarksUiState(isLoading = false),
    onRetryBookmarks: () -> Unit = {},
    dynamicColor: Boolean = false,
) {
    val bodyMotion = Modifier.rootBodyMotion()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var showCwaKeyDialog by remember { mutableStateOf(false) }
    fun dismissPicker() {
        adding = false
        if (showNewWidgetBadge) scope.launch { actions.markYouBikeWidgetSeen() }
    }
    var configJson by rememberSaveable { mutableStateOf<String?>(null) }
    var revealWidgetId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val config = remember(configJson) { configJson?.let {
        runCatching { OverviewLayoutJson.decodeFromString<OverviewWidgetInstance>(it) }.getOrNull()
    } }
    fun configure(widget: OverviewWidgetInstance) { configJson = OverviewLayoutJson.encodeToString(OverviewWidgetInstance.serializer(), widget) }
    fun leave(action: () -> Unit) {
        if (editor.saving) return
        if (editor.editing && (editor.hasChanges || editor.loading)) pendingExit = action
        else { if (editor.editing) actions.cancelEdit(); action() }
    }
    fun save(after: () -> Unit = {}) { scope.launch { if (actions.saveLayout()) after() } }
    BackHandler(editor.editing) { leave {} }
    val widgets = editor.draft ?: editor.committed ?: state.widgets.orEmpty()
    fun putWidget(widget: OverviewWidgetInstance) {
        if (widgets.none { it.id == widget.id && it.enabled }) revealWidgetId = widget.id
        actions.putWidget(widget)
    }
    val visible = remember(widgets, editor.editing, state.items) {
        widgets.filter { it.type != "date_time" && it.enabled && (editor.editing || it.type != "alerts" || state.items.any { item ->
            item.kind == OverviewItemKind.ScheduleChange || item.kind == OverviewItemKind.GradeUpdate
        }) }
    }
    val density = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var actionsHeight by remember { mutableStateOf(statusBarHeight + 64.dp) }
    var actionsWidth by remember { mutableStateOf(144.dp) }
    var scrollOffset by rememberSaveable { mutableFloatStateOf(0f) }
    var gridScroll by remember { mutableStateOf<ScrollableState?>(null) }
    val dateHeight = 96.dp * density.fontScale
    val expandedDateTop = actionsHeight + 8.dp
    val navigationClearance = LocalNavigationClearance.current
    val header: @Composable () -> Unit = {
        Column {
            if (editor.editing) Spacer(Modifier.height(actionsHeight + 12.dp)) else {
                Spacer(Modifier.height(statusBarHeight + 8.dp))
                com.clhs.score.ui.PocketBrand(dynamicColor, endPadding = actionsWidth + 16.dp)
                Spacer(Modifier.height((expandedDateTop - statusBarHeight - 56.dp).coerceAtLeast(0.dp) + dateHeight + 12.dp))
            }
            if (editor.editing) Text("長按拖曳排列，拖曳右下角調整大小", Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            editor.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { save() }, enabled = !editor.saving) { Text("重試") }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            bottomBar = { AnimatedVisibility(editor.editing,
                enter = expandVertically(animationSpec = PocketMotion.contentReflow()) + fadeIn(PocketMotion.stateChange()),
                exit = shrinkVertically(animationSpec = PocketMotion.contentReflow()) + fadeOut(PocketMotion.stateChange())) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
                    HorizontalFloatingToolbar(expanded = true,
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
                        FilledTonalButton(onClick = hapticClick { adding = true }, shape = CircleShape,
                            enabled = !editor.saving, modifier = Modifier.weight(1f).semantics {
                                if (showNewWidgetBadge) stateDescription = "有新元件"
                            }) {
                            OutlinedRoundedSymbol("add", contentDescription = null, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            BadgedBox(badge = { if (showNewWidgetBadge) Badge() }) {
                                Text("新增元件")
                            }
                        }
                        TextButton(onClick = { confirmReset = true }, enabled = !editor.saving) { Text("重設") }
                        if (editor.canUndoReset) TextButton(onClick = actions.undoReset, enabled = !editor.saving) { Text("復原") }
                    }
                }
            } },
        ) { padding ->
            // Editing controls reserve a fixed viewport; normal navigation stays in the scroll extent.
            CompositionLocalProvider(LocalNavigationClearance provides if (editor.editing) 0.dp else navigationClearance) {
                Column(Modifier.fillMaxSize().padding(padding)
                    .padding(bottom = if (editor.editing) navigationClearance else 0.dp)
                    .padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        editor.loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        editor.layoutError != null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(editor.layoutError)
                            Button(onClick = actions.retryLayout) { Text("重試") }
                        }
                        else -> OverviewWidgetGrid(visible, editor.editing, !editor.saving,
                            onBeginEdit = actions.beginEdit,
                            onMove = { id, index ->
                                val targetId = visible.getOrNull(index)?.id
                                actions.moveWidget(id, widgets.indexOfFirst { it.id == targetId }.coerceAtLeast(0))
                            }, onChange = actions.putWidget, onConfigure = ::configure,
                            onRemove = actions.removeWidget,
                            onDuplicate = { configure(it.copy(id = UUID.randomUUID().toString())) },
                            modifier = Modifier.widthIn(max = 1200.dp).fillMaxWidth().weight(1f),
                            revealWidgetId = revealWidgetId, onWidgetRevealed = { revealWidgetId = null },
                            header = header,
                            onScrollOffsetChanged = { scrollOffset = it },
                            onScrollStateReady = { gridScroll = it },
                            legacyOrder = editor.legacyOrder, onVisualOrderReady = actions.prepareLayoutOrder,
                        ) { widget ->
                            Box(bodyMotion) {
                                OverviewWidgetContent(widget, state, authState is AuthState.Authenticated,
                                    editor.editing, { leave(onRequestLogin) }, { destination -> leave { onOpenDestination(destination) } },
                                    { onDismissCwaKeyMessage(); showCwaKeyDialog = true }, onRefresh,
                                    unreadAnnouncementIds = unreadAnnouncementIds, isReadingAvailable = isReadingAvailable,
                                    bookmarksUiState = bookmarksUiState, onRetryBookmarks = onRetryBookmarks,
                                    authState = authState, onRetryYouBike = onRefreshYouBike, onRetryWeather = onRefreshWeather)
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(statusBarHeight + 12.dp).background(Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background.copy(alpha = 0f)),
        )))
        OverviewDateTimeHeader(state.now, scrollOffset,
            expandedWidth = (maxWidth - 32.dp).coerceAtMost(1200.dp),
            expandedLeft = (maxWidth - (maxWidth - 32.dp).coerceAtMost(1200.dp)) / 2,
            expandedTop = expandedDateTop, expandedHeight = dateHeight, dockTop = statusBarHeight + 8.dp,
            dockWidth = (maxWidth - actionsWidth - 32.dp).coerceIn(80.dp, 128.dp),
            forceCompact = editor.editing,
            modifier = gridScroll?.let { Modifier.scrollable(it, Orientation.Vertical) } ?: Modifier)
        Surface(
            modifier = Modifier.align(Alignment.TopEnd)
                .onSizeChanged { size -> with(density) { actionsHeight = size.height.toDp(); actionsWidth = size.width.toDp() } }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp, shadowElevation = 3.dp,
        ) {
            Row(if (editor.editing) Modifier.padding(start = 4.dp) else Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor.editing) {
                    TextButton(onClick = { leave {} }, enabled = !editor.saving) { Text("取消") }
                    Button(onClick = { save() }, shape = CircleShape, modifier = Modifier.height(48.dp),
                        enabled = !editor.saving && !editor.loading && editor.layoutError == null) {
                        Text(if (editor.saving) "儲存中…" else "完成")
                    }
                } else {
                    FilledTonalIconButton(onClick = hapticClick(actions.beginEdit), shape = CircleShape,
                        enabled = !editor.loading && editor.layoutError == null) {
                        OutlinedRoundedSymbol("dashboard_customize", contentDescription = "編輯首頁")
                    }
                    AccountIconButton(onClick = { leave(onOpenPersonal) }, showUpdateBadge = showUpdateBadge, animateShape = false)
                }
            }
        }

    }

    if (showCwaKeyDialog) com.clhs.score.ui.ProfileCwaApiKeyDialog(
        saving = cwaKeySaving, configured = cwaKeyConfigured, message = cwaKeyMessage,
        onConfirm = onSaveCwaApiKey, onRemove = onClearCwaApiKey,
        onDismiss = {
            if (!cwaKeySaving) {
                showCwaKeyDialog = false
                onDismissCwaKeyMessage()
            }
        },
    )
    if (adding) OverviewWidgetPicker(widgets, showNewWidgetBadge = showNewWidgetBadge, onDismiss = ::dismissPicker, onChoose = { widget ->
        dismissPicker()
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (widget.type in setOf("weather", "youbike", "countdown", "calendar", "announcements")) configure(widget)
        else putWidget(widget.copy(enabled = true))
    })
    config?.let { widget -> OverviewWidgetConfigSheet(widget, state, actions.searchLocations, actions.searchStations,
        onConfirm = { putWidget(it.copy(enabled = true)); configJson = null }, onDismiss = { configJson = null },
        cwaKeyConfigured = cwaKeyConfigured, cwaKeySaving = cwaKeySaving, cwaKeyMessage = cwaKeyMessage,
        onSaveCwaApiKey = onSaveCwaApiKey, onClearCwaApiKey = onClearCwaApiKey,
        onDismissCwaKeyMessage = onDismissCwaKeyMessage) }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("重設首頁？") },
        text = { Text("將草稿改回預設版面，完成前仍可復原。") },
        confirmButton = { TextButton(onClick = { actions.resetLayout(); confirmReset = false }) { Text("重設") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
    pendingExit?.let { exit -> AlertDialog(onDismissRequest = { if (!editor.saving) pendingExit = null },
        title = { Text("儲存首頁變更？") }, text = { Text(editor.error ?: "尚有未儲存的變更。") },
        confirmButton = { TextButton(onClick = { save { pendingExit = null; exit() } }, enabled = !editor.saving && !editor.loading && editor.layoutError == null) { Text("儲存") } },
        dismissButton = { Row {
            TextButton(onClick = { actions.cancelEdit(); pendingExit = null; exit() }, enabled = !editor.saving) { Text("放棄") }
            TextButton(onClick = { pendingExit = null }, enabled = !editor.saving) { Text("繼續編輯") }
        } }) }
}
