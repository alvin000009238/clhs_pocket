package com.clhs.score.ui

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorkManagerInfoTest {
    @Test
    fun groupsAndSortsWorkWhileFormattingScheduleAndAttempts() {
        val infos = listOf(
            item("later", WorkInfo.State.ENQUEUED, 2_000L, 1),
            item("finished", WorkInfo.State.FAILED, 0L, 3),
            item("running", WorkInfo.State.RUNNING, 0L, 2),
            item("blocked", WorkInfo.State.BLOCKED, 1_000L, 1),
        )

        val groups = groupWorkInfos(infos)

        assertEquals(listOf("blocked", "later"), groups.getValue(WorkManagerSection.Scheduled).map { it.id })
        assertEquals("running", groups.getValue(WorkManagerSection.Running).single().id)
        assertEquals(3, groups.getValue(WorkManagerSection.Finished).single().attemptNumber)
        assertEquals("-", formatNextSchedule(0L))
        assertEquals("-", formatNextSchedule(Long.MAX_VALUE))
        val expected = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(1_000L))
        assertEquals(expected, formatNextSchedule(1_000L))
    }

    private fun item(id: String, state: WorkInfo.State, next: Long, attempt: Int) = WorkManagerItem(
        id = id,
        tags = listOf("school_announcement"),
        state = state,
        nextScheduleTimeMillis = next,
        attemptNumber = attempt,
    )
}
