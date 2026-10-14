package com.clhs.score.data

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal val SCHOOL_COURSE_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

internal fun ScheduleItem.occurrenceOn(date: LocalDate): CourseOccurrence? {
    val (start, end) = periodInterval() ?: return null
    return CourseOccurrence(this, date.atTime(start).atZone(SCHOOL_COURSE_ZONE).toInstant(),
        date.atTime(end).atZone(SCHOOL_COURSE_ZONE).toInstant())
}

internal fun ScheduleItem.periodInterval(): Pair<LocalTime, LocalTime>? {
    val times = PERIOD_TIMES.getOrNull(period - 1) ?: return null
    val start = runCatching { LocalTime.parse(times.start) }.getOrNull() ?: return null
    val end = runCatching { LocalTime.parse(times.end) }.getOrNull() ?: return null
    return (start to end).takeIf { start < end }
}

internal data class CourseOccurrence(
    val item: ScheduleItem,
    val start: Instant,
    val end: Instant,
) {
    fun contains(now: Instant): Boolean = now >= start && now < end
}

internal data class CourseResolution(
    val current: CourseOccurrence? = null,
    val nextBoundary: Instant? = null,
    val valid: Boolean = false,
    val upcoming: CourseOccurrence? = null,
)

/** Dated weekly reports use their listed dates; semester reports repeat by weekday. */
internal fun resolveCourses(report: ScheduleReport?, clock: Clock, remindBeforeClass: Boolean = false): CourseResolution {
    if (report == null) return CourseResolution()
    val now = clock.instant()
    val today = now.atZone(SCHOOL_COURSE_ZONE).toLocalDate()
    val weekRange = if (report.scope == ScheduleScope.CURRENT_WEEK) {
        val first = runCatching { LocalDate.parse(report.weekStartDate) }.getOrNull() ?: return CourseResolution()
        val last = runCatching { LocalDate.parse(report.weekEndDate) }.getOrNull() ?: return CourseResolution()
        if (ChronoUnit.DAYS.between(first, last) !in 0..6) return CourseResolution()
        if (now >= last.plusDays(1).atStartOfDay(SCHOOL_COURSE_ZONE).toInstant()) return CourseResolution()
        first to last
    } else {
        null
    }
    if (report.scope != ScheduleScope.CURRENT_WEEK && report.scope != ScheduleScope.SEMESTER) return CourseResolution()
    val semesterDates = (0L..7L).map(today::plusDays)
    val courses = report.displayItems().flatMap { item ->
        if (item.dayOfWeek !in 1..7 || item.subjectName.isBlank()) return@flatMap emptyList()
        val dates = if (report.scope == ScheduleScope.SEMESTER) {
            semesterDates.filter { it.dayOfWeek.value == item.dayOfWeek }
        } else {
            val (first, last) = weekRange ?: return@flatMap emptyList()
            listOf(first.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(item.dayOfWeek))))
                .filter { it <= last }
        }
        dates.mapNotNull(item::occurrenceOn)
    }.sortedBy { it.start }
    // Ambiguous overlapping slots are not safe to publish, even if their titles match.
    if (courses.zipWithNext().any { (a, b) -> a.end > b.start }) return CourseResolution()
    val current = courses.singleOrNull { it.contains(now) }
    val next = courses.firstOrNull { it.start > now }
    val advance = if (remindBeforeClass) next?.start?.minusSeconds(5 * 60L) else null
    val upcoming = next?.takeIf { current == null && advance != null && now >= advance }
    val weekExpiry = weekRange?.second?.plusDays(1)?.atStartOfDay(SCHOOL_COURSE_ZONE)?.toInstant()
    val boundary = listOfNotNull(current?.end, next?.start, advance?.takeIf { it > now }, weekExpiry).minOrNull()
    return CourseResolution(current, boundary, courses.any { it.end > now }, upcoming)
}
