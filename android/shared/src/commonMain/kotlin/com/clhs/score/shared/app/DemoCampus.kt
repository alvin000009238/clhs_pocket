package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.domain.overview.OverviewDestination
import com.clhs.score.domain.overview.OverviewItem
import com.clhs.score.domain.overview.OverviewItemKind
import com.clhs.score.domain.overview.OverviewPriority
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.importantAnnouncementsFrom
import com.clhs.score.domain.overview.recentEvents
import com.clhs.score.domain.overview.withPreferences
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.shiftDays

/**
 * Synthetic public campus data expressed in the shared announcement, calendar and
 * overview models, so selection, ordering and countdown text come from shared logic.
 */
internal object DemoCampus {
    private data class Notice(
        val id: String, val title: String, val daysAgo: Int, val unit: String, val category: String,
        val pinned: Boolean = false,
    )

    private val extraNotices = listOf(
        Notice("45061", "第二次段考考場配置與應試注意事項", 2, "教學組", "考試"),
        Notice("45055", "校慶運動會各項競賽報名開始", 4, "體育組", "活動"),
        Notice("45049", "高一多元選修課程加退選說明", 6, "實研組", "課程"),
        Notice("45040", "獎助學金申請收件至月底截止", 9, "註冊組", "獎助學金"),
    )

    /** Announcements a fresh install has not opened yet. */
    val defaultUnreadIds: Set<String> = setOf("45072", "45068", "45055")

    private data class Event(val id: String, val title: String, val offset: Int, val length: Int = 1)

    private val calendar = listOf(
        Event("demo-e1", "高二校外教學", -1, 3),
        Event("demo-e2", "社團成果發表", 3),
        Event("demo-e3", "第二次段考", 8, 2),
        Event("demo-e4", "校慶運動會", 16, 2),
        Event("demo-e5", "期末考", 51, 3),
        Event("demo-e6", "寒假開始", 58),
    )

    val defaultCountdownEventIds: Set<String> = setOf("demo-e3", "demo-e5")

    fun sharedAnnouncements(clock: PocketClock): List<SchoolAnnouncement> = PocketData.current.announcements(clock)

    fun sharedEvents(clock: PocketClock): List<SchoolCalendarEvent> = PocketData.current.events(clock)

    fun demoAnnouncements(clock: PocketClock): List<SchoolAnnouncement> {
        val today = clock.date()
        val seeded = FakeData.announcementPage.announcements.map {
            it.copy(title = it.title.replace("八月", ""), date = today.shiftDays(-1).slashText())
        }
        return seeded + extraNotices.map {
            SchoolAnnouncement(
                id = it.id, title = it.title, date = today.shiftDays(-it.daysAgo.toLong()).slashText(),
                category = it.category, unit = it.unit, issuer = it.unit, isPinned = it.pinned, contentType = "content",
            )
        }
    }

    fun demoEvents(clock: PocketClock): List<SchoolCalendarEvent> {
        val today = clock.date()
        val midnight = localTimeOf(0, 0)
        return calendar.map {
            val start = today.shiftDays(it.offset.toLong())
            SchoolCalendarEvent(it.id, it.title, start.at(midnight), start.shiftDays(it.length.toLong()).at(midnight), isAllDay = true)
        }
    }

    /** The public half of the shared overview state for [clock]. */
    fun overview(clock: PocketClock): OverviewState {
        val now = clock.dateTime()
        val events = sharedEvents(clock)
        return OverviewState(
            calendarEvents = events,
            announcements = sharedAnnouncements(clock),
            now = now,
            isInitialLoading = false,
            items = events.filter { it.endExclusive > now }.map {
                OverviewItem(
                    id = "event-${it.id}", priority = OverviewPriority.P2, kind = OverviewItemKind.CalendarEvent,
                    title = it.title, supportingText = "", label = "行事曆", icon = "event",
                    destination = OverviewDestination.CalendarEvent(it.id), eventTime = it.start,
                    displayDate = it.start.datePart(),
                )
            },
        )
    }

    fun announcementRow(
        item: SchoolAnnouncement, unreadIds: Set<String>, bookmarkedIds: Set<String> = emptySet(),
    ): PocketAnnouncement = PocketAnnouncement(
        id = item.id,
        title = item.title,
        dateText = item.date,
        // Month and day without leading zeros, from either `2026/11/05` or the ISO `2026-11-05`.
        shortDateText = item.date.split('/', '-').let { parts -> if (parts.size > 2) parts.drop(1) else parts }
            .joinToString("/") { it.trimStart('0') },
        unit = item.unit,
        category = item.category,
        isPinned = item.isPinned,
        isUnread = item.id in unreadIds,
        isBookmarked = item.id in bookmarkedIds,
    )

    fun announcements(clock: PocketClock, unreadIds: Set<String> = defaultUnreadIds): List<PocketAnnouncement> =
        sharedAnnouncements(clock).map { announcementRow(it, unreadIds) }

    /** Home list: unread, pinned and bookmarked first, as selected by the shared overview logic. */
    fun importantAnnouncements(
        clock: PocketClock, config: OverviewWidgetConfig.ListItems,
        unreadIds: Set<String> = defaultUnreadIds, bookmarkedIds: Set<String> = emptySet(),
    ): List<PocketAnnouncement> {
        val all = sharedAnnouncements(clock).associateBy { it.id }
        return overview(clock)
            .importantAnnouncementsFrom(config, unreadIds, all.values.filter { it.id in bookmarkedIds })
            .mapNotNull { all[(it.destination as? OverviewDestination.Announcement)?.id] }
            .map { announcementRow(it, unreadIds, bookmarkedIds) }
    }

    fun eventRow(event: SchoolCalendarEvent, today: LocalDate): PocketEventRow {
        val start = event.start.datePart()
        val last = event.endExclusive.datePart().shiftDays(-1)
        val offset = today.daysUntil(start)
        val ongoing = offset <= 0 && today <= last
        val range = if (start.daysUntil(last) > 0) "${start.shortText()} – ${last.shortText()}" else
            "${start.shortText()} 週${start.weekdayLabel()}"
        val relative = when {
            ongoing -> "進行中"
            offset < 0 -> "已結束"
            offset == 1L -> "明天"
            else -> "$offset 天後"
        }
        return PocketEventRow(
            id = event.id,
            monthText = "${start.monthNumber()}月",
            dayText = start.dayNumber().toString(),
            title = event.title,
            subtitle = "$range · $relative",
            isOngoing = ongoing,
        )
    }

    fun upcomingEvents(clock: PocketClock): List<SchoolCalendarEvent> {
        val now = clock.dateTime()
        return sharedEvents(clock).filter { it.endExclusive > now }
    }

    fun events(
        clock: PocketClock, config: OverviewWidgetConfig.ListItems = OverviewWidgetConfig.ListItems(20), shown: Int = config.limit,
    ): List<PocketEventRow> {
        val byId = sharedEvents(clock).associateBy { it.id }
        return overview(clock).recentEvents(config.copy(limit = shown.coerceIn(1, 20)))
            .mapNotNull { byId[(it.destination as? OverviewDestination.CalendarEvent)?.id] }
            .map { eventRow(it, clock.date()) }
    }

    fun countdowns(clock: PocketClock, eventIds: Set<String> = defaultCountdownEventIds): List<PocketCountdown> =
        overview(clock).withPreferences(OverviewPreferences(countdownEventIds = eventIds)).countdowns.map {
            PocketCountdown(it.id, it.label.removeSuffix(" 天"), it.title, it.supportingText.removeSuffix(" 開始"))
        }
}
