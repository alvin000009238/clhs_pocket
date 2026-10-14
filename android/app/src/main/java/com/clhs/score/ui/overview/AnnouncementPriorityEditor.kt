package com.clhs.score.ui.overview

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.clhs.score.domain.overview.AnnouncementPriority
import com.clhs.score.ui.OutlinedRoundedSymbol

@Composable
internal fun AnnouncementPriorityEditor(order: List<AnnouncementPriority>, onChange: (List<AnnouncementPriority>) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<AnnouncementPriority, Int>() }
    val spacing = with(LocalDensity.current) { 8.dp.toPx() }
    var dragged by remember { mutableStateOf<AnnouncementPriority?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { index, priority ->
            key(priority) {
                var offset by remember { mutableFloatStateOf(0f) }
                fun move(direction: Int) {
                    val reordered = order.toMutableList()
                    reordered.add(index + direction, reordered.removeAt(index))
                    onChange(reordered)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { heights[priority] = it.height }
                        .zIndex(if (dragged == priority) 1f else 0f).graphicsLayer { translationY = offset },
                    shape = MaterialTheme.shapes.medium,
                    color = if (dragged == priority) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).semantics {
                            contentDescription = "拖曳調整${priority.title}優先順序"
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("上移") { move(-1); true })
                                if (index < order.lastIndex) add(CustomAccessibilityAction("下移") { move(1); true })
                            }
                        }.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                offset += delta
                                val direction = if (offset > 0) 1 else -1
                                val neighbor = order.getOrNull(index + direction)
                                if (neighbor == null) offset = 0f else {
                                    val neighborHeight = heights[neighbor] ?: 0
                                    val distance = ((heights[priority] ?: 0) + neighborHeight) / 2f + spacing
                                    if (offset * direction >= distance) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        move(direction)
                                        offset -= direction * (neighborHeight + spacing)
                                    }
                                }
                            },
                            onDragStarted = { dragged = priority; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onDragStopped = { dragged = null; offset = 0f },
                        ), contentAlignment = Alignment.Center) {
                            OutlinedRoundedSymbol("drag_indicator", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${index + 1}  ${priority.title}", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
