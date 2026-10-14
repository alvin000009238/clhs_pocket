@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clhs.score.ui.overview

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.clhs.score.domain.overview.*
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.ui.theme.PocketVisual
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Only visible cells are composed; the drag overlay remains outside the lazy item lifecycle. */
@Composable
internal fun OverviewWidgetGrid(
    widgets: List<OverviewWidgetInstance>, editing: Boolean, enabled: Boolean,
    onBeginEdit: () -> Unit, onMove: (String, Int) -> Unit,
    onChange: (OverviewWidgetInstance) -> Unit, onConfigure: (OverviewWidgetInstance) -> Unit,
    onRemove: (String) -> Unit, onDuplicate: (OverviewWidgetInstance) -> Unit,
    modifier: Modifier = Modifier, revealWidgetId: String? = null, onWidgetRevealed: () -> Unit = {},
    header: @Composable () -> Unit = {},
    onScrollOffsetChanged: (Float) -> Unit = {},
    onScrollStateReady: (ScrollableState) -> Unit = {},
    legacyOrder: Boolean = false, onVisualOrderReady: (Int, List<String>) -> Unit = { _, _ -> },
    content: @Composable (OverviewWidgetInstance) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var scrollOffset by rememberSaveable { mutableFloatStateOf(0f) }
    var maxScroll by remember { mutableFloatStateOf(0f) }
    val onScrollOffsetLatest by rememberUpdatedState(onScrollOffsetChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { scrollOffset }.collect { onScrollOffsetLatest(it) }
    }
    val clearance = with(density) { com.clhs.score.ui.navigation.LocalNavigationClearance.current.toPx() }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var headerHeight by remember { mutableIntStateOf(0) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var resizingId by remember { mutableStateOf<String?>(null) }
    var resizePosition by remember { mutableStateOf(Offset.Zero) }
    var resizeSize by remember { mutableStateOf(Offset.Zero) }
    var resizeTarget by remember { mutableStateOf<OverviewWidgetSize?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }
    var secondFingerScrolling by remember { mutableStateOf(false) }
    var updateDrag by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentEditing by rememberUpdatedState(editing)
    val currentEnabled by rememberUpdatedState(enabled)
    val scroll = rememberScrollableState { delta ->
        val old = scrollOffset.coerceIn(0f, maxScroll)
        scrollOffset = (old - delta).coerceIn(0f, maxScroll)
        old - scrollOffset
    }
    LaunchedEffect(scroll) { onScrollStateReady(scroll) }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val columns = OverviewLayoutEngine.columns(maxWidth.value, density.fontScale)
        val placements = remember(widgets, columns, legacyOrder) {
            OverviewLayoutEngine.pack(widgets.map { widget ->
                if (OverviewWidgetDefinitions[widget.type]?.multiple == false)
                    widget.copy(size = OverviewWidgetSize(4, 1)) else widget
            }, columns, legacyOrder)
        }
        if (legacyOrder) SideEffect {
            onVisualOrderReady(columns, placements.map { it.id })
        }
        var naturalHeights by remember(columns, maxWidth, density.fontScale) { mutableStateOf<Map<String, Int>>(emptyMap()) }
        val visible = remember(widgets) { widgets.filter { it.enabled } }
        val gap = with(density) { 12.dp.toPx() }
        // Keep the fixed padding/gaps fixed; reserve extra height for larger text.
        val rowHeight = with(density) { (40.dp + 72.dp * density.fontScale.coerceAtLeast(1f)).toPx() }
        val availableWidth = with(density) { maxWidth.toPx() }
        val cellWidth = (availableWidth - gap * (columns - 1)) / columns
        val widthPitch = cellWidth + gap
        val heightPitch = rowHeight + gap
        val rowCount = placements.maxOfOrNull { it.row + it.rows } ?: 0
        fun rowGeometry(heights: Map<String, Int>): Pair<List<Float>, List<Float>> {
            val rows = MutableList(rowCount) { 0f }
            placements.forEach { p ->
                if (OverviewWidgetDefinitions[visible.first { it.id == p.id }.type]?.multiple == false) {
                    rows[p.row] = maxOf(rows[p.row], (heights[p.id] ?: rowHeight.roundToInt()).toFloat())
                } else repeat(p.rows) { row -> rows[p.row + row] = maxOf(rows[p.row + row], rowHeight) }
            }
            val tops = mutableListOf(headerHeight.toFloat())
            rows.forEach { height -> tops.add(tops.last() + height + gap) }
            return tops to rows
        }
        val (rowTops, _) = rowGeometry(naturalHeights)
        val activeRowTops by rememberUpdatedState(rowTops)
        val activeNaturalHeights by rememberUpdatedState(naturalHeights)
        fun topOf(p: OverviewPlacement): Float = activeRowTops.getOrElse(p.row) { p.row * heightPitch }
        // Row height reserves space; each card keeps its own natural or selected height.
        fun heightOf(p: OverviewPlacement): Float =
            activeNaturalHeights[p.id]?.toFloat() ?: (p.rows * heightPitch - gap)
        val activePlacements by rememberUpdatedState(placements)
        val activeWidgets by rememberUpdatedState(widgets)
        val onMoveLatest by rememberUpdatedState(onMove)
        val onChangeLatest by rememberUpdatedState(onChange)
        val onBeginEditLatest by rememberUpdatedState(onBeginEdit)
        val overlay = (draggedId ?: resizingId)?.let { id -> visible.firstOrNull { it.id == id } }
        val revealMotion = PocketMotion.selection<Float>()
        val onWidgetRevealedLatest by rememberUpdatedState(onWidgetRevealed)
        LaunchedEffect(revealWidgetId, placements, naturalHeights, viewportHeight, maxScroll, headerHeight) {
            val placement = placements.firstOrNull { it.id == revealWidgetId } ?: return@LaunchedEffect
            if (viewportHeight <= 0f || visible.any {
                OverviewWidgetDefinitions[it.type]?.multiple == false && it.id !in naturalHeights
            }) return@LaunchedEffect
            // Wait for measurement to include the new cell before clamping its destination.
            val expectedMaxScroll = (rowTops.last() + clearance - viewportHeight).coerceAtLeast(0f)
            if (maxScroll != expectedMaxScroll) return@LaunchedEffect
            val target = (topOf(placement) - headerHeight).coerceIn(0f, maxScroll)
            scroll.animateScrollBy(scrollOffset - target, revealMotion)
            onWidgetRevealedLatest()
        }
        com.clhs.score.ui.navigation.RegisterRootScroll {
            if (scrollOffset > 0f) scroll.animateScrollBy(scrollOffset, revealMotion)
        }
        LaunchedEffect(draggedId, columns, widthPitch, heightPitch) {
            val id = draggedId ?: return@LaunchedEffect
            var lastFrame = 0L
            while (isActive) withFrameNanos { frame ->
                val elapsed = if (lastFrame == 0L) 0f else (frame - lastFrame) / 1_000_000_000f
                lastFrame = frame
                val edge = with(density) { 64.dp.toPx() }
                val speed = when {
                    secondFingerScrolling -> 0f
                    pointerPosition.y < edge -> -((edge - pointerPosition.y) / edge).coerceIn(0f, 1f)
                    pointerPosition.y > viewportHeight - edge -> ((pointerPosition.y - viewportHeight + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                val old = scrollOffset
                scrollOffset = (old + speed * with(density) { 640.dp.toPx() } * elapsed).coerceIn(0f, maxScroll)
                if (scrollOffset != old) updateDrag?.invoke()
            }
        }
        val provider = object : LazyLayoutItemProvider {
            override val itemCount: Int get() = visible.size + 1
            override fun getKey(index: Int): Any = if (index == visible.size) "overview-header" else visible[index].id
            override fun getContentType(index: Int): Any = if (index == visible.size) "overview-header" else visible[index].type
            @Composable override fun Item(index: Int, key: Any) {
                if (index == visible.size) {
                    Box { header() }
                    return
                }
                val widget = visible[index]
                val placement = placements[index]
                val target = IntOffset((placement.column * widthPitch).roundToInt(), (topOf(placement) - headerHeight).roundToInt())
                // Initial measurement is layout, not a reorder animation.
                val animated = if (editing) animateIntOffsetAsState(target, PocketMotion.selection(), label = "元件位置").value else target
                val canMove = enabled
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (widget.id == draggedId || widget.id == resizingId) 0f else 1f
                    translationX = (animated.x - target.x).toFloat()
                    translationY = (animated.y - target.y).toFloat()
                }) {
                    OverviewWidgetHost(widget, editing, canMove, activeWidgets,
                        onMove, onChange, onConfigure, onRemove, onDuplicate,
                        content)
                }
            }
        }
        LazyLayout(itemProvider = { provider }, modifier = Modifier.fillMaxSize()
            // A gesture belongs to the viewport, not a lazy cell that can be recreated
            // when its index or size changes while the pointer is still down.
            .pointerInput(columns, widthPitch, heightPitch) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled) return@awaitEachGesture
                    val placement = activePlacements.firstOrNull { p ->
                        val left = p.column * widthPitch
                        val top = topOf(p) - scrollOffset
                        down.position.x in left..(left + p.columns * widthPitch - gap) &&
                            down.position.y in top..(top + heightOf(p))
                    } ?: return@awaitEachGesture
                    val widget = activeWidgets.firstOrNull { it.id == placement.id } ?: return@awaitEachGesture
                    val definition = OverviewWidgetDefinitions[widget.type]
                    if (!currentEditing && widget.type in setOf("calendar", "announcements")) {
                        awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        // Cancel the row's click on a long hold; ordinary taps still navigate.
                        do {
                            val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                            changes.forEach { it.consume() }
                        } while (changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    val origin = Offset(placement.column * widthPitch, topOf(placement) - scrollOffset)
                    val handle = with(density) { 48.dp.toPx() }
                    val resize = currentEditing && definition?.multiple == true && widget.config() != null &&
                        down.position.x >= origin.x + placement.columns * widthPitch - gap - handle &&
                        down.position.y >= origin.y + heightOf(placement) - handle
                    if (resize) {
                        down.consume()
                        resizingId = widget.id
                        resizePosition = origin
                        val startSize = widget.size
                        val startPixels = Offset(placement.columns * widthPitch - gap, heightOf(placement))
                        resizeSize = startPixels
                        resizeTarget = startSize
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        try {
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                    .firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                if (!currentEnabled) break
                                val delta = change.position - down.position
                                val sizes = requireNotNull(definition).sizes
                                resizeSize = Offset(
                                    (startPixels.x + delta.x).coerceIn(sizes.minOf { it.columns }.coerceAtMost(columns) * widthPitch - gap,
                                        sizes.maxOf { it.columns }.coerceAtMost(columns) * widthPitch - gap),
                                    (startPixels.y + delta.y).coerceIn(sizes.minOf { it.rows } * heightPitch - gap,
                                        sizes.maxOf { it.rows } * heightPitch - gap),
                                )
                                val desiredColumns = startSize.columns + delta.x / widthPitch
                                val desiredRows = startSize.rows + delta.y / heightPitch
                                val nearest = requireNotNull(definition).sizes.minBy { size ->
                                    val x = size.columns - desiredColumns
                                    val y = size.rows - desiredRows
                                    x * x + y * y
                                }
                                if (nearest != resizeTarget) {
                                    resizeTarget = nearest
                                    activeWidgets.firstOrNull { it.id == widget.id }?.let {
                                        onChangeLatest(it.copy(size = nearest))
                                    }
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                        } finally {
                            resizingId = null
                            resizeTarget = null
                        }
                    } else {
                        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        if (!currentEnabled) return@awaitEachGesture
                        if (!currentEditing) onBeginEditLatest()
                        draggedId = widget.id
                        grabOffset = press.position - origin
                        dragPosition = origin
                        dragSize = Offset(placement.columns * widthPitch - gap, heightOf(placement))
                        pointerPosition = press.position
                        // Freeze reference geometry: moving the placeholder must not
                        // feed its repacked position back into the next reorder.
                        val original = activeWidgets
                        val from = original.indexOfFirst { it.id == widget.id }
                        val centers = activePlacements.map { p ->
                            DragSlot(original.indexOfFirst { it.id == p.id }, false,
                                Offset(p.column * widthPitch + (p.columns * widthPitch - gap) / 2f,
                                    topOf(p) + heightOf(p) / 2f))
                        }
                        val rowStarts = activePlacements.groupBy { it.row }.values.map { row ->
                            val index = row.minOf { p -> original.indexOfFirst { it.id == p.id } }
                            DragSlot((index - if (index > from) 1 else 0).coerceAtLeast(0), true,
                                Offset(0f, topOf(row.first()) - gap / 2f))
                        } + DragSlot(original.lastIndex, true, Offset(0f, activeRowTops.last() - gap / 2f))
                        val dragHalfSize = Offset((placement.columns * widthPitch - gap) / 2f, heightOf(placement) / 2f)
                        val small = placement.columns < columns && definition?.multiple == true
                        val threshold = with(density) { 16.dp.toPx() }
                        var accepted = centers.first { it.index == from }.copy(ownRow = widget.ownRow)
                        updateDrag = {
                            val center = dragPosition + dragHalfSize + Offset(0f, scrollOffset)
                            val rowSlot = if (small) rowStarts.minByOrNull { kotlin.math.abs(it.center.y - center.y) } else null
                            val retainRow = small && accepted.ownRow && accepted.center.x == 0f && kotlin.math.abs(accepted.center.y - center.y) < threshold * 2f
                            val nearest = when {
                                retainRow -> accepted
                                rowSlot != null && kotlin.math.abs(rowSlot.center.y - center.y) < threshold -> rowSlot
                                else -> centers.minBy { (it.center - center).getDistanceSquared() }
                            }
                            val changed = nearest.index != accepted.index || nearest.ownRow != accepted.ownRow
                            val sufficientlyCloser = nearest.ownRow || accepted.ownRow ||
                                (nearest.center - center).getDistance() + threshold < (accepted.center - center).getDistance()
                            if (changed && sufficientlyCloser) {
                                accepted = nearest
                                activeWidgets.firstOrNull { it.id == widget.id }?.let {
                                    if (it.ownRow != nearest.ownRow) onChangeLatest(it.copy(ownRow = nearest.ownRow))
                                }
                                onMoveLatest(widget.id, nearest.index)
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        try {
                            while (true) {
                                val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                                val change = changes.firstOrNull { it.id == press.id } ?: break
                                val delta = change.positionChange()
                                change.consume()
                                if (!change.pressed || !currentEnabled) break
                                val secondary = changes.firstOrNull { it.id != press.id && it.pressed }
                                secondFingerScrolling = secondary != null
                                if (secondary != null) scrollOffset = (scrollOffset - secondary.positionChange().y).coerceIn(0f, maxScroll)
                                changes.filter { it.id != press.id }.forEach { it.consume() }
                                dragPosition += delta
                                pointerPosition = dragPosition + grabOffset
                                updateDrag?.invoke()
                            }
                        } finally {
                            updateDrag = null
                            secondFingerScrolling = false
                            draggedId = null
                        }
                    }
                }
            }
            .scrollable(scroll, Orientation.Vertical)
            .semantics {
                verticalScrollAxisRange = ScrollAxisRange({ scrollOffset.coerceIn(0f, maxScroll) }, { maxScroll })
                scrollBy { _, y -> scope.launch { scroll.scrollBy(-y) }; true }
                collectionInfo = CollectionInfo(placements.maxOfOrNull { it.row + it.rows } ?: 0, columns)
            }, measurePolicy = LazyLayoutMeasurePolicy { constraints ->
                viewportHeight = constraints.maxHeight.toFloat()
                val headerCells = compose(visible.size).map {
                    it.measure(Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth))
                }
                headerHeight = headerCells.maxOfOrNull { it.height } ?: 0
                // Original singleton sections keep their natural content height. There are
                // bounded in number, so measuring them outside the viewport stays bounded.
                val natural = placements.mapIndexedNotNull { index, p ->
                    if (OverviewWidgetDefinitions[visible[index].type]?.multiple != false) null else {
                        val width = (p.columns * widthPitch - gap).roundToInt().coerceAtLeast(1)
                        p.id to compose(index).map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
                    }
                }.toMap()
                val heights = natural.mapValues { (_, cells) -> cells.maxOfOrNull { it.height } ?: 0 }
                if (naturalHeights != heights) naturalHeights = heights
                val (measuredTops, _) = rowGeometry(heights)
                val extent = (measuredTops.lastOrNull() ?: 0f) - gap
                maxScroll = (extent + gap + clearance - constraints.maxHeight).coerceAtLeast(0f)
                val offset = scrollOffset.coerceIn(0f, maxScroll)
                if (scrollOffset != offset) scrollOffset = offset
                val measured = placements.mapIndexedNotNull { index, p ->
                    val top = measuredTops[p.row] - offset
                    val height = heights[p.id]?.toFloat() ?: (p.rows * heightPitch - gap)
                    // Retain the active gesture owner after reordering moves its cell.
                    if (p.id != draggedId && p.id != resizingId && (top + height < -heightPitch || top > constraints.maxHeight + heightPitch)) null else {
                        val cells = natural[p.id] ?: compose(index).map { it.measure(Constraints.fixed(
                            (p.columns * widthPitch - gap).roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1))) }
                        Triple(p, cells, top.roundToInt())
                    }
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    headerCells.forEach { it.placeRelative(0, -offset.roundToInt()) }
                    measured.forEach { (p, cells, top) -> cells.forEach { it.placeRelative((p.column * widthPitch).roundToInt(), top) } }
                }
            })
        if (overlay != null) {
            val resizing = resizingId != null
            OverviewWidgetPreview(
                resizing = resizing,
                position = { if (resizing) resizePosition else dragPosition },
                size = { if (resizing) resizeSize else dragSize },
            ) { content(overlay) }
        }
    }
}

@Composable
private fun OverviewWidgetPreview(
    resizing: Boolean, position: () -> Offset, size: () -> Offset,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // Pointer motion only places this layer; resize recomposes this preview alone.
    val pixels = size()
    Box(Modifier.offset { position().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .size(with(density) { pixels.x.toDp() }, with(density) { pixels.y.toDp() })
        .graphicsLayer { scaleX = if (resizing) 1f else 1.02f; scaleY = scaleX }
        .clearAndSetSemantics { testTag = if (resizing) "overview-resize-preview" else "overview-drag-preview" }
        .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)) {
        content()
    }
}

@Composable
private fun OverviewWidgetHost(
    widget: OverviewWidgetInstance, editing: Boolean, enabled: Boolean,
    widgets: List<OverviewWidgetInstance>, onMove: (String, Int) -> Unit,
    onChange: (OverviewWidgetInstance) -> Unit, onConfigure: (OverviewWidgetInstance) -> Unit,
    onRemove: (String) -> Unit, onDuplicate: (OverviewWidgetInstance) -> Unit,
    content: @Composable (OverviewWidgetInstance) -> Unit,
) {
    val definition = OverviewWidgetDefinitions[widget.type]
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val title = definition?.title ?: "不支援的元件"
    val index = widgets.indexOfFirst { it.id == widget.id }
    val outline by animateColorAsState(if (editing) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        PocketMotion.microVisualState(), label = "編輯元件輪廓")
    val hostShape = when (widget.type) { "schedule" -> PocketVisual.heroShape; "weather", "youbike", "countdown", "date_time" -> PocketVisual.metricShape; else -> MaterialTheme.shapes.large }
    Box(Modifier.fillMaxSize().then(if (definition?.multiple == false) Modifier else Modifier.clip(hostShape))
        .border(2.dp, outline, hostShape)
        .semantics(mergeDescendants = editing) {
            if (editing && enabled) customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction("上移$title") { onMove(widget.id, index - 1); true })
                if (index < widgets.lastIndex) add(CustomAccessibilityAction("下移$title") { onMove(widget.id, index + 1); true })
                if (widget.config() != null) add(CustomAccessibilityAction("設定$title") { onConfigure(widget); true })
                definition?.takeIf { it.multiple }?.sizes?.forEach { size -> add(CustomAccessibilityAction("尺寸 ${size.columns} × ${size.rows}") {
                    onChange(widget.copy(size = size)); true
                }) }
                add(CustomAccessibilityAction("移除$title") { onRemove(widget.id); true })
            }
        }) {
        Box(Modifier.padding(if (editing && definition?.multiple == false && widget.type != "schedule") 8.dp else 0.dp)) { content(widget) }
        if (editing) {
            // Intercepts content navigation while leaving drag gestures on the parent.
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.08f))
                .pointerInput(widget.id, widget.configuration) { detectTapGestures(onTap = {
                    if (widget.config() != null && enabled) onConfigure(widget)
                }) })
            Box(Modifier.align(Alignment.TopEnd)) {
                FilledTonalIconButton(onClick = { menu = true }, enabled = enabled,
                    modifier = Modifier.padding(4.dp)) {
                    OutlinedRoundedSymbol("more_vert", contentDescription = "$title 元件選單")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (widget.config() != null) DropdownMenuItem(text = { Text("設定") }, onClick = { menu = false; onConfigure(widget) })
                    definition?.takeIf { it.multiple }?.sizes?.forEach { size -> DropdownMenuItem(text = { Text("${size.columns} × ${size.rows}" + if (size == widget.size) " ✓" else "") },
                        onClick = { menu = false; onChange(widget.copy(size = size)) }) }
                    if (index > 0) DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMove(widget.id, index - 1) })
                    if (index < widgets.lastIndex) DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMove(widget.id, index + 1) })
                    if (definition?.multiple == true && widget.config() != null) DropdownMenuItem(text = { Text("複製") },
                        onClick = { menu = false; onDuplicate(widget) })
                    DropdownMenuItem(text = { Text("移除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onRemove(widget.id) })
                }
            }
            if (definition?.multiple == true && widget.config() != null) {
                Box(Modifier.align(Alignment.BottomEnd).size(48.dp)
                    .semantics { contentDescription = "調整$title 大小" },
                    contentAlignment = Alignment.BottomEnd) {
                    Text("◢", modifier = Modifier.padding(end = 8.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private data class DragSlot(val index: Int, val ownRow: Boolean, val center: Offset)
