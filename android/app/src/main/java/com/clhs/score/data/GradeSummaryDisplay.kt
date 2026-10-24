package com.clhs.score.data

import java.math.RoundingMode

internal data class GradeSummaryDisplay(
    val totalText: String = "--",
    val averageText: String = "--",
    val isTotalEstimated: Boolean = false,
    val isAverageEstimated: Boolean = false,
    val hasOfficialAverage: Boolean = false,
    val needsCreditAttention: Boolean = true,
)

internal fun gradeSummaryDisplay(
    report: GradeReport,
    appliedCredits: Map<String, Int>,
    isAppliedStateReliable: Boolean,
): GradeSummaryDisplay {
    val summary = report.examSummary
    val officialTotal = summary?.totalScoreDisplay?.takeIf { SimulatorCredits.summaryTotal(it) != null }
    val officialAverage = summary?.averageScoreDisplay?.takeIf { SimulatorCredits.summaryAverage(it) != null }
    val names = SimulatorCredits.defaultCredits(report.subjects)?.keys
    val validCredits = names != null && appliedCredits.keys == names &&
        report.subjects.size <= SimulatorCredits.MAX_SUBJECTS &&
        appliedCredits.values.all { it in 1..SimulatorCredits.MAX_CREDITS }
    val publishedSubjects = report.subjects.filter { SimulatorCredits.subjectScore(it) != null }
    val calculation = if (validCredits) {
        SimulatorCredits.calculate(
            publishedSubjects,
            appliedCredits.filterKeys { key -> publishedSubjects.any { cleanSubjectName(it.subjectName) == key } },
        )
    } else null
    val estimatedTotal = calculation?.weightedTotal?.stripTrailingZeros()?.toPlainString()
    val estimatedAverage = calculation?.weightedAverage?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()
    val matches = validCredits && isAppliedStateReliable &&
        SimulatorCredits.verify(report.subjects, summary, appliedCredits).status ==
        SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL
    return GradeSummaryDisplay(
        totalText = officialTotal ?: estimatedTotal?.let { "$it*" } ?: "--",
        averageText = officialAverage ?: estimatedAverage?.let { "$it*" } ?: "--",
        isTotalEstimated = officialTotal == null && estimatedTotal != null,
        isAverageEstimated = officialAverage == null && estimatedAverage != null,
        hasOfficialAverage = officialAverage != null,
        needsCreditAttention = !matches,
    )
}
