package com.clhs.score.data

import java.time.Instant
import java.time.ZoneId

/** One daily device-local interval; the end is exclusive. */
data class BackgroundPause(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439)
        require(startMinute != endMinute)
    }

    fun contains(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!enabled) return false
        val time = now.atZone(zone)
        val minute = time.hour * 60 + time.minute
        return if (startMinute < endMinute) minute in startMinute until endMinute
        else minute >= startMinute || minute < endMinute
    }
}
