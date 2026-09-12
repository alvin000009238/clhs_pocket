package com.clhs.score.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal enum class WorkManagerSection(val label: String) {
    Scheduled("排程中"), Running("執行中"), Finished("已完成"),
}

internal data class WorkManagerItem(
    val id: String,
    val tags: List<String>,
    val state: WorkInfo.State,
    val nextScheduleTimeMillis: Long,
    val attemptNumber: Int,
)

internal fun WorkInfo.toWorkManagerItem() = WorkManagerItem(
    id = id.toString(),
    tags = tags.sorted(),
    state = state,
    nextScheduleTimeMillis = nextScheduleTimeMillis,
    attemptNumber = runAttemptCount + 1,
)

internal fun groupWorkInfos(
    infos: List<WorkManagerItem>,
): Map<WorkManagerSection, List<WorkManagerItem>> {
    val groups = infos.groupBy { info ->
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WorkManagerSection.Scheduled
            WorkInfo.State.RUNNING -> WorkManagerSection.Running
            else -> WorkManagerSection.Finished
        }
    }
    return WorkManagerSection.entries.associateWith { section ->
        groups[section].orEmpty().sortedWith(
            compareBy<WorkManagerItem> {
                if (it.nextScheduleTimeMillis in 1 until Long.MAX_VALUE) {
                    it.nextScheduleTimeMillis
                } else {
                    Long.MAX_VALUE
                }
            }.thenBy(WorkManagerItem::id),
        )
    }
}

@Composable
fun WorkManagerInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context.applicationContext) }
    val query = remember { WorkQuery.fromStates(WorkInfo.State.entries) }
    val infos by workManager.getWorkInfosFlow(query).collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = remember(infos) { groupWorkInfos(infos.map(WorkInfo::toWorkManagerItem)) }

    SubpageLayout(title = "執行資訊", onBack = onBack) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WorkManagerSection.entries.forEach { section ->
                item { Text(section.label, style = MaterialTheme.typography.titleMedium) }
                val sectionInfos = groups[section].orEmpty()
                if (sectionInfos.isEmpty()) {
                    item { Text("-", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(sectionInfos, key = { it.id }) { info -> WorkInfoCard(info) }
                }
            }
        }
    }
}

@Composable
private fun WorkInfoCard(info: WorkManagerItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Text(
            text = buildString {
                appendLine("Id: ${info.id}")
                appendLine("Tags:")
                info.tags.forEach { appendLine("- $it") }
                appendLine("State: ${info.state.name}")
                appendLine("Next scheduled run: ${formatNextSchedule(info.nextScheduleTimeMillis)}")
                append("Attempt #${info.attemptNumber}")
            },
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun formatNextSchedule(timestampMillis: Long): String =
    if (timestampMillis !in 1 until Long.MAX_VALUE) {
        "-"
    } else {
        WORK_TIME_FORMAT.format(Instant.ofEpochMilli(timestampMillis))
    }

private val WORK_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
