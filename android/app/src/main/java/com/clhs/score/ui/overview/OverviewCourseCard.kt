package com.clhs.score.ui.overview

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.domain.overview.OverviewHero
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.ui.theme.PocketMotion
import com.clhs.score.ui.theme.PocketVisual
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeroCard(
    hero: OverviewHero?, now: LocalDateTime, hasSchedule: Boolean,
    needsSetup: Boolean, nextClass: OverviewItem?, onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = PocketVisual.heroShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                hero?.statusText(now) ?: when {
                    needsSetup -> "課表尚未設定"
                    hasSchedule -> "目前沒有待上課程"
                    else -> "暫無可用課表"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (hero == null) {
                Text(
                    when {
                        needsSetup -> "選擇學期與班級後，就能查看現在與下一節課。"
                        hasSchedule -> "目前課表範圍內沒有下一節課。"
                        else -> "前往課表重新查詢，取得最新課程資訊。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Crossfade(targetState = hero.courseName, animationSpec = PocketMotion.stateChange(), label = "課程名稱") { name ->
                    Text(name, style = MaterialTheme.typography.headlineLargeEmphasized,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(listOf(hero.courseLabel, hero.supportingText).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            hero?.courseProgress?.let { progress ->
                LinearWavyProgressIndicator(progress = { progress },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "課程進度" })
            }
            nextClass?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                Text("下一節課 · ${it.title.removePrefix("下一節 ")}", style = MaterialTheme.typography.titleSmall)
                Text(listOf(it.label, it.supportingText.replace(Regex("^\\d{2}:\\d{2}[-–]\\d{2}:\\d{2}( · )?"), ""))
                    .filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (needsSetup && hero == null) Text("設定課表", Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}