package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleChangeType
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleReport
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

    private val ownClass get() = PocketData.current.scheduleClasses.firstOrNull()

    private val periods = PERIOD_TIMES.mapIndexed { index, time -> PocketPeriod(index + 1, time.start, time.end) }

    /** Periods 1–7 always show; period 8 and later periods only when a lesson or change uses them. */
    private const val ALWAYS_SHOWN_PERIODS = 7

    private fun period(number: Int): PocketPeriod = periods.getOrNull(number - 1) ?: PocketPeriod(number, "", "")

    private fun visiblePeriods(cells: List<PocketScheduleCell>): List<PocketPeriod> {
        val used = cells.flatMap { it.period until it.period + it.span }
        return ((1..ALWAYS_SHOWN_PERIODS) + used).filter { it >= 1 }.distinct().sorted().map(::period)
    }

    private data class Slot(
        val item: ScheduleItem, val change: PocketCellChange, val note: String, val original: String = item.subjectName,
    )

    /** What the schedule screen is looking at; the home hero always uses the defaults. */
    data class View(
        val classValue: String = "",
        val weekOffset: Int = 0,
        val overrides: List<ScheduleSubjectOverride> = emptyList(),
    )

    private fun classOf(view: View) =
        PocketData.current.scheduleClasses.firstOrNull { it.value == view.classValue } ?: ownClass

    /** Null while the live schedule has not been loaded, or is not valid for the requested week. */
    private fun report(scope: ScheduleScope, clock: PocketClock, view: View = View()): ScheduleReport? =
        PocketData.current.schedule(scope, clock.date().shiftDays(7L * view.weekOffset), view.classValue)

    private fun slots(scope: PocketScheduleScope, clock: PocketClock, view: View): List<Slot> {
        val isWeek = scope == PocketScheduleScope.CURRENT_WEEK
        val report = report(if (isWeek) ScheduleScope.CURRENT_WEEK else ScheduleScope.SEMESTER, clock, view) ?: return emptyList()
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
            .filter { it.item.dayOfWeek <= SCHOOL_DAYS && it.item.period >= 1 }
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
        val (year, term) = source?.yearTermValue.orEmpty().split("_").let { it[0] to it.getOrElse(1) { "1" } }
        return PocketSchedule(
            scope = scope,
            scopeLabel = if (isWeek) "本週課表" else "學期課表",
            termText = if (source == null) "" else "$year 學年度 第 $term 學期",
            className = classOf(view)?.text.orEmpty(),
            rangeText = "${monday.shortText()} – ${monday.shiftDays(SCHOOL_DAYS - 1L).shortText()}",
            weekText = source?.weekNo?.let { "第 $it 週" }.orEmpty(),
            days = days,
            periods = visiblePeriods(cells),
            cells = cells,
            changeCount = source?.changes.orEmpty().size,
            isAvailable = source != null,
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
                val start = period(head.item.period)
                val end = period(head.item.period + span - 1)
                val now = clock.minuteOfDay
                val timed = start.start.isNotEmpty() && end.end.isNotEmpty()
                val inLesson = timed && (head.item.period until head.item.period + span).any {
                    now >= minuteOfDay(period(it).start) && now < minuteOfDay(period(it).end)
                }
                val status = when {
                    !trackStatus || !timed || day != clock.dayOfWeek || head.change == PocketCellChange.CANCELLED -> PocketCellStatus.NORMAL
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
                    timeText = if (timed) "${start.start} – ${end.end}" else "第 ${head.item.period} 節",
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
            source?.displayItems().orEmpty().filter { it.dayOfWeek == date.weekday().isoNumber }.mapNotNull { it.occurrenceOn(date) }
        }.sortedBy { it.start }
    }

    fun hero(clock: PocketClock): PocketCourseHero? {
        val now = clock.instant()
        val today = clock.date()
        val all = occurrences(clock)
        if (all.isEmpty()) return null
        fun CourseOccurrence.minutes(): Pair<Int, Int> = period(item.period).let { minuteOfDay(it.start) to minuteOfDay(it.end) }
        fun CourseOccurrence.date() = schoolDateTimeAt(start.epochMillis()).datePart()
        val current = resolveCourses(report(ScheduleScope.CURRENT_WEEK, clock), now).current
        val next = all.firstOrNull { it.start > now }

        if (current != null) {
            val (start, end) = current.minutes()
            val period = period(current.item.period)
            return PocketCourseHero(
                headline = "現在上課 · 第 ${current.item.period} 節",
                courseName = current.item.subjectName,
                supportingText = place(current.item),
                isInClass = true,
                progress = (clock.minuteOfDay - start).toDouble() / (end - start),
                timeText = "${period.start} – ${period.end} · 還有 ${end - clock.minuteOfDay} 分鐘下課",
                nextLine = next?.takeIf { it.date().daysUntil(today) == 0L }
                    ?.let { "下一堂 ${it.item.subjectName} · ${period(it.item.period).start}" },
            )
        }
        val upcoming = all.firstOrNull { it.start > now } ?: return null
        val period = period(upcoming.item.period)
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
                nextLine = following?.let { "接著 ${it.item.subjectName} · ${period(it.item.period).start}" },
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
        if (!grade.isLoaded) return changes
        return changes + PocketAlertRow("grade-${grade.examValue}", "${grade.examTitle}成績已公布", grade.termTitle, "chart.bar.fill")
    }

    private fun place(item: ScheduleItem): String =
        listOf(item.classroom, item.teacherName).filter { it.isNotEmpty() }.joinToString(" · ")
}
