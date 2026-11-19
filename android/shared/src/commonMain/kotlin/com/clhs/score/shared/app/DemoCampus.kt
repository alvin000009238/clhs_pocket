package com.clhs.score.shared.app

internal object DemoCampus {
    private data class Notice(
        val id: String,
        val title: String,
        val daysAgo: Int,
        val unit: String,
        val category: String,
        val pinned: Boolean = false,
        val unread: Boolean = false,
    )

    private val notices = listOf(
        Notice("demo-45072", "圖書館閉館時間異動公告", 1, "讀者服務組", "公告", pinned = true, unread = true),
        Notice("demo-45068", "中央研究院高中生命科學研究人才培育計畫", 1, "設備組", "最新消息", unread = true),
        Notice("demo-45061", "第二次段考考場配置與應試注意事項", 2, "教學組", "考試"),
        Notice("demo-45055", "校慶運動會各項競賽報名開始", 4, "體育組", "活動", unread = true),
        Notice("demo-45049", "高一多元選修課程加退選說明", 6, "實研組", "課程"),
        Notice("demo-45040", "獎助學金申請收件至月底截止", 9, "註冊組", "獎助學金"),
    )

    fun announcements(clock: PocketClock): List<PocketAnnouncement> {
        val today = clock.epochDay()
        return notices.map {
            val date = civilDateOf(today - it.daysAgo)
            PocketAnnouncement(
                id = it.id,
                title = it.title,
                dateText = date.slashText(),
                shortDateText = date.shortText(),
                unit = it.unit,
                category = it.category,
                isPinned = it.pinned,
                isUnread = it.unread,
            )
        }
    }

    private data class Event(val id: String, val title: String, val offset: Int, val length: Int = 1)

    private val calendar = listOf(
        Event("demo-e1", "高二校外教學", -1, 3),
        Event("demo-e2", "社團成果發表", 3),
        Event("demo-e3", "第二次段考", 8, 2),
        Event("demo-e4", "校慶運動會", 16, 2),
        Event("demo-e5", "期末考", 51, 3),
        Event("demo-e6", "寒假開始", 58),
    )

    fun events(clock: PocketClock): List<PocketEventRow> {
        val today = clock.epochDay()
        return calendar.map { event ->
            val startDay = today + event.offset
            val start = civilDateOf(startDay)
            val end = civilDateOf(startDay + event.length - 1)
            val ongoing = event.offset <= 0 && event.offset + event.length > 0
            val range = if (event.length > 1) "${start.shortText()} – ${end.shortText()}" else
                "${start.shortText()} 週${WEEKDAY_LABELS[dayOfWeekOf(startDay) - 1]}"
            val relative = when {
                ongoing -> "進行中"
                event.offset == 1 -> "明天"
                else -> "${event.offset} 天後"
            }
            PocketEventRow(
                id = event.id,
                monthText = "${start.month}月",
                dayText = start.day.toString(),
                title = event.title,
                subtitle = "$range · $relative",
                isOngoing = ongoing,
            )
        }
    }

    fun countdowns(clock: PocketClock): List<PocketCountdown> {
        val today = clock.epochDay()
        return listOf("demo-e3", "demo-e5").map { id ->
            val event = calendar.first { it.id == id }
            val date = civilDateOf(today + event.offset)
            PocketCountdown(id, "${event.offset}", event.title, date.shortText())
        }
    }
}
