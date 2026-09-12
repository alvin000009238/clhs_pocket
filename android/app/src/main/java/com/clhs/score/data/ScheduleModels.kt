package com.clhs.score.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

@Serializable
data class PeriodTime(val start: String, val end: String) {
    val singleLine: String get() = "$start-$end"
    val multiLine: String get() = "$start\n$end"
}

val PERIOD_TIMES = listOf(
    PeriodTime("08:10", "09:00"),
    PeriodTime("09:10", "10:00"),
    PeriodTime("10:10", "11:00"),
    PeriodTime("11:10", "12:00"),
    PeriodTime("13:00", "13:50"),
    PeriodTime("14:00", "14:50"),
    PeriodTime("15:05", "15:55"),
    PeriodTime("16:05", "16:55"),
)

@Serializable
data class ScheduleYearTermOption(
    val text: String,
    val value: String,
)

@Serializable
data class ScheduleClassOption(
    val text: String,
    val value: String,
)

@Serializable
enum class ScheduleScope {
    SEMESTER,
    CURRENT_WEEK,
}

data class ScheduleWeekOption(
    val text: String,
    val value: String,
    val startDate: String,
    val endDate: String,
)

@Serializable
data class ScheduleItem(
    val dayOfWeek: Int, // 1 for Monday, 7 for Sunday
    val period: Int,    // 1 to 9 (or more)
    val subjectName: String,
    val teacherName: String = "",
    val classroom: String = "",
)

const val SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH = 80

@Serializable
data class ScheduleSubjectOverride(
    val originalSubjectName: String,
    val customSubjectName: String? = null,
    val customTeacherName: String? = null,
    val customClassroom: String? = null,
)

internal fun ScheduleSubjectOverride.normalizedOrNull(): ScheduleSubjectOverride? {
    val original = originalSubjectName.trim()
    require(original.isNotEmpty()) { "Original subject name is required" }

    fun String?.customValue(): String? = this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.also { value ->
            require(value.length <= SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH) {
                "Custom schedule value is too long"
            }
        }

    val normalized = copy(
        originalSubjectName = original,
        customSubjectName = customSubjectName.customValue(),
        customTeacherName = customTeacherName.customValue(),
        customClassroom = customClassroom.customValue(),
    )
    return normalized.takeIf {
        it.customSubjectName != null || it.customTeacherName != null || it.customClassroom != null
    }
}

internal fun List<ScheduleSubjectOverride>.normalizedSubjectOverrides(): List<ScheduleSubjectOverride> =
    mapNotNull(ScheduleSubjectOverride::normalizedOrNull)
        .associateBy(ScheduleSubjectOverride::originalSubjectName)
        .values
        .sortedBy(ScheduleSubjectOverride::originalSubjectName)

internal fun List<ScheduleItem>.applySubjectOverrides(
    overrides: List<ScheduleSubjectOverride>,
): List<ScheduleItem> {
    if (overrides.isEmpty()) return this
    val overridesBySubject = overrides.associateBy(ScheduleSubjectOverride::originalSubjectName)
    return map { item ->
        val override = overridesBySubject[item.subjectName] ?: return@map item
        item.copy(
            subjectName = override.customSubjectName ?: item.subjectName,
            teacherName = override.customTeacherName ?: item.teacherName,
            classroom = override.customClassroom ?: item.classroom,
        )
    }
}

@Serializable
enum class ScheduleChangeType {
    ADDED,
    REMOVED,
    MODIFIED,
}

@Serializable
data class ScheduleChange(
    val type: ScheduleChangeType,
    val dayOfWeek: Int,
    val period: Int,
    val semesterItem: ScheduleItem? = null,
    val weekItem: ScheduleItem? = null,
)

@Serializable
data class ScheduleReport(
    val yearTermValue: String,
    val classNo: String,
    val scope: ScheduleScope,
    val weekNo: String? = null,
    val weekStartDate: String? = null,
    val weekEndDate: String? = null,
    val items: List<ScheduleItem>,
    val changes: List<ScheduleChange>? = null,
    val subjectOverrides: List<ScheduleSubjectOverride> = emptyList(),
) {
    fun isValidOn(date: LocalDate): Boolean {
        if (scope == ScheduleScope.SEMESTER) return true
        val start = weekStartDate?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() } ?: return false
        val end = weekEndDate?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() } ?: return false
        return date in start..end
    }
}

internal fun ScheduleReport.displayItems(): List<ScheduleItem> =
    items.applySubjectOverrides(subjectOverrides)

@JvmName("applySubjectOverridesToChanges")
internal fun List<ScheduleChange>.applySubjectOverrides(
    overrides: List<ScheduleSubjectOverride>,
): List<ScheduleChange> {
    if (overrides.isEmpty()) return this
    return map { change ->
        change.copy(
            semesterItem = change.semesterItem
                ?.let(::listOf)
                ?.applySubjectOverrides(overrides)
                ?.single(),
            weekItem = change.weekItem
                ?.let(::listOf)
                ?.applySubjectOverrides(overrides)
                ?.single(),
        )
    }
}

internal fun ScheduleReport.displayChanges(): List<ScheduleChange> =
    changes.orEmpty().applySubjectOverrides(subjectOverrides)

internal fun ScheduleReport.refreshAt(): LocalDateTime? {
    if (scope != ScheduleScope.CURRENT_WEEK) return null
    val startDate = weekStartDate
        ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        ?: return LocalDateTime.MIN
    val datedItems = items.map { item ->
        val dayOfWeek = runCatching { DayOfWeek.of(item.dayOfWeek) }.getOrNull()
            ?: return LocalDateTime.MIN
        startDate.with(TemporalAdjusters.nextOrSame(dayOfWeek)) to item
    }
    val lastClassDate = datedItems.maxOfOrNull { it.first }
        ?: return startDate.plusWeeks(1).atStartOfDay()
    val endTimes = datedItems.filter { it.first == lastClassDate }.map { (_, item) ->
        PERIOD_TIMES.getOrNull(item.period - 1)
            ?.end
            ?.let { value -> runCatching { LocalTime.parse(value) }.getOrNull() }
    }
    val refreshTime = if (endTimes.any { it == null }) {
        LocalTime.parse(PERIOD_TIMES.last().end)
    } else {
        endTimes.filterNotNull().maxOrNull() ?: return LocalDateTime.MIN
    }
    return lastClassDate.atTime(refreshTime)
}

internal fun ScheduleReport.shouldRefreshAt(now: LocalDateTime): Boolean =
    refreshAt()?.let { refreshAt -> !now.isBefore(refreshAt) } ?: false

internal fun ScheduleReport.refreshTargetDateAt(now: LocalDateTime): LocalDate {
    val today = now.toLocalDate()
    val nextWeekStart = weekStartDate
        ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
        ?.plusWeeks(1)
        ?: return today
    return if (shouldRefreshAt(now) && today < nextWeekStart) nextWeekStart else today
}

internal fun compareScheduleItems(
    semesterItems: List<ScheduleItem>,
    weekItems: List<ScheduleItem>,
): List<ScheduleChange> {
    val semesterBySlot = semesterItems.associateBy { it.dayOfWeek to it.period }
    val weekBySlot = weekItems.associateBy { it.dayOfWeek to it.period }

    return (semesterBySlot.keys + weekBySlot.keys)
        .sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        .mapNotNull { (dayOfWeek, period) ->
            val semesterItem = semesterBySlot[dayOfWeek to period]
            val weekItem = weekBySlot[dayOfWeek to period]
            if (semesterItem == weekItem) return@mapNotNull null

            ScheduleChange(
                type = when {
                    semesterItem == null -> ScheduleChangeType.ADDED
                    weekItem == null -> ScheduleChangeType.REMOVED
                    else -> ScheduleChangeType.MODIFIED
                },
                dayOfWeek = dayOfWeek,
                period = period,
                semesterItem = semesterItem,
                weekItem = weekItem,
            )
        }
}

internal fun parseScheduleWeekOptions(json: String): List<ScheduleWeekOption> {
    val root = runCatching { SchoolJson.parseToJsonElement(json) }.getOrNull()
    val options = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["Data"] ?: root["data"] ?: root["Result"] ?: root["result"] ?: root["items"]) as? JsonArray
        else -> null
    } ?: return emptyList()

    return options.mapNotNull { element ->
        val option = element as? JsonObject ?: return@mapNotNull null
        val item = option["Item"] as? JsonObject
        val weekNo = (option.stringField("Value", "value") ?: item?.stringField("WeekNo"))
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.toString()
            ?: return@mapNotNull null
        val startDate = item?.dateField("StartDateDisplay", "StartDate") ?: return@mapNotNull null
        val endDate = item.dateField("EndDateDisplay", "EndDate") ?: return@mapNotNull null
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return@mapNotNull null
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return@mapNotNull null
        if (end < start) return@mapNotNull null

        ScheduleWeekOption(
            text = option.stringField("DisplayText", "Text", "text") ?: "第 $weekNo 週",
            value = weekNo,
            startDate = startDate,
            endDate = endDate,
        )
    }
}

internal fun selectScheduleWeek(
    options: List<ScheduleWeekOption>,
    targetDate: LocalDate,
): ScheduleWeekOption? =
    options
        .filter { option ->
            val start = runCatching { LocalDate.parse(option.startDate) }.getOrNull()
            val end = runCatching { LocalDate.parse(option.endDate) }.getOrNull()
            start != null && end != null && targetDate in start..end
        }
        .distinctBy { it.value }
        .singleOrNull()

internal fun parseScheduleItems(timetableJson: String): List<ScheduleItem> {
    val root = runCatching { SchoolJson.parseToJsonElement(timetableJson) }.getOrNull() ?: return emptyList()
    val items = mutableListOf<ScheduleItem>()

    fun extractClasses(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val subjectName = element.stringField(
                    "SubjectName",
                    "subjectName",
                    "CourseName",
                    "SubjectDisplay",
                )
                val dayOfWeek = element.intField("WeekDay", "weekDay", "DayOfWeek")
                val period = element.intField("SectionSeq", "sectionSeq", "Section", "Period")

                if (!subjectName.isNullOrBlank() && dayOfWeek != null && dayOfWeek in 1..7 && period != null && period > 0) {
                    items.add(
                        ScheduleItem(
                            dayOfWeek = dayOfWeek,
                            period = period,
                            subjectName = subjectName,
                            teacherName = element.stringField("TeacherNameDisplay", "TeacherName", "FirstTeacherName").orEmpty(),
                            classroom = element.stringField("ClassroomName", "ClassroomDisplay", "Classroom").orEmpty(),
                        ),
                    )
                }

                element.values.forEach { extractClasses(it) }
            }
            is JsonArray -> element.forEach { extractClasses(it) }
            else -> Unit
        }
    }

    extractClasses(root)
    return items.distinctBy { it.dayOfWeek to it.period }
}

private fun JsonObject.stringField(vararg names: String): String? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

private fun JsonObject.intField(vararg names: String): Int? =
    names.firstNotNullOfOrNull { name ->
        (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }

private fun JsonObject.dateField(vararg names: String): String? =
    stringField(*names)?.take(10)

val predefinedColors = listOf(
    0xFF984357,
    0xFF699C5E,
    0xFF5D70C3,
    0xFF984A26,
    0xFF31A18A,
    0xFF8D60B2,
    0xFF805C0E,
    0xFF209DB1,
    0xFFAB558A,
    0xFF616A0D,
    0xFF5591C9,
    0xFFB75357,
    0xFF217541,
    0xFF8483CA,
    0xFFB06018,
    0xFF0B726C,
    0xFFA877B4,
    0xFF8F7412,
    0xFF016E8B,
    0xFFBE708E,
    0xFF618524,
    0xFF3A62A4,
    0xFFC37363,
    0xFF128C64,
    0xFF6A539D,
    0xFFB67F40,
    0xFF08888E,
    0xFF884880,
    0xFF978F3C,
    0xFF1481B3,
)

val darkPredefinedColors = listOf(
    0xFFDD8898,
    0xFFAADA9F,
    0xFFA3B7FE,
    0xFFDC8F6D,
    0xFF83DFC8,
    0xFFCFA6F4,
    0xFFC79D53,
    0xFF7BDBEF,
    0xFFEF9CCD,
    0xFFA1AD5D,
    0xFF9BCDFE,
    0xFFFA9597,
    0xFF72BD8D,
    0xFFC3C2FF,
    0xFFF3A16A,
    0xFF62C1BA,
    0xFFE6B7F0,
    0xFFD2B854,
    0xFF56BED9,
    0xFFF5AECA,
    0xFFA9CD6B,
    0xFF83A8E8,
    0xFFF5AE9F,
    0xFF65CBA5,
    0xFFA998DF,
    0xFFF0B979,
    0xFF58CDD1,
    0xFFCB8CC2,
    0xFFD6CE80,
    0xFF65C1EA,
)

fun getSubjectColors(
    subjectNames: Iterable<String>,
    isDarkTheme: Boolean = false,
): Map<String, Long> {
    val palette = if (isDarkTheme) darkPredefinedColors else predefinedColors
    return subjectNames
        .distinct()
        .sorted()
        .mapIndexed { index, subjectName ->
            subjectName to palette[index % palette.size]
        }
        .toMap()
}
