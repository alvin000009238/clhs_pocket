package com.clhs.score.shared.app

/** Minimal proleptic Gregorian arithmetic so the demo core needs no date library. */
internal data class CivilDate(val year: Int, val month: Int, val day: Int)

internal fun PocketClock.epochDay(): Long = epochDayOf(year, month, day)

internal fun epochDayOf(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1L else year.toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

internal fun civilDateOf(epochDay: Long): CivilDate {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + (if (month <= 2) 1 else 0)).toInt()
    return CivilDate(year, month, day)
}

/** 1 = Monday … 7 = Sunday. */
internal fun dayOfWeekOf(epochDay: Long): Int = (((epochDay + 3) % 7 + 7) % 7).toInt() + 1

internal val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

internal fun Int.pad2(): String = if (this < 10) "0$this" else toString()

internal fun CivilDate.slashText(): String = "$year/${month.pad2()}/${day.pad2()}"

internal fun CivilDate.shortText(): String = "$month/$day"

internal fun minuteOfDay(hhmm: String): Int =
    hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

/** Formats a value held in tenths, e.g. 768 -> "76.8". */
internal fun tenthsText(tenths: Int): String {
    val sign = if (tenths < 0) "-" else ""
    val abs = if (tenths < 0) -tenths else tenths
    return "$sign${abs / 10}.${abs % 10}"
}

/** Formats a value held in hundredths, e.g. 7683 -> "76.83". */
internal fun hundredthsText(hundredths: Int): String {
    val sign = if (hundredths < 0) "-" else ""
    val abs = if (hundredths < 0) -hundredths else hundredths
    return "$sign${abs / 100}.${(abs % 100).pad2()}"
}
