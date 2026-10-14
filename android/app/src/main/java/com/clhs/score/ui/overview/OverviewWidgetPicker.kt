@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.clhs.score.domain.overview.*
import com.clhs.score.ui.OutlinedRoundedSymbol
import java.time.LocalDate

@Composable
internal fun OverviewWidgetPicker(
    widgets: List<OverviewWidgetInstance>,
    onChoose: (OverviewWidgetInstance) -> Unit,
    onDismiss: () -> Unit,
    showNewWidgetBadge: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))) {
        LazyColumn(Modifier.fillMaxWidth().widthIn(max = 640.dp).align(Alignment.CenterHorizontally),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("打造你的首頁", style = MaterialTheme.typography.headlineMedium)
                Text("選擇元件，再設定內容與尺寸", Modifier.padding(top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(OverviewWidgetDefinitions.all, key = { it.type }) { definition ->
                val existing = widgets.firstOrNull { it.type == definition.type }
                val available = definition.multiple || existing == null || !existing.enabled
                val accent = when (definition.type) {
                    "weather", "countdown" -> MaterialTheme.colorScheme.tertiaryContainer
                    "youbike", "calendar" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                Surface(onClick = {
                    onChoose(existing?.takeIf { !it.enabled } ?: OverviewWidgetInstance.create(definition.type,
                        when (definition.type) {
                            "calendar" -> OverviewWidgetConfig.ListItems(3)
                            "announcements" -> OverviewWidgetConfig.ListItems(2)
                            "countdown" -> OverviewWidgetConfig.CustomCountdown("", LocalDate.now(SCHOOL_ZONE).toString())
                            else -> OverviewWidgetConfig.None
                        }))
                }, enabled = available, shape = MaterialTheme.shapes.extraLarge,
                    color = if (available) accent else MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().semantics {
                        if (showNewWidgetBadge && definition.type == "youbike") stateDescription = "新元件"
                    }) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedRoundedSymbol(definition.icon, contentDescription = null, size = 32.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BadgedBox(badge = { if (showNewWidgetBadge && definition.type == "youbike") Badge() }) {
                                Text(definition.title, style = MaterialTheme.typography.titleLarge)
                            }
                            Text(when (definition.type) {
                                "schedule" -> "下一節課與今日課程"
                                "weather" -> "溫度、降雨與天氣預報"
                                "youbike" -> "常用站點的可借車與空位"
                                "countdown" -> "讓重要日子一目了然"
                                "alerts" -> "掌握調課與成績更新"
                                "calendar" -> "近期校園行程"
                                else -> "關注學校的重要消息"
                            }, style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis)
                            if (!available) Text("已加入", style = MaterialTheme.typography.labelMedium)
                            else if (existing?.enabled == false) Text("重新加入", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedRoundedSymbol(if (available) "add" else "check", contentDescription = null)
                    }
                }
            }
        }
    }
}
