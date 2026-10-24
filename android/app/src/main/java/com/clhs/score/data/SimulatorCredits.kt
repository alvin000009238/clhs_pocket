package com.clhs.score.data

import java.math.BigDecimal
import java.math.RoundingMode

/** Combined credits for each reported subject, never a claim about official course weights. */
object SimulatorCredits {
    const val MAX_CREDITS = 4
    internal const val MAX_SUBJECTS = 64
    private val hundred = BigDecimal(100)
    private val decimalPattern = Regex("[0-9]+(?:\\.[0-9]+)?")

    enum class Issue {
        INVALID_SUBJECT_NAMES, TOO_MANY_SUBJECTS, INVALID_CREDITS, INVALID_SCORE,
        NO_INCLUDED_SUBJECTS, MISSING_SUMMARY, INVALID_SUMMARY,
    }
    enum class VerificationStatus { UNAVAILABLE, MATCHES_OFFICIAL_WEIGHTED_TOTAL, MISMATCH }
    enum class InferenceStatus { UNAVAILABLE, NO_SOLUTIONS, UNIQUE_UNDER_BOUNDS, AMBIGUOUS, SEARCH_LIMITED }
    enum class Truncation { NODE_CAP, CANDIDATE_CAP }

    data class Calculation(
        val weightedTotal: BigDecimal? = null,
        val totalCredits: Int = 0,
        /** Division is rounded to 12 decimal places; weightedTotal remains exact. */
        val weightedAverage: BigDecimal? = null,
        val issue: Issue? = null,
    )

    data class Verification(
        val status: VerificationStatus,
        val calculation: Calculation,
        val issue: Issue? = null,
    )

    data class SearchLimits(val maxNodes: Int = 50_000, val maxCandidates: Int = 32) {
        init {
            require(maxNodes in 1..200_000)
            require(maxCandidates in 1..128)
        }
    }

    data class Inference(
        val status: InferenceStatus,
        val candidates: List<Map<String, Int>> = emptyList(),
        val possibleTotalCredits: List<Int> = emptyList(),
        val visitedNodes: Int = 0,
        val truncation: Truncation? = null,
        val issue: Issue? = null,
    )

    /** Null rejects duplicate cleaned names instead of silently overwriting a subject. */
    fun defaultCredits(subjects: List<SubjectScore>): Map<String, Int>? =
        names(subjects)?.associateWith { name ->
            if (isCoreSubject(name)) 4 else 2
        }

    /** Missing map entries default to one. Excluded subjects must be filtered by the caller. */
    fun calculate(
        subjects: List<SubjectScore>,
        credits: Map<String, Int> = emptyMap(),
        adjustedScores: Map<String, BigDecimal> = emptyMap(),
    ): Calculation {
        if (subjects.size > MAX_SUBJECTS) return Calculation(issue = Issue.TOO_MANY_SUBJECTS)
        val names = names(subjects) ?: return Calculation(issue = Issue.INVALID_SUBJECT_NAMES)
        if (!validCredits(credits, names) || adjustedScores.keys.any { it !in names }) {
            return Calculation(issue = Issue.INVALID_CREDITS)
        }
        var total = BigDecimal.ZERO
        var count = 0
        subjects.forEachIndexed { index, subject ->
            val credit = credits[names[index]] ?: 1
            val score = adjustedScores[names[index]]?.takeIf(::validScore)
                ?: if (names[index] in adjustedScores) null else subjectScore(subject)
            if (score == null) return Calculation(issue = Issue.INVALID_SCORE)
            total += score * BigDecimal(credit)
            count += credit
        }
        if (count == 0) return Calculation(issue = Issue.NO_INCLUDED_SUBJECTS)
        return Calculation(total, count, total.divide(BigDecimal(count), 12, RoundingMode.HALF_UP))
    }

    /** The official weighted total and average must both agree at their displayed precision. */
    fun verify(
        subjects: List<SubjectScore>,
        summary: ExamSummary?,
        credits: Map<String, Int> = emptyMap(),
    ): Verification {
        val calculation = calculate(subjects, credits)
        if (calculation.issue != null) {
            return Verification(VerificationStatus.UNAVAILABLE, calculation, issue = calculation.issue)
        }
        val displays = summaryDisplays(summary)
            ?: return Verification(
                VerificationStatus.UNAVAILABLE, calculation,
                issue = if (summary == null) Issue.MISSING_SUMMARY else Issue.INVALID_SUMMARY,
            )
        val matches = displays.matches(checkNotNull(calculation.weightedTotal), calculation.totalCredits)
        return Verification(
            if (matches) VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL else VerificationStatus.MISMATCH,
            calculation,
        )
    }

    /**
     * Searches positive integer combined credits without subject-name priors.
     * A complete unique solution is only unique within these bounds, not an official credit map.
     * Missing or flagged scores cannot be used to infer credits.
     */
    fun infer(
        subjects: List<SubjectScore>,
        summary: ExamSummary?,
        fixedCredits: Map<String, Int> = emptyMap(),
        limits: SearchLimits = SearchLimits(),
    ): Inference {
        fun unavailable(issue: Issue) = Inference(InferenceStatus.UNAVAILABLE, issue = issue)
        if (subjects.size > MAX_SUBJECTS) return unavailable(Issue.TOO_MANY_SUBJECTS)
        if (subjects.isEmpty()) return unavailable(Issue.NO_INCLUDED_SUBJECTS)
        val names = names(subjects) ?: return unavailable(Issue.INVALID_SUBJECT_NAMES)
        if (!validCredits(fixedCredits, names)) return unavailable(Issue.INVALID_CREDITS)
        val displays = summaryDisplays(summary)
            ?: return unavailable(if (summary == null) Issue.MISSING_SUMMARY else Issue.INVALID_SUMMARY)
        val scores = subjects.map(::subjectScore)
        if (scores.any { it == null }) return unavailable(Issue.INVALID_SCORE)
        val exactScores = scores.map { checkNotNull(it) }

        // Intersect display rounding intervals before enumerating any weight vectors.
        val possibleTotals = (1..subjects.size * MAX_CREDITS).filter { count ->
            displays.total.low < displays.average.high * BigDecimal(count) &&
                displays.average.low * BigDecimal(count) < displays.total.high
        }
        val minimum = IntArray(subjects.size) { fixedCredits[names[it]] ?: 1 }
        val maximum = IntArray(subjects.size) { fixedCredits[names[it]] ?: MAX_CREDITS }
        val suffixMinCount = IntArray(subjects.size + 1)
        val suffixMaxCount = IntArray(subjects.size + 1)
        val suffixMinScore = Array(subjects.size + 1) { BigDecimal.ZERO }
        val suffixMaxScore = Array(subjects.size + 1) { BigDecimal.ZERO }
        for (index in subjects.indices.reversed()) {
            suffixMinCount[index] = suffixMinCount[index + 1] + minimum[index]
            suffixMaxCount[index] = suffixMaxCount[index + 1] + maximum[index]
            suffixMinScore[index] = suffixMinScore[index + 1] + exactScores[index] * BigDecimal(minimum[index])
            suffixMaxScore[index] = suffixMaxScore[index + 1] + exactScores[index] * BigDecimal(maximum[index])
        }
        val candidates = mutableListOf<Map<String, Int>>()
        val weights = IntArray(subjects.size)
        var nodes = 0
        var truncation: Truncation? = null
        fun search(index: Int, count: Int, total: BigDecimal) {
            if (truncation != null) return
            if (nodes == limits.maxNodes) {
                truncation = Truncation.NODE_CAP
                return
            }
            nodes++
            if (possibleTotals.none { it in count + suffixMinCount[index]..count + suffixMaxCount[index] }) return
            if (total + suffixMinScore[index] >= displays.total.high ||
                total + suffixMaxScore[index] < displays.total.low
            ) return
            if (index == subjects.size) {
                if (count > 0 && displays.matches(total, count)) {
                    if (candidates.size == limits.maxCandidates) {
                        truncation = Truncation.CANDIDATE_CAP
                    } else {
                        candidates += names.indices.associate { names[it] to weights[it] }
                    }
                }
                return
            }
            for (credit in minimum[index]..maximum[index]) {
                weights[index] = credit
                search(index + 1, count + credit, total + exactScores[index] * BigDecimal(credit))
                if (truncation != null) return
            }
        }
        search(0, 0, BigDecimal.ZERO)
        val status = when {
            truncation != null -> InferenceStatus.SEARCH_LIMITED
            candidates.isEmpty() -> InferenceStatus.NO_SOLUTIONS
            candidates.size == 1 -> InferenceStatus.UNIQUE_UNDER_BOUNDS
            else -> InferenceStatus.AMBIGUOUS
        }
        val rankedCandidates = candidates.sortedWith(
            compareBy<Map<String, Int>> { candidate -> candidate.values.any { it % 2 != 0 } }
                .thenByDescending { candidate -> candidate.count { (name, credit) -> isCoreSubject(name) && credit == 4 } },
        )
        return Inference(status, rankedCandidates, possibleTotals, nodes, truncation)
    }

    private fun isCoreSubject(name: String): Boolean =
        name in setOf("國", "數", "英") ||
            listOf("國文", "國語文", "數學", "英文", "英語文").any(name::contains)

    private fun names(subjects: List<SubjectScore>): List<String>? {
        val names = subjects.map { cleanSubjectName(it.subjectName) }
        return names.takeIf { it.none(String::isBlank) && it.distinct().size == it.size }
    }

    private fun validCredits(credits: Map<String, Int>, names: List<String>): Boolean =
        credits.all { (name, credit) -> name in names && credit in 1..MAX_CREDITS }

    internal fun subjectScore(subject: SubjectScore): BigDecimal? =
        if (subject.absent || subject.cheating) null else decimal(subject.scoreDisplay)?.takeIf(::validScore)

    private fun validScore(score: BigDecimal): Boolean =
        score.scale() in 0..8 && score.precision() <= 18 && score >= BigDecimal.ZERO && score <= hundred

    // Exponents and oversized inputs are rejected before constructing a BigDecimal.
    private fun decimal(display: String): BigDecimal? {
        if (display.length > 32) return null
        val text = display.trim()
        if (!decimalPattern.matches(text)) return null
        return text.toBigDecimalOrNull()?.takeIf { it.scale() <= 8 && it.precision() <= 18 }
    }

    /** Positive HALF_UP displays represent [value-halfUnit, value+halfUnit). */
    private data class Interval(val low: BigDecimal, val high: BigDecimal) {
        fun contains(value: BigDecimal): Boolean = value >= low && value < high
    }

    private fun interval(value: BigDecimal): Interval {
        val halfUnit = BigDecimal("0.5").movePointLeft(value.scale())
        return Interval((value - halfUnit).max(BigDecimal.ZERO), value + halfUnit)
    }

    private data class Displays(val total: Interval, val average: Interval) {
        fun matches(weightedTotal: BigDecimal, count: Int): Boolean = count > 0 &&
            total.contains(weightedTotal) &&
            weightedTotal >= average.low * BigDecimal(count) &&
            weightedTotal < average.high * BigDecimal(count)
    }

    private fun summaryDisplays(summary: ExamSummary?): Displays? {
        summary ?: return null
        val total = summaryTotal(summary.totalScoreDisplay) ?: return null
        val average = summaryAverage(summary.averageScoreDisplay) ?: return null
        return Displays(interval(total), interval(average))
    }

    internal fun summaryTotal(display: String): BigDecimal? =
        decimal(display)?.takeIf { it <= hundred * BigDecimal(MAX_CREDITS * MAX_SUBJECTS) }

    internal fun summaryAverage(display: String): BigDecimal? =
        decimal(display)?.takeIf { it <= hundred }

}
