package com.clhs.score.data

import kotlinx.serialization.Serializable

/** User-authored timetable changes; the on-disk JSON shape is shared with Android. */
const val SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH = 80

@Serializable
data class ScheduleSubjectOverride(
    val originalSubjectName: String,
    val customSubjectName: String? = null,
    val customTeacherName: String? = null,
    val customClassroom: String? = null,
)

/**
 * Preserves the existing Android normalization and validation semantics.
 * Public because the Android app and the iOS app consume a separate KMP module.
 */
fun ScheduleSubjectOverride.normalizedOrNull(): ScheduleSubjectOverride? {
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

/** Last valid customization for the same original subject wins, then sort by key. */
fun List<ScheduleSubjectOverride>.normalizedSubjectOverrides(): List<ScheduleSubjectOverride> =
    mapNotNull(ScheduleSubjectOverride::normalizedOrNull)
        .associateBy(ScheduleSubjectOverride::originalSubjectName)
        .values
        .sortedBy(ScheduleSubjectOverride::originalSubjectName)
