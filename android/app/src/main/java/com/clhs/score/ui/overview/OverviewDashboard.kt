@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui.overview

import com.clhs.score.ui.rootBodyMotion
import com.clhs.score.ui.pocketFloatingShadow

import com.clhs.score.ui.navigation.PocketScaffold as Scaffold

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.clhs.score.data.FakeData
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
import kotlin.math.abs
import kotlin.math.roundToInt

@androidx.compose.ui.tooling.preview.Preview(name = "首頁浮動工具", widthDp = 390, heightDp = 844)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁深色", widthDp = 390, heightDp = 844, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁橫向", widthDp = 650, heightDp = 340)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁大字", widthDp = 320, heightDp = 1000, fontScale = 2f)
@androidx.compose.ui.tooling.preview.Preview(name = "首頁短視窗大字", widthDp = 390, heightDp = 260, fontScale = 2f)
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
        hero = scheduleOverviewHero(report, now),
        items = scheduleOverviewItems(report, now) + gradeOverviewItems(FakeData.overviewGradeReminder))
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
    var restoringAlertIds by remember(authState) { mutableStateOf(emptySet<String>()) }
    val restoration = remember(authState) { Animatable(1f) }
    LaunchedEffect(restoringAlertIds) {
        if (restoringAlertIds.isNotEmpty()) {
            restoration.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
            restoringAlertIds = emptySet()
        }
    }
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
                            restoringIds = restoringAlertIds,
                            restoration = restoration.asState(),
                            stacked = alertsStacked,
                            maxListHeight = alertListHeight,
                            cardGutter = alertGutter,
                            panelVisible = showAlertPanel,
                            onStackedChange = { alertsStacked = it },
                            onHide = { id ->
                                if (id !in hiddenAlertIds) hiddenAlertIds = ArrayList(hiddenAlertIds + id)
                            },
                            onHideAll = {
                                hiddenAlertIds = ArrayList((hiddenAlertIds + visibleAlerts.map { it.id }).distinct())
                            },
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
                            scope.launch {
                                if (visibleAlerts.isNotEmpty()) {
                                    restoration.snapTo(0f)
                                    restoringAlertIds = alerts.filter { it.id in hiddenAlertIds }.map { it.id }.toSet()
                                }
                                hiddenAlertIds = arrayListOf()
                                alertsStacked = false
                            }
                        },
                        shape = CircleShape,
                        modifier = Modifier.pocketFloatingShadow(CircleShape).semantics {
                            stateDescription = "有 $hiddenCount 則通知已隱藏"
                        },
                    ) {
                        OverviewAlertBell(hiddenCount, alertsStacked)
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


private val OverviewAlertContentBottom = HorizontalAlignmentLine(::maxOf)

@Composable
private fun OverviewAlertPanel(
    alerts: List<OverviewItem>,
    restoringIds: Set<String>,
    restoration: State<Float>,
    stacked: Boolean,
    maxListHeight: Dp,
    cardGutter: Dp,
    panelVisible: Boolean,
    onStackedChange: (Boolean) -> Unit,
    onHide: (String) -> Unit,
    onHideAll: () -> Unit,
    onOpen: (OverviewItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasStackControl = alerts.size > 1
    val restoringControl = hasStackControl && restoringIds.isNotEmpty() &&
        alerts.count { it.id !in restoringIds } == 1
    val collapsed = stacked && hasStackControl
    val swipeStates = alerts.map { item -> key(item.id) { rememberSwipeToDismissBoxState() } }
    val frontSwipe = swipeStates.first()
    var dragDismissal by remember { mutableStateOf<Float?>(null) }
    var dismissCommitted by remember { mutableStateOf(false) }
    LaunchedEffect(panelVisible) {
        if (panelVisible) dismissCommitted = false
    }
    val minimization = animateFloatAsState(
        targetValue = dragDismissal ?: if (dismissCommitted) 1f else 0f,
        animationSpec = if (dragDismissal != null) snap() else tween(300, easing = FastOutSlowInEasing),
        finishedListener = { if (it == 1f && dismissCommitted && panelVisible) onHideAll() },
        label = "overview-alert-minimization",
    )
    val stackDepth = 8.dp
    val controlInset = if (LocalDensity.current.fontScale >= 1.5f) 30.dp else 18.dp
    val scrollState = rememberScrollState()
    LaunchedEffect(collapsed) {
        if (collapsed) scrollState.scrollTo(0)
    }
    var dragExpansion by remember { mutableStateOf<Float?>(null) }
    val expansion = animateFloatAsState(
        targetValue = dragExpansion ?: if (collapsed) 0f else 1f,
        animationSpec = if (dragExpansion != null) snap() else tween(280, easing = FastOutSlowInEasing),
        label = "overview-alert-expansion",
    )
    val dragModifier = Modifier.overviewAlertDrag(
        collapsed = collapsed,
        canMinimize = collapsed || !hasStackControl,
        expansion = expansion,
        onProgress = { dragExpansion = it },
        onStackedChange = onStackedChange,
        onDismissProgress = { dragDismissal = it },
        onDismiss = { dismissCommitted = true },
    )
    val interactive = panelVisible && !dismissCommitted
    var panelWidth by remember { mutableIntStateOf(0) }
    Box(
        modifier.layout { measurable, constraints ->
            val panel = measurable.measure(constraints)
            val progress = minimization.value
            val height = (panel.height * (1f - progress)).roundToInt()
            val lift = 24.dp.toPx()
            layout(panel.width, height) {
                panel.placeRelativeWithLayer(0, 0) {
                    alpha = 1f - progress
                    scaleX = 1f - 0.16f * progress
                    scaleY = scaleX
                    translationY = -lift * progress
                    transformOrigin = TransformOrigin(0.85f, 0f)
                }
            }
        }.padding(bottom = OverviewSpacing.headerGap).onSizeChanged { panelWidth = it.width }.semantics {
            if (interactive) {
                customActions = listOf(CustomAccessibilityAction("收起所有通知") {
                    dismissCommitted = true
                    true
                })
            }
        },
    ) {
        Layout(
            modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
                val promotion = if (alerts.size == 2) {
                    swipeStates.maxOf { it.dismissFraction(constraints.maxWidth) }
                } else 0f
                val controlProgress = if (restoringControl) restoration.value else 1f
                val inset = if (hasStackControl) {
                    (controlInset.toPx() * (1f - promotion) * controlProgress).roundToInt()
                } else 0
                val list = measurable.measure(constraints.offset(vertical = -inset))
                val contentBottom = list[OverviewAlertContentBottom]
                val occupiedHeight = if (contentBottom == AlignmentLine.Unspecified) list.height
                    else contentBottom.coerceIn(0, list.height)
                layout(list.width, occupiedHeight + inset) { list.placeRelative(0, 0) }
            }.heightIn(max = maxListHeight).verticalScroll(scrollState),
            content = {
                alerts.forEachIndexed { index, item ->
                    key(item.id) {
                        val isTop = index == 0
                        val enabled = interactive && (isTop || (!collapsed && dragExpansion == null))
                        val canDragVertically = interactive &&
                            if (collapsed) isTop else !hasStackControl || scrollState.maxValue == 0
                        val gesture = if (canDragVertically) dragModifier else Modifier
                        SwipeableOverviewAlert(
                            panelVisible = enabled,
                            onHide = { onHide(item.id) },
                            swipe = swipeStates[index],
                            modifier = if (isTop || !collapsed) gesture else gesture.clearAndSetSemantics {
                                hideFromAccessibility()
                            },
                        ) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = cardGutter)) {
                                OverviewAlertCard(
                                    item = item,
                                    expandStack = isTop && collapsed,
                                    enabled = enabled,
                                    containerAlpha = if (hasStackControl) 1f - 0.1f * expansion.value else 0.9f,
                                ) {
                                    if (isTop && collapsed) onStackedChange(false) else onOpen(item)
                                }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val fraction = expansion.value
            val depth = stackDepth.roundToPx()
            val cardConstraints = constraints.copy(minHeight = 0)
            val cards = measurables.map { it.measure(cardConstraints) }
            val stackScale = 1f - (2 * depth / constraints.maxWidth.coerceAtLeast(1).toFloat())
                .coerceAtMost(1f) * (1f - fraction)
            val promotion = if (collapsed) frontSwipe.dismissFraction(constraints.maxWidth) else 0f
            val topHeight = cards.first().height
            val nextTopHeight = cards.getOrNull(1)?.height ?: topHeight
            val gap = OverviewSpacing.cardGap.roundToPx()
            val presence = alerts.map { if (it.id in restoringIds) restoration.value else 1f }
            val swipeFractions = swipeStates.map { it.dismissFraction(constraints.maxWidth) }
            val occupiedSpans = cards.mapIndexed { index, card ->
                val swipe = if (collapsed) 0f else swipeFractions[index]
                (card.height + gap) * presence[index] * (1f - swipe)
            }
            var nextExpandedY = 0
            val offsets = cards.mapIndexed { index, card ->
                val stackedY = if (index == 0) 0 else (topHeight + depth - card.height).coerceAtLeast(0)
                val promotedY = if (index <= 1) 0 else (nextTopHeight + depth - card.height).coerceAtLeast(0)
                val foldedY = stackedY + (promotedY - stackedY) * promotion
                val y = (foldedY + (nextExpandedY - foldedY) * fraction).roundToInt()
                nextExpandedY += occupiedSpans[index].roundToInt()
                y
            }
            val remainingHeight = cards.indices.drop(1).maxOfOrNull { index ->
                offsets[index] + ((cards[index].height + gap) * presence[index]).roundToInt() - gap
            } ?: topHeight
            val visibleFrontHeight = topHeight * presence.first()
            val frontHeight = (visibleFrontHeight + (remainingHeight - visibleFrontHeight) * promotion).roundToInt()
            val height = if (!collapsed && fraction == 1f) {
                (nextExpandedY - gap).coerceAtLeast(0)
            } else maxOf(remainingHeight, frontHeight)
            // Keep the scroll viewport tall enough to draw the departing card below the occupied space.
            val drawingHeight = cards.indices.fold(height) { bottom, index ->
                val swipe = swipeFractions[index]
                if (swipe > 0f && swipe < 1f) {
                    maxOf(bottom, offsets[index] + cards[index].height)
                } else bottom
            }
            layout(constraints.maxWidth, drawingHeight, mapOf(OverviewAlertContentBottom to height)) {
                cards.forEachIndexed { index, card ->
                    card.placeRelativeWithLayer(
                        x = 0,
                        y = offsets[index],
                        zIndex = (cards.size - index).toFloat(),
                    ) {
                        alpha = presence[index]
                        scaleX = when (index) {
                            0 -> 1f
                            1 -> if (collapsed) stackScale + (1f - stackScale) * promotion else stackScale
                            else -> stackScale
                        }
                    }
                }
            }
        }
        if (hasStackControl) {
            OverviewAlertStackControl(
                additionalCount = alerts.size - 1,
                collapsed = collapsed,
                expansion = expansion,
                onClick = { if (panelVisible && !dismissCommitted) onStackedChange(!collapsed) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = cardGutter).graphicsLayer {
                    val restoreAlpha = if (restoringControl) restoration.value else 1f
                    if (alerts.size == 2) {
                        val remaining = 1f - swipeStates.maxOf { it.dismissFraction(panelWidth) }
                        alpha = remaining * restoreAlpha
                        scaleX = 0.85f + 0.15f * remaining
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(1f, 1f)
                    } else {
                        alpha = restoreAlpha
                        scaleX = 1f
                        scaleY = 1f
                    }
                },
            )
        }
    }
}

private fun SwipeToDismissBoxState.dismissFraction(width: Int): Float =
    if (width <= 0 || dismissDirection == SwipeToDismissBoxValue.Settled) 0f
    else (abs(requireOffset()) / width).coerceIn(0f, 1f)

@Composable
private fun Modifier.overviewAlertDrag(
    collapsed: Boolean,
    canMinimize: Boolean,
    expansion: State<Float>,
    onProgress: (Float?) -> Unit,
    onStackedChange: (Boolean) -> Unit,
    onDismissProgress: (Float?) -> Unit,
    onDismiss: () -> Unit,
): Modifier {
    val updateProgress by rememberUpdatedState(onProgress)
    val toggle by rememberUpdatedState(onStackedChange)
    val updateDismissProgress by rememberUpdatedState(onDismissProgress)
    val dismiss by rememberUpdatedState(onDismiss)
    return pointerInput(collapsed, canMinimize) {
        val distanceThreshold = 28.dp.toPx()
        val travelRange = 120.dp.toPx()
        var origin = 0f
        var distance = 0f
        detectVerticalDragGestures(
            onDragStart = {
                origin = expansion.value
                distance = 0f
            },
            onVerticalDrag = { _, delta ->
                distance += delta
                if (collapsed || !canMinimize) {
                    val expansionDistance = if (collapsed) distance.coerceAtLeast(0f) else distance
                    updateProgress((origin + expansionDistance / travelRange).coerceIn(0f, 1f))
                }
                if (canMinimize) updateDismissProgress((-distance / travelRange).coerceIn(0f, 0.98f))
            },
            onDragCancel = {
                updateProgress(null)
                updateDismissProgress(null)
            },
            onDragEnd = {
                if (collapsed && distance >= distanceThreshold) toggle(false)
                updateProgress(null)
                updateDismissProgress(null)
                if (distance <= -distanceThreshold) {
                    if (canMinimize) dismiss() else toggle(true)
                }
            },
        )
    }
}
@Composable
private fun OverviewAlertBell(hiddenCount: Int, stacked: Boolean) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(hiddenCount, stacked) {
        angle.snapTo(0f)
        angle.animateTo(0f, animationSpec = keyframes {
            durationMillis = 440
            -14f at 80
            12f at 160
            -8f at 240
            4f at 320
        })
    }
    OutlinedRoundedSymbol(
        "notifications",
        contentDescription = "恢復已隱藏的通知",
        modifier = Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0.25f)
            rotationZ = angle.value
        },
    )
}

@Composable
private fun OverviewAlertStackControl(
    additionalCount: Int,
    collapsed: Boolean,
    expansion: State<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics {
                contentDescription = if (collapsed) "展開另外 $additionalCount 則通知" else "顯示較少"
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = hapticClick { onClick() },
            )
            .padding(bottom = 3.dp),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Layout(
                modifier = Modifier.clip(CircleShape).indication(interactionSource, ripple(bounded = false))
                    .padding(horizontal = 8.dp, vertical = 3.dp).clearAndSetSemantics {},
                content = {
                    OverviewAlertControlLabel("keyboard_arrow_down", "+$additionalCount")
                    OverviewAlertControlLabel("keyboard_arrow_up", "顯示較少")
                },
            ) { measurables, constraints ->
                val fraction = expansion.value
                val (shortLabel, longLabel) = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                val width = (shortLabel.width + (longLabel.width - shortLabel.width) * fraction).roundToInt()
                val height = maxOf(shortLabel.height, longLabel.height)
                layout(width, height) {
                    shortLabel.placeRelativeWithLayer(width - shortLabel.width, (height - shortLabel.height) / 2) {
                        alpha = 1f - fraction
                        translationY = -4.dp.toPx() * fraction
                    }
                    longLabel.placeRelativeWithLayer(width - longLabel.width, (height - longLabel.height) / 2) {
                        alpha = fraction
                        translationY = 4.dp.toPx() * (1f - fraction)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewAlertControlLabel(icon: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedRoundedSymbol(icon, contentDescription = null, size = 14.dp)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SwipeableOverviewAlert(
    panelVisible: Boolean,
    onHide: () -> Unit,
    swipe: SwipeToDismissBoxState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hide by rememberUpdatedState(onHide)
    LaunchedEffect(swipe, panelVisible) {
        if (!panelVisible) return@LaunchedEffect
        swipe.reset()
        snapshotFlow { swipe.settledValue }.first { it != SwipeToDismissBoxValue.Settled }
        hide()
    }
    SwipeToDismissBox(
        state = swipe,
        gesturesEnabled = panelVisible,
        backgroundContent = {},
        modifier = modifier.fillMaxWidth().semantics {
            if (panelVisible) {
                customActions = listOf(CustomAccessibilityAction("隱藏此通知") { hide(); true })
            }
        },
    ) {
        content()
    }
}
