package com.clhs.score.ui.calendar

import com.clhs.score.data.SchoolCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CalendarAgendaTest {
    @Test
    fun eventPositionIncludesSearchWarningAndEveryDateHeading() {
        val date = LocalDate.of(2026, 9, 12)
        fun event(id: String, day: LocalDate) = SchoolCalendarEvent(
            id, id, day.atStartOfDay(), day.plusDays(1).atStartOfDay(), true,
        )
        val groups = linkedMapOf(
            date to listOf(event("first", date), event("second", date)),
            date.plusDays(1) to listOf(event("target", date.plusDays(1))),
        )
        assertEquals(2, calendarEventListIndex(groups, "first", false))
        assertEquals(3, calendarEventListIndex(groups, "second", false))
        assertEquals(5, calendarEventListIndex(groups, "target", false))
        assertEquals(6, calendarEventListIndex(groups, "target", true))
        assertNull(calendarEventListIndex(groups, "removed", true))
        assertNull(calendarEventListIndex(emptyMap(), "target", false))
    }
}
