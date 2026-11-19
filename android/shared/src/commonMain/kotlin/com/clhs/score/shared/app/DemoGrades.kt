package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import com.clhs.score.data.PercentileInfo
import com.clhs.score.data.SubjectScore
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.previousExamOf
import com.clhs.score.data.weightedAverage
import kotlin.math.roundToInt

/** Presents the shared synthetic reports through the shared grade analysis. */
internal object DemoGrades {
    val DEFAULT_EXAM: String get() = FakeData.CURRENT_EXAM_VALUE

    val terms: List<PocketGradeTerm>
        get() = FakeData.structure.reversed().map { term ->
            PocketGradeTerm(term.text, term.value, term.exams.map { PocketExamOption(it.text, it.value) })
        }

    fun report(examValue: String): PocketGradeReport {
        val term = FakeData.structure.firstOrNull { term -> term.exams.any { it.value == examValue } }
            ?: FakeData.structure.first { it.value == FakeData.CURRENT_YEAR_VALUE }
        val exam = term.exams.firstOrNull { it.value == examValue } ?: term.exams.last()
        val report = FakeData.reportFor(term.value, exam.value)
        val previous = term.previousExamOf(exam.value)
        val analysis = buildGradeAnalysis(
            report = report,
            comparisonReport = previous?.let { FakeData.reportFor(term.value, it.value) },
            previousExamName = previous?.text,
        )
        val delta = analysis.comparison?.averageDelta?.let { (it * 100).roundToInt() }
        val summary = report.examSummary
        return PocketGradeReport(
            examValue = exam.value,
            examTitle = exam.text,
            termTitle = term.text,
            averageText = summary?.averageScoreDisplay ?: "--",
            totalText = summary?.totalScoreDisplay ?: "--",
            deltaText = delta?.let { (if (it > 0) "+" else "") + hundredthsText(it) }.orEmpty(),
            deltaSign = when {
                delta == null || delta == 0 -> 0
                delta > 0 -> 1
                else -> -1
            },
            classRankText = analysis.classPercentile?.rank?.toString() ?: "--",
            categoryRankText = analysis.categoryPercentile?.rank?.toString() ?: "--",
            classTopText = analysis.classPercentile.topText(),
            categoryTopText = analysis.categoryPercentile.topText(),
            strengths = analysis.strengths.map(::insight),
            weaknesses = analysis.weaknesses.map(::insight),
            subjects = report.subjects.map(::subjectRow),
            trend = term.exams.map {
                val average = FakeData.reportFor(term.value, it.value).weightedAverage()
                PocketTrendPoint(it.text, average, hundredthsText((average * 100).roundToInt()))
            },
            updatedAtText = report.studentInfo.updatedAt,
        )
    }

    private fun PercentileInfo?.topText(): String = this?.let { "$count 人 · 前 $topPercent%" }.orEmpty()

    private fun signedTenths(value: Double): String {
        val tenths = (value * 10).roundToInt()
        return (if (tenths > 0) "+" else "") + tenthsText(tenths)
    }

    private fun insight(subject: SubjectScore) = PocketSubjectInsight(
        name = cleanSubjectName(subject.subjectName),
        detail = "${subject.scoreDisplay} 分 · 班平均 ${subject.classAverageDisplay}",
        percentText = signedTenths(subject.diffValue),
    )

    private fun subjectRow(subject: SubjectScore): PocketSubjectGrade {
        val hasScore = !subject.absent && !subject.cheating
        val diff = (subject.diffValue * 10).roundToInt()
        return PocketSubjectGrade(
            name = cleanSubjectName(subject.subjectName),
            scoreText = subject.scoreDisplay,
            fraction = if (hasScore) (subject.scoreValue / 100.0).coerceIn(0.0, 1.0) else -1.0,
            averageFraction = (subject.classAverageValue / 100.0).coerceIn(0.0, 1.0),
            classAverageText = subject.classAverageDisplay,
            diffText = if (hasScore) signedTenths(subject.diffValue) else "",
            diffSign = if (!hasScore || diff == 0) 0 else if (diff > 0) 1 else -1,
            classRankText = rankText(subject.classRank, subject.classRankCount),
            yearRankText = rankText(subject.yearRank, subject.yearRankCount),
            isFlunk = subject.flunk,
        )
    }

    private fun rankText(rank: Int?, count: Int?): String =
        if (rank == null || count == null) "--" else "$rank/$count"
}
