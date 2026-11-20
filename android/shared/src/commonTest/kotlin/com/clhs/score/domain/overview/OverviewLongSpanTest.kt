package com.clhs.score.domain.overview

import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.parseLocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spans longer than the Long nanosecond range (about 292 years) must not wrap around. */
class OverviewLongSpanTest {
    private fun at(date: String, hour: Int = 0, minute: Int = 0): LocalDateTime =
        parseLocalDate(date).at(localTimeOf(hour, minute))

    @Test
    fun customCountdownFarInTheFutureIsNotExpired() {
        val state = OverviewState(now = at("2026-10-05", 8))
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("百年", "2500-01-01", "12:00"))
        assertEquals("172848 天", widget.countdownItem(state)?.label)
        val past = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("古代", "1500-01-01", "12:00"))
        assertEquals("已到期", past.countdownItem(state)?.label)
    }

    @Test
    fun schoolEventCountdownFarInTheFutureCountsDays() {
        val start = at("2500-01-01", 12)
        val event = SchoolCalendarEvent("far", "遠期活動", start, at("2500-01-01", 13), false)
        val state = OverviewState(now = at("2026-10-05", 8), calendarEvents = listOf(event))
            .withPreferences(com.clhs.score.data.OverviewPreferences(countdownEventIds = setOf("far")))
        assertEquals("172848 天", state.countdowns.single().label)
    }

    @Test
    fun eventsLongerThanTheNanosecondRangeAreStillFilteredAsLong() {
        val start = at("2026-10-01")
        val events = listOf(
            SchoolCalendarEvent("week", "一週活動", start, at("2026-10-08"), true),
            SchoolCalendarEvent("ages", "超長活動", start, at("2400-10-01"), true),
        )
        val items = events.map {
            OverviewItem("calendar-${it.id}", OverviewPriority.P2, OverviewItemKind.CalendarEvent, it.title, "", "",
                "calendar_today", OverviewDestination.CalendarEvent(it.id), eventTime = it.start)
        }
        val state = OverviewState(calendarEvents = events, items = items, now = start)
        assertEquals(listOf("一週活動"), state.recentEvents(OverviewWidgetConfig.ListItems(5, maxEventDurationDays = 7)).map { it.title })
        assertEquals(listOf("一週活動"), state.recentEvents(OverviewWidgetConfig.ListItems(5, maxEventDurationDays = 136_600)).map { it.title })
        assertEquals(2, state.recentEvents(OverviewWidgetConfig.ListItems(5, maxEventDurationDays = 136_601)).size)
        assertEquals(2, state.recentEvents(OverviewWidgetConfig.ListItems(5, maxEventDurationDays = Int.MAX_VALUE)).size)
    }
}
