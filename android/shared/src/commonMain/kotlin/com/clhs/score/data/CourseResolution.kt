package com.clhs.score.data

import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.LocalTime
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.dayOfWeekOf
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.nextOrSame
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.parseLocalTimeOrNull
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.schoolEpochMillis
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.weekday

private fun LocalDate.schoolInstantAt(time: LocalTime): Instant = instantOfEpochMilli(at(time).schoolEpochMillis())

private fun LocalDate.schoolStartOfDay(): Instant = schoolInstantAt(localTimeOf(0, 0))

fun ScheduleItem.occurrenceOn(date: LocalDate): CourseOccurrence? {
    val (start, end) = periodInterval() ?: return null
    return CourseOccurrence(this, date.schoolInstantAt(start), date.schoolInstantAt(end))
}

fun ScheduleItem.periodInterval(): Pair<LocalTime, LocalTime>? {
    val times = PERIOD_TIMES.getOrNull(period - 1) ?: return null
    val start = parseLocalTimeOrNull(times.start) ?: return null
    val end = parseLocalTimeOrNull(times.end) ?: return null
    return (start to end).takeIf { start < end }
}

data class CourseOccurrence(
    val item: ScheduleItem,
    val start: Instant,
    val end: Instant,
) {
    fun contains(now: Instant): Boolean = now >= start && now < end
}

data class CourseResolution(
    val current: CourseOccurrence? = null,
    val nextBoundary: Instant? = null,
    val valid: Boolean = false,
    val upcoming: CourseOccurrence? = null,
    val scheduleAvailable: Boolean = valid,
)

/** Dated weekly reports use their listed dates; semester reports repeat by weekday. */
fun resolveCourses(report: ScheduleReport?, now: Instant, remindBeforeClass: Boolean = false): CourseResolution {
    if (report == null) return CourseResolution()
    val today = schoolDateTimeAt(now.epochMillis()).datePart()
    val weekRange = if (report.scope == ScheduleScope.CURRENT_WEEK) {
        val first = report.weekStartDate?.let(::parseLocalDateOrNull) ?: return CourseResolution()
        val last = report.weekEndDate?.let(::parseLocalDateOrNull) ?: return CourseResolution()
        if (first.daysUntil(last) !in 0..6) return CourseResolution()
        if (now >= last.shiftDays(1).schoolStartOfDay()) return CourseResolution()
        first to last
    } else {
        null
    }
    if (report.scope != ScheduleScope.CURRENT_WEEK && report.scope != ScheduleScope.SEMESTER) return CourseResolution()
    val items = report.displayItems()
    if (items.any { it.dayOfWeek !in 1..7 || it.subjectName.isBlank() || it.periodInterval() == null }) {
        return CourseResolution()
    }
    val semesterDates = (0L..7L).map(today::shiftDays)
    val courses = items.flatMap { item ->
        val dates = if (report.scope == ScheduleScope.SEMESTER) {
            semesterDates.filter { it.weekday().isoNumber == item.dayOfWeek }
        } else {
            val (first, last) = weekRange ?: return@flatMap emptyList()
            listOf(first.nextOrSame(dayOfWeekOf(item.dayOfWeek))).filter { it <= last }
        }
        dates.mapNotNull(item::occurrenceOn)
    }.sortedBy { it.start }
    // Ambiguous overlapping slots are not safe to publish, even if their titles match.
    if (courses.zipWithNext().any { (a, b) -> a.end > b.start }) return CourseResolution()
    val current = courses.singleOrNull { it.contains(now) }
    val next = courses.firstOrNull { it.start > now }
    val advance = if (remindBeforeClass) next?.start?.let { instantOfEpochMilli(it.epochMillis() - 5 * 60_000L) } else null
    val upcoming = next?.takeIf { current == null && advance != null && now >= advance }
    val weekExpiry = weekRange?.second?.shiftDays(1)?.schoolStartOfDay()
    val boundary = listOfNotNull(current?.end, next?.start, advance?.takeIf { it > now }, weekExpiry).minOrNull()
    return CourseResolution(current, boundary, courses.any { it.end > now }, upcoming,
        scheduleAvailable = courses.isNotEmpty() || (weekRange != null && report.items.isEmpty()))
}
