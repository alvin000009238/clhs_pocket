@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui.overview

import com.clhs.score.ui.rootBodyMotion
import com.clhs.score.ui.pocketFloatingShadow

import com.clhs.score.ui.navigation.PocketScaffold as Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@androidx.compose.ui.tooling.preview.Preview(name = "首頁浮動工具", widthDp = 390, heightDp = 844)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁深色", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁橫向", widthDp = 650, heightDp = 340)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁大字", widthDp = 320, heightDp = 1000, fontScale = 2f)
@Composable
private fun DashboardFloatingToolsPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(DashboardEditingPreviewProvider::class) mode: String,
) {
    val editing = mode == "editing"
    val now = java.time.LocalDateTime.of(2026, 11, 11, 14, 51)
    val report = com.clhs.score.data.FakeScheduleData.report("115_1", "230", scope = com.clhs.score.data.ScheduleScope.CURRENT_WEEK, today = now.toLocalDate())
    val widgets = defaultOverviewWidgets() + OverviewWidgetInstance.create("countdown",
        OverviewWidgetConfig.CustomCountdown("期末考", "2026-12-30"))
    val state = OverviewState(now = now, isInitialLoading = false, hasSchedule = true,
        hero = scheduleOverviewHero(report, now), items = scheduleOverviewItems(report, now) +
            OverviewItem("preview-grade-update", OverviewPriority.P1, OverviewItemKind.GradeUpdate,
                "第一次段考有 1 項更新", "查看成績與排名", "成績更新", "notifications",
                OverviewDestination.GradeExam("115_1", "1")))
    com.clhs.score.ui.theme.ScoreTheme {
        OverviewDashboard(
            state = state,
            editor = OverviewEditorState(committed = widgets, draft = if (editing) widgets else null, loading = false),
            actions = OverviewEditorActions(
                beginEdit = {}, cancelEdit = {}, saveLayout = { true }, putWidget = {},
                removeWidget = {}, moveWidget = { _, _ -> }, resetLayout = {}, undoReset = {},
                searchLocations = { emptyList() }, searchStations = { emptyList() },
                retryLayout = {}, markYouBikeWidgetSeen = { true },
            ),
            showNewWidgetBadge = false, authState = AuthState.Authenticated(1L),
            onRequestLogin = {}, onOpenPersonal = {}, onOpenDestination = {}, showUpdateBadge = false,
            onRefresh = {}, unreadAnnouncementIds = emptySet(), isReadingAvailable = true,
            onRefreshYouBike = {}, cwaKeyConfigured = false, cwaKeySaving = false, cwaKeyMessage = null,
            onSaveCwaApiKey = {}, onClearCwaApiKey = {}, onDismissCwaKeyMessage = {}, onRefreshWeather = {}, initialAlertsStacked = mode == "stacked", initialAlertsHidden = mode == "hidden",
        )
    }
}

class DashboardEditingPreviewProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<String> {
    override val values = sequenceOf("expanded", "stacked", "hidden", "editing")
}

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
    initialAlertsStacked: Boolean = false,
    initialAlertsHidden: Boolean = false,
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
    val visible = remember(widgets) {
        widgets.filter { it.enabled && it.type != "date_time" && it.type != "alerts" }
    }
    val alerts = remember(state.items, authState) {
        if (authState is AuthState.Authenticated) state.items.filter { it.kind.isOverviewAlert() }
        else emptyList()
    }
    var hiddenAlertIds by rememberSaveable(authState) { mutableStateOf(if (initialAlertsHidden) ArrayList(alerts.map { it.id }) else arrayListOf()) }
    var alertsStacked by rememberSaveable(authState) { mutableStateOf(initialAlertsStacked) }
    val visibleAlerts = alerts.filterNot { it.id in hiddenAlertIds }
    val canShowAlerts = authState is AuthState.Authenticated
    val showAlertPanel = canShowAlerts && !editor.editing && visibleAlerts.isNotEmpty()
    val inspectionMode = LocalInspectionMode.current
    val alertVisibility = remember(authState, canShowAlerts) {
        MutableTransitionState(inspectionMode && showAlertPanel)
    }
    alertVisibility.targetState = showAlertPanel
    val hiddenCount = alerts.size - visibleAlerts.size
    var alertsHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var actionsHeight by remember { mutableStateOf(statusBarHeight + 64.dp) }
    var actionsWidth by remember { mutableStateOf(144.dp) }
    var scrollOffset by rememberSaveable { mutableFloatStateOf(0f) }
    var gridScroll by remember { mutableStateOf<ScrollableState?>(null) }
    val dateHeight = 96.dp * density.fontScale
    val headerTop = actionsHeight + OverviewSpacing.headerGap
    val expandedDateTop = headerTop + if (canShowAlerts) with(density) { alertsHeight.toDp() } else 0.dp
    val navigationClearance = LocalNavigationClearance.current
    val editProgress by animateFloatAsState(
        targetValue = if (editor.editing) 1f else 0f,
        animationSpec = PocketMotion.contentReflow(),
        label = "首頁編輯版面",
    )
    val editingClearance = navigationClearance * editProgress.coerceIn(0f, 1f)
    val header: @Composable () -> Unit = {
        Column {
            Box {
                Spacer(Modifier.height(lerp(
                    expandedDateTop + dateHeight + 12.dp,
                    actionsHeight + 12.dp,
                    editProgress.coerceIn(0f, 1f),
                )))
                if (!editor.editing || editProgress < 1f) {
                    Box(Modifier.padding(top = statusBarHeight + 8.dp).graphicsLayer {
                        alpha = 1f - editProgress.coerceIn(0f, 1f)
                    }) {
                        com.clhs.score.ui.PocketBrand(dynamicColor, endPadding = (actionsWidth - 8.dp).coerceAtLeast(0.dp))
                    }
                }
            }
            AnimatedVisibility(editor.editing,
                enter = expandVertically(PocketMotion.contentReflow(), expandFrom = Alignment.Top) +
                    fadeIn(PocketMotion.stateChange()),
                exit = shrinkVertically(PocketMotion.contentReflow(), shrinkTowards = Alignment.Top) +
                    fadeOut(PocketMotion.microVisualState()),
            ) {
                Text("長按拖曳排列，拖曳右下角調整大小", Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            editor.error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { save() }, enabled = !editor.saving) { Text("重試") }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val alertListHeight = (maxHeight * 0.45f).coerceAtLeast(64.dp)
        val overviewWidth = (maxWidth - OverviewSpacing.page * 2).coerceIn(0.dp, 1200.dp)
        val alertGutter = (maxWidth - overviewWidth) / 2
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            bottomBar = { AnimatedVisibility(editor.editing,
                enter = expandVertically(animationSpec = PocketMotion.contentReflow()) + fadeIn(PocketMotion.stateChange()),
                exit = shrinkVertically(animationSpec = PocketMotion.contentReflow()) + fadeOut(PocketMotion.stateChange())) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), contentAlignment = Alignment.Center) {
                    HorizontalFloatingToolbar(expanded = true,
                        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                            .pocketFloatingShadow(FloatingToolbarDefaults.ContainerShape),
                        expandedShadowElevation = 0.dp,
                        collapsedShadowElevation = 0.dp) {
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
            CompositionLocalProvider(LocalNavigationClearance provides (navigationClearance - editingClearance)) {
                Column(Modifier.fillMaxSize().padding(padding)
                    .padding(bottom = editingClearance)
                    .padding(horizontal = OverviewSpacing.page), horizontalAlignment = Alignment.CenterHorizontally) {
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
        val expandedDateWidth = (maxWidth - OverviewSpacing.page * 2).coerceAtMost(1200.dp)
        OverviewDateTimeHeader(
            state.now, scrollOffset,
            expandedWidth = expandedDateWidth, expandedLeft = (maxWidth - expandedDateWidth) / 2,
            expandedTop = expandedDateTop, expandedHeight = dateHeight,
            dockTop = statusBarHeight + 8.dp,
            dockWidth = (maxWidth - actionsWidth - 32.dp).coerceIn(80.dp, 128.dp),
            forceCompact = editor.editing,
            modifier = gridScroll?.let { Modifier.scrollable(it, Orientation.Vertical) } ?: Modifier,
        )
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(top = headerTop)
                .fillMaxWidth()
                .onSizeChanged { alertsHeight = it.height },
        ) {
            if (canShowAlerts) {
                AnimatedVisibility(
                    visibleState = alertVisibility,
                    enter = fadeIn(PocketMotion.stateChange()) +
                        expandVertically(PocketMotion.contentReflow(), expandFrom = Alignment.Top, clip = false) +
                        slideInVertically(PocketMotion.contentReflow()) { -it / 4 },
                    exit = fadeOut(PocketMotion.stateChange()) +
                        shrinkVertically(PocketMotion.contentReflow(), shrinkTowards = Alignment.Top, clip = false) +
                        slideOutVertically(PocketMotion.contentReflow()) { -it / 4 },
                ) {
                    var lastVisibleAlerts by remember { mutableStateOf(visibleAlerts) }
                    SideEffect { if (visibleAlerts.isNotEmpty()) lastVisibleAlerts = visibleAlerts }
                    if (visibleAlerts.isNotEmpty() || lastVisibleAlerts.isNotEmpty()) {
                        OverviewAlertPanel(
                            alerts = visibleAlerts.ifEmpty { lastVisibleAlerts },
                            stacked = alertsStacked,
                            maxListHeight = alertListHeight,
                            cardGutter = alertGutter,
                            panelVisible = showAlertPanel,
                            onStackedChange = { alertsStacked = it },
                            onHide = { id -> if (id !in hiddenAlertIds) hiddenAlertIds = ArrayList(hiddenAlertIds + id) },
                            onOpen = { item -> if (showAlertPanel) leave { onOpenDestination(item.destination) } },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd)
                .onSizeChanged { size -> with(density) { actionsHeight = size.height.toDp(); actionsWidth = size.width.toDp() } }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!editor.editing && hiddenCount > 0) {
                Box {
                    FilledTonalIconButton(
                        onClick = hapticClick {
                            hiddenAlertIds = arrayListOf()
                            alertsStacked = false
                        },
                        shape = CircleShape,
                        modifier = Modifier.pocketFloatingShadow(CircleShape).semantics {
                            stateDescription = "有 $hiddenCount 則通知已收合"
                        },
                    ) {
                        OutlinedRoundedSymbol("notifications", contentDescription = "恢復已收合的通知")
                    }
                    Badge(
                        Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp)
                            .clearAndSetSemantics {},
                    ) {
                        Text(if (hiddenCount > 99) "99+" else hiddenCount.toString())
                    }
                }
            }
            Surface(
                modifier = Modifier.pocketFloatingShadow(MaterialTheme.shapes.extraLarge),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                val toolbarEnter = fadeIn(PocketMotion.stateChange())
                val toolbarExit = fadeOut(PocketMotion.microVisualState())
                AnimatedContent(
                    targetState = editor.editing,
                    contentAlignment = Alignment.CenterEnd,
                    transitionSpec = { toolbarEnter togetherWith toolbarExit },
                    label = "首頁工具列",
                ) { editing ->
                    Row(if (editing) Modifier.padding(start = 4.dp) else Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (editing) {
                            TextButton(onClick = { leave {} }, enabled = editor.editing && !editor.saving) { Text("取消") }
                            Button(onClick = { save() }, shape = CircleShape, modifier = Modifier.height(48.dp),
                                enabled = editor.editing && !editor.saving && !editor.loading && editor.layoutError == null) {
                                Text(if (editor.saving) "儲存中…" else "完成")
                            }
                        } else {
                            FilledTonalIconButton(onClick = hapticClick(actions.beginEdit), shape = CircleShape,
                                enabled = !editor.editing && !editor.loading && editor.layoutError == null) {
                                OutlinedRoundedSymbol("dashboard_customize", contentDescription = "編輯首頁")
                            }
                            AccountIconButton(onClick = { if (!editor.editing) leave(onOpenPersonal) }, showUpdateBadge = showUpdateBadge, animateShape = false)
                        }
                    }
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


@Composable
private fun OverviewAlertPanel(
    alerts: List<OverviewItem>,
    stacked: Boolean,
    maxListHeight: Dp,
    cardGutter: Dp,
    panelVisible: Boolean,
    onStackedChange: (Boolean) -> Unit,
    onHide: (String) -> Unit,
    onOpen: (OverviewItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasStackControl = alerts.size > 1
    val showStack = stacked && hasStackControl
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val controlInset = if (largeText) 32.dp else 24.dp
    val cardClearance = if (largeText) 12.dp else 8.dp
    Box(
        modifier.animateContentSize(PocketMotion.contentReflow())
            .padding(bottom = OverviewSpacing.headerGap),
    ) {
        Column(Modifier.padding(bottom = if (hasStackControl) controlInset else 0.dp)) {
            if (showStack) {
                Box(Modifier.fillMaxWidth().padding(horizontal = cardGutter)) {
                    Box(
                        Modifier.matchParentSize().padding(top = 8.dp, start = 8.dp, end = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f), MaterialTheme.shapes.large)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), MaterialTheme.shapes.large),
                    )
                    Box(Modifier.padding(bottom = 8.dp)) {
                        OverviewAlertCard(
                            alerts.first(), expandStack = true, bottomClearance = cardClearance,
                            onClick = { onStackedChange(false) },
                        )
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = maxListHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(OverviewSpacing.cardGap)) {
                    alerts.forEachIndexed { index, item ->
                        key(item.id) {
                            SwipeableOverviewAlert(
                                item, panelVisible, cardGutter,
                                bottomClearance = if (hasStackControl && index == alerts.lastIndex) cardClearance else 0.dp,
                                onHide = { onHide(item.id) }, onClick = { onOpen(item) },
                            )
                        }
                    }
                }
            }
        }
        if (hasStackControl) {
            FilledTonalButton(
                onClick = hapticClick { onStackedChange(!showStack) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = cardGutter).heightIn(min = 48.dp),
            ) {
                OutlinedRoundedSymbol(if (showStack) "keyboard_arrow_down" else "keyboard_arrow_up",
                    contentDescription = null, size = 18.dp)
                if (showStack) {
                    Text("+${alerts.size - 1}", Modifier.semantics {
                        contentDescription = "展開另外 ${alerts.size - 1} 則通知"
                    })
                } else Text("顯示較少")
            }
        }
    }
}

@Composable
private fun SwipeableOverviewAlert(
    item: OverviewItem,
    panelVisible: Boolean,
    cardGutter: Dp,
    bottomClearance: Dp,
    onHide: () -> Unit,
    onClick: () -> Unit,
) {
    val hide by rememberUpdatedState(onHide)
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe, panelVisible) {
        if (!panelVisible) return@LaunchedEffect
        swipe.reset()
        snapshotFlow { swipe.settledValue }.first { it != SwipeToDismissBoxValue.Settled }
        hide()
    }
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {},
        modifier = Modifier.fillMaxWidth().semantics {
            customActions = listOf(CustomAccessibilityAction("收合此通知") { hide(); true })
        },
    ) {
        // Keep the inset inside the moving content so the swipe travels to the viewport edge.
        Box(Modifier.fillMaxWidth().padding(horizontal = cardGutter)) {
            OverviewAlertCard(item, bottomClearance = bottomClearance, onClick = onClick)
        }
    }
}
