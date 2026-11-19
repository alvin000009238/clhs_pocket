@file:Suppress("ACTUAL_WITHOUT_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "EXTENSION_SHADOWED_BY_MEMBER")

package com.clhs.score.shared.time


import java.time.ZoneId

actual typealias DayOfWeek = java.time.DayOfWeek
actual typealias LocalDate = java.time.LocalDate
actual typealias LocalTime = java.time.LocalTime
actual typealias LocalDateTime = java.time.LocalDateTime

private val schoolZone: ZoneId = ZoneId.of(SCHOOL_ZONE_ID)

actual fun parseLocalDate(text: String): LocalDate = java.time.LocalDate.parse(text)
actual fun localDateOf(year: Int, month: Int, day: Int): LocalDate = java.time.LocalDate.of(year, month, day)
actual fun localDateOfEpochDay(epochDay: Long): LocalDate = java.time.LocalDate.ofEpochDay(epochDay)
actual operator fun LocalDate.compareTo(other: LocalDate): Int = compareTo(other as java.time.chrono.ChronoLocalDate)
actual fun LocalDate.epochDay(): Long = toEpochDay()
actual fun LocalDate.yearNumber(): Int = year
actual fun LocalDate.monthNumber(): Int = monthValue
actual fun LocalDate.dayNumber(): Int = dayOfMonth
actual fun LocalDate.weekday(): DayOfWeek = dayOfWeek
actual fun LocalDate.shiftDays(days: Long): LocalDate = plusDays(days)
actual fun LocalDate.shiftMonths(months: Long): LocalDate = plusMonths(months)
actual fun LocalDate.at(time: LocalTime): LocalDateTime = atTime(time)

actual fun parseLocalTime(text: String): LocalTime = java.time.LocalTime.parse(text)
actual fun localTimeOf(hour: Int, minute: Int): LocalTime = java.time.LocalTime.of(hour, minute)
actual fun LocalTime.hourNumber(): Int = hour
actual fun LocalTime.minuteNumber(): Int = minute

actual val LOCAL_DATE_TIME_MIN: LocalDateTime = java.time.LocalDateTime.MIN
actual operator fun LocalDateTime.compareTo(other: LocalDateTime): Int =
    compareTo(other as java.time.chrono.ChronoLocalDateTime<*>)
actual fun LocalDateTime.datePart(): LocalDate = toLocalDate()
actual fun LocalDateTime.timePart(): LocalTime = toLocalTime()

actual fun schoolDateTimeAt(epochMillis: Long): LocalDateTime =
    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), schoolZone)
actual fun LocalDateTime.schoolEpochMillis(): Long = atZone(schoolZone).toInstant().toEpochMilli()

actual typealias Instant = java.time.Instant

actual fun instantOfEpochMilli(epochMilli: Long): Instant = java.time.Instant.ofEpochMilli(epochMilli)
actual fun parseInstant(text: String): Instant = java.time.Instant.parse(text)
actual fun Instant.epochMillis(): Long = toEpochMilli()
