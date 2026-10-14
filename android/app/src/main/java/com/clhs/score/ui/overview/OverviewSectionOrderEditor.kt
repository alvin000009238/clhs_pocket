package com.clhs.score.ui.overview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.hapticToggle
import com.clhs.score.ui.theme.PocketMotion

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OverviewSectionOrderEditor(
    preferences: OverviewPreferences,
    enabled: Boolean,
    onChange: (OverviewPreferences) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val heights = remember { mutableStateMapOf<String, Int>() }
    val spacing = with(LocalDensity.current) { 8.dp.toPx() }
    var activeDraggedSection by remember { mutableStateOf<String?>(null) }
    val order = preferences.orderedSections()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(order, key = { it }) { section ->
            val title = when (section) {
                "schedule" -> "課表摘要"
                "weather" -> "天氣"
                "youbike" -> "YouBike"
                "alerts" -> "課程與成績異動"
                "countdown" -> "倒數"
                "calendar" -> "近期行程"
                else -> "重要公告"
            }
            val dragging = activeDraggedSection == section
            var offset by remember { mutableFloatStateOf(0f) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .animateItem(
                        fadeInSpec = null,
                        placementSpec = if (activeDraggedSection != null && !dragging) {
                            PocketMotion.selection()
                        } else {
                            null
                        },
                        fadeOutSpec = null,
                    )
                    .onSizeChanged { heights[section] = it.height }
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = offset }
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = "拖曳調整$title 順序"
                            customActions = if (!enabled) emptyList() else buildList {
                                if (order.first() != section) {
                                    add(CustomAccessibilityAction("上移") {
                                        onChange(preferences.moveSection(section, -1))
                                        true
                                    })
                                }
                                if (order.last() != section) {
                                    add(CustomAccessibilityAction("下移") {
                                        onChange(preferences.moveSection(section, 1))
                                        true
                                    })
                                }
                            }
                        }
                        .draggable(
                            orientation = Orientation.Vertical,
                            enabled = enabled,
                            state = rememberDraggableState { delta ->
                                offset += delta
                                val direction = if (offset > 0) 1 else -1
                                val neighbor = order.getOrNull(order.indexOf(section) + direction)
                                if (neighbor == null) {
                                    offset = 0f
                                } else {
                                    val neighborHeight = heights[neighbor] ?: 0
                                    val distance = ((heights[section] ?: 0) + neighborHeight) / 2f + spacing
                                    if (offset * direction >= distance) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onChange(preferences.moveSection(section, direction))
                                        offset -= direction * (neighborHeight + spacing)
                                    }
                                }
                            },
                            onDragStarted = {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                activeDraggedSection = section
                            },
                            onDragStopped = {
                                activeDraggedSection = null
                                offset = 0f
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedRoundedSymbol("drag_indicator", tint = tint)
                }
                Text(title, Modifier.weight(1f))
                Switch(
                    checked = preferences.shows(section),
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = title },
                    onCheckedChange = hapticToggle { checked ->
                        onChange(
                            preferences.copy(
                                hiddenSections = if (checked) {
                                    preferences.hiddenSections - section
                                } else {
                                    preferences.hiddenSections + section
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}
