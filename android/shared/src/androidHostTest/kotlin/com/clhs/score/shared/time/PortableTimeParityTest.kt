package com.clhs.score.shared.time

import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The iOS calendar types must agree with the `java.time` types Android keeps using. */
class PortableTimeParityTest {
    @Test
    fun datesMatchJavaTimeAcrossTheSupportedRange() {
        val random = Random(42)
        repeat(50_000) {
            val epochDay = random.nextLong(-700_000, 2_900_000)
            val expected = java.time.LocalDate.ofEpochDay(epochDay)
            val portable = PortableLocalDate.ofEpochDay(epochDay)
            assertEquals(expected.toString(), portable.toString())
            assertEquals(expected.dayOfWeek.name, portable.dayOfWeek.name)
            assertEquals(epochDay, PortableLocalDate.of(expected.year, expected.monthValue, expected.dayOfMonth).epochDay)
            assertEquals(epochDay, PortableLocalDate.parse(expected.toString()).epochDay)
            val months = random.nextLong(-30, 30)
            assertEquals(expected.plusMonths(months).toString(), portable.plusMonths(months).toString())
        }
    }

    @Test
    fun timesAndSchoolClockMatchJavaTime() {
        val random = Random(43)
        val zone = ZoneId.of(SCHOOL_ZONE_ID)
        repeat(20_000) {
            val nanos = when (it % 3) {
                0 -> random.nextLong(0, 86_400) * 1_000_000_000L
                1 -> random.nextLong(0, 86_400_000) * 1_000_000L
                else -> random.nextLong(0, 1_440) * 60_000_000_000L
            }
            val expected = java.time.LocalTime.ofNanoOfDay(nanos)
            assertEquals(expected.toString(), PortableLocalTime.ofNanoOfDay(nanos).toString())
            assertEquals(nanos, PortableLocalTime.parse(expected.toString()).nanoOfDay)

            val millis = random.nextLong(315_532_800_000L, 4_102_444_800_000L)
            val javaDateTime = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zone)
            val portable = PortableLocalDateTime.ofEpochSecond(
                millis.floorDiv(1000L), (millis.mod(1000L) * 1_000_000).toInt(), SCHOOL_UTC_OFFSET_SECONDS,
            )
            assertEquals(javaDateTime.toLocalDate().toString(), portable.date.toString())
            assertEquals(javaDateTime.toLocalTime().toString(), portable.time.toString())
            assertEquals(millis / 1000, portable.toEpochSecond(SCHOOL_UTC_OFFSET_SECONDS))
            val shift = random.nextLong(-400_000_000_000_000L, 400_000_000_000_000L)
            val shifted = portable.plusNanos(shift)
            assertEquals(javaDateTime.plusNanos(shift).toString().take(19), "${shifted.date}T${shifted.time}:00".take(19))
        }
    }

    @Test
    fun malformedTextIsRejectedLikeJavaTime() {
        listOf("2026-02-30", "2026-2-3", "20260203", "2026-13-01", "abcd-ef-gh", "").forEach { text ->
            assertFailsWith<Exception> { java.time.LocalDate.parse(text) }
            assertFailsWith<IllegalArgumentException>(text) { PortableLocalDate.parse(text) }
        }
        listOf("24:00", "8:00", "08:60", "08-00", "08:00:", "").forEach { text ->
            assertFailsWith<Exception> { java.time.LocalTime.parse(text) }
            assertFailsWith<IllegalArgumentException>(text) { PortableLocalTime.parse(text) }
        }
    }
}
