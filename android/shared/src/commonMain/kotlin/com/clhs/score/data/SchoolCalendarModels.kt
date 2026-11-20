package com.clhs.score.data

import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDateTime

const val SCHOOL_CALENDAR_ICS_URL =
    "https://calendar.google.com/calendar/ical/document%40clhs.tyc.edu.tw/public/basic.ics"
const val SCHOOL_CALENDAR_WEB_URL =
    "https://calendar.google.com/calendar/u/0/r?cid=document@clhs.tyc.edu.tw"
const val SCHOOL_CALENDAR_SEARCH_MAX_LENGTH = 100

data class SchoolCalendarEvent(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val endExclusive: LocalDateTime,
    val isAllDay: Boolean,
    val location: String? = null,
)

data class SchoolCalendarFeed(
    val events: List<SchoolCalendarEvent>,
    val skippedRecurringEvents: Int = 0,
)

data class SchoolCalendarSnapshot(
    val feed: SchoolCalendarFeed,
    val fetchedAt: Instant,
)
