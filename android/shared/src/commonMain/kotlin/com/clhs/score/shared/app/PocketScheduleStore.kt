package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.normalizedOrNull
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.shiftDays
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class PocketSubjectOverride(
    val original: String,
    val subject: String,
    val teacher: String,
    val classroom: String,
)

/**
 * Class and week switching plus personal subject overrides for the schedule screen.
 * Overrides travel as the JSON of the shared [ScheduleSubjectOverride] list and are
 * normalised by the shared rules, so both platforms accept the same values.
 */
object PocketScheduleStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ScheduleSubjectOverride.serializer())

    const val MIN_WEEK_OFFSET = -2
    const val MAX_WEEK_OFFSET = 2

    private fun decode(overrides: String): List<ScheduleSubjectOverride> =
        runCatching { json.decodeFromString(serializer, overrides) }.getOrDefault(emptyList()).valid()

    /** Entries the shared rules reject (for example a value over the length limit) are dropped, never thrown. */
    private fun List<ScheduleSubjectOverride>.valid(): List<ScheduleSubjectOverride> =
        mapNotNull { runCatching { it.normalizedOrNull() }.getOrNull() }.distinctBy { it.originalSubjectName }

    private fun encode(overrides: List<ScheduleSubjectOverride>) =
        json.encodeToString(serializer, overrides.valid())

    /** The overrides in the form the account store and the settings backup use. */
    fun overrideModels(overrides: String): List<ScheduleSubjectOverride> = decode(overrides)

    fun overridesJson(models: List<ScheduleSubjectOverride>): String = encode(models)

    fun classes(): List<PocketChoice> = PocketData.current.scheduleClasses.map { PocketChoice(it.value, it.text, "") }

    /** Terms to choose from, newest first, as the school lists them. */
    fun years(): List<PocketChoice> = PocketData.current.scheduleYears.map { PocketChoice(it.value, it.text, "") }

    /**
     * The school's weeks of [yearValue]: id is the week's Monday (ISO date), detail its date range.
     * Null while the list has not been loaded.
     */
    fun weeks(yearValue: String): List<PocketChoice>? = PocketData.current.scheduleWeeks(yearValue)?.mapNotNull { week ->
        val start = parseLocalDateOrNull(week.startDate) ?: return@mapNotNull null
        val end = parseLocalDateOrNull(week.endDate) ?: start.shiftDays(6)
        PocketChoice(week.startDate, week.text, "${start.shortText()} – ${end.shortText()}")
    }

    /** Whole weeks from the week of [clock] to the week starting on [startDate]; null when the date is malformed. */
    fun weekOffset(clock: PocketClock, startDate: String): Int? {
        val start = parseLocalDateOrNull(startDate) ?: return null
        val monday = clock.date().shiftDays(-(clock.dayOfWeek - 1).toLong())
        // A school week listed from its Sunday holds the lessons of the following Monday.
        val days = monday.daysUntil(start) + 1
        return (if (days >= 0) days / 7 else (days - 6) / 7).toInt()
    }

    /**
     * The timetable of the chosen term and class, any number of weeks away. Unlike [schedule]
     * the week is not limited, because the student can pick any week the school lists.
     */
    fun chosenSchedule(
        scope: PocketScheduleScope,
        clock: PocketClock,
        yearValue: String,
        classValue: String,
        weekOffset: Int,
        overrides: String,
    ): PocketSchedule = DemoSchedule.schedule(
        scope, clock,
        DemoSchedule.View(
            classValue,
            if (scope == PocketScheduleScope.CURRENT_WEEK) weekOffset else 0,
            decode(overrides),
            yearValue,
        ),
    )

    fun schedule(
        scope: PocketScheduleScope,
        clock: PocketClock,
        classValue: String,
        weekOffset: Int,
        overrides: String,
    ): PocketSchedule = DemoSchedule.schedule(
        scope, clock,
        DemoSchedule.View(
            classValue,
            if (scope == PocketScheduleScope.CURRENT_WEEK) weekOffset.coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET) else 0,
            decode(overrides),
        ),
    )

    fun overrides(overrides: String): List<PocketSubjectOverride> = decode(overrides).map {
        PocketSubjectOverride(
            it.originalSubjectName, it.customSubjectName.orEmpty(), it.customTeacherName.orEmpty(), it.customClassroom.orEmpty(),
        )
    }

    /** Blank fields keep the school's value; an override with nothing custom is removed. */
    fun setOverride(overrides: String, original: String, subject: String, teacher: String, classroom: String): String {
        // Same limit as Android's editor; longer input is cut rather than rejected so saving never throws.
        fun String.clamped() = trim().take(SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH).trim()
        val entry = runCatching {
            ScheduleSubjectOverride(
                originalSubjectName = original,
                customSubjectName = subject.clamped().takeIf { it.isNotEmpty() && it != original },
                customTeacherName = teacher.clamped().takeIf { it.isNotEmpty() },
                customClassroom = classroom.clamped().takeIf { it.isNotEmpty() },
            ).normalizedOrNull()
        }.getOrNull()
        return encode(decode(overrides).filter { it.originalSubjectName != original } + listOfNotNull(entry))
    }

    /** Longest subject, teacher or classroom text an override may hold. */
    fun maxOverrideLength(): Int = SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH

    fun clearOverride(overrides: String, original: String): String =
        encode(decode(overrides).filter { it.originalSubjectName != original })
}
