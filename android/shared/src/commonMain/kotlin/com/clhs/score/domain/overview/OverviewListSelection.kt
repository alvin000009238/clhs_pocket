package com.clhs.score.domain.overview

import com.clhs.score.shared.time.LOCAL_DATE_TIME_ORDER
import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.NANOS_PER_DAY
import com.clhs.score.shared.time.NANOS_PER_HOUR
import com.clhs.score.shared.time.NANOS_PER_MINUTE
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.hourMinuteText
import com.clhs.score.shared.time.isValidTimeZoneId
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.nanosUntil
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.parseLocalTime
import com.clhs.score.shared.time.randomUuidString
import com.clhs.score.shared.time.schoolNow
import com.clhs.score.shared.time.shiftNanos
import com.clhs.score.shared.time.timePart
import com.clhs.score.shared.time.yearNumber
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementPage

fun OverviewState.importantAnnouncementsFrom(
    config: OverviewWidgetConfig.ListItems,
    unreadIds: Set<String>,
    bookmarked: List<SchoolAnnouncement>,
): List<OverviewItem> {
    val bookmarkedIds = bookmarked.map { it.id }.toSet()
    val candidates = (announcements + bookmarked).distinctBy { it.id }
    fun matches(id: String, pinned: Boolean, priority: AnnouncementPriority): Boolean = when (priority) {
        AnnouncementPriority.Unread -> id in unreadIds
        AnnouncementPriority.Pinned -> pinned
        AnnouncementPriority.Bookmarked -> id in bookmarkedIds
    }
    val comparator = config.announcementPriority.fold(compareBy<SchoolAnnouncement> { 0 }) { order, priority ->
        order.thenByDescending { matches(it.id, it.isPinned, priority) }
    }.thenByDescending { parseLocalDateOrNull(it.date.replace('/', '-'))?.epochDay() ?: Long.MIN_VALUE }
    return candidates.filter { item -> config.announcementPriority.any { matches(item.id, item.isPinned, it) } }
        .sortedWith(comparator).take(config.limit).map(::announcementOverviewItem)
}

fun OverviewState.recentEvents(config: OverviewWidgetConfig.ListItems): List<OverviewItem> {
    val eventsById = calendarEvents.associateBy { it.id }
    return items.asSequence().filter { it.kind == OverviewItemKind.CalendarEvent }
        .filter { item ->
            val maximum = config.maxEventDurationDays ?: return@filter true
            val event = eventsById[(item.destination as? OverviewDestination.CalendarEvent)?.id] ?: return@filter true
            event.start.nanosUntil(event.endExclusive) <= maximum * NANOS_PER_DAY
        }.sortedWith(compareBy(nullsFirst(LOCAL_DATE_TIME_ORDER)) { it.eventTime }).take(config.limit).toList()
}

fun announcementOverviewItems(page: SchoolAnnouncementPage): List<OverviewItem> =
    page.announcements
        .asSequence()
        .filter { it.isPinned }
        .map(::announcementOverviewItem)
        .toList()

fun announcementOverviewItem(announcement: SchoolAnnouncement): OverviewItem =
    OverviewItem(
        id = "announcement-${announcement.id}",
        priority = OverviewPriority.P2,
        kind = OverviewItemKind.Announcement,
        title = announcement.title,
        supportingText = listOf(announcement.unit, announcement.date).filter(String::isNotBlank).joinToString(" · "),
        label = if (announcement.isPinned) "置頂公告" else "公告",
        displayDate = parseLocalDateOrNull(announcement.date.replace('/', '-')),
        icon = "campaign",
        destination = OverviewDestination.Announcement(announcement.id, announcement.category, announcement.externalUrl),
        order = 60,
    )
