package com.clhs.score.data

import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class BackgroundPauseTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private fun instant(time: String) = java.time.LocalDateTime.parse("2026-09-27T$time").atZone(taipei).toInstant()

    @Test fun overnightIntervalIncludesStartAndExcludesEnd() {
        val pause = BackgroundPause(enabled = true)
        for ((time, expected) in listOf("22:59:59" to false, "23:00:00" to true,
            "23:59:59" to true, "00:00:00" to true, "06:59:59" to true, "07:00:00" to false)) {
            assertEquals(time, expected, pause.contains(instant(time), taipei))
        }
        assertFalse(BackgroundPause().contains(instant("00:00:00"), taipei))
    }

    @Test fun sameDayIntervalAndDeviceTimezoneAreRespected() {
        val pause = BackgroundPause(true, 12 * 60, 13 * 60)
        assertFalse(pause.contains(instant("11:59:59"), taipei))
        assertTrue(pause.contains(instant("12:00:00"), taipei))
        assertFalse(pause.contains(instant("13:00:00"), taipei))
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"))
            assertTrue(pause.contains(Instant.parse("2026-09-27T04:30:00Z")))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertFalse(pause.contains(Instant.parse("2026-09-27T04:30:00Z")))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun invalidIntervalsAreRejected() {
        for ((start, end) in listOf(-1 to 420, 1380 to 1440, 420 to 420)) {
            assertTrue(runCatching { BackgroundPause(true, start, end) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
