package com.clhs.score.ui.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.clhs.score.data.SchoolCalendarEvent
import java.time.ZoneId
import java.time.ZoneOffset

private val schoolTimeZone = ZoneId.of("Asia/Taipei")
private const val UTC_TIME_ZONE_ID = "UTC"

internal data class CalendarEventIntentTimes(
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZoneId: String,
)

internal fun SchoolCalendarEvent.toCalendarEventIntentTimes(): CalendarEventIntentTimes? {
    if (isAllDay) {
        val beginDate = start.toLocalDate()
        val endDate = endExclusive.toLocalDate()
        if (!endDate.isAfter(beginDate)) return null
        return CalendarEventIntentTimes(
            beginMillis = beginDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            endMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            allDay = true,
            timeZoneId = UTC_TIME_ZONE_ID,
        )
    }

    if (!endExclusive.isAfter(start)) return null
    return CalendarEventIntentTimes(
        beginMillis = start.atZone(schoolTimeZone).toInstant().toEpochMilli(),
        endMillis = endExclusive.atZone(schoolTimeZone).toInstant().toEpochMilli(),
        allDay = false,
        timeZoneId = schoolTimeZone.id,
    )
}

internal fun SchoolCalendarEvent.toCalendarInsertIntent(): Intent? {
    val times = toCalendarEventIntentTimes() ?: return null
    return Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, times.beginMillis)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, times.endMillis)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, times.allDay)
        putExtra(CalendarContract.Events.EVENT_TIMEZONE, times.timeZoneId)
        putExtra(CalendarContract.Events.EVENT_END_TIMEZONE, times.timeZoneId)
        location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    }
}
