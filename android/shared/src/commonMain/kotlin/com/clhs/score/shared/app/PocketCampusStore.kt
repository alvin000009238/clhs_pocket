package com.clhs.score.shared.app

import com.clhs.score.data.RecordHistoryStatistic

import com.clhs.score.data.RecordCategoryCount

import com.clhs.score.data.MeritRecord

import com.clhs.score.data.FakeRecordData
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.weekday
import com.clhs.score.shared.time.yearNumber

enum class PocketAnnouncementFilter { ALL, UNREAD, BOOKMARKED }

data class PocketAttachment(val name: String, val sizeText: String, val url: String = "")

data class PocketAnnouncementDetail(
    val id: String,
    val title: String,
    val dateText: String,
    val unit: String,
    val category: String,
    val isPinned: Boolean,
    val paragraphs: List<String>,
    val attachments: List<PocketAttachment>,
    val officialUrl: String,
    /** The body as formatting-only HTML (no scripts, styles, images or embeds); empty when there is none. */
    val html: String = "",
    val images: List<PocketAnnouncementImage> = emptyList(),
)

/** [canPreview] is false for images outside the school site, which are opened on request only, as on Android. */
data class PocketAnnouncementImage(val url: String, val description: String, val canPreview: Boolean)

data class PocketCalendarDay(
    val key: String,
    val dayText: String,
    val inMonth: Boolean,
    val isToday: Boolean,
    val eventCount: Int,
    val accessibilityLabel: String,
)

data class PocketCalendarMonth(
    val year: Int,
    val month: Int,
    val title: String,
    val weekdays: List<String>,
    val days: List<PocketCalendarDay>,
    val events: List<PocketEventRow>,
)

data class PocketRecordCount(val category: String, val count: Int, val isConcern: Boolean)

data class PocketRecordRow(
    val id: String,
    val dateText: String,
    val category: String,
    val countText: String,
    val detail: String,
    val note: String,
    val isConcern: Boolean,
    /** Merit approval details behind 查看核定資訊; empty for attendance. */
    val group: String = "",
    val yearTerm: String = "",
    val checkDate: String = "",
    val cleanDate: String = "",
)

/** Android's 查看歷年統計 table: one row per category, one cell per grade and half. */
data class PocketRecordHistory(val terms: List<String>, val rows: List<PocketRecordHistoryRow>, val note: String)

data class PocketRecordHistoryRow(val category: String, val cells: List<String>, val descriptions: List<String>)

data class PocketRecords(
    val semesters: List<String>,
    val semesterIndex: Int,
    val summary: List<PocketRecordCount>,
    val rows: List<PocketRecordRow>,
    val emptyText: String,
    /** False while this semester's records have not been loaded. */
    val isLoaded: Boolean = true,
    /** Up to three attendance categories for the summary, 病假, 事假 and 曠課 first. */
    val highlights: List<PocketRecordCount> = emptyList(),
)

/** Public campus content plus the demo attendance and merit records. */
object PocketCampusStore {
    private const val ALL = "全部"
    private const val REWARDS = "獎勵"
    private const val PENALTIES = "懲處"

    fun defaultUnreadIds(): Set<String> = DemoCampus.defaultUnreadIds

    fun categories(clock: PocketClock): List<String> =
        listOf(ALL) + DemoCampus.sharedAnnouncements(clock).map { it.category }.filter { it.isNotBlank() }.distinct()

    fun announcements(
        clock: PocketClock,
        category: String,
        query: String,
        filter: PocketAnnouncementFilter,
        unreadIds: Set<String>,
        bookmarkedIds: Set<String>,
    ): List<PocketAnnouncement> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val listed = DemoCampus.sharedAnnouncements(clock)
        // A bookmark outlives the first page of the school's list.
        val saved = if (filter == PocketAnnouncementFilter.BOOKMARKED) {
            val onPage = listed.map { it.id }.toSet()
            PocketData.current.savedAnnouncements.filter { it.id in bookmarkedIds && it.id !in onPage }
        } else emptyList()
        return (listed + saved)
            .filter { category == ALL || category.isBlank() || it.category == category }
            .filter { item ->
                val text = "${item.title} ${item.unit} ${item.category}".lowercase()
                words.all { it in text }
            }
            .filter {
                when (filter) {
                    PocketAnnouncementFilter.ALL -> true
                    PocketAnnouncementFilter.UNREAD -> it.id in unreadIds
                    PocketAnnouncementFilter.BOOKMARKED -> it.id in bookmarkedIds
                }
            }
            .sortedWith(compareByDescending<com.clhs.score.data.SchoolAnnouncement> { it.isPinned }.thenByDescending { it.date })
            .map { DemoCampus.announcementRow(it, unreadIds, bookmarkedIds) }
    }

    fun unreadCount(clock: PocketClock, unreadIds: Set<String>): Int =
        DemoCampus.sharedAnnouncements(clock).count { it.id in unreadIds }

    fun announcementDetail(clock: PocketClock, id: String): PocketAnnouncementDetail? {
        val item = DemoCampus.sharedAnnouncements(clock).firstOrNull { it.id == id }
            ?: PocketData.current.savedAnnouncements.firstOrNull { it.id == id }
        if (!PocketData.isDemo) {
            // Null until the live detail has been loaded; the list row is not a substitute for the body.
            // A loaded detail stands on its own: removing the bookmark of an announcement that is only
            // known from the bookmarks must not empty the page that is showing it.
            val live = PocketData.current.announcementDetail(id) ?: return null
            return PocketAnnouncementDetail(
                id = live.id, title = live.title, dateText = live.date, unit = live.unit, category = live.category,
                isPinned = item?.isPinned == true,
                paragraphs = plainParagraphs(live.htmlContent),
                attachments = live.attachments.map { file ->
                    PocketAttachment(file.name, file.sizeBytes?.let { bytes -> if (bytes >= 1_048_576) "${bytes / 1_048_576} MB" else "${(bytes + 1023) / 1024} KB" }.orEmpty(), file.url)
                },
                officialUrl = live.officialUrl,
                html = formattingHtml(live.htmlContent),
                images = live.images.map { PocketAnnouncementImage(it.url, it.description, it.canPreview) },
            )
        }
        item ?: return null
        return PocketAnnouncementDetail(
            id = item.id,
            title = item.title,
            dateText = item.date,
            unit = item.unit,
            category = item.category,
            isPinned = item.isPinned,
            paragraphs = listOf(
                "各位同學好：",
                "「${item.title}」相關事項已公告，請同學留意時程並依規定辦理。若有疑問，請於上班時間洽${item.unit}。",
                "此為示範資料，內容為虛構，僅用於展示公告內文的排版。",
            ),
            attachments = if (item.isPinned || item.category == "活動") {
                listOf(PocketAttachment("實施計畫.pdf", "248 KB"), PocketAttachment("報名表.docx", "36 KB"))
            } else emptyList(),
            officialUrl = "https://www.clhs.tyc.edu.tw/ischool/public/news_view/show.php?nid=${item.id}",
            html = "<p>各位同學好：</p>" +
                "<p>「${item.title}」相關事項已公告，請同學留意時程並<b>依規定辦理</b>。</p>" +
                "<ul><li>請於期限內完成，<u>逾期不受理</u>。</li><li>若有疑問，請於上班時間洽${item.unit}。</li></ul>" +
                "<p>詳細說明請見<a href=\"https://www.clhs.tyc.edu.tw/\">學校網站</a>。</p>" +
                "<p>此為示範資料，內容為虛構，僅用於展示公告內文的排版。</p>",
        )
    }

    private val droppedBlocks = Regex(
        "(?is)<(script|style|iframe|object|embed|svg|form|head|noscript)\\b.*?</\\1\\s*>|<!--.*?-->",
    )
    private val droppedTags = Regex(
        "(?is)</?(script|style|iframe|object|embed|svg|form|head|noscript|img|picture|source|video|audio|link|meta|base|input|button)\\b[^>]*>",
    )
    private val riskyAttributes = Regex("(?is)\\s(on\\w+|style|src|srcset|background|class|id)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
    private val unsafeLink = Regex("(?is)\\shref\\s*=\\s*(\"(?!https?://)[^\"]*\"|'(?!https?://)[^']*'|(?!https?://|[\"'])[^\\s>]+)")

    /**
     * Announcement HTML reduced to what the native renderer may show: text formatting, lists,
     * tables and http(s) links. Nothing that runs or loads a resource survives, so rendering the
     * body never contacts another server; images are listed separately.
     */
    internal fun formattingHtml(html: String): String = html
        .replace(droppedBlocks, "").replace(droppedTags, "")
        .replace(riskyAttributes, "").replace(unsafeLink, "")
        .trim()

    private val blockBreak = Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>")
    private val anyTag = Regex("<[^>]*>")

    /** Announcement HTML reduced to readable paragraphs; markup is never rendered. */
    internal fun plainParagraphs(html: String): List<String> = html
        .replace(blockBreak, "\n").replace(anyTag, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Month grid starting on Monday, padded to whole weeks. */
    fun month(clock: PocketClock, year: Int, month: Int): PocketCalendarMonth {
        val first = localDateOf(year, month, 1)
        val today = clock.date()
        val events = DemoCampus.sharedEvents(clock)
        fun count(date: com.clhs.score.shared.time.LocalDate) =
            events.count { DemoCampus.covers(it, date) }
        var cursor = first.shiftDays(-(first.weekday().isoNumber - 1).toLong())
        val days = mutableListOf<PocketCalendarDay>()
        while (days.size % 7 != 0 || cursor.monthNumber() == month || days.isEmpty() || cursor < first) {
            val inMonth = cursor.monthNumber() == month && cursor.yearNumber() == year
            val eventCount = count(cursor)
            days += PocketCalendarDay(
                key = "${cursor.yearNumber()}-${cursor.monthNumber().pad2()}-${cursor.dayNumber().pad2()}",
                dayText = cursor.dayNumber().toString(),
                inMonth = inMonth,
                isToday = cursor.epochDay() == today.epochDay(),
                eventCount = eventCount,
                accessibilityLabel = "${cursor.monthNumber()} 月 ${cursor.dayNumber()} 日 週${cursor.weekdayLabel()}" +
                    (if (cursor.epochDay() == today.epochDay()) "，今天" else "") + (if (eventCount > 0) "，$eventCount 個行程" else ""),
            )
            cursor = cursor.shiftDays(1)
            if (days.size >= 42) break
        }
        val last = cursor
        return PocketCalendarMonth(
            year = year,
            month = month,
            title = "$year 年 $month 月",
            weekdays = WEEKDAY_LABELS,
            days = days,
            events = events
                .filter { DemoCampus.lastDay(it) >= first && it.start.datePart() < last && overlapsMonth(it, year, month) }
                .sortedBy { it.start.datePart().epochDay() }
                .map { DemoCampus.eventRow(it, today, clock.dateTime()) },
        )
    }

    private fun overlapsMonth(event: com.clhs.score.data.SchoolCalendarEvent, year: Int, month: Int): Boolean {
        val next = if (month == 12) localDateOf(year + 1, 1, 1) else localDateOf(year, month + 1, 1)
        return event.start.datePart() < next && DemoCampus.lastDay(event) >= localDateOf(year, month, 1)
    }

    fun eventsOn(clock: PocketClock, dayKey: String): List<PocketEventRow> {
        val parts = dayKey.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return emptyList()
        val date = localDateOf(parts[0], parts[1], parts[2])
        return DemoCampus.sharedEvents(clock)
            .filter { DemoCampus.covers(it, date) }
            .map { DemoCampus.eventRow(it, clock.date(), clock.dateTime()) }
    }

    private val concerns = setOf("曠課", "遲到", "警告", "小過", "大過")

    fun attendance(semesterIndex: Int): PocketRecords {
        val semesters = PocketData.current.recordSemesters
        if (semesters.isEmpty()) return PocketRecords(emptyList(), 0, emptyList(), emptyList(), "", isLoaded = false)
        val index = semesterIndex.coerceIn(0, semesters.lastIndex)
        val record = PocketData.current.attendance(semesters[index])
            ?: return PocketRecords(semesters.map { it.label }, index, emptyList(), emptyList(), "", isLoaded = false)
        return PocketRecords(
            semesters = semesters.map { it.label },
            semesterIndex = index,
            summary = record.summary.map { PocketRecordCount(it.category, it.count, it.category in concerns && it.count > 0) },
            rows = record.details.mapIndexed { row, it ->
                PocketRecordRow("a$row", it.date, it.category, "${it.count} 節", it.sections, "", it.category in concerns)
            },
            emptyText = if (record.summary.isEmpty()) "這個學期還沒有出缺勤資料" else "這個學期沒有缺曠紀錄",
            highlights = attendanceHighlights(record.summary)
                .map { PocketRecordCount(it.category, it.count, it.category in concerns && it.count > 0) },
        )
    }

    fun merits(semesterIndex: Int): PocketRecords = merits(semesterIndex, ALL)

    /** Android's merit filters: 全部, 獎勵 and 懲處, by the school's group. */
    fun meritFilters(): List<String> = listOf(ALL, REWARDS, PENALTIES)

    /** The semester's merits in [filter], newest first; the summary always counts every record. */
    fun merits(semesterIndex: Int, filter: String): PocketRecords {
        val semesters = PocketData.current.recordSemesters
        if (semesters.isEmpty()) return PocketRecords(emptyList(), 0, emptyList(), emptyList(), "", isLoaded = false)
        val index = semesterIndex.coerceIn(0, semesters.lastIndex)
        val records = PocketData.current.merits(semesters[index])
            ?: return PocketRecords(semesters.map { it.label }, index, emptyList(), emptyList(), "", isLoaded = false)
        val visible = records.filter { includes(filter, it) }.sortedByDescending { recordDateKey(it.date) }
        return PocketRecords(
            semesters = semesters.map { it.label },
            semesterIndex = index,
            summary = records.groupBy { it.category }
                .map { (category, items) -> PocketRecordCount(category, items.sumOf { it.count }, category in concerns) },
            rows = visible.mapIndexed { row, it ->
                PocketRecordRow(
                    id = "m$row", dateText = it.date, category = it.category, countText = "${it.count} 次",
                    detail = it.reason, note = if (it.cleanDate.isNotBlank()) "已於 ${it.cleanDate} 銷過" else "",
                    isConcern = it.category in concerns,
                    group = it.group.ifBlank { "未分類" }, yearTerm = it.yearTerm, checkDate = it.checkDate, cleanDate = it.cleanDate,
                )
            },
            emptyText = if (records.isNotEmpty()) "這個分類沒有紀錄" else "這個學期沒有獎懲紀錄",
        )
    }

    private fun includes(filter: String, record: MeritRecord): Boolean = when (filter) {
        REWARDS -> record.group.trim() in listOf("獎", "獎勵")
        PENALTIES -> record.group.trim() in listOf("懲", "懲處", "罰", "懲罰")
        else -> true
    }

    /** School dates are ROC `yyy/MM/dd`; padding keeps them in order as text. */
    private fun recordDateKey(date: String): String =
        date.split('/', '-', '.').joinToString("/") { it.trim().padStart(4, '0') }

    private fun attendanceHighlights(summary: List<RecordCategoryCount>): List<RecordCategoryCount> {
        val categories = summary.filterNot { it.category.trim() in listOf("全部", "合計", "總計", "總數", "總節數", "總節次", "總缺曠", "缺曠總計") }
        val preferred = listOf("病假", "事假", "曠課")
        return categories.sortedWith(
            compareBy<RecordCategoryCount> { preferred.indexOf(it.category.trim()).takeIf { index -> index >= 0 } ?: preferred.size }
                .thenByDescending { if (it.category.trim() in preferred) 0 else it.count },
        ).take(3)
    }

    private val historyTerms get() = listOf("一上", "一下", "二上", "二下", "三上", "三下")

    /** Null while the history has not been loaded. */
    fun attendanceHistory(): PocketRecordHistory? = PocketData.current.attendanceHistory?.let { history(it, "") }

    fun meritHistory(): PocketRecordHistory? = PocketData.current.meritHistory?.let { history(it, "次") }

    private fun history(statistics: List<RecordHistoryStatistic>, unit: String) = PocketRecordHistory(
        terms = historyTerms,
        rows = statistics.map { statistic ->
            val counts = historyTerms.indices.map { statistic.semesterCounts.getOrNull(it) }
            PocketRecordHistoryRow(
                category = statistic.category,
                cells = counts.map { it?.toString() ?: "—" },
                descriptions = counts.mapIndexed { index, count ->
                    "${statistic.category} ${historyTerms[index]} ${count?.let { if (unit.isEmpty()) "$it" else "$it $unit" } ?: "未提供"}"
                },
            )
        },
        note = "依年級與上下學期顯示，${if (unit.isEmpty()) "校方統計數量" else "單位：$unit"}。— 表示未提供。",
    )
}
