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
    val DEFAULT_EXAM: String get() = PocketData.current.let { PocketExamKey.of(it.currentYearValue, it.currentExamValue) }

    val terms: List<PocketGradeTerm>
        get() = PocketData.current.structure.reversed().map { term ->
            PocketGradeTerm(term.text, term.value, term.exams.map { PocketExamOption(it.text, PocketExamKey.of(term.value, it.value)) })
        }

    fun report(examValue: String): PocketGradeReport {
        val source = PocketData.current
        val located = source.locateExam(examValue)
        val term = located?.first
        val exam = located?.second
        val report = if (term != null && exam != null) source.report(term.value, exam.value) else null
        if (term == null || exam == null || report == null) {
            val key = if (term != null && exam != null) PocketExamKey.of(term.value, exam.value) else examValue
            return unloaded(key, exam?.text.orEmpty(), term?.text.orEmpty())
        }
        val previous = term.previousExamOf(exam.value)
        val analysis = buildGradeAnalysis(
            report = report,
            comparisonReport = previous?.let { source.report(term.value, it.value) },
            previousExamName = previous?.text,
        )
        val delta = analysis.comparison?.averageDelta?.let { (it * 100).roundToInt() }
        val summary = report.examSummary
        return PocketGradeReport(
            examValue = PocketExamKey.of(term.value, exam.value),
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
            trend = term.exams.mapNotNull { option ->
                // An exam whose official average is not published yet has no point, not a 0.00 one.
                source.report(term.value, option.value)?.examSummary?.averageScoreDisplay?.toDoubleOrNull()?.let { average ->
                    PocketTrendPoint(option.text, average, hundredthsText((average * 100).roundToInt()))
                }
            },
            updatedAtText = report.studentInfo.updatedAt,
        )
    }

    /** Placeholder while the live report for this exam has not been loaded. */
    private fun unloaded(examValue: String, examTitle: String, termTitle: String) = PocketGradeReport(
        examValue = examValue, examTitle = examTitle, termTitle = termTitle, averageText = "--", totalText = "--",
        deltaText = "", deltaSign = 0, classRankText = "--", categoryRankText = "--", classTopText = "", categoryTopText = "",
        strengths = emptyList(), weaknesses = emptyList(), subjects = emptyList(), trend = emptyList(), updatedAtText = "",
        isLoaded = false,
    )

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
