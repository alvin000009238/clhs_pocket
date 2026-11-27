package com.clhs.score.shared.app

import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.AnnouncementCategory
import com.clhs.score.data.AnnouncementFilterOptions
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.timePart
import com.clhs.score.shared.time.hourMinuteText
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.datePart

/** A calendar event as the native detail sheet shows it. */
data class PocketEventDetail(
    val id: String,
    val title: String,
    val dateText: String,
    val timeText: String,
    val location: String?,
    val isAllDay: Boolean,
    val relativeText: String,
)

/**
 * Paged school announcements for the native list, mirroring the Android
 * `SchoolAnnouncementsViewModel` rules: pages are appended without duplicates, the
 * search text is trimmed and capped, and another page exists while
 * `pageIndex + 1 < totalPages`. Demo mode serves a deterministic multi-page archive.
 */
object PocketAnnouncementFeed {
    const val DEMO_PAGE_SIZE = 10
    const val ALL_ID = ALL_UNITS_ID
    const val SCHOOL_WEBSITE_URL = "https://www.clhs.tyc.edu.tw/"
    const val SCHOOL_ANNOUNCEMENTS_URL = "https://www.clhs.tyc.edu.tw/ischool/publish_page/0/"
    const val SCHOOL_CALENDAR_URL = "https://calendar.google.com/calendar/u/0/r?cid=document@clhs.tyc.edu.tw"
    val reminderIntervalsMinutes: List<Int> = listOf(30, 60, 180, 360, 720, 1_440)
    const val DEFAULT_REMINDER_INTERVAL_MINUTES = 60

    private data class ArchiveNotice(val title: String, val unitId: String, val category: String)

    private val demoUnits = listOf(
        AnnouncementUnit(ALL_UNITS_ID, "全部"),
        AnnouncementUnit("101", "教學組"),
        AnnouncementUnit("102", "註冊組"),
        AnnouncementUnit("103", "設備組"),
        AnnouncementUnit("104", "實研組"),
        AnnouncementUnit("201", "學務處"),
        AnnouncementUnit("202", "體育組"),
        AnnouncementUnit("203", "衛生組"),
        AnnouncementUnit("301", "總務處"),
        AnnouncementUnit("401", "輔導室"),
        AnnouncementUnit("501", "讀者服務組"),
    )

    private val demoCategories = listOf(
        AnnouncementCategory(ALL_UNITS_ID, "全部"),
        AnnouncementCategory("1", "公告"),
        AnnouncementCategory("2", "最新消息"),
        AnnouncementCategory("3", "考試"),
        AnnouncementCategory("4", "活動"),
        AnnouncementCategory("5", "課程"),
        AnnouncementCategory("6", "獎助學金"),
        AnnouncementCategory("7", "升學"),
        AnnouncementCategory("8", "競賽"),
    )

    private val archive = listOf(
        ArchiveNotice("高三學測模擬考成績查詢說明", "101", "考試"),
        ArchiveNotice("冬季制服換季提醒", "201", "公告"),
        ArchiveNotice("科學班專題研究成果發表會", "104", "活動"),
        ArchiveNotice("校園環境消毒作業通知", "203", "公告"),
        ArchiveNotice("大學個人申請校內說明會", "401", "升學"),
        ArchiveNotice("全國高中英文作文比賽校內初賽", "101", "競賽"),
        ArchiveNotice("班級學習歷程檔案上傳期限", "102", "課程"),
        ArchiveNotice("校內游泳池開放時段調整", "202", "公告"),
        ArchiveNotice("清寒學生助學金申請", "201", "獎助學金"),
        ArchiveNotice("電腦教室設備維護停用公告", "103", "公告"),
        ArchiveNotice("圖書館新書展與閱讀心得徵文", "501", "活動"),
        ArchiveNotice("自主學習計畫期中檢核", "104", "課程"),
        ArchiveNotice("高一新生健康檢查補檢通知", "203", "公告"),
        ArchiveNotice("第一次段考補考名單與時間", "101", "考試"),
        ArchiveNotice("學生自治會幹部選舉公告", "201", "最新消息"),
        ArchiveNotice("校園停電與供水暫停通知", "301", "公告"),
        ArchiveNotice("生涯輔導講座：認識大學學群", "401", "升學"),
        ArchiveNotice("全市中學生數理能力競賽報名", "104", "競賽"),
        ArchiveNotice("轉學生編班與選課結果", "102", "課程"),
        ArchiveNotice("班際籃球賽賽程公告", "202", "活動"),
        ArchiveNotice("校外獎學金彙整（十月）", "102", "獎助學金"),
        ArchiveNotice("期中教學意見調查開放填答", "101", "最新消息"),
        ArchiveNotice("交通安全宣導週活動", "201", "活動"),
        ArchiveNotice("實驗室安全講習必修場次", "103", "課程"),
        ArchiveNotice("心理健康篩檢施測說明", "401", "公告"),
        ArchiveNotice("圖書館電子資源使用講習", "501", "課程"),
        ArchiveNotice("校園工程施工動線調整", "301", "公告"),
        ArchiveNotice("高二分組選課說明會", "101", "升學"),
        ArchiveNotice("運動代表隊集訓公假名單", "202", "最新消息"),
        ArchiveNotice("學生平安保險理賠申請方式", "301", "公告"),
        ArchiveNotice("國際交流計畫甄選公告", "201", "活動"),
        ArchiveNotice("多元表現成果彙整注意事項", "102", "升學"),
        ArchiveNotice("午餐供應商滿意度調查", "203", "最新消息"),
        ArchiveNotice("全國科展校內複審結果", "104", "競賽"),
        ArchiveNotice("暑期輔導課程繳費說明", "102", "課程"),
        ArchiveNotice("清潔區域分配與評分方式", "203", "公告"),
        ArchiveNotice("家庭教育講座報名", "401", "活動"),
        ArchiveNotice("開學註冊須知", "102", "公告"),
        ArchiveNotice("教科書訂購與發放時間", "301", "課程"),
        ArchiveNotice("新學期校園安全提醒", "201", "最新消息"),
    )

    private fun archiveAnnouncements(clock: PocketClock): List<SchoolAnnouncement> {
        val today = clock.date()
        val names = demoUnits.associate { it.id to it.name }
        return archive.mapIndexed { index, notice ->
            val unit = names.getValue(notice.unitId)
            SchoolAnnouncement(
                id = (44990 - index * 7).toString(),
                title = notice.title,
                date = today.shiftDays(-(12L + index * 3L)).slashText(),
                category = notice.category,
                unit = unit,
                issuer = unit,
                isPinned = false,
                contentType = "content",
            )
        }
    }

    /** Every demo announcement in the order the school lists them: pinned first, newest first. */
    private fun demoFeed(clock: PocketClock): List<SchoolAnnouncement> =
        (DemoCampus.sharedAnnouncements(clock) + archiveAnnouncements(clock))
            .distinctBy { it.id }
            .sortedWith(compareByDescending<SchoolAnnouncement> { it.isPinned }.thenByDescending { it.date.replace('-', '/') })

    fun demoFilterOptions(): AnnouncementFilterOptions = AnnouncementFilterOptions(demoUnits, demoCategories)

    /** One page of the demo archive, filtered the way the school's query endpoint filters. */
    fun demoPage(
        clock: PocketClock, pageIndex: Int, keyword: String, unitId: String, categoryId: String,
    ): SchoolAnnouncementPage {
        val words = normalizedQuery(keyword).lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val unit = demoUnits.firstOrNull { it.id == unitId && it.id != ALL_UNITS_ID }?.name
        val category = demoCategories.firstOrNull { it.id == categoryId && it.id != ALL_UNITS_ID }?.name
        val matching = demoFeed(clock)
            .filter { unit == null || it.unit == unit }
            .filter { category == null || it.category == category }
            .filter { item -> "${item.title} ${item.unit}".lowercase().let { text -> words.all { it in text } } }
        val totalPages = maxOf(1, (matching.size + DEMO_PAGE_SIZE - 1) / DEMO_PAGE_SIZE)
        val page = pageIndex.coerceAtLeast(0)
        return SchoolAnnouncementPage(
            announcements = matching.drop(page * DEMO_PAGE_SIZE).take(DEMO_PAGE_SIZE),
            pageIndex = page,
            totalPages = totalPages,
            fetchedAt = clock.instant(),
        )
    }

    /** A demo announcement from any page, for the detail screen. */
    fun demoAnnouncement(clock: PocketClock, id: String): SchoolAnnouncement? =
        if (PocketData.isDemo) demoFeed(clock).firstOrNull { it.id == id } else null

    fun normalizedQuery(query: String): String = query.trim().take(SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH)

    fun isValidFilterId(id: String): Boolean = id == ALL_UNITS_ID || (id.isNotEmpty() && id.all { it.isDigit() })

    fun fetchedAtMillis(page: SchoolAnnouncementPage): Long = page.fetchedAt.epochMillis()

    fun hasMore(page: SchoolAnnouncementPage): Boolean = page.pageIndex + 1 < page.totalPages

    /** [existing] followed by the new page, each announcement once. */
    fun append(existing: List<SchoolAnnouncement>, incoming: List<SchoolAnnouncement>): List<SchoolAnnouncement> =
        (existing + incoming).distinctBy { it.id }

    fun rows(
        items: List<SchoolAnnouncement>, unreadIds: Set<String>, bookmarkedIds: Set<String>,
    ): List<PocketAnnouncement> = items.map { DemoCampus.announcementRow(it, unreadIds, bookmarkedIds) }

    /** Bookmarked announcements known from the current list, loaded pages or the saved copies. */
    fun bookmarks(
        clock: PocketClock, loaded: List<SchoolAnnouncement>, unreadIds: Set<String>, bookmarkedIds: Set<String>,
    ): List<PocketAnnouncement> {
        val known = (DemoCampus.sharedAnnouncements(clock) + loaded + PocketData.current.savedAnnouncements +
            (if (PocketData.isDemo) demoFeed(clock) else emptyList()))
            .distinctBy { it.id }
        return known.filter { it.id in bookmarkedIds }
            .sortedByDescending { it.date.replace('-', '/') }
            .map { DemoCampus.announcementRow(it, unreadIds, bookmarkedIds) }
    }

    /** Upcoming calendar events whose title or location contains every search word. */
    fun searchEvents(clock: PocketClock, query: String): List<PocketEventRow> {
        val words = normalizedQuery(query).lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return DemoCampus.upcomingEvents(clock)
            .sortedWith { a, b -> a.start.compareTo(b.start) }
            .filter { event -> "${event.title} ${event.location.orEmpty()}".lowercase().let { text -> words.all { it in text } } }
            .map { DemoCampus.eventRow(it, clock.date(), clock.dateTime()) }
    }

    fun eventDetail(clock: PocketClock, id: String): PocketEventDetail? {
        val event = DemoCampus.sharedEvents(clock).firstOrNull { it.id == id } ?: return null
        val row = DemoCampus.eventRow(event, clock.date(), clock.dateTime())
        val start = event.start.datePart()
        val last = DemoCampus.lastDay(event)
        val dateText = if (start == last) "${start.slashText()} 週${start.weekdayLabel()}" else
            "${start.slashText()} 週${start.weekdayLabel()} – ${last.slashText()} 週${last.weekdayLabel()}"
        return PocketEventDetail(
            id = event.id,
            title = event.title,
            dateText = dateText,
            timeText = if (event.isAllDay) "全天" else
                "${event.start.timePart().hourMinuteText()}–${event.endExclusive.timePart().hourMinuteText()}",
            location = event.location?.takeIf { it.isNotBlank() },
            isAllDay = event.isAllDay,
            relativeText = row.subtitle.substringAfterLast(" · "),
        )
    }

    /** [layout] with a new home countdown widget for the school event [eventId]. */
    fun addEventCountdown(layout: String, eventId: String, clock: PocketClock): String {
        val dashboard = PocketDashboard
        val before = dashboard.instances(layout).map { it.id }.toSet()
        val added = dashboard.add(layout, "countdown", clock)
        val id = dashboard.instances(added).firstOrNull { it.id !in before }?.id ?: return layout
        return dashboard.setSchoolEvent(added, id, eventId, clock)
    }

    internal fun event(clock: PocketClock, id: String): SchoolCalendarEvent? =
        DemoCampus.sharedEvents(clock).firstOrNull { it.id == id }
}
