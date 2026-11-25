@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.clhs.score.shared.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Calendar types for shared school logic: `java.time` on Android (so existing Android callers keep
 * their exact types) and the portable implementations elsewhere. Factories and accessors are
 * top-level functions because `java.time` statics cannot be expressed on an expect class.
 */
expect enum class DayOfWeek { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

expect class LocalDate
expect class LocalTime : Comparable<LocalTime>
expect class LocalDateTime

/** ISO number, Monday = 1. */
val DayOfWeek.isoNumber: Int get() = ordinal + 1

/** Throws for numbers outside 1..7, like `DayOfWeek.of`. */
fun dayOfWeekOf(isoNumber: Int): DayOfWeek {
    require(isoNumber in 1..7) { "Invalid day of week" }
    return DayOfWeek.entries[isoNumber - 1]
}

/** Strict ISO `yyyy-MM-dd`; throws on anything else. */
expect fun parseLocalDate(text: String): LocalDate
expect fun localDateOf(year: Int, month: Int, day: Int): LocalDate
expect fun localDateOfEpochDay(epochDay: Long): LocalDate
expect operator fun LocalDate.compareTo(other: LocalDate): Int
expect fun LocalDate.epochDay(): Long
expect fun LocalDate.yearNumber(): Int
expect fun LocalDate.monthNumber(): Int
expect fun LocalDate.dayNumber(): Int
expect fun LocalDate.weekday(): DayOfWeek
expect fun LocalDate.shiftDays(days: Long): LocalDate
expect fun LocalDate.shiftMonths(months: Long): LocalDate
expect fun LocalDate.at(time: LocalTime): LocalDateTime

fun parseLocalDateOrNull(text: String): LocalDate? = runCatching { parseLocalDate(text) }.getOrNull()

/** Same contract as `TemporalAdjusters.nextOrSame`. */
fun LocalDate.nextOrSame(dayOfWeek: DayOfWeek): LocalDate =
    shiftDays(((dayOfWeek.isoNumber - weekday().isoNumber + 7) % 7).toLong())

/** Same contract as `TemporalAdjusters.previousOrSame`. */
fun LocalDate.previousOrSame(dayOfWeek: DayOfWeek): LocalDate =
    shiftDays(-((weekday().isoNumber - dayOfWeek.isoNumber + 7) % 7).toLong())

/** Whole days from this date to [other]; negative when [other] is earlier. */
fun LocalDate.daysUntil(other: LocalDate): Long = other.epochDay() - epochDay()

/** `HH:mm`, `HH:mm:ss` or fractional seconds; throws on anything else. */
expect fun parseLocalTime(text: String): LocalTime
expect fun localTimeOf(hour: Int, minute: Int): LocalTime
expect fun LocalTime.hourNumber(): Int
expect fun LocalTime.minuteNumber(): Int

fun parseLocalTimeOrNull(text: String): LocalTime? = runCatching { parseLocalTime(text) }.getOrNull()

expect val LOCAL_DATE_TIME_MIN: LocalDateTime
expect operator fun LocalDateTime.compareTo(other: LocalDateTime): Int
expect fun LocalDateTime.datePart(): LocalDate
expect fun LocalDateTime.timePart(): LocalTime

/** School wall-clock time. Taiwan has used a fixed UTC+8 offset without daylight saving since 1980. */
const val SCHOOL_ZONE_ID = "Asia/Taipei"
const val SCHOOL_UTC_OFFSET_SECONDS = 8 * 60 * 60

expect fun schoolDateTimeAt(epochMillis: Long): LocalDateTime
expect fun LocalDateTime.schoolEpochMillis(): Long

fun currentEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
fun schoolNow(): LocalDateTime = schoolDateTimeAt(currentEpochMillis())
fun schoolToday(): LocalDate = schoolNow().datePart()

expect class Instant : Comparable<Instant>

expect fun instantOfEpochMilli(epochMilli: Long): Instant
/** ISO UTC text such as `2026-08-09T05:00:00Z`; throws on anything else. */
expect fun parseInstant(text: String): Instant
expect fun Instant.epochMillis(): Long

fun currentInstant(): Instant = instantOfEpochMilli(currentEpochMillis())

expect fun LocalTime.nanoOfDayValue(): Long
expect fun LocalDateTime.shiftNanos(nanos: Long): LocalDateTime

/**
 * Signed time from this date-time to [other], like `java.time.Duration.between`. Exact to the
 * nanosecond within about ±146 years and to the millisecond beyond, so every supported date fits.
 */
fun LocalDateTime.durationUntil(other: LocalDateTime): Duration =
    datePart().daysUntil(other.datePart()).days +
        (other.timePart().nanoOfDayValue() - timePart().nanoOfDayValue()).nanoseconds

const val NANOS_PER_MINUTE = 60_000_000_000L
const val NANOS_PER_HOUR = 60 * NANOS_PER_MINUTE
const val NANOS_PER_DAY = 24 * NANOS_PER_HOUR

/** Zero-padded `HH:mm`. */
fun LocalTime.hourMinuteText(): String =
    "${hourNumber().toString().padStart(2, '0')}:${minuteNumber().toString().padStart(2, '0')}"

/** Whether the platform recognises [id] as a time zone. */
expect fun isValidTimeZoneId(id: String): Boolean
expect fun randomUuidString(): String

/** Chronological order; `java.time.LocalDateTime` is not `Comparable<LocalDateTime>` in common code. */
val LOCAL_DATE_TIME_ORDER: Comparator<LocalDateTime> get() = Comparator { a, b -> a.compareTo(b) }
