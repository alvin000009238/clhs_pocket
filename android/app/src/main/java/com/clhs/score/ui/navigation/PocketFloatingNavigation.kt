@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import com.clhs.score.ui.FilledRoundedSymbol
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.theme.PocketMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class IndicatorPhase { Idle, Pressed, Dragging, Settling, TapTransition }

/** Geometry is shared by overlay placement and content clearance, never copied into pages. */
internal object PocketNavigationLayout {
    val maxWidth = 304.dp
    val edgePadding = 16.dp
    val bottomOffset = 12.dp
    val clearance = 80.dp
    val expandedHeight = 64.dp
    val compactHeight = 56.dp
}

@Composable
internal fun PocketFloatingNavigation(
    destinations: List<TopLevelDestination>,
    selected: TopLevelDestination,
    hasUnread: Boolean,
    compact: Boolean,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
    onDragSelect: (TopLevelDestination) -> Unit = onSelect,
) {
    val spatial = PocketMotion.selection<Float>()
    val effects = PocketMotion.microVisualState<Float>()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)
    val commitDrag by rememberUpdatedState(onDragSelect)
    var phase by remember { mutableStateOf(IndicatorPhase.Idle) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    var dragPosition by remember { mutableFloatStateOf(destinations.indexOf(selected).toFloat()) }
    var dragOrigin by remember { mutableIntStateOf(destinations.indexOf(selected)) }
    var previewIndex by remember { mutableIntStateOf(dragOrigin) }
    var dragCommit by remember { mutableStateOf<TopLevelDestination?>(null) }
    val visualSelected = if (phase == IndicatorPhase.Dragging) destinations[previewIndex] else selected
    val selection = updateTransition(visualSelected, label = "頂層選取")
    val indicatorPosition = remember { Animatable(destinations.indexOf(selected).toFloat()) }
    val indicatorAlpha = remember { Animatable(1f) }
    val indicatorScale = remember { Animatable(1f) }
    var previousSelection by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (dragCommit == selected) {
            dragCommit = null
            previousSelection = selected
            return@LaunchedEffect
        }
        dragCommit = null
        motionJob?.cancel()
        val interruptedDragPosition = if (phase == IndicatorPhase.Dragging) dragPosition else null
        motionJob = scope.launch {
            interruptedDragPosition?.let { indicatorPosition.snapTo(it) }
            phase = IndicatorPhase.TapTransition
            val target = destinations.indexOf(selected).toFloat()
            val distance = kotlin.math.abs(destinations.indexOf(previousSelection) - target)
            previousSelection = selected
            // Also consider the visible position when a new tap interrupts an unfinished transition.
            val fadeThrough = distance >= 2f || kotlin.math.abs(indicatorPosition.value - target) > 1.5f
            if (fadeThrough) {
                coroutineScope {
                    launch { indicatorAlpha.animateTo(0f, effects) }
                    launch { indicatorScale.animateTo(0.88f, spatial) }
                }
                indicatorPosition.snapTo(target)
            }
            coroutineScope {
                launch { indicatorPosition.animateTo(target, spatial) }
                launch { indicatorAlpha.animateTo(1f, effects) }
                launch { indicatorScale.animateTo(1f, spatial) }
            }
            phase = IndicatorPhase.Idle
        }
    }
    val position = if (phase == IndicatorPhase.Dragging) dragPosition else indicatorPosition.value
    // Distance from the nearest slot gently stretches the same pill while it travels.
    val travel = (kotlin.math.abs(position - kotlin.math.round(position)) * 2f).coerceIn(0f, 1f)
    val verticalPadding by animateDpAsState(if (compact) 2.dp else 6.dp,
        PocketMotion.selection(), label = "導覽內距")
    val toolbarHeight by animateDpAsState(if (compact) PocketNavigationLayout.compactHeight else PocketNavigationLayout.expandedHeight,
        PocketMotion.selection(), label = "導覽高度")
    val indicatorHeight by animateDpAsState(if (compact) 34.dp else 38.dp,
        PocketMotion.selection(), label = "指示器高度")
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier.widthIn(max = PocketNavigationLayout.maxWidth).height(toolbarHeight)
            .dropShadow(
                shape = FloatingToolbarDefaults.ContainerShape,
                shadow = Shadow(
                    radius = 8.dp,
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f),
                    offset = DpOffset(0.dp, 2.dp),
                ),
            ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = verticalPadding),
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            toolbarContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        expandedShadowElevation = 0.dp,
        collapsedShadowElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.widthIn(min = 192.dp, max = 288.dp).fillMaxWidth()) {
            val slot = maxWidth / destinations.size
            val slotPx = with(density) { slot.toPx() }
            fun finishDrag(velocity: Float, cancelled: Boolean) {
                if (phase != IndicatorPhase.Dragging) return
                val targetIndex = if (cancelled) dragOrigin else navigationDragTarget(
                    dragPosition, dragOrigin, velocity / slotPx, destinations.lastIndex,
                )
                val releasedPosition = dragPosition
                motionJob?.cancel()
                motionJob = scope.launch {
                    // Transfer ownership without rendering a frame at the old animated offset.
                    indicatorPosition.snapTo(releasedPosition)
                    indicatorAlpha.snapTo(1f)
                    indicatorScale.snapTo(1f)
                    phase = IndicatorPhase.Settling
                    val target = destinations[targetIndex]
                    if (!cancelled && target != currentSelected) {
                        dragCommit = target
                        commitDrag(target)
                    }
                    indicatorPosition.animateTo(targetIndex.toFloat(), spatial,
                        initialVelocity = (velocity / slotPx).coerceIn(-8f, 8f))
                    phase = IndicatorPhase.Idle
                }
            }
            val pillWidth = (slot - 8.dp) + 6.dp * travel
            Box(Modifier
                .offset {
                    IntOffset((slot * position + (slot - pillWidth) / 2).roundToPx(),
                        ((48.dp - indicatorHeight) / 2).roundToPx())
                }
                .size(pillWidth, indicatorHeight - 2.dp * travel)
                .testTag("PocketNavigationIndicator")
                .graphicsLayer {
                    alpha = if (phase == IndicatorPhase.Dragging) 1f else indicatorAlpha.value
                    scaleX = if (phase == IndicatorPhase.Dragging) 1f else indicatorScale.value
                    scaleY = 1f - (1f - scaleX) / 2f
                }
                .background(MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(indicatorHeight / 2 * (1f - 0.16f * travel))))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                destinations.forEach { destination ->
                    val isSelected = selected == destination
                    val isVisualSelected = visualSelected == destination
                    val interactions = remember { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    LaunchedEffect(pressed) {
                        if (pressed && phase == IndicatorPhase.Idle) phase = IndicatorPhase.Pressed
                        else if (!pressed && phase == IndicatorPhase.Pressed) phase = IndicatorPhase.Idle
                    }
                    val unread = destination == TopLevelDestination.Campus && hasUnread
                    val filled by selection.animateFloat(transitionSpec = { effects }, label = "圖示填色") {
                        if (it == destination) 1f else 0f
                    }
                    Box(Modifier.weight(1f)) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = { PlainTooltip { Text(destination.label) } },
                        state = rememberTooltipState(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(48.dp)
                                .then(if (isSelected) Modifier.pointerInput(slotPx) {
                                    val velocityTracker = VelocityTracker()
                                    detectDragGestures(
                                        orientationLock = Orientation.Horizontal,
                                        onDragStart = { down, _, _ ->
                                            motionJob?.cancel()
                                            dragOrigin = destinations.indexOf(currentSelected)
                                            dragPosition = indicatorPosition.value
                                            previewIndex = dragOrigin
                                            dragCommit = null
                                            velocityTracker.resetTracking()
                                            velocityTracker.addPosition(down.uptimeMillis, down.position)
                                            phase = IndicatorPhase.Dragging
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                                            dragPosition = (dragPosition + delta.x / slotPx)
                                                .coerceIn(0f, destinations.lastIndex.toFloat())
                                            // Hysteresis avoids repeated preview ticks around a midpoint.
                                            val nearest = dragPosition.roundToInt()
                                            if (nearest != previewIndex && abs(dragPosition - previewIndex) > 0.6f) {
                                                previewIndex = nearest
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                            }
                                        },
                                        onDragEnd = { up ->
                                            velocityTracker.addPosition(up.uptimeMillis, up.position)
                                            finishDrag(velocityTracker.calculateVelocity().x, false)
                                        },
                                        onDragCancel = { finishDrag(0f, true) },
                                    )
                                } else Modifier)
                                .selectable(isSelected, onClick = { onSelect(destination) },
                                    role = Role.Tab, interactionSource = interactions, indication = null)
                                .semantics {
                                    contentDescription = destination.label
                                    if (unread) stateDescription = "有未讀內容"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            BadgedBox(badge = {
                                if (unread) Badge(Modifier.clearAndSetSemantics {})
                            }) {
                                Box(Modifier.size(24.dp).graphicsLayer {
                                    scaleX = 1f + 0.04f * filled
                                    scaleY = scaleX
                                }) {
                                    val color = if (isVisualSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    Box(Modifier.graphicsLayer { alpha = 1f - filled }) {
                                        OutlinedRoundedSymbol(destination.icon, tint = color, contentDescription = null)
                                    }
                                    Box(Modifier.graphicsLayer { alpha = filled }) {
                                        FilledRoundedSymbol(destination.icon, tint = color, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

/** Slot-normalized thresholds stay consistent across normal, compact and narrow layouts. */
internal fun navigationDragTarget(position: Float, origin: Int, velocity: Float, lastIndex: Int): Int {
    val displacement = position - origin
    if (abs(displacement) < 0.18f) return origin
    // A deliberate flick can reach the next anchor without dragging past its centre.
    val projected = if (abs(velocity) >= 3f) position + velocity.coerceIn(-8f, 8f) * 0.10f else position
    return projected.roundToInt().coerceIn(0, lastIndex)
}
