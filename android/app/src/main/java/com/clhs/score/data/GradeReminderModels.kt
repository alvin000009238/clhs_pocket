package com.clhs.score.data

import kotlinx.serialization.Serializable

@Serializable
data class GradeReminderState(
    val enabled: Boolean = false,
    val studentNo: String = "",
    val yearValue: String = "",
    val yearLabel: String = "",
    val examValue: String = "",
    val examName: String = "",
    val activatedAtMillis: Long = 0L,
    val expiresAtMillis: Long = 0L,
    val lastCheckedAtMillis: Long? = null,
    val snapshot: GradeReminderSnapshot? = null,
    val latestChangeSet: GradeChangeSet? = null,
    val stoppedReason: String? = null,
    val consecutiveFailures: Int = 0,
) {
    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        enabled && nowMillis < expiresAtMillis

    fun isActiveFor(
        studentNo: String,
        yearValue: String?,
        examValue: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean =
        isActive(nowMillis) &&
            this.studentNo == studentNo &&
            this.yearValue == yearValue &&
            this.examValue == examValue
}

internal data class GradeReminderIdentity(
    val enabled: Boolean,
    val studentNo: String,
    val yearValue: String,
    val examValue: String,
    val activatedAtMillis: Long,
    val expiresAtMillis: Long,
)

internal fun GradeReminderState.identity() = GradeReminderIdentity(
    enabled = enabled,
    studentNo = studentNo,
    yearValue = yearValue,
    examValue = examValue,
    activatedAtMillis = activatedAtMillis,
    expiresAtMillis = expiresAtMillis,
)

internal fun mutateReminderStateIfCurrent(
    current: GradeReminderState,
    expected: GradeReminderIdentity,
    transform: (GradeReminderState) -> GradeReminderState,
): GradeReminderState? = current.takeIf { it.identity() == expected }?.let(transform)

@Serializable
data class GradeReminderSnapshot(
    val summary: GradeReminderSummarySnapshot? = null,
    val subjects: List<GradeReminderSubjectSnapshot> = emptyList(),
    val standards: List<GradeReminderStandardSnapshot> = emptyList(),
)

@Serializable
data class GradeReminderSummarySnapshot(
    val examName: String,
    val totalScore: String?,
    val averageScore: String?,
    val classRank: String?,
    val classCount: String?,
    val categoryRank: String?,
    val categoryRankCount: String?,
    val flunkCount: String?,
)

@Serializable
data class GradeReminderSubjectSnapshot(
    val key: String,
    val name: String,
    val score: String?,
    val classAverage: String?,
    val classRank: String?,
    val classRankCount: String?,
    val yearRank: String?,
    val yearRankCount: String?,
    val flunk: String,
    val absent: String,
    val cheating: String,
)

@Serializable
data class GradeReminderStandardSnapshot(
    val key: String,
    val name: String,
    val top: String?,
    val front: String?,
    val average: String?,
    val back: String?,
    val bottom: String?,
    val standardDeviation: String?,
    val above90Count: String,
    val above80Count: String,
    val above70Count: String,
    val above60Count: String,
    val above50Count: String,
    val above40Count: String,
    val above30Count: String,
    val above20Count: String,
    val above10Count: String,
    val above0Count: String,
)

@Serializable
data class GradeChangeSet(
    val studentNo: String,
    val yearValue: String,
    val examValue: String,
    val examName: String,
    val checkedAtMillis: Long,
    val changes: List<GradeChange>,
) {
    val hasChanges: Boolean
        get() = changes.isNotEmpty()
}

@Serializable
data class GradeChange(
    val targetName: String,
    val subjectName: String? = null,
    val field: GradeChangeField,
    val oldValue: String? = null,
    val newValue: String? = null,
)

@Serializable
enum class GradeChangeField {
    SUMMARY_PRESENCE,
    EXAM_NAME,
    SUMMARY_TOTAL,
    SUMMARY_AVERAGE,
    SUMMARY_CLASS_RANK,
    SUMMARY_CLASS_RANK_COUNT,
    SUMMARY_CATEGORY_RANK,
    SUMMARY_CATEGORY_RANK_COUNT,
    SUMMARY_FLUNK_COUNT,
    SUBJECT_PRESENCE,
    SUBJECT_SCORE,
    SUBJECT_CLASS_AVERAGE,
    SUBJECT_CLASS_RANK,
    SUBJECT_CLASS_RANK_COUNT,
    SUBJECT_YEAR_RANK,
    SUBJECT_YEAR_RANK_COUNT,
    SUBJECT_FLUNK,
    SUBJECT_ABSENT,
    SUBJECT_CHEATING,
    STANDARD_PRESENCE,
    STANDARD_TOP,
    STANDARD_FRONT,
    STANDARD_AVERAGE,
    STANDARD_BACK,
    STANDARD_BOTTOM,
    STANDARD_DEVIATION,
    DISTRIBUTION_ABOVE_90,
    DISTRIBUTION_ABOVE_80,
    DISTRIBUTION_ABOVE_70,
    DISTRIBUTION_ABOVE_60,
    DISTRIBUTION_ABOVE_50,
    DISTRIBUTION_ABOVE_40,
    DISTRIBUTION_ABOVE_30,
    DISTRIBUTION_ABOVE_20,
    DISTRIBUTION_ABOVE_10,
    DISTRIBUTION_ABOVE_0,
}
