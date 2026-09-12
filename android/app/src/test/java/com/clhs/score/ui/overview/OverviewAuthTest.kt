package com.clhs.score.ui.overview

import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewPriority
import com.clhs.score.viewmodel.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class OverviewAuthTest {
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
    fun announcementDatesStayUnambiguousAndPreserveUnknownFormats() {
        val today = LocalDate.of(2026, 9, 9)
        assertEquals("教務處 · 今天", compactAnnouncementDate("教務處 · 2026/09/09", today))
        assertEquals("教務處 · 昨天", compactAnnouncementDate("教務處 · 2026-09-08", today))
        assertEquals("教務處 · 9/7", compactAnnouncementDate("教務處 · 2026/09/07", today))
        assertEquals("教務處 · 2025/09/07", compactAnnouncementDate("教務處 · 2025/09/07", today))
        assertEquals("教務處 · 2026/02/30", compactAnnouncementDate("教務處 · 2026/02/30", today))
        assertEquals("教務處", compactAnnouncementDate("教務處", today))
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
