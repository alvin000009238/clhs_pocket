package com.clhs.score.shared.app

internal object DemoSchedule {
    private val periods = listOf(
        PocketPeriod(1, "08:10", "09:00"),
        PocketPeriod(2, "09:10", "10:00"),
        PocketPeriod(3, "10:10", "11:00"),
        PocketPeriod(4, "11:10", "12:00"),
        PocketPeriod(5, "13:00", "13:50"),
        PocketPeriod(6, "14:00", "14:50"),
        PocketPeriod(7, "15:05", "15:55"),
        PocketPeriod(8, "16:05", "16:55"),
    )

    /** Blocks never merge across the lunch break between periods 4 and 5. */
    private const val LAST_MORNING_PERIOD = 4

    private val grid: List<List<String>> = listOf(
        listOf("國語文", "國語文", "數學", "英語文", "物理", "物理", "體育", "班會"),
        listOf("數學", "數學", "英語文", "化學", "歷史", "地理", "美術", "美術"),
        listOf("英語文", "國語文", "化學", "化學", "數學", "公民", "社團", "社團"),
        listOf("物理", "數學", "國語文", "歷史", "英語文", "音樂", "地科", "體育"),
        listOf("地理", "英語文", "數學", "國語文", "公民", "生涯規劃", "地科", "自主學習"),
    )

    private val teachers = mapOf(
        "國語文" to "林老師", "數學" to "陳老師", "英語文" to "王老師", "物理" to "張老師",
        "化學" to "李老師", "歷史" to "黃老師", "地理" to "吳老師", "公民" to "劉老師",
        "體育" to "蔡老師", "美術" to "楊老師", "音樂" to "許老師", "地科" to "鄭老師",
        "班會" to "導師", "社團" to "", "生涯規劃" to "謝老師", "自主學習" to "",
    )

    private val rooms = mapOf(
        "物理" to "物理實驗室", "化學" to "化學實驗室", "體育" to "操場", "美術" to "美術教室",
        "音樂" to "音樂教室", "地科" to "地科教室", "社團" to "社團教室", "自主學習" to "圖書館",
    )

    private const val HOME_ROOM = "示範教室"

    private val lightPalette = listOf(
        0xFF3A62A4, 0xFFB75357, 0xFF217541, 0xFF8483CA, 0xFFB06018, 0xFF0B726C, 0xFFA877B4,
        0xFF8F7412, 0xFF016E8B, 0xFFBE708E, 0xFF618524, 0xFFC37363, 0xFF128C64, 0xFF6A539D,
        0xFFB67F40, 0xFF08888E, 0xFF884880, 0xFF978F3C, 0xFF1481B3,
    )

    private val darkPalette = listOf(
        0xFF9BCDFE, 0xFFFA9597, 0xFF72BD8D, 0xFFC3C2FF, 0xFFF3A16A, 0xFF62C1BA, 0xFFE6B7F0,
        0xFFD2B854, 0xFF56BED9, 0xFFF5AECA, 0xFFA9CD6B, 0xFFF5AE9F, 0xFF65CBA5, 0xFFA998DF,
        0xFFF0B979, 0xFF58CDD1, 0xFFCB8CC2, 0xFFD6CE80, 0xFF65C1EA,
    )

    private val subjectIndex: Map<String, Int> =
        grid.flatten().distinct().sorted().withIndex().associate { it.value to it.index }

    private data class Slot(
        val day: Int,
        val period: Int,
        val subject: String,
        val change: PocketCellChange = PocketCellChange.NONE,
        val note: String = "",
    )

    private fun slots(scope: PocketScheduleScope): List<Slot> {
        val base = grid.flatMapIndexed { dayIndex, row ->
            row.mapIndexed { periodIndex, subject -> Slot(dayIndex + 1, periodIndex + 1, subject) }
        }
        if (scope == PocketScheduleScope.SEMESTER) return base
        return base.map { slot ->
            when {
                slot.day == 3 && slot.period == 6 ->
                    slot.copy(subject = "數學", change = PocketCellChange.CHANGED, note = "原「公民」調課")
                slot.day == 5 && slot.period == 8 ->
                    slot.copy(change = PocketCellChange.CANCELLED, note = "本週停課")
                else -> slot
            }
        }
    }

    private fun mondayOf(clock: PocketClock): Long = clock.epochDay() - (clock.dayOfWeek - 1)

    fun schedule(scope: PocketScheduleScope, clock: PocketClock): PocketSchedule {
        val monday = mondayOf(clock)
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val days = (1..5).map { day ->
            val date = civilDateOf(monday + day - 1)
            PocketScheduleDay(
                dayOfWeek = day,
                label = WEEKDAY_LABELS[day - 1],
                dateText = date.shortText(),
                isToday = day == clock.dayOfWeek,
            )
        }
        val cells = mergedCells(slots(scope), clock, trackStatus = isWeek)
        val first = civilDateOf(monday)
        val last = civilDateOf(monday + 4)
        return PocketSchedule(
            scope = scope,
            scopeLabel = if (isWeek) "本週課表" else "學期課表",
            termText = "114 學年度 第 1 學期",
            className = "示範班級",
            rangeText = "${first.shortText()} – ${last.shortText()}",
            weekText = "第 12 週",
            days = days,
            periods = periods,
            cells = cells,
            changeCount = cells.count { it.change != PocketCellChange.NONE },
        )
    }

    private fun mergedCells(slots: List<Slot>, clock: PocketClock, trackStatus: Boolean): List<PocketScheduleCell> {
        val result = mutableListOf<PocketScheduleCell>()
        slots.groupBy { it.day }.forEach { (day, daySlots) ->
            val sorted = daySlots.sortedBy { it.period }
            var index = 0
            while (index < sorted.size) {
                val head = sorted[index]
                var span = 1
                while (index + span < sorted.size) {
                    val next = sorted[index + span]
                    val previous = sorted[index + span - 1]
                    val crossesLunch = previous.period == LAST_MORNING_PERIOD
                    val sameBlock = next.subject == head.subject && next.change == head.change
                    if (crossesLunch || !sameBlock) break
                    span++
                }
                val startPeriod = periods[head.period - 1]
                val endPeriod = periods[head.period + span - 2]
                val status = when {
                    !trackStatus || day != clock.dayOfWeek -> PocketCellStatus.NORMAL
                    clock.minuteOfDay >= minuteOfDay(endPeriod.end) -> PocketCellStatus.ENDED
                    clock.minuteOfDay >= minuteOfDay(startPeriod.start) -> PocketCellStatus.CURRENT
                    else -> PocketCellStatus.NORMAL
                }
                val paletteIndex = subjectIndex[head.subject] ?: 0
                result += PocketScheduleCell(
                    id = "$day-${head.period}",
                    dayOfWeek = day,
                    period = head.period,
                    span = span,
                    subject = head.subject,
                    teacher = teachers[head.subject].orEmpty(),
                    classroom = rooms[head.subject] ?: HOME_ROOM,
                    timeText = "${startPeriod.start} – ${endPeriod.end}",
                    colorLight = lightPalette[paletteIndex % lightPalette.size],
                    colorDark = darkPalette[paletteIndex % darkPalette.size],
                    status = status,
                    change = head.change,
                    changeNote = head.note,
                )
                index += span
            }
        }
        return result
    }

    fun hero(clock: PocketClock): PocketCourseHero {
        val active = slots(PocketScheduleScope.CURRENT_WEEK)
            .filter { it.change != PocketCellChange.CANCELLED }
        val today = active.filter { it.day == clock.dayOfWeek }.sortedBy { it.period }
        val now = clock.minuteOfDay
        val current = today.firstOrNull {
            val period = periods[it.period - 1]
            now >= minuteOfDay(period.start) && now < minuteOfDay(period.end)
        }
        val upcoming = today.firstOrNull { now < minuteOfDay(periods[it.period - 1].start) }

        if (current != null) {
            val period = periods[current.period - 1]
            val start = minuteOfDay(period.start)
            val end = minuteOfDay(period.end)
            val next = today.firstOrNull { it.period > current.period && it.subject != current.subject }
            return PocketCourseHero(
                headline = "現在上課 · 第 ${current.period} 節",
                courseName = current.subject,
                supportingText = place(current),
                isInClass = true,
                progress = (now - start).toDouble() / (end - start),
                timeText = "${period.start} – ${period.end} · 還有 ${end - now} 分鐘下課",
                nextLine = next?.let { "下一堂 ${it.subject} · ${periods[it.period - 1].start}" },
            )
        }
        if (upcoming != null) {
            val period = periods[upcoming.period - 1]
            val minutes = minuteOfDay(period.start) - now
            val wait = if (minutes >= 60) "${minutes / 60} 小時 ${minutes % 60} 分鐘後" else "$minutes 分鐘後"
            val following = today.firstOrNull { it.period > upcoming.period && it.subject != upcoming.subject }
            return PocketCourseHero(
                headline = "下一堂 · 第 ${upcoming.period} 節",
                courseName = upcoming.subject,
                supportingText = place(upcoming),
                isInClass = false,
                progress = -1.0,
                timeText = "${period.start} – ${period.end} · $wait",
                nextLine = following?.let { "接著 ${it.subject} · ${periods[it.period - 1].start}" },
            )
        }
        val nextDay = ((clock.dayOfWeek + 1)..(clock.dayOfWeek + 7))
            .map { (it - 1) % 7 + 1 }
            .first { day -> active.any { it.day == day } }
        val first = active.filter { it.day == nextDay }.minBy { it.period }
        val label = if (nextDay == clock.dayOfWeek % 7 + 1) "明天" else "週${WEEKDAY_LABELS[nextDay - 1]}"
        val period = periods[first.period - 1]
        return PocketCourseHero(
            headline = if (today.isEmpty()) "今天沒有課程" else "今日課程已結束",
            courseName = "$label ${first.subject}",
            supportingText = place(first),
            isInClass = false,
            progress = -1.0,
            timeText = "$label 第 ${first.period} 節 · ${period.start} 開始",
            nextLine = null,
        )
    }

    private fun place(slot: Slot): String {
        val room = rooms[slot.subject] ?: HOME_ROOM
        val teacher = teachers[slot.subject].orEmpty()
        return if (teacher.isEmpty()) room else "$room · $teacher"
    }
}
