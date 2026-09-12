package com.clhs.score.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class PercentileInfo(
    val rank: Int,
    val count: Int,
    val topPercent: Int,
) {
    val rankLabel: String = "$rank/$count"
    val percentLabel: String = "前 $topPercent%"
}

data class SubjectAnalysis(
    val subject: SubjectScore,
    val standard: GradeStandard?,
    val level: String?,
    val standardDistance: String?,
    val distributionSummary: String?,
    val comparison: SubjectComparison?,
)

data class GradeAnalysis(
    val weightedAverage: Double,
    val highestScore: Double?,
    val classPercentile: PercentileInfo?,
    val categoryPercentile: PercentileInfo?,
    val strengths: List<SubjectScore>,
    val weaknesses: List<SubjectScore>,
    val summaryText: String,
    val comparison: GradeComparison?,
    val subjects: List<SubjectAnalysis>,
)

data class GradeTrendPoint(
    val examName: String,
    val weightedAverage: Double,
    val classRank: Int?,
    val highestScore: Double?,
)

data class GradeTrend(
    val points: List<GradeTrendPoint>,
) {
    val averageLine: String = points.joinToString(" → ") { "%.1f".format(it.weightedAverage) }
}

data class HistoryExamRef(
    val yearTerm: YearTermOption,
    val exam: ExamOption,
) {
    val yearValue: String = yearTerm.value
    val examValue: String = exam.value
    val examName: String = exam.text
}

data class SimulationHistorySource(
    val yearTerm: YearTermOption,
    val exams: List<ExamOption>,
    val usesPreviousTerm: Boolean,
    val examRefs: List<HistoryExamRef>? = null,
) {
    val historyExams: List<HistoryExamRef> = examRefs ?: exams.map { HistoryExamRef(yearTerm, it) }

    val label: String = if (usesPreviousTerm) {
        "近 ${historyExams.size} 次段考"
    } else {
        "同學期前 ${exams.size} 次考試"
    }
}

data class GradeComparison(
    val previousExamName: String,
    val averageDelta: Double,
    val highestScoreDelta: Double?,
    val classRankDelta: Int?,
    val categoryRankDelta: Int?,
    val subjectComparisons: Map<String, SubjectComparison>,
) {
    val summaryText: String
        get() {
            val averageText = deltaText("平均", averageDelta, unit = "分")
            val rankText = classRankDelta?.let { rankDeltaText("班排", it) }
            return listOfNotNull(averageText, rankText).joinToString("，").ifBlank {
                "已載入上一考比較"
            }
        }
}

data class SubjectComparison(
    val subjectName: String,
    val scoreDelta: Double,
)

fun buildGradeAnalysis(
    report: GradeReport,
    comparisonReport: GradeReport? = null,
    previousExamName: String? = null,
): GradeAnalysis {
    val comparison = comparisonReport?.let {
        buildGradeComparison(
            current = report,
            previous = it,
            previousExamName = previousExamName ?: it.examSummary?.examName.orEmpty().ifBlank { "上一考" },
        )
    }
    val subjectComparisons = comparison?.subjectComparisons.orEmpty()
    val subjectAnalyses = report.subjects.mapIndexed { index, subject ->
        val standard = report.standardFor(subject, index)
        SubjectAnalysis(
            subject = subject,
            standard = standard,
            level = standard?.let { gradeLevel(subject.scoreValue, it) },
            standardDistance = standard?.let { standardDistance(subject.scoreValue, it) },
            distributionSummary = standard?.let { distributionSummary(subject.scoreValue, it) },
            comparison = subjectComparisons[cleanSubjectName(subject.subjectName)],
        )
    }
    val strengths = report.subjects
        .filter { it.diffValue >= 1.0 }
        .sortedByDescending { it.diffValue }
        .take(2)
    val weaknesses = report.subjects
        .filter { it.diffValue <= -1.0 }
        .sortedBy { it.diffValue }
        .take(2)

    return GradeAnalysis(
        weightedAverage = report.weightedAverage(),
        highestScore = report.highestScore(),
        classPercentile = percentile(report.examSummary?.classRank, report.examSummary?.classCount),
        categoryPercentile = percentile(report.examSummary?.categoryRank, report.examSummary?.categoryRankCount),
        strengths = strengths,
        weaknesses = weaknesses,
        summaryText = summaryText(report, strengths, weaknesses, comparison),
        comparison = comparison,
        subjects = subjectAnalyses,
    )
}

fun buildGradeComparison(
    current: GradeReport,
    previous: GradeReport,
    previousExamName: String,
): GradeComparison {
    val previousBySubject = previous.subjects.associateBy { cleanSubjectName(it.subjectName) }
    val subjectComparisons = current.subjects.mapNotNull { subject ->
        val key = cleanSubjectName(subject.subjectName)
        val previousSubject = previousBySubject[key] ?: return@mapNotNull null
        key to SubjectComparison(
            subjectName = subject.subjectName,
            scoreDelta = subject.scoreValue - previousSubject.scoreValue,
        )
    }.toMap()
    return GradeComparison(
        previousExamName = previousExamName,
        averageDelta = current.weightedAverage() - previous.weightedAverage(),
        highestScoreDelta = current.highestScore()?.let { currentHigh ->
            previous.highestScore()?.let { previousHigh -> currentHigh - previousHigh }
        },
        classRankDelta = rankDelta(current.examSummary?.classRank, previous.examSummary?.classRank),
        categoryRankDelta = rankDelta(current.examSummary?.categoryRank, previous.examSummary?.categoryRank),
        subjectComparisons = subjectComparisons,
    )
}

fun percentile(rank: Double?, count: Int?): PercentileInfo? {
    if (rank == null || count == null || count <= 0) return null
    val rankInt = rank.toInt()
    if (rankInt <= 0) return null
    val topPercent = ((rank / count) * 100.0).roundToInt().coerceIn(1, 100)
    return PercentileInfo(rank = rankInt, count = count, topPercent = topPercent)
}

fun YearTermOption.previousExamOf(examValue: String?): ExamOption? {
    if (examValue.isNullOrBlank()) return null
    val index = exams.indexOfFirst { it.value == examValue }
    return if (index > 0) exams[index - 1] else null
}

fun YearTermOption.previousExamsOf(examValue: String?, limit: Int = 2): List<ExamOption> {
    if (examValue.isNullOrBlank() || limit <= 0) return emptyList()
    val index = exams.indexOfFirst { it.value == examValue }
    if (index <= 0) return emptyList()
    return exams.subList(max(0, index - limit), index)
}

fun List<YearTermOption>.sameTermHistorySource(
    yearValue: String?,
    examValue: String?,
): SimulationHistorySource? {
    if (yearValue.isNullOrBlank()) return null
    val current = firstOrNull { it.value == yearValue } ?: return null
    val sameTermExams = current.previousExamsOf(examValue, limit = Int.MAX_VALUE)
    if (sameTermExams.isEmpty()) return null
    return SimulationHistorySource(
        yearTerm = current,
        exams = sameTermExams,
        usesPreviousTerm = false,
    )
}

fun List<YearTermOption>.sameTermTrendSource(
    yearValue: String?,
): SimulationHistorySource? {
    if (yearValue.isNullOrBlank()) return null
    val current = firstOrNull { it.value == yearValue } ?: return null
    if (current.exams.size < 2) return null
    return SimulationHistorySource(
        yearTerm = current,
        exams = current.exams,
        usesPreviousTerm = false,
    )
}

fun List<YearTermOption>.simulationHistorySource(
    yearValue: String?,
    examValue: String?,
): SimulationHistorySource? {
    if (yearValue.isNullOrBlank() || examValue.isNullOrBlank()) return null
    val orderedExams = sortedWith(
        compareBy({ it.sortKey().first }, { it.sortKey().second }),
    ).flatMap { yearTerm ->
        yearTerm.exams.map { exam -> HistoryExamRef(yearTerm, exam) }
    }
    val currentIndex = orderedExams.indexOfFirst {
        it.yearValue == yearValue && it.examValue == examValue
    }
    if (currentIndex <= 0) return null
    val historyExams = orderedExams.subList(0, currentIndex)
    if (historyExams.isEmpty()) return null
    return SimulationHistorySource(
        yearTerm = historyExams.first().yearTerm,
        exams = historyExams.map { it.exam },
        usesPreviousTerm = historyExams.any { it.yearValue != yearValue },
        examRefs = historyExams,
    )
}

fun List<YearTermOption>.latestYearTerm(): YearTermOption? =
    maxWithOrNull(
        compareBy(
            { option -> parseYearTerm(option.value, defaultYear = "0", defaultTerm = "0").first.toIntOrNull() ?: 0 },
            { option -> parseYearTerm(option.value, defaultYear = "0", defaultTerm = "0").second.toIntOrNull() ?: 0 },
        ),
    )

fun YearTermOption.latestExam(): ExamOption? = exams.lastOrNull()

fun buildGradeTrend(
    currentExamName: String,
    currentReport: GradeReport,
    previousReports: List<Pair<String, GradeReport>>,
): GradeTrend {
    val previousPoints = previousReports.map { (examName, report) ->
        report.toTrendPoint(examName)
    }
    return GradeTrend(points = previousPoints + currentReport.toTrendPoint(currentExamName))
}

fun buildGradeTrend(
    reports: List<Pair<String, GradeReport>>,
): GradeTrend = GradeTrend(
    points = reports.map { (examName, report) -> report.toTrendPoint(examName) },
)

fun deltaText(label: String, delta: Double, unit: String = ""): String {
    val direction = when {
        delta > 0.05 -> "+"
        delta < -0.05 -> ""
        else -> ""
    }
    return "$label $direction${"%.1f".format(delta)}$unit"
}

fun rankDeltaText(label: String, delta: Int): String = when {
    delta > 0 -> "${label}進步 $delta 名"
    delta < 0 -> "${label}退步 ${abs(delta)} 名"
    else -> "${label}持平"
}

private fun rankDelta(current: Double?, previous: Double?): Int? {
    if (current == null || previous == null) return null
    return previous.toInt() - current.toInt()
}

private fun YearTermOption.sortKey(): Pair<Int, Int> {
    val (year, term) = parseYearTerm(value, defaultYear = "0", defaultTerm = "0")
    return (year.toIntOrNull() ?: 0) to (term.toIntOrNull() ?: 0)
}

fun weightedAverageFor(
    subjects: List<SubjectScore>,
    adjustedScores: Map<String, Double> = emptyMap(),
    includedSubjects: Set<String>? = null,
): Double {
    val activeSubjects = activeWeightedSubjects(subjects, includedSubjects)
    val totalWeight = activeSubjects.sumOf { subjectWeight(it.subjectName) }
    if (totalWeight <= 0) return 0.0
    return weightedTotalFor(activeSubjects, adjustedScores) / totalWeight
}

fun weightedTotalFor(
    subjects: List<SubjectScore>,
    adjustedScores: Map<String, Double> = emptyMap(),
    includedSubjects: Set<String>? = null,
): Double {
    return activeWeightedSubjects(subjects, includedSubjects).sumOf { subject ->
        val score = adjustedScores[cleanSubjectName(subject.subjectName)] ?: subject.scoreValue
        score.coerceIn(0.0, 100.0) * subjectWeight(subject.subjectName)
    }
}

private fun activeWeightedSubjects(
    subjects: List<SubjectScore>,
    includedSubjects: Set<String>?,
): List<SubjectScore> {
    return if (includedSubjects != null) {
        subjects.filter { cleanSubjectName(it.subjectName) in includedSubjects }
    } else {
        subjects
    }
}


private fun GradeReport.toTrendPoint(examName: String): GradeTrendPoint = GradeTrendPoint(
    examName = examName.ifBlank { examSummary?.examName.orEmpty().ifBlank { "考試" } },
    weightedAverage = weightedAverage(),
    classRank = examSummary?.classRank?.toInt(),
    highestScore = highestScore(),
)

private fun summaryText(
    report: GradeReport,
    strengths: List<SubjectScore>,
    weaknesses: List<SubjectScore>,
    comparison: GradeComparison?,
): String {
    val classRank = percentile(report.examSummary?.classRank, report.examSummary?.classCount)
    val rankPart = classRank?.let { "本次班排 ${it.rankLabel}" }
        ?: "本次加權平均 ${"%.1f".format(report.weightedAverage())}"
    val levelPart = classRank?.let { "整體屬${performanceLevel(it.topPercent)}" }
    val strengthPart = strengths.takeIf { it.isNotEmpty() }?.let {
        "優勢科目為${subjectListText(it)}"
    }
    val weaknessPart = weaknesses.takeIf { it.isNotEmpty() }?.let {
        "待加強為${subjectListText(it)}"
    }
    val comparePart = comparison?.let {
        if (it.averageDelta > 0.05) "較上一考進步 ${"%.1f".format(it.averageDelta)} 分"
        else if (it.averageDelta < -0.05) "較上一考下降 ${"%.1f".format(abs(it.averageDelta))} 分"
        else "與上一考表現接近"
    }
    return listOfNotNull(rankPart, levelPart, strengthPart, weaknessPart, comparePart)
        .joinToString("，")
        .plus("。")
}

private fun performanceLevel(topPercent: Int): String = when {
    topPercent <= 25 -> "班級前段"
    topPercent <= 50 -> "中上"
    topPercent <= 75 -> "中段"
    else -> "需要加強"
}

private fun subjectListText(subjects: List<SubjectScore>): String =
    subjects.joinToString("與") { shortenSubjectName(it.subjectName) }

private fun standardDistance(score: Double, standard: GradeStandard): String {
    val top = standard.top
    val front = standard.front
    val average = standard.average
    val back = standard.back
    return when {
        top != null && score >= top -> "已達頂標以上"
        top != null && front != null && score >= front -> "距頂標 ${"%.1f".format(top - score)} 分"
        front != null && score < front && average != null && score >= average -> "距前標 ${"%.1f".format(front - score)} 分"
        average != null && score < average && back != null && score >= back -> "距均標 ${"%.1f".format(average - score)} 分"
        back != null && score < back -> "低於後標 ${"%.1f".format(back - score)} 分"
        else -> "落點資料不足"
    }
}

private fun distributionSummary(score: Double, standard: GradeStandard): String {
    val mine = scoreDistributions(score, standard).firstOrNull { it.isMine } ?: return "分佈資料不足"
    val medianText = standard.average?.let {
        if (score >= it) "高於均標" else "低於均標"
    } ?: "均標資料不足"
    return "位於 ${mine.label}，該級距 ${mine.count} 人，$medianText"
}
