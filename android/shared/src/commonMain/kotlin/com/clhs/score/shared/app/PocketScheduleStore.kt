package com.clhs.score.shared.app

import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.normalizedOrNull
import com.clhs.score.data.normalizedSubjectOverrides
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
        runCatching { json.decodeFromString(serializer, overrides) }.getOrDefault(emptyList()).normalizedSubjectOverrides()

    private fun encode(overrides: List<ScheduleSubjectOverride>) =
        json.encodeToString(serializer, overrides.normalizedSubjectOverrides())

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
        val entry = ScheduleSubjectOverride(
            originalSubjectName = original,
            customSubjectName = subject.trim().takeIf { it.isNotEmpty() && it != original },
            customTeacherName = teacher.trim().takeIf { it.isNotEmpty() },
            customClassroom = classroom.trim().takeIf { it.isNotEmpty() },
        ).normalizedOrNull()
        return encode(decode(overrides).filter { it.originalSubjectName != original } + listOfNotNull(entry))
    }

    fun clearOverride(overrides: String, original: String): String =
        encode(decode(overrides).filter { it.originalSubjectName != original })
}
