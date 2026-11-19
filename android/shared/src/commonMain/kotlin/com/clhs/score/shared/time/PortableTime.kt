package com.clhs.score.shared.time

/**
 * Minimal ISO calendar types for platforms without `java.time`.
 * They mirror only the operations shared school logic needs; Android keeps the real `java.time` types.
 */
enum class PortableDayOfWeek { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

class PortableLocalDate private constructor(val epochDay: Long) : Comparable<PortableLocalDate> {
    val year: Int
    val monthValue: Int
    val dayOfMonth: Int

    init {
        // Howard Hinnant's civil-from-days algorithm.
        val z = epochDay + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        dayOfMonth = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
        monthValue = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        year = (yearOfEra + era * 400 + if (monthValue <= 2) 1 else 0).toInt()
    }

    val dayOfWeek: PortableDayOfWeek
        get() = PortableDayOfWeek.entries[((epochDay + 3) % 7 + 7).toInt() % 7]

    fun plusDays(days: Long): PortableLocalDate = ofEpochDay(epochDay + days)

    fun plusMonths(months: Long): PortableLocalDate {
        val total = year * 12L + (monthValue - 1) + months
        val newYear = total.floorDiv(12L).toInt()
        val newMonth = (total.mod(12L) + 1).toInt()
        return of(newYear, newMonth, minOf(dayOfMonth, lengthOfMonth(newYear, newMonth)))
    }

    override fun compareTo(other: PortableLocalDate): Int = epochDay.compareTo(other.epochDay)
    override fun equals(other: Any?): Boolean = other is PortableLocalDate && epochDay == other.epochDay
    override fun hashCode(): Int = epochDay.hashCode()
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${monthValue.pad2()}-${dayOfMonth.pad2()}"

    companion object {
        fun ofEpochDay(epochDay: Long): PortableLocalDate {
            require(epochDay in -365_243_219_162L..365_241_780_471L) { "Date out of range" }
            return PortableLocalDate(epochDay)
        }

        fun of(year: Int, month: Int, day: Int): PortableLocalDate {
            require(year in 0..9999 && month in 1..12 && day in 1..lengthOfMonth(year, month)) {
                "Invalid date"
            }
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return PortableLocalDate(era * 146_097 + dayOfEra - 719_468)
        }

        /** Strict `yyyy-MM-dd`, like `LocalDate.parse`. */
        fun parse(text: String): PortableLocalDate {
            require(text.length == 10 && text[4] == '-' && text[7] == '-') { "Invalid date" }
            return of(text.digits(0, 4), text.digits(5, 7), text.digits(8, 10))
        }

        fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

class PortableLocalTime private constructor(val nanoOfDay: Long) : Comparable<PortableLocalTime> {
    val hour: Int get() = (nanoOfDay / NANOS_PER_HOUR).toInt()
    val minute: Int get() = (nanoOfDay / NANOS_PER_MINUTE % 60).toInt()
    val second: Int get() = (nanoOfDay / NANOS_PER_SECOND % 60).toInt()
    val nano: Int get() = (nanoOfDay % NANOS_PER_SECOND).toInt()

    fun toSecondOfDay(): Int = (nanoOfDay / NANOS_PER_SECOND).toInt()

    override fun compareTo(other: PortableLocalTime): Int = nanoOfDay.compareTo(other.nanoOfDay)
    override fun equals(other: Any?): Boolean = other is PortableLocalTime && nanoOfDay == other.nanoOfDay
    override fun hashCode(): Int = nanoOfDay.hashCode()

    /** ISO form with the shortest of HH:mm, HH:mm:ss or fractional seconds, like `LocalTime.toString`. */
    override fun toString(): String = buildString {
        append(hour.pad2()).append(':').append(minute.pad2())
        if (second != 0 || nano != 0) append(':').append(second.pad2())
        if (nano != 0) {
            val fraction = nano.toString().padStart(9, '0')
            append('.').append(
                when {
                    nano % 1_000_000 == 0 -> fraction.substring(0, 3)
                    nano % 1_000 == 0 -> fraction.substring(0, 6)
                    else -> fraction
                },
            )
        }
    }

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND
        const val NANOS_PER_HOUR = 60 * NANOS_PER_MINUTE
        const val NANOS_PER_DAY = 24 * NANOS_PER_HOUR

        fun of(hour: Int, minute: Int, second: Int = 0, nano: Int = 0): PortableLocalTime {
            require(hour in 0..23 && minute in 0..59 && second in 0..59 && nano in 0..999_999_999) {
                "Invalid time"
            }
            return PortableLocalTime(
                hour * NANOS_PER_HOUR + minute * NANOS_PER_MINUTE + second * NANOS_PER_SECOND + nano,
            )
        }

        fun ofNanoOfDay(nanoOfDay: Long): PortableLocalTime {
            require(nanoOfDay in 0 until NANOS_PER_DAY) { "Invalid time" }
            return PortableLocalTime(nanoOfDay)
        }

        /** `HH:mm`, `HH:mm:ss` or `HH:mm:ss.fraction`, like `LocalTime.parse`. */
        fun parse(text: String): PortableLocalTime {
            require(text.length >= 5 && text[2] == ':') { "Invalid time" }
            val hour = text.digits(0, 2)
            val minute = text.digits(3, 5)
            if (text.length == 5) return of(hour, minute)
            require(text.length >= 8 && text[5] == ':') { "Invalid time" }
            val second = text.digits(6, 8)
            if (text.length == 8) return of(hour, minute, second)
            require(text[8] == '.' && text.length in 10..18) { "Invalid time" }
            val fraction = text.substring(9)
            return of(hour, minute, second, fraction.padEnd(9, '0').digits(0, 9))
        }
    }
}

class PortableLocalDateTime(val date: PortableLocalDate, val time: PortableLocalTime) :
    Comparable<PortableLocalDateTime> {
    override fun compareTo(other: PortableLocalDateTime): Int =
        date.compareTo(other.date).takeIf { it != 0 } ?: time.compareTo(other.time)

    override fun equals(other: Any?): Boolean =
        other is PortableLocalDateTime && date == other.date && time == other.time

    override fun hashCode(): Int = 31 * date.hashCode() + time.hashCode()
    override fun toString(): String = "${date}T$time"

    fun toEpochSecond(offsetSeconds: Int): Long =
        date.epochDay * 86_400 + time.toSecondOfDay() - offsetSeconds

    companion object {
        /** Earlier than any school date; stands in for `LocalDateTime.MIN`. */
        val MIN = PortableLocalDateTime(PortableLocalDate.of(0, 1, 1), PortableLocalTime.of(0, 0))

        fun ofEpochSecond(epochSecond: Long, nano: Int, offsetSeconds: Int): PortableLocalDateTime {
            val local = epochSecond + offsetSeconds
            val day = local.floorDiv(86_400L)
            val secondOfDay = local.mod(86_400L)
            return PortableLocalDateTime(
                PortableLocalDate.ofEpochDay(day),
                PortableLocalTime.ofNanoOfDay(secondOfDay * PortableLocalTime.NANOS_PER_SECOND + nano),
            )
        }
    }
}

private fun Int.pad2(): String = toString().padStart(2, '0')

private fun String.digits(start: Int, end: Int): Int {
    var value = 0
    for (index in start until end) {
        val char = this[index]
        require(char in '0'..'9') { "Invalid number" }
        value = value * 10 + (char - '0')
    }
    return value
}

/** UTC timestamp with millisecond precision, standing in for `java.time.Instant`. */
class PortableInstant private constructor(val epochMilli: Long) : Comparable<PortableInstant> {
    override fun compareTo(other: PortableInstant): Int = epochMilli.compareTo(other.epochMilli)
    override fun equals(other: Any?): Boolean = other is PortableInstant && epochMilli == other.epochMilli
    override fun hashCode(): Int = epochMilli.hashCode()

    override fun toString(): String {
        val utc = PortableLocalDateTime.ofEpochSecond(
            epochMilli.floorDiv(1000L), (epochMilli.mod(1000L) * 1_000_000).toInt(), 0,
        )
        val time = utc.time
        // `Instant.toString` always prints seconds.
        val seconds = if (time.second == 0 && time.nano == 0) ":00" else ""
        return "${utc.date}T$time${seconds}Z"
    }

    companion object {
        fun ofEpochMilli(epochMilli: Long): PortableInstant = PortableInstant(epochMilli)

        /** ISO UTC text such as `2026-08-09T05:00:00Z`. */
        fun parse(text: String): PortableInstant {
            require(text.length >= 20 && text[10] == 'T' && text.endsWith('Z')) { "Invalid instant" }
            val date = PortableLocalDate.parse(text.substring(0, 10))
            val time = PortableLocalTime.parse(text.substring(11, text.length - 1))
            return PortableInstant(
                PortableLocalDateTime(date, time).toEpochSecond(0) * 1000 + time.nano / 1_000_000,
            )
        }
    }
}
