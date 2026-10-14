package com.clhs.score.ui.overview

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.data.YouBikeState
import com.clhs.score.domain.overview.SCHOOL_ZONE
import com.clhs.score.ui.hapticClick
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.ui.theme.PocketVisual
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun YouBikeCard(
    state: YouBikeState, now: Instant, onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    stationName: String = "YouBike", compact: Boolean = false,
    editing: Boolean = false, expanded: Boolean = true,
) {
    Surface(onClick = hapticClick(onRefresh), enabled = !editing && !state.isRefreshing, modifier = modifier.fillMaxWidth(), shape = PocketVisual.metricShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (expanded) 16.dp else 8.dp),
            verticalArrangement = if (expanded) Arrangement.SpaceBetween else Arrangement.spacedBy(4.dp)) {
            val snapshot = state.snapshot
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(stationName, Modifier.weight(1f).padding(end = if (editing) 40.dp else 0.dp),
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (state.isManuallyRefreshing && state.isRefreshing && !editing) {
                    LoadingIndicator(Modifier.size(24.dp).clearAndSetSemantics { contentDescription = "正在更新 YouBike" })
                }
                if (!expanded && snapshot != null && (state.failed || snapshot.isStale(now))) {
                    Text(if (state.failed) "更新失敗" else "資料過期", style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, softWrap = false)
                }
            }
            if (snapshot == null) {
                if (state.failed) Text("無法取得資料，點擊重試",
                    style = MaterialTheme.typography.bodySmall)
                else YouBikeLoadingSkeleton(expanded)
            } else if (!snapshot.isOperating) {
                Text("暫停營運", style = MaterialTheme.typography.titleMedium)
            } else {
                val stale = snapshot.isStale(now)
                val rows = listOf(listOf("可借車" to snapshot.availableBikes, (if (compact) "可還車" else "可還車位") to snapshot.emptyDocks)) +
                    if (expanded) listOf(listOf("一般" to snapshot.ordinaryBikes, "電輔車" to snapshot.electricBikes)) else emptyList()
                rows.forEach { metrics ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.forEach { (label, count) ->
                        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "$label ${count ?: "無資料"}" },
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Crossfade(count, animationSpec = PocketMotion.stateChange(), label = label) { number ->
                                Text(number?.toString() ?: "—", style = if (expanded) MaterialTheme.typography.displaySmallEmphasized
                                    else MaterialTheme.typography.titleLargeEmphasized,
                                    maxLines = 1, softWrap = false)
                            }
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    }
                }
                if (!expanded) {
                    Text(if (snapshot.ordinaryBikes != null && snapshot.electricBikes != null)
                        "一般 ${snapshot.ordinaryBikes} · 電輔 ${snapshot.electricBikes}" else "車種明細暫時無法取得",
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = "一般車 ${snapshot.ordinaryBikes ?: "無資料"}，電輔車 ${snapshot.electricBikes ?: "無資料"}"
                        }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    val age = Duration.between(snapshot.updatedAt, now)
                    Text(when {
                        state.failed -> "更新失敗，顯示上次資料"
                        stale -> "資料可能已過期"
                        age.toMinutes() == 0L -> "剛剛更新"
                        else -> "更新於 ${snapshot.updatedAt.atZone(SCHOOL_ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))}"
                    }, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
