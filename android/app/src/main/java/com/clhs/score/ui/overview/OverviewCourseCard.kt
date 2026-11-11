package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.domain.overview.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroCard(
    hero: OverviewHero?, now: LocalDateTime, hasSchedule: Boolean,
    needsSetup: Boolean, nextClass: OverviewItem?, onClick: () -> Unit,
) {
    val currentChanges = hero?.changesFor(hero.courseKey).orEmpty()
    val next = hero?.nextCourse
    val nextChanges = hero?.changesFor(next?.key).orEmpty()
    val consumed = (currentChanges + nextChanges).map { it.id }.toSet()
    val remaining = hero?.scheduleChanges.orEmpty().filterNot { it.id in consumed }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = com.clhs.score.ui.theme.PocketVisual.heroShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(hero?.statusText(now) ?: when {
                        needsSetup -> "課表尚未設定"
                        hasSchedule -> "目前沒有待上課程"
                        else -> "暫無可用課表"
                    }, style = MaterialTheme.typography.labelLarge)
                    hero?.let {
                        val format = DateTimeFormatter.ofPattern("HH:mm")
                        val timing = listOfNotNull(it.startsAt?.format(format), it.endsAt?.format(format)).joinToString("–")
                        if (timing.isNotBlank()) Text(timing, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                    }
                }
                if (hero != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(hero.courseName, style = MaterialTheme.typography.headlineMediumEmphasized)
                            currentChanges.forEach { ChangeBadge(it) }
                        }
                        Text(hero.courseLabel, style = MaterialTheme.typography.labelLarge)
                    }
                    CourseDetails(listOf(hero.classroom, hero.teacherName).filter(String::isNotBlank)
                        .joinToString(" · ").ifBlank { hero.supportingText })
                    currentChanges.filter { it.courseChange?.type == ScheduleChangeType.MODIFIED }
                        .forEach { CourseDetails(it.supportingText) }
                } else {
                    Text(when {
                        needsSetup -> "選擇學期與班級後，就能查看現在與下一節課。"
                        hasSchedule -> "目前課表範圍內沒有下一節課。"
                        else -> "前往課表重新查詢，取得最新課程資訊。"
                    }, style = MaterialTheme.typography.bodyMedium)
                    if (needsSetup) Text("設定課表", style = MaterialTheme.typography.labelLarge)
                }
            }
            val nextName = next?.courseName ?: nextClass?.title?.removePrefix("下一節 ")
            if (nextName != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(listOfNotNull("下一節", next?.courseLabel ?: nextClass?.label).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium)
                        Text(nextName, style = MaterialTheme.typography.titleMedium)
                        nextChanges.forEach { ChangeBadge(it) }
                    }
                    CourseDetails(if (next != null) listOf(next.classroom, next.teacherName).filter(String::isNotBlank).joinToString(" · ")
                        else nextClass?.supportingText.orEmpty())
                    nextChanges.filter { it.courseChange?.type == ScheduleChangeType.MODIFIED }.forEach { CourseDetails(it.supportingText) }
                }
            }
            if (remaining.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val limit = (2 - consumed.size).coerceAtLeast(0)
                    remaining.take(limit).forEach {
                        Text("${it.title} · ${it.supportingText}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (remaining.size > limit) Text("另有 ${remaining.size - limit} 項課程異動", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun CourseDetails(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ChangeBadge(item: OverviewItem) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Text(when (item.courseChange?.type) {
            ScheduleChangeType.ADDED -> "新增"
            ScheduleChangeType.MODIFIED -> "調課"
            ScheduleChangeType.REMOVED -> "停課"
            null -> "異動"
        }, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}
