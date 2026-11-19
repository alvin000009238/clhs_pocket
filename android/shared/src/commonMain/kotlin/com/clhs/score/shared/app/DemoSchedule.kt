package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.getSubjectColors
import com.clhs.score.shared.time.localDateOf

/** Presents the shared synthetic timetable (`FakeScheduleData`) for SwiftUI. */
internal object DemoSchedule {
    /** Blocks never merge across the lunch break between periods 4 and 5. */
    private const val LAST_MORNING_PERIOD = 4
    private const val SCHOOL_DAYS = 5

    private val demoClass get() = FakeScheduleData.classes.first()

    private val periods = PERIOD_TIMES.mapIndexed { index, time -> PocketPeriod(index + 1, time.start, time.end) }

    private data class Slot(val item: ScheduleItem, val change: PocketCellChange, val note: String)

    private fun report(scope: ScheduleScope, clock: PocketClock) =
        localDateOf(clock.year, clock.month, clock.day).let { today ->
            FakeScheduleData.report(FakeScheduleData.currentYearValue(today), demoClass.value, scope, today)
        }

    private fun slots(scope: PocketScheduleScope, clock: PocketClock): List<Slot> {
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val report = report(if (isWeek) ScheduleScope.CURRENT_WEEK else ScheduleScope.SEMESTER, clock)
        val changes = report.changes.orEmpty().associateBy { it.dayOfWeek to it.period }
        val live = report.items.map { item ->
            val change = changes[item.dayOfWeek to item.period]
            when (change?.type) {
                ScheduleChangeType.ADDED -> Slot(item, PocketCellChange.ADDED, "本週加課")
                ScheduleChangeType.MODIFIED -> Slot(
                    item,
                    PocketCellChange.CHANGED,
                    change.semesterItem?.subjectName?.takeIf { it != item.subjectName }
                        ?.let { "原「$it」調課" } ?: "本週異動",
                )
                else -> Slot(item, PocketCellChange.NONE, "")
            }
        }
        val removed = changes.values
            .filter { it.type == ScheduleChangeType.REMOVED }
            .mapNotNull { change -> change.semesterItem?.let { Slot(it, PocketCellChange.CANCELLED, "本週停課") } }
        return (live + removed).filter { it.item.dayOfWeek <= SCHOOL_DAYS && it.item.period <= periods.size }
    }

    fun schedule(scope: PocketScheduleScope, clock: PocketClock): PocketSchedule {
        val monday = clock.epochDay() - (clock.dayOfWeek - 1)
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val source = report(if (isWeek) ScheduleScope.CURRENT_WEEK else ScheduleScope.SEMESTER, clock)
        val days = (1..SCHOOL_DAYS).map { day ->
            PocketScheduleDay(
                dayOfWeek = day,
                label = WEEKDAY_LABELS[day - 1],
                dateText = civilDateOf(monday + day - 1).shortText(),
                isToday = day == clock.dayOfWeek,
            )
        }
        val cells = mergedCells(slots(scope, clock), clock, trackStatus = isWeek)
        val (year, term) = source.yearTermValue.split("_").let { it[0] to it.getOrElse(1) { "1" } }
        return PocketSchedule(
            scope = scope,
            scopeLabel = if (isWeek) "本週課表" else "學期課表",
            termText = "$year 學年度 第 $term 學期",
            className = demoClass.text,
            rangeText = "${civilDateOf(monday).shortText()} – ${civilDateOf(monday + SCHOOL_DAYS - 1).shortText()}",
            weekText = source.weekNo?.let { "第 $it 週" }.orEmpty(),
            days = days,
            periods = periods,
            cells = cells,
            changeCount = source.changes.orEmpty().size,
        )
    }

    private fun mergedCells(slots: List<Slot>, clock: PocketClock, trackStatus: Boolean): List<PocketScheduleCell> {
        val names = slots.map { it.item.subjectName }
        val light = getSubjectColors(names, isDarkTheme = false)
        val dark = getSubjectColors(names, isDarkTheme = true)
        val result = mutableListOf<PocketScheduleCell>()
        slots.groupBy { it.item.dayOfWeek }.forEach { (day, daySlots) ->
            val sorted = daySlots.sortedBy { it.item.period }
            var index = 0
            while (index < sorted.size) {
                val head = sorted[index]
                var span = 1
                while (index + span < sorted.size) {
                    val next = sorted[index + span]
                    val previous = sorted[index + span - 1]
                    val contiguous = next.item.period == previous.item.period + 1 &&
                        previous.item.period != LAST_MORNING_PERIOD
                    val sameBlock = next.item.subjectName == head.item.subjectName &&
                        next.item.classroom == head.item.classroom && next.change == head.change
                    if (!contiguous || !sameBlock) break
                    span++
                }
                val start = periods[head.item.period - 1]
                val end = periods[head.item.period + span - 2]
                val status = when {
                    !trackStatus || day != clock.dayOfWeek -> PocketCellStatus.NORMAL
                    clock.minuteOfDay >= minuteOfDay(end.end) -> PocketCellStatus.ENDED
                    clock.minuteOfDay >= minuteOfDay(start.start) -> PocketCellStatus.CURRENT
                    else -> PocketCellStatus.NORMAL
                }
                result += PocketScheduleCell(
                    id = "$day-${head.item.period}",
                    dayOfWeek = day,
                    period = head.item.period,
                    span = span,
                    subject = head.item.subjectName,
                    teacher = head.item.teacherName,
                    classroom = head.item.classroom,
                    timeText = "${start.start} – ${end.end}",
                    colorLight = light.getValue(head.item.subjectName),
                    colorDark = dark.getValue(head.item.subjectName),
                    status = status,
                    change = head.change,
                    changeNote = head.note,
                )
                index += span
            }
        }
        return result
    }

    fun hero(clock: PocketClock): PocketCourseHero? {
        val active = slots(PocketScheduleScope.CURRENT_WEEK, clock)
            .filter { it.change != PocketCellChange.CANCELLED }
            .map { it.item }
        if (active.isEmpty()) return null
        val today = active.filter { it.dayOfWeek == clock.dayOfWeek }.sortedBy { it.period }
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
            val next = today.firstOrNull { it.period > current.period && it.subjectName != current.subjectName }
            return PocketCourseHero(
                headline = "現在上課 · 第 ${current.period} 節",
                courseName = current.subjectName,
                supportingText = place(current),
                isInClass = true,
                progress = (now - start).toDouble() / (end - start),
                timeText = "${period.start} – ${period.end} · 還有 ${end - now} 分鐘下課",
                nextLine = next?.let { "下一堂 ${it.subjectName} · ${periods[it.period - 1].start}" },
            )
        }
        if (upcoming != null) {
            val period = periods[upcoming.period - 1]
            val minutes = minuteOfDay(period.start) - now
            val wait = if (minutes >= 60) "${minutes / 60} 小時 ${minutes % 60} 分鐘後" else "$minutes 分鐘後"
            val following = today.firstOrNull { it.period > upcoming.period && it.subjectName != upcoming.subjectName }
            return PocketCourseHero(
                headline = "下一堂 · 第 ${upcoming.period} 節",
                courseName = upcoming.subjectName,
                supportingText = place(upcoming),
                isInClass = false,
                progress = -1.0,
                timeText = "${period.start} – ${period.end} · $wait",
                nextLine = following?.let { "接著 ${it.subjectName} · ${periods[it.period - 1].start}" },
            )
        }
        val nextDay = ((clock.dayOfWeek + 1)..(clock.dayOfWeek + 7))
            .map { (it - 1) % 7 + 1 }
            .first { day -> active.any { it.dayOfWeek == day } }
        val first = active.filter { it.dayOfWeek == nextDay }.minBy { it.period }
        val label = if (nextDay == clock.dayOfWeek % 7 + 1) "明天" else "週${WEEKDAY_LABELS[nextDay - 1]}"
        val period = periods[first.period - 1]
        return PocketCourseHero(
            headline = if (today.isEmpty()) "今天沒有課程" else "今日課程已結束",
            courseName = "$label ${first.subjectName}",
            supportingText = place(first),
            isInClass = false,
            progress = -1.0,
            timeText = "$label 第 ${first.period} 節 · ${period.start} 開始",
            nextLine = null,
        )
    }

    private fun place(item: ScheduleItem): String =
        listOf(item.classroom, item.teacherName).filter { it.isNotEmpty() }.joinToString(" · ")
}
