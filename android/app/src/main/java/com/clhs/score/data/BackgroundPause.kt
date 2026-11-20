package com.clhs.score.data

import java.time.Instant
import java.time.ZoneId

fun BackgroundPause.contains(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val time = now.atZone(zone)
    return containsMinute(time.hour * 60 + time.minute)
}
