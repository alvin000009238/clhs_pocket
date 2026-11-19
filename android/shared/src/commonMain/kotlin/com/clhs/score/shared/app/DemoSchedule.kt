package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.applySubjectOverrides
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.getSubjectColors
import com.clhs.score.data.CourseOccurrence
import com.clhs.score.data.displayItems
import com.clhs.score.data.occurrenceOn
import com.clhs.score.data.resolveCourses
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.weekday

/** Presents the shared synthetic timetable (`FakeScheduleData`) for SwiftUI. */
internal object DemoSchedule {
    /** Blocks never merge across the lunch break between periods 4 and 5. */
    private const val LAST_MORNING_PERIOD = 4
    private const val SCHOOL_DAYS = 5

    private val demoClass get() = FakeScheduleData.classes.first()

    private val periods = PERIOD_TIMES.mapIndexed { index, time -> PocketPeriod(index + 1, time.start, time.end) }

    private data class Slot(
        val item: ScheduleItem, val change: PocketCellChange, val note: String, val original: String = item.subjectName,
    )

    /** What the schedule screen is looking at; the home hero always uses the defaults. */
    data class View(
        val classValue: String = "",
        val weekOffset: Int = 0,
        val overrides: List<ScheduleSubjectOverride> = emptyList(),
    )

    private fun classOf(view: View) = FakeScheduleData.classes.firstOrNull { it.value == view.classValue } ?: demoClass

    private fun report(scope: ScheduleScope, clock: PocketClock, view: View = View()) =
        clock.date().shiftDays(7L * view.weekOffset).let { today ->
            FakeScheduleData.report(FakeScheduleData.currentYearValue(today), classOf(view).value, scope, today)
        }

    private fun slots(scope: PocketScheduleScope, clock: PocketClock, view: View): List<Slot> {
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val report = report(if (isWeek) ScheduleScope.CURRENT_WEEK else ScheduleScope.SEMESTER, clock, view)
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
        return (live + removed)
            .filter { it.item.dayOfWeek <= SCHOOL_DAYS && it.item.period <= periods.size }
            .map { it.copy(item = listOf(it.item).applySubjectOverrides(view.overrides).single()) }
    }

    fun schedule(scope: PocketScheduleScope, clock: PocketClock, view: View = View()): PocketSchedule {
        val monday = clock.date().shiftDays(7L * view.weekOffset - (clock.dayOfWeek - 1))
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val source = report(if (isWeek) ScheduleScope.CURRENT_WEEK else ScheduleScope.SEMESTER, clock, view)
        val days = (1..SCHOOL_DAYS).map { day ->
            PocketScheduleDay(
                dayOfWeek = day,
                label = WEEKDAY_LABELS[day - 1],
                dateText = monday.shiftDays(day - 1L).shortText(),
                isToday = day == clock.dayOfWeek && (!isWeek || view.weekOffset == 0),
            )
        }
        val cells = mergedCells(slots(scope, clock, view), clock, trackStatus = isWeek && view.weekOffset == 0)
        val (year, term) = source.yearTermValue.split("_").let { it[0] to it.getOrElse(1) { "1" } }
        return PocketSchedule(
            scope = scope,
            scopeLabel = if (isWeek) "本週課表" else "學期課表",
            termText = "$year 學年度 第 $term 學期",
            className = classOf(view).text,
            rangeText = "${monday.shortText()} – ${monday.shiftDays(SCHOOL_DAYS - 1L).shortText()}",
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
                val now = clock.minuteOfDay
                val inLesson = (head.item.period until head.item.period + span).any {
                    now >= minuteOfDay(periods[it - 1].start) && now < minuteOfDay(periods[it - 1].end)
                }
                val status = when {
                    !trackStatus || day != clock.dayOfWeek || head.change == PocketCellChange.CANCELLED -> PocketCellStatus.NORMAL
                    now >= minuteOfDay(end.end) -> PocketCellStatus.ENDED
                    inLesson -> PocketCellStatus.CURRENT
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
                    originalSubject = head.original,
                    isOverridden = head.original != head.item.subjectName,
                )
                index += span
            }
        }
        return result
    }

    /** Lessons of the coming week: this week's dated report first, the semester pattern afterwards. */
    private fun occurrences(clock: PocketClock): List<CourseOccurrence> {
        val today = clock.date()
        val week = report(ScheduleScope.CURRENT_WEEK, clock)
        val semester = report(ScheduleScope.SEMESTER, clock)
        val sunday = today.shiftDays(7L - clock.dayOfWeek)
        return (0L..7L).map(today::shiftDays).flatMap { date ->
            val source = if (date <= sunday) week else semester
            source.displayItems().filter { it.dayOfWeek == date.weekday().isoNumber }.mapNotNull { it.occurrenceOn(date) }
        }.sortedBy { it.start }
    }

    fun hero(clock: PocketClock): PocketCourseHero? {
        val now = clock.instant()
        val today = clock.date()
        val all = occurrences(clock)
        if (all.isEmpty()) return null
        fun CourseOccurrence.minutes(): Pair<Int, Int> = periods[item.period - 1].let { minuteOfDay(it.start) to minuteOfDay(it.end) }
        fun CourseOccurrence.date() = schoolDateTimeAt(start.epochMillis()).datePart()
        val current = resolveCourses(report(ScheduleScope.CURRENT_WEEK, clock), now).current
        val next = all.firstOrNull { it.start > now }

        if (current != null) {
            val (start, end) = current.minutes()
            val period = periods[current.item.period - 1]
            return PocketCourseHero(
                headline = "現在上課 · 第 ${current.item.period} 節",
                courseName = current.item.subjectName,
                supportingText = place(current.item),
                isInClass = true,
                progress = (clock.minuteOfDay - start).toDouble() / (end - start),
                timeText = "${period.start} – ${period.end} · 還有 ${end - clock.minuteOfDay} 分鐘下課",
                nextLine = next?.takeIf { it.date().daysUntil(today) == 0L }
                    ?.let { "下一堂 ${it.item.subjectName} · ${periods[it.item.period - 1].start}" },
            )
        }
        val upcoming = all.firstOrNull { it.start > now } ?: return null
        val period = periods[upcoming.item.period - 1]
        val daysAhead = today.daysUntil(upcoming.date())
        if (daysAhead == 0L) {
            val minutes = upcoming.minutes().first - clock.minuteOfDay
            val wait = if (minutes >= 60) "${minutes / 60} 小時 ${minutes % 60} 分鐘後" else "$minutes 分鐘後"
            val following = all.firstOrNull {
                it.start > upcoming.start && it.date().daysUntil(today) == 0L
            }
            return PocketCourseHero(
                headline = "下一堂 · 第 ${upcoming.item.period} 節",
                courseName = upcoming.item.subjectName,
                supportingText = place(upcoming.item),
                isInClass = false,
                progress = -1.0,
                timeText = "${period.start} – ${period.end} · $wait",
                nextLine = following?.let { "接著 ${it.item.subjectName} · ${periods[it.item.period - 1].start}" },
            )
        }
        val label = if (daysAhead == 1L) "明天" else "週${upcoming.date().weekdayLabel()}"
        val hadLessonsToday = all.any { it.date().daysUntil(today) == 0L }
        return PocketCourseHero(
            headline = if (hadLessonsToday) "今日課程已結束" else "今天沒有課程",
            courseName = "$label ${upcoming.item.subjectName}",
            supportingText = place(upcoming.item),
            isInClass = false,
            progress = -1.0,
            timeText = "$label 第 ${upcoming.item.period} 節 · ${period.start} 開始",
            nextLine = null,
        )
    }

    /** This week's timetable changes followed by the latest grade publication. */
    fun alerts(clock: PocketClock): List<PocketAlertRow> {
        val week = schedule(PocketScheduleScope.CURRENT_WEEK, clock)
        val changes = week.cells.filter { it.change != PocketCellChange.NONE }.map { cell ->
            val day = week.days.first { it.dayOfWeek == cell.dayOfWeek }
            val verb = when (cell.change) {
                PocketCellChange.CANCELLED -> "停課"
                PocketCellChange.ADDED -> "加課"
                else -> "調課"
            }
            PocketAlertRow(
                id = "change-${cell.id}",
                title = "${cell.subject} $verb",
                detail = "週${day.label} ${day.dateText} 第 ${cell.period} 節" + if (cell.changeNote.isNotBlank()) " · ${cell.changeNote}" else "",
                symbol = if (cell.change == PocketCellChange.CANCELLED) "calendar.badge.minus" else "arrow.triangle.2.circlepath",
            )
        }
        val grade = DemoGrades.report(DemoGrades.DEFAULT_EXAM)
        return changes + PocketAlertRow("grade-${grade.examValue}", "${grade.examTitle}成績已公布", grade.termTitle, "chart.bar.fill")
    }

    private fun place(item: ScheduleItem): String =
        listOf(item.classroom, item.teacherName).filter { it.isNotEmpty() }.joinToString(" · ")
}
