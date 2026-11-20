package com.clhs.score.shared.net

import com.clhs.score.data.SCHOOL_CALENDAR_ICS_URL
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolCalendarFeed
import com.clhs.score.shared.time.LOCAL_DATE_TIME_ORDER
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.NANOS_PER_DAY
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.localDateOf
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.shiftNanos

/** Public school calendar feed. Never authenticated. */
class PublicCalendarClient(
    transport: HttpTransport,
    feedUrl: String = SCHOOL_CALENDAR_ICS_URL,
) {
    private val http = PublicHttp(transport)
    private val feedUrl = requireNotNull(PortableUrl.parse(feedUrl)) { "Invalid feed URL" }

    /** Raw ICS text; parse it with [SchoolCalendarIcs.parse]. */
    suspend fun fetchFeedBody(): String = http.get(feedUrl, "Calendar", MAX_FEED_BYTES, maxRedirects = 5)

    companion object {
        const val MAX_FEED_BYTES = 5L * 1024 * 1024
    }
}

/**
 * Portable ICS reader with the contract of the biweekly-based Android parser: single events only
 * (recurring ones are counted, not expanded), cancelled events dropped, all-day events as local dates,
 * timed events converted to school time.
 */
object SchoolCalendarIcs {
    private val recurrenceProperties = setOf("RRULE", "EXRULE", "RDATE", "EXDATE")

    private class Property(val name: String, val parameters: Map<String, String>, val value: String)

    fun parse(source: String): SchoolCalendarFeed {
        val lines = unfold(source.removePrefix("\uFEFF"))
        val begin = lines.indexOfFirst { it.equals("BEGIN:VCALENDAR", ignoreCase = true) }
        if (begin < 0) throw PublicDataException("Invalid calendar data")
        val events = ArrayList<List<Property>>()
        var current: ArrayList<Property>? = null
        var nested = 0
        for (line in lines.drop(begin + 1)) {
            val property = parseProperty(line) ?: continue
            when {
                property.name == "BEGIN" && property.value.equals("VEVENT", ignoreCase = true) && current == null ->
                    current = ArrayList()
                property.name == "BEGIN" -> if (current != null) nested++
                property.name == "END" && property.value.equals("VEVENT", ignoreCase = true) && nested == 0 -> {
                    current?.let(events::add)
                    current = null
                }
                property.name == "END" && property.value.equals("VCALENDAR", ignoreCase = true) && current == null -> break
                property.name == "END" -> if (current != null && nested > 0) nested--
                else -> if (nested == 0) current?.add(property)
            }
        }
        val (recurring, single) = events.partition { event -> event.any { it.name in recurrenceProperties } }
        return SchoolCalendarFeed(
            events = single.mapNotNull(::toEvent)
                .distinctBy(SchoolCalendarEvent::id)
                .sortedWith { a, b -> LOCAL_DATE_TIME_ORDER.compare(a.start, b.start) },
            skippedRecurringEvents = recurring.size,
        )
    }

    private fun unfold(source: String): List<String> {
        val lines = ArrayList<String>()
        for (raw in source.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + raw.substring(1)
            } else if (raw.isNotEmpty()) {
                lines.add(raw)
            }
        }
        return lines
    }

    private fun parseProperty(line: String): Property? {
        var quoted = false
        var colon = -1
        for ((index, char) in line.withIndex()) {
            if (char == '"') quoted = !quoted
            if (char == ':' && !quoted) {
                colon = index
                break
            }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val parameters = head.drop(1).mapNotNull { parameter ->
            val name = parameter.substringBefore('=', "").uppercase().takeIf(String::isNotEmpty) ?: return@mapNotNull null
            name to parameter.substringAfter('=').trim('"')
        }.toMap()
        return Property(head.first().substringAfterLast('.').uppercase(), parameters, line.substring(colon + 1))
    }

    private class DateValue(val dateTime: LocalDateTime, val hasTime: Boolean)

    private fun toEvent(properties: List<Property>): SchoolCalendarEvent? {
        fun first(name: String) = properties.firstOrNull { it.name == name }
        if (first("STATUS")?.value?.trim().equals("CANCELLED", ignoreCase = true)) return null
        val startValue = first("DTSTART")?.let(::parseDate) ?: return null
        val isAllDay = !startValue.hasTime
        val start = startValue.dateTime
        val parsedEnd = first("DTEND")?.let(::parseDate)?.dateTime
        val durationEnd = first("DURATION")?.value?.let(::parseDurationNanos)?.let(start::shiftNanos)
        val defaultEnd = if (isAllDay) start.shiftNanos(NANOS_PER_DAY) else start
        val endExclusive = (parsedEnd ?: durationEnd)?.takeIf { it >= start } ?: defaultEnd
        val title = first("SUMMARY")?.value?.let(::unescapeText)?.takeIf(String::isNotBlank) ?: "未命名活動"
        return SchoolCalendarEvent(
            id = first("UID")?.value?.takeIf(String::isNotBlank) ?: "$start-$title",
            title = title,
            start = start,
            endExclusive = endExclusive,
            isAllDay = isAllDay,
            location = first("LOCATION")?.value?.let(::unescapeText)?.takeIf(String::isNotBlank),
        )
    }

    private val DATE = Regex("""(\d{4})(\d{2})(\d{2})""")
    private val DATE_TIME = Regex("""(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z?)""")

    private fun parseDate(property: Property): DateValue? = runCatching {
        val value = property.value.trim()
        DATE.matchEntire(value)?.let { match ->
            val (year, month, day) = match.destructured
            return@runCatching DateValue(localDateOf(year.toInt(), month.toInt(), day.toInt()).at(localTimeOf(0, 0)), hasTime = false)
        }
        val match = DATE_TIME.matchEntire(value) ?: return@runCatching null
        val (year, month, day, hour, minute, second, utc) = match.destructured
        val date = localDateOf(year.toInt(), month.toInt(), day.toInt())
        val secondOfDay = hour.toInt() * 3600L + minute.toInt() * 60L + second.toInt()
        require(hour.toInt() < 24 && minute.toInt() < 60 && second.toInt() < 61)
        val offsetSeconds = if (utc == "Z") 0 else utcOffsetSeconds(property.parameters["TZID"])
        val local = if (offsetSeconds != null) {
            schoolDateTimeAt((date.epochDay() * 86_400L + secondOfDay - offsetSeconds) * 1000L)
        } else {
            // Floating times, Asia/Taipei and zones whose rules are not bundled are read as school
            // wall-clock time; the school feed itself only uses Asia/Taipei.
            date.at(localTimeOf(0, 0)).shiftNanos(secondOfDay * 1_000_000_000L)
        }
        DateValue(local, hasTime = true)
    }.getOrNull()

    private val DURATION = Regex("""([+-]?)P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""")

    private val ETC_GMT = Regex("""Etc/GMT([+-])(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val FIXED_OFFSET = Regex("""(?:UTC|GMT)?([+-])(\d{1,2})(?::?(\d{2}))?""", RegexOption.IGNORE_CASE)
    private val UTC_ZONES = setOf("utc", "gmt", "z", "zulu", "uct", "universal", "etc/utc", "etc/gmt", "etc/uct", "etc/universal", "etc/zulu", "etc/gmt0", "gmt0")

    /** UTC offset of a fixed-offset TZID, or null for a floating time or a zone read as school time. */
    private fun utcOffsetSeconds(tzid: String?): Int? {
        val zone = tzid?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (zone.lowercase() in UTC_ZONES) return 0
        ETC_GMT.matchEntire(zone)?.let { match ->
            // POSIX sign: Etc/GMT-8 is eight hours ahead of UTC.
            val hours = match.groupValues[2].toInt().takeIf { it <= 14 } ?: return null
            return (if (match.groupValues[1] == "-") hours else -hours) * 3_600
        }
        FIXED_OFFSET.matchEntire(zone)?.let { match ->
            val hours = match.groupValues[2].toInt()
            val minutes = match.groupValues[3].ifEmpty { "0" }.toInt()
            if (hours > 14 || minutes > 59) return null
            val seconds = hours * 3_600 + minutes * 60
            return if (match.groupValues[1] == "-") -seconds else seconds
        }
        return null
    }

    private fun parseDurationNanos(value: String): Long? {
        val match = DURATION.matchEntire(value.trim()) ?: return null
        val (sign, weeks, days, hours, minutes, seconds) = match.destructured
        var totalSeconds = 0L
        for ((text, unitSeconds) in listOf(weeks to 604_800L, days to 86_400L, hours to 3_600L, minutes to 60L, seconds to 1L)) {
            if (text.isEmpty()) continue
            // Each part is bounded before multiplying, so no input can overflow into a plausible value.
            val amount = text.toLongOrNull()?.takeIf { it <= MAX_DURATION_SECONDS / unitSeconds } ?: return null
            totalSeconds += amount * unitSeconds
            if (totalSeconds > MAX_DURATION_SECONDS) return null
        }
        return (if (sign == "-") -totalSeconds else totalSeconds) * 1_000_000_000L
    }

    private const val MAX_DURATION_SECONDS = 366L * 86_400

    private fun unescapeText(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n', 'N' -> append('\n')
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }
}
