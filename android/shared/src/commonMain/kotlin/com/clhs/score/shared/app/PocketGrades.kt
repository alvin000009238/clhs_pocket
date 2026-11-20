package com.clhs.score.shared.app

import com.clhs.score.data.GradeReport
import com.clhs.score.data.SimulatorCredits
import com.clhs.score.data.SubjectScore
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.gradeLevel
import com.clhs.score.data.gradeSummaryDisplay
import com.clhs.score.data.previousExamOf
import com.clhs.score.data.rankDeltaText
import com.clhs.score.data.scoreDistributions
import com.clhs.score.data.standardFor
import com.clhs.score.data.weightedAverageFor
import com.clhs.score.data.weightedTotalFor
import kotlin.math.roundToInt

data class PocketStandardMark(val label: String, val valueText: String, val value: Double)

data class PocketDistributionBar(val label: String, val count: Int, val isMine: Boolean)

data class PocketSubjectDetail(
    val name: String,
    val examTitle: String,
    val scoreText: String,
    val score: Double,
    val isFlunk: Boolean,
    val statusText: String,
    val classAverageText: String,
    val diffText: String,
    val diffSign: Int,
    val classRankText: String,
    val yearRankText: String,
    val levelText: String,
    val deviationText: String,
    val standards: List<PocketStandardMark>,
    val distribution: List<PocketDistributionBar>,
    val distributionSummary: String,
    val trend: List<PocketTrendPoint>,
    val classTrend: List<PocketTrendPoint>,
    val changeText: String,
)

data class PocketAnalysis(
    val summary: String,
    val changes: List<String>,
    val improved: List<PocketSubjectInsight>,
    val declined: List<PocketSubjectInsight>,
)

data class PocketSimulatorRow(
    val name: String,
    val credits: Int,
    val officialScoreText: String,
    val score: Double,
    val scoreText: String,
    val isAdjusted: Boolean,
    val isIncluded: Boolean,
    val canSimulate: Boolean,
)

enum class PocketCreditCheck { MATCHES, MISMATCH, UNAVAILABLE }

data class PocketCreditCandidate(val index: Int, val totalCredits: Int, val summary: String)

data class PocketSimulation(
    val rows: List<PocketSimulatorRow>,
    val averageText: String,
    val totalText: String,
    val officialAverageText: String,
    val deltaText: String,
    val deltaSign: Int,
    val totalCredits: Int,
    val check: PocketCreditCheck,
    val checkTitle: String,
    val checkDetail: String,
    val inferenceText: String,
    val candidates: List<PocketCreditCandidate>,
    val hasChanges: Boolean,
)

/** Subject detail, analysis and summary text built from the shared grade analysis. */
object PocketGrades {
    private val source get() = PocketData.current

    /** Null when the exam is unknown or, in live mode, the structure has not been loaded. */
    private fun locate(examValue: String) = source.locateExam(examValue)

    internal fun report(examValue: String): GradeReport? =
        locate(examValue)?.let { (term, exam) -> source.report(term.value, exam.value) }

    private fun analysisOf(examValue: String) = locate(examValue)?.let { (term, exam) ->
        val report = source.report(term.value, exam.value) ?: return@let null
        val previous = term.previousExamOf(exam.value)
        buildGradeAnalysis(
            report = report,
            comparisonReport = previous?.let { source.report(term.value, it.value) },
            previousExamName = previous?.text,
        )
    }

    fun subjectDetail(examValue: String, subjectName: String): PocketSubjectDetail? {
        val (term, exam) = locate(examValue) ?: return null
        val item = (analysisOf(examValue) ?: return null).subjects
            .firstOrNull { cleanSubjectName(it.subject.subjectName) == subjectName } ?: return null
        val subject = item.subject
        val standard = item.standard
        val hasScore = !subject.absent && !subject.cheating
        val diff = (subject.diffValue * 10).roundToInt()
        val history = term.exams.mapNotNull { option ->
            source.report(term.value, option.value)?.subjects
                ?.firstOrNull { cleanSubjectName(it.subjectName) == subjectName }
                ?.let { option.text to it }
        }
        return PocketSubjectDetail(
            name = subjectName,
            examTitle = "${term.text} · ${exam.text}",
            scoreText = subject.scoreDisplay,
            score = subject.scoreValue,
            isFlunk = subject.flunk,
            statusText = when {
                subject.absent -> "缺考"
                subject.cheating -> "成績註記"
                subject.flunk -> "不及格"
                else -> ""
            },
            classAverageText = subject.classAverageDisplay,
            diffText = if (hasScore) (if (diff > 0) "+" else "") + tenthsText(diff) else "",
            diffSign = if (!hasScore || diff == 0) 0 else if (diff > 0) 1 else -1,
            classRankText = rank(subject.classRank, subject.classRankCount),
            yearRankText = rank(subject.yearRank, subject.yearRankCount),
            levelText = if (hasScore) item.level.orEmpty() else "",
            deviationText = standard?.standardDeviation?.let { tenthsText((it * 10).roundToInt()) }.orEmpty(),
            standards = standard?.let {
                listOf("頂標" to it.top, "前標" to it.front, "均標" to it.average, "後標" to it.back, "底標" to it.bottom)
                    .mapNotNull { (label, value) ->
                        value?.let { PocketStandardMark(label, tenthsText((value * 10).roundToInt()), value) }
                    }
            }.orEmpty(),
            distribution = standard?.let { scoreDistributions(subject.scoreValue, it) }.orEmpty()
                .map { PocketDistributionBar(it.label, it.count, it.isMine && hasScore) },
            distributionSummary = item.distributionSummary.orEmpty(),
            trend = history.filter { !it.second.absent && !it.second.cheating }
                .map { (name, score) -> PocketTrendPoint(name, score.scoreValue, score.scoreDisplay) },
            classTrend = history
                .map { (name, score) -> PocketTrendPoint(name, score.classAverageValue, score.classAverageDisplay) },
            changeText = item.comparison?.let { comparison ->
                val delta = (comparison.scoreDelta * 10).roundToInt()
                when {
                    delta > 0 -> "比上次進步 ${tenthsText(delta)} 分"
                    delta < 0 -> "比上次退步 ${tenthsText(-delta)} 分"
                    else -> "與上次持平"
                }
            }.orEmpty(),
        )
    }

    fun analysis(examValue: String, unused: Boolean = false): PocketAnalysis {
        val analysis = analysisOf(examValue) ?: return PocketAnalysis("", emptyList(), emptyList(), emptyList())
        val comparison = analysis.comparison
        fun rows(positive: Boolean) = analysis.subjects
            .mapNotNull { item -> item.comparison?.let { item.subject to it.scoreDelta } }
            .filter { (_, delta) -> if (positive) delta > 0.05 else delta < -0.05 }
            .sortedByDescending { (_, delta) -> if (positive) delta else -delta }
            .take(3)
            .map { (subject, change) ->
                val delta = (change * 10).roundToInt()
                PocketSubjectInsight(
                    name = cleanSubjectName(subject.subjectName),
                    detail = "這次 ${subject.scoreDisplay} 分",
                    percentText = (if (delta > 0) "+" else "") + tenthsText(delta),
                )
            }
        return PocketAnalysis(
            summary = analysis.summaryText,
            changes = comparison?.let {
                listOfNotNull(
                    it.classRankDelta?.let { delta -> rankDeltaText("班排", delta) },
                    it.categoryRankDelta?.let { delta -> rankDeltaText("類組排名", delta) },
                )
            }.orEmpty(),
            improved = rows(true),
            declined = rows(false),
        )
    }

    /** Plain-text summary for the system share sheet; never includes the student number. */
    fun shareText(examValue: String): String {
        val (term, exam) = locate(examValue) ?: return ""
        val report = report(examValue) ?: return ""
        val analysis = analysisOf(examValue) ?: return ""
        return buildString {
            appendLine("${term.text} ${exam.text} 成績摘要")
            report.examSummary?.let { appendLine("加權平均 ${it.averageScoreDisplay} · 總分 ${it.totalScoreDisplay}") }
            analysis.classPercentile?.let { appendLine("班級排名 ${it.rankLabel}（${it.percentLabel}）") }
            analysis.categoryPercentile?.let { appendLine("類組排名 ${it.rankLabel}（${it.percentLabel}）") }
            appendLine()
            report.subjects.forEach {
                appendLine("${cleanSubjectName(it.subjectName)} ${it.scoreDisplay}（班平均 ${it.classAverageDisplay}）")
            }
            appendLine()
            append("由壢中 Pocket 產生")
        }
    }

    /** Null while the report for this exam has not been loaded. */
    fun simulator(examValue: String): PocketSimulator? = report(examValue)?.let(::PocketSimulator)

    private fun rank(rank: Int?, count: Int?) = if (rank == null || count == null) "--" else "$rank/$count"
}

/** A finished credit search for one exam, applied with [PocketSimulator.applyInference]. */
class PocketCreditInference internal constructor(internal val value: SimulatorCredits.Inference)

/**
 * Editable what-if state for one exam. Credits are the shared [SimulatorCredits]
 * integers from 1 to 4; exclusions are a subject selection, never a zero credit.
 */
class PocketSimulator internal constructor(private val report: GradeReport) {
    private val subjects: List<SubjectScore> = report.subjects
    private val names = subjects.map { cleanSubjectName(it.subjectName) }
    private val defaults: Map<String, Int> = SimulatorCredits.defaultCredits(subjects).orEmpty()
    private val simulatable = subjects.filter { SimulatorCredits.subjectScore(it) != null }
        .mapTo(mutableSetOf()) { cleanSubjectName(it.subjectName) }

    private var credits: Map<String, Int> = defaults
    private var adjusted: Map<String, Double> = emptyMap()
    private var included: Set<String> = simulatable
    private var inferred: SimulatorCredits.Inference? = null

    fun setCredits(name: String, value: Int) {
        if (name in defaults) credits = credits + (name to value.coerceIn(1, SimulatorCredits.MAX_CREDITS))
        inferred = null
    }

    fun setScore(name: String, value: Double) {
        if (name !in simulatable || !value.isFinite()) return
        adjusted = adjusted + (name to value.coerceIn(0.0, 100.0).roundToInt().toDouble())
    }

    fun clearScore(name: String) { adjusted = adjusted - name }

    fun setIncluded(name: String, isIncluded: Boolean) {
        if (name in simulatable) included = if (isIncluded) included + name else included - name
    }

    fun reset() {
        credits = defaults
        adjusted = emptyMap()
        included = simulatable
        inferred = null
    }

    /** Searches for credits that reproduce the official summary; call only after a mismatch. */
    fun inferCredits() { applyInference(computeInference()) }

    /**
     * The search alone. It reads only the immutable report, never the editable state, so a caller
     * may run it off the main thread and drop the result if the user cancels or edits meanwhile.
     */
    fun computeInference(): PocketCreditInference = PocketCreditInference(SimulatorCredits.infer(subjects, report.examSummary))

    fun applyInference(result: PocketCreditInference) { inferred = result.value }

    fun applyCandidate(index: Int) {
        inferred?.candidates?.getOrNull(index)?.let { credits = defaults + it }
    }

    fun state(): PocketSimulation {
        val verification = SimulatorCredits.verify(subjects, report.examSummary, credits)
        val average = weightedAverageFor(subjects, adjusted, included, credits)
        val total = weightedTotalFor(subjects, adjusted, included, credits)
        val baseline = weightedAverageFor(subjects, emptyMap(), simulatable, credits)
        val delta = ((average - baseline) * 100).roundToInt()
        val changed = adjusted.isNotEmpty() || included != simulatable
        val display = gradeSummaryDisplay(report, credits, isAppliedStateReliable = true)
        val check = when (verification.status) {
            SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL -> PocketCreditCheck.MATCHES
            SimulatorCredits.VerificationStatus.MISMATCH -> PocketCreditCheck.MISMATCH
            SimulatorCredits.VerificationStatus.UNAVAILABLE -> PocketCreditCheck.UNAVAILABLE
        }
        val inference = inferred
        return PocketSimulation(
            rows = subjects.mapIndexed { index, subject ->
                val name = names[index]
                val score = adjusted[name] ?: subject.scoreValue
                PocketSimulatorRow(
                    name = name,
                    credits = credits[name] ?: 1,
                    officialScoreText = subject.scoreDisplay,
                    score = score,
                    scoreText = if (name in adjusted) score.roundToInt().toString() else subject.scoreDisplay,
                    isAdjusted = name in adjusted,
                    isIncluded = name in included,
                    canSimulate = name in simulatable,
                )
            },
            averageText = if (included.isEmpty()) "--" else hundredthsText((average * 100).roundToInt()),
            totalText = if (included.isEmpty()) "--" else tenthsText((total * 10).roundToInt()),
            officialAverageText = display.averageText,
            deltaText = if (!changed || delta == 0) "" else (if (delta > 0) "+" else "") + hundredthsText(delta),
            deltaSign = if (!changed || delta == 0) 0 else if (delta > 0) 1 else -1,
            totalCredits = names.filter { it in included }.sumOf { credits[it] ?: 1 },
            check = check,
            checkTitle = when (check) {
                PocketCreditCheck.MATCHES -> "學分與校方總分相符"
                PocketCreditCheck.MISMATCH -> "學分與校方總分不符"
                PocketCreditCheck.UNAVAILABLE -> "無法驗證學分"
            },
            checkDetail = when (check) {
                PocketCreditCheck.MATCHES -> "以這組學分計算的加權總分與成績單一致。"
                PocketCreditCheck.MISMATCH -> "預設學分算不出成績單上的加權總分，可以手動調整或自動推算。"
                PocketCreditCheck.UNAVAILABLE -> "這次考試缺少可比對的校方總分或有未公布的科目。"
            },
            inferenceText = when (inference?.status) {
                null -> ""
                SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS -> "在 1 到 4 學分內只有一組符合，並非校方公布的學分。"
                SimulatorCredits.InferenceStatus.AMBIGUOUS -> "有 ${inference.candidates.size} 組學分都符合，請選擇與課程相符的一組。"
                SimulatorCredits.InferenceStatus.SEARCH_LIMITED -> "組合太多，僅列出部分結果。"
                SimulatorCredits.InferenceStatus.NO_SOLUTIONS -> "找不到符合校方總分的學分組合。"
                SimulatorCredits.InferenceStatus.UNAVAILABLE -> "這次考試無法推算學分。"
            },
            candidates = inference?.candidates.orEmpty().take(6).mapIndexed { index, candidate ->
                PocketCreditCandidate(
                    index = index,
                    totalCredits = candidate.values.sum(),
                    summary = names.filter { it in candidate }.joinToString("、") { "$it ${candidate[it]}" },
                )
            },
            hasChanges = changed || credits != defaults,
        )
    }
}
