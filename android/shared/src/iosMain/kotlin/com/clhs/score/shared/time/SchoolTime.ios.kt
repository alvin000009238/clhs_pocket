@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.clhs.score.shared.time

import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.timeZoneWithName

actual typealias DayOfWeek = PortableDayOfWeek
actual typealias LocalDate = PortableLocalDate
actual typealias LocalTime = PortableLocalTime
actual typealias LocalDateTime = PortableLocalDateTime

actual fun parseLocalDate(text: String): LocalDate = PortableLocalDate.parse(text)
actual fun localDateOf(year: Int, month: Int, day: Int): LocalDate = PortableLocalDate.of(year, month, day)
actual fun localDateOfEpochDay(epochDay: Long): LocalDate = PortableLocalDate.ofEpochDay(epochDay)
actual operator fun LocalDate.compareTo(other: LocalDate): Int = epochDay.compareTo(other.epochDay)
actual fun LocalDate.epochDay(): Long = epochDay
actual fun LocalDate.yearNumber(): Int = year
actual fun LocalDate.monthNumber(): Int = monthValue
actual fun LocalDate.dayNumber(): Int = dayOfMonth
actual fun LocalDate.weekday(): DayOfWeek = dayOfWeek
actual fun LocalDate.shiftDays(days: Long): LocalDate = plusDays(days)
actual fun LocalDate.shiftMonths(months: Long): LocalDate = plusMonths(months)
actual fun LocalDate.at(time: LocalTime): LocalDateTime = PortableLocalDateTime(this, time)

actual fun parseLocalTime(text: String): LocalTime = PortableLocalTime.parse(text)
actual fun localTimeOf(hour: Int, minute: Int): LocalTime = PortableLocalTime.of(hour, minute)
actual fun LocalTime.hourNumber(): Int = hour
actual fun LocalTime.minuteNumber(): Int = minute

actual val LOCAL_DATE_TIME_MIN: LocalDateTime = PortableLocalDateTime.MIN
actual operator fun LocalDateTime.compareTo(other: LocalDateTime): Int =
    date.epochDay.compareTo(other.date.epochDay).takeIf { it != 0 } ?: time.nanoOfDay.compareTo(other.time.nanoOfDay)
actual fun LocalDateTime.datePart(): LocalDate = date
actual fun LocalDateTime.timePart(): LocalTime = time

actual fun schoolDateTimeAt(epochMillis: Long): LocalDateTime = PortableLocalDateTime.ofEpochSecond(
    epochMillis.floorDiv(1000L),
    (epochMillis.mod(1000L) * 1_000_000).toInt(),
    SCHOOL_UTC_OFFSET_SECONDS,
)

actual fun LocalDateTime.schoolEpochMillis(): Long =
    toEpochSecond(SCHOOL_UTC_OFFSET_SECONDS) * 1000 + time.nano / 1_000_000

actual typealias Instant = PortableInstant

actual fun instantOfEpochMilli(epochMilli: Long): Instant = PortableInstant.ofEpochMilli(epochMilli)
actual fun parseInstant(text: String): Instant = PortableInstant.parse(text)
actual fun Instant.epochMillis(): Long = epochMilli

actual fun LocalTime.nanoOfDayValue(): Long = nanoOfDay
actual fun LocalDateTime.shiftNanos(nanos: Long): LocalDateTime = plusNanos(nanos)
actual fun isValidTimeZoneId(id: String): Boolean = NSTimeZone.timeZoneWithName(id) != null
actual fun randomUuidString(): String = NSUUID().UUIDString.lowercase()
