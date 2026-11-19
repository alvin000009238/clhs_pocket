package com.clhs.score.shared.app

import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.hourNumber
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.minuteNumber
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.parseLocalTime
import com.clhs.score.shared.time.schoolEpochMillis
import com.clhs.score.shared.time.weekday
import com.clhs.score.shared.time.yearNumber

internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun PocketClock.date(): LocalDate = localDateOf(year, month, day)
internal fun PocketClock.dateTime(): LocalDateTime = date().at(localTimeOf(minuteOfDay / 60, minuteOfDay % 60))
internal fun PocketClock.instant(): Instant = instantOfEpochMilli(dateTime().schoolEpochMillis())

internal fun Int.pad2(): String = toString().padStart(2, '0')
internal fun LocalDate.slashText(): String = "${yearNumber()}/${monthNumber().pad2()}/${dayNumber().pad2()}"
internal fun LocalDate.shortText(): String = "${monthNumber()}/${dayNumber()}"
internal fun LocalDate.weekdayLabel(): String = WEEKDAY_LABELS[weekday().isoNumber - 1]

internal fun minuteOfDay(hhmm: String): Int = parseLocalTime(hhmm).let { it.hourNumber() * 60 + it.minuteNumber() }

/** Formats a value held in tenths, e.g. 768 -> "76.8". */
internal fun tenthsText(tenths: Int): String {
    val abs = if (tenths < 0) -tenths else tenths
    return "${if (tenths < 0) "-" else ""}${abs / 10}.${abs % 10}"
}

/** Formats a value held in hundredths, e.g. 7683 -> "76.83". */
internal fun hundredthsText(hundredths: Int): String {
    val abs = if (hundredths < 0) -hundredths else hundredths
    return "${if (hundredths < 0) "-" else ""}${abs / 100}.${(abs % 100).pad2()}"
}
