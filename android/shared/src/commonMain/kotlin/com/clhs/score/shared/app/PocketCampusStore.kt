package com.clhs.score.shared.app

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

data class PocketAttachment(val name: String, val sizeText: String)

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
)

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
)

data class PocketRecords(
    val semesters: List<String>,
    val semesterIndex: Int,
    val summary: List<PocketRecordCount>,
    val rows: List<PocketRecordRow>,
    val emptyText: String,
)

/** Public campus content plus the demo attendance and merit records. */
object PocketCampusStore {
    private const val ALL = "全部"

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
        return DemoCampus.sharedAnnouncements(clock)
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
        val item = DemoCampus.sharedAnnouncements(clock).firstOrNull { it.id == id } ?: return null
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
        )
    }

    /** Month grid starting on Monday, padded to whole weeks. */
    fun month(clock: PocketClock, year: Int, month: Int): PocketCalendarMonth {
        val first = localDateOf(year, month, 1)
        val today = clock.date()
        val events = DemoCampus.sharedEvents(clock)
        fun count(date: com.clhs.score.shared.time.LocalDate) =
            events.count { it.start.datePart() <= date && date < it.endExclusive.datePart() }
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
                .filter { it.endExclusive.datePart() > first && it.start.datePart() < last && overlapsMonth(it, year, month) }
                .sortedBy { it.start.datePart().epochDay() }
                .map { DemoCampus.eventRow(it, today) },
        )
    }

    private fun overlapsMonth(event: com.clhs.score.data.SchoolCalendarEvent, year: Int, month: Int): Boolean {
        val next = if (month == 12) localDateOf(year + 1, 1, 1) else localDateOf(year, month + 1, 1)
        return event.start.datePart() < next && event.endExclusive.datePart() > localDateOf(year, month, 1)
    }

    fun eventsOn(clock: PocketClock, dayKey: String): List<PocketEventRow> {
        val parts = dayKey.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return emptyList()
        val date = localDateOf(parts[0], parts[1], parts[2])
        return DemoCampus.sharedEvents(clock)
            .filter { it.start.datePart() <= date && date < it.endExclusive.datePart() }
            .map { DemoCampus.eventRow(it, clock.date()) }
    }

    private val concerns = setOf("曠課", "遲到", "警告", "小過", "大過")

    fun attendance(semesterIndex: Int): PocketRecords {
        val index = semesterIndex.coerceIn(0, FakeRecordData.semesters.lastIndex)
        val record = FakeRecordData.attendance(FakeRecordData.semesters[index])
        return PocketRecords(
            semesters = FakeRecordData.semesters.map { it.label },
            semesterIndex = index,
            summary = record.summary.map { PocketRecordCount(it.category, it.count, it.category in concerns && it.count > 0) },
            rows = record.details.mapIndexed { row, it ->
                PocketRecordRow("a$row", it.date, it.category, "${it.count} 節", it.sections, "", it.category in concerns)
            },
            emptyText = if (record.summary.isEmpty()) "這個學期還沒有出缺勤資料" else "這個學期沒有缺曠紀錄",
        )
    }

    fun merits(semesterIndex: Int): PocketRecords {
        val index = semesterIndex.coerceIn(0, FakeRecordData.semesters.lastIndex)
        val records = FakeRecordData.merits(FakeRecordData.semesters[index])
        return PocketRecords(
            semesters = FakeRecordData.semesters.map { it.label },
            semesterIndex = index,
            summary = records.groupBy { it.category }
                .map { (category, items) -> PocketRecordCount(category, items.sumOf { it.count }, category in concerns) },
            rows = records.mapIndexed { row, it ->
                PocketRecordRow(
                    id = "m$row", dateText = it.date, category = it.category, countText = "${it.count} 次",
                    detail = it.reason, note = if (it.cleanDate.isNotBlank()) "已於 ${it.cleanDate} 銷過" else "",
                    isConcern = it.category in concerns,
                )
            },
            emptyText = "這個學期沒有獎懲紀錄",
        )
    }
}
