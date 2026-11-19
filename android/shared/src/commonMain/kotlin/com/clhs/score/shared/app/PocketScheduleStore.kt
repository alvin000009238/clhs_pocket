package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.normalizedOrNull
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

    fun classes(): List<PocketChoice> = PocketData.current.scheduleClasses.map { PocketChoice(it.value, it.text, "") }

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
