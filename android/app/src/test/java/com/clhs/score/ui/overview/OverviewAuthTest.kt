package com.clhs.score.ui.overview

import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewPriority
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.forGeneration
import com.clhs.score.data.YouBikeState
import com.clhs.score.data.fakeYouBikeSnapshot
import com.clhs.score.viewmodel.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class OverviewAuthTest {
    @Test fun countdownTextKeepsTerminalStatesAndFutureDurationDistinct() {
        val countdown = item(OverviewItemKind.CalendarEvent)
        for (label in listOf("已到期", "已結束", "今天", "進行中")) {
            assertEquals(label, countdown.copy(label = label).countdownText())
        }
        assertEquals("3 天後", countdown.copy(label = "3 天").countdownText())
    }
    @Test
    fun heroStatusUsesCourseTimesAndRoundsPartialMinutesUp() {
        val start = java.time.LocalDateTime.parse("2026-10-05T08:10")
        val hero = com.clhs.score.domain.overview.OverviewHero(
            start.toLocalDate().atStartOfDay(), "接下來", "第 1 節", "國語文", "高二30 · 張三",
            startsAt = start, endsAt = start.plusMinutes(50),
        )
        assertEquals("下一節課 · 3 天後", hero.statusText(start.minusDays(3)))
        assertEquals("下一節課 · 明天", hero.statusText(start.minusDays(1)))
        assertEquals("下一節課 · 42 分鐘後", hero.statusText(start.minusMinutes(42)))
        assertEquals("下一節課 · 1 分鐘後", hero.statusText(start.minusSeconds(1)))
        assertEquals("上課中 · 還有 50 分鐘", hero.statusText(start))
        assertEquals("上課中 · 還有 23 分鐘", hero.statusText(start.plusMinutes(27)))
        assertEquals("上課中 · 還有 1 分鐘", hero.statusText(start.plusMinutes(50).minusSeconds(1)))
        assertEquals("下一節課", hero.copy(startsAt = null, endsAt = null).statusText(start))
    }

    @Test
    fun guestAndAccountGenerationChangesPreservePublicYouBikeSnapshot() {
        val youBike = YouBikeState(fakeYouBikeSnapshot(Instant.parse("2026-10-02T04:43:03Z")))
        val state = OverviewState(youBike = youBike, authGeneration = 1L)
        assertEquals(youBike, state.forGeneration(null).youBike)
        assertEquals(youBike, state.forGeneration(2L).youBike)
    }

    @Test
    fun guestOverviewKeepsPublicItemsAndHidesPrivateItems() {
        val items = listOf(
            item(OverviewItemKind.CurrentClass),
            item(OverviewItemKind.CalendarEvent),
            item(OverviewItemKind.Announcement),
            item(OverviewItemKind.GradeUpdate),
        )

        assertEquals(
            listOf(OverviewItemKind.CalendarEvent, OverviewItemKind.Announcement),
            items.filter { it.isVisibleForAuth(AuthState.Guest) }.map(OverviewItem::kind),
        )
        assertEquals(items, items.filter { it.isVisibleForAuth(AuthState.Authenticated(1L)) })
    }

    @Test
    fun announcementSupportingTextOnlyShowsUnit() {
        assertEquals("教務處", announcementSupportingText("教務處 · 2026/09/09"))
        assertEquals("教務處", announcementSupportingText("教務處 · 2026-09-09 09:07:30"))
        assertEquals("教務處", announcementSupportingText("教務處 · 2026-09-09T18:30:00"))
        assertEquals("教務處", announcementSupportingText("教務處"))
    }
    private fun item(kind: OverviewItemKind) = OverviewItem(
        id = kind.name,
        priority = OverviewPriority.P1,
        kind = kind,
        title = kind.name,
        supportingText = "",
        label = "",
        icon = "",
        destination = if (kind == OverviewItemKind.CalendarEvent) {
            OverviewDestination.Calendar
        } else {
            OverviewDestination.Schedule
        },
    )
}
