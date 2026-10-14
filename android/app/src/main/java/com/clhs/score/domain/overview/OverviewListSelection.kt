package com.clhs.score.domain.overview

import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.SchoolAnnouncement
import java.time.Duration
import java.time.LocalDate

internal fun OverviewState.importantAnnouncements(
    config: OverviewWidgetConfig.ListItems,
    unreadIds: Set<String>,
    bookmarks: List<AnnouncementBookmark>,
): List<OverviewItem> {
    val bookmarkedIds = bookmarks.map { it.id }.toSet()
    val candidates = (announcements + bookmarks.map { it.toAnnouncement() }).distinctBy { it.id }
    fun matches(id: String, pinned: Boolean, priority: AnnouncementPriority): Boolean = when (priority) {
        AnnouncementPriority.Unread -> id in unreadIds
        AnnouncementPriority.Pinned -> pinned
        AnnouncementPriority.Bookmarked -> id in bookmarkedIds
    }
    val comparator = config.announcementPriority.fold(compareBy<SchoolAnnouncement> { 0 }) { order, priority ->
        order.thenByDescending { matches(it.id, it.isPinned, priority) }
    }.thenByDescending { runCatching { LocalDate.parse(it.date.replace('/', '-')) }.getOrNull() ?: LocalDate.MIN }
    return candidates.filter { item -> config.announcementPriority.any { matches(item.id, item.isPinned, it) } }
        .sortedWith(comparator).take(config.limit).map(::announcementOverviewItem)
}

internal fun OverviewState.recentEvents(config: OverviewWidgetConfig.ListItems): List<OverviewItem> {
    val eventsById = calendarEvents.associateBy { it.id }
    return items.asSequence().filter { it.kind == OverviewItemKind.CalendarEvent }
        .filter { item ->
            val maximum = config.maxEventDurationDays ?: return@filter true
            val event = eventsById[(item.destination as? OverviewDestination.CalendarEvent)?.id] ?: return@filter true
            Duration.between(event.start, event.endExclusive) <= Duration.ofDays(maximum.toLong())
        }.sortedBy { it.eventTime }.take(config.limit).toList()
}
