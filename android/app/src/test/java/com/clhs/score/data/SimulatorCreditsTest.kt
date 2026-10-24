package com.clhs.score.data

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorCreditsTest {
    @Test
    fun defaultsUseFourForChineseMathEnglishAndTwoForOtherSubjects() {
        val subjects = listOf(subject("國語文", "50"), subject("數學甲", "80"), subject("英文", "60"), subject("化學", "70"))
        val defaults = SimulatorCredits.defaultCredits(subjects)!!
        assertEquals(mapOf("國語文" to 4, "數學甲" to 4, "英文" to 4, "化學" to 2), defaults)
        assertEquals(mapOf("國文" to 4, "英語文" to 4, "選修數學甲" to 4), SimulatorCredits.defaultCredits(listOf(subject("國文", "50"), subject("英語文", "60"), subject("選修數學甲", "70"))))
        val result = SimulatorCredits.calculate(subjects, defaults)
        assertDecimal("900", result.weightedTotal)
        assertEquals(14, result.totalCredits)
    }

    @Test
    fun fullCleanedNamesStayDistinctAndCombinedCreditsAreNotSplit() {
        val subjects = listOf(subject("數學<br/>-甲", "50"), subject("數學-乙", "80"))
        val credits = mapOf("數學-甲" to 4, "數學-乙" to 2)
        val result = SimulatorCredits.calculate(subjects, credits)
        assertDecimal("360", result.weightedTotal)
        assertEquals(6, result.totalCredits)
        assertEquals(credits, SimulatorCredits.infer(subjects, summary("360", "60"), credits).candidates.single())
    }

    @Test
    fun duplicateCleanedNamesRejectAllOperations() {
        val subjects = listOf(subject("Math<br/>", "50"), subject("Math", "80"))
        assertNull(SimulatorCredits.defaultCredits(subjects))
        assertEquals(SimulatorCredits.Issue.INVALID_SUBJECT_NAMES, SimulatorCredits.calculate(subjects).issue)
        assertEquals(
            SimulatorCredits.Issue.INVALID_SUBJECT_NAMES,
            SimulatorCredits.infer(subjects, summary("210", "70")).issue,
        )
    }

    @Test
    fun calculatorUsesExactDisplayDecimalsAndExplicitOverrides() {
        val subjects = listOf(subject("A", "0.1").copy(score = 99.0), subject("B", "0.2"))
        val credits = mapOf("A" to 2, "B" to 1)
        assertDecimal("0.4", SimulatorCredits.calculate(subjects, credits).weightedTotal)
        val adjusted = SimulatorCredits.calculate(subjects, credits, mapOf("A" to BigDecimal("0.3")))
        assertDecimal("0.8", adjusted.weightedTotal)
        assertDecimal("0.266666666667", adjusted.weightedAverage)
    }

    @Test
    fun missingMalformedAbsentAndCheatingScoresNeverBecomeZero() {
        val invalid = listOf("", "--", "缺考", "NaN", "1e2", "101", "-1", "0.123456789")
            .map { subject("A", it).copy(score = 80.0) } +
            listOf(subject("A", "0").copy(absent = true), subject("A", "0").copy(cheating = true))
        invalid.forEach { score ->
            assertEquals(SimulatorCredits.Issue.INVALID_SCORE, SimulatorCredits.calculate(listOf(score)).issue)
            assertEquals(
                SimulatorCredits.Issue.INVALID_SCORE,
                SimulatorCredits.infer(listOf(score), summary("160", "80")).issue,
            )
        }
    }

    @Test
    fun zeroCreditsAreRejectedForCalculationVerificationAndFixedConstraints() {
        val subjects = listOf(subject("A", "50"), subject("B", "80"))
        val credits = mapOf("A" to 0, "B" to 2)
        assertEquals(
            SimulatorCredits.Issue.INVALID_CREDITS,
            SimulatorCredits.calculate(subjects, credits).issue,
        )
        assertEquals(SimulatorCredits.Issue.INVALID_CREDITS, SimulatorCredits.infer(subjects, summary("160", "80"), credits).issue)
        assertEquals(SimulatorCredits.VerificationStatus.UNAVAILABLE, SimulatorCredits.verify(subjects, summary("160", "80"), credits).status)
    }

    @Test
    fun explicitNumericOverrideCanSimulateAnOtherwiseMissingScore() {
        val result = SimulatorCredits.calculate(
            listOf(subject("A", "缺考").copy(absent = true)), adjustedScores = mapOf("A" to BigDecimal("75")),
        )
        assertDecimal("75", result.weightedAverage)
        assertEquals(
            SimulatorCredits.Issue.INVALID_SCORE,
            SimulatorCredits.calculate(listOf(subject("A", "75")), adjustedScores = mapOf("A" to BigDecimal("101"))).issue,
        )
    }

    @Test
    fun invalidOrUnknownCreditKeysAreRejectedInsteadOfSilentlyIgnored() {
        val subjects = listOf(subject("A", "80"))
        listOf(mapOf("A" to -1), mapOf("A" to 5), mapOf("Unknown" to 1)).forEach { credits ->
            assertEquals(SimulatorCredits.Issue.INVALID_CREDITS, SimulatorCredits.calculate(subjects, credits).issue)
            assertEquals(
                SimulatorCredits.Issue.INVALID_CREDITS,
                SimulatorCredits.infer(subjects, summary("160", "80"), credits).issue,
            )
        }
    }

    @Test
    fun uniqueSolutionIsOnlyUniqueWithinTheCreditBounds() {
        val result = SimulatorCredits.infer(pair(), summary("210", "70.0"))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, result.status)
        assertEquals(listOf(3), result.possibleTotalCredits)
        assertEquals(mapOf("A" to 1, "B" to 2), result.candidates.single())
        assertNull(result.truncation)
    }

    @Test
    fun equalScoresHaveMultipleSolutionsButAllCreditsArePositive() {
        val result = SimulatorCredits.infer(listOf(subject("A", "50"), subject("B", "50")), summary("200", "50"))
        assertEquals(SimulatorCredits.InferenceStatus.AMBIGUOUS, result.status)
        assertEquals(3, result.candidates.size)
        assertEquals(setOf(mapOf("A" to 1, "B" to 3), mapOf("A" to 2, "B" to 2), mapOf("A" to 3, "B" to 1)), result.candidates.toSet())
    }

    @Test
    fun candidatesRankAllEvenBeforeOddThenPreferMoreCoreSubjectsWithFourCredits() {
        val names = listOf("國語文", "英文", "數學甲", "化學", "物理")
        val subjects = names.map { subject(it, "50") }
        val result = SimulatorCredits.infer(
            subjects, summary("800", "50"), limits = SimulatorCredits.SearchLimits(50_000, 128),
        )
        assertEquals(SimulatorCredits.InferenceStatus.AMBIGUOUS, result.status)
        assertNull(result.truncation)
        val candidates = result.candidates
        val a = names.zip(listOf(4, 4, 4, 3, 1)).toMap()
        val b = names.zip(listOf(4, 4, 2, 4, 2)).toMap()
        assertTrue(candidates.indexOf(b) >= 0)
        assertTrue(candidates.indexOf(a) > candidates.indexOf(b))
        candidates.zipWithNext().forEach { (before, after) ->
            val beforeHasOdd = before.values.any { it % 2 != 0 }
            val afterHasOdd = after.values.any { it % 2 != 0 }
            assertFalse(beforeHasOdd && !afterHasOdd)
            if (beforeHasOdd == afterHasOdd) {
                assertTrue(names.take(3).count { before[it] == 4 } >= names.take(3).count { after[it] == 4 })
            }
        }
        assertEquals(65, candidates.size)
        candidates.forEach { candidate ->
            assertEquals(
                SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
                SimulatorCredits.verify(subjects, summary("800", "50"), candidate).status,
            )
        }
    }

    @Test
    fun exampleSupportsRequestedCombinedCreditsButDoesNotIdentifyOfficialWeights() {
        val subjects = listOf("50", "67", "58", "77", "80").mapIndexed { index, score -> subject("S$index", score) }
        val credits = listOf(4, 2, 4, 3, 3).mapIndexed { index, credit -> "S$index" to credit }.toMap()
        val summary = summary("1037", "64.8125")
        val verified = SimulatorCredits.verify(subjects, summary, credits)
        assertEquals(SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL, verified.status)
        val inferred = SimulatorCredits.infer(subjects, summary, limits = SimulatorCredits.SearchLimits(200_000, 128))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, inferred.status)
        assertTrue(credits in inferred.candidates)
        assertEquals(1, inferred.candidates.size)
    }

    @Test
    fun fixedCreditsConstrainRatherThanBiasTheSearch() {
        val subjects = listOf(subject("A", "50"), subject("B", "50"))
        val summary = summary("200", "50")
        val unique = SimulatorCredits.infer(subjects, summary, mapOf("A" to 3))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, unique.status)
        assertEquals(mapOf("A" to 3, "B" to 1), unique.candidates.single())
        assertEquals(
            SimulatorCredits.InferenceStatus.NO_SOLUTIONS,
            SimulatorCredits.infer(subjects, summary, mapOf("A" to 4)).status,
        )
    }

    @Test
    fun impossibleTotalsAndNonintegerCreditCountsHaveNoSolution() {
        assertEquals(SimulatorCredits.InferenceStatus.NO_SOLUTIONS, SimulatorCredits.infer(pair(), summary("201", "67")).status)
        val fractional = SimulatorCredits.infer(pair(), summary("210.00", "60.00"))
        assertEquals(SimulatorCredits.InferenceStatus.NO_SOLUTIONS, fractional.status)
        assertTrue(fractional.possibleTotalCredits.isEmpty())
    }

    @Test
    fun decimalInferenceNeverPassesThroughBinaryFloatingPoint() {
        val subjects = listOf(subject("A", "0.1"), subject("B", "0.2"))
        val result = SimulatorCredits.infer(subjects, summary("0.50", "0.16666667"))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, result.status)
        assertEquals(mapOf("A" to 1, "B" to 2), result.candidates.single())
    }

    @Test
    fun displayRoundingIntervalsPermitRoundedAveragesAndUseHalfOpenBounds() {
        val subjects = listOf(subject("A", "0"), subject("B", "100"))
        assertEquals(
            SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
            SimulatorCredits.verify(subjects, summary("200", "66.67"), mapOf("A" to 1, "B" to 2)).status,
        )
        val boundary = listOf(subject("A", "66.675"))
        assertEquals(
            SimulatorCredits.VerificationStatus.MISMATCH,
            SimulatorCredits.verify(boundary, summary("133.35", "66.67"), mapOf("A" to 2)).status,
        )
        assertEquals(
            SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
            SimulatorCredits.verify(boundary, summary("133.35", "66.68"), mapOf("A" to 2)).status,
        )
    }

    @Test
    fun bothTotalAndAverageMustMatchToVerify() {
        val credits = mapOf("A" to 1, "B" to 2)
        assertEquals(SimulatorCredits.VerificationStatus.MISMATCH, SimulatorCredits.verify(pair(), summary("211", "70"), credits).status)
        assertEquals(SimulatorCredits.VerificationStatus.MISMATCH, SimulatorCredits.verify(pair(), summary("210", "71"), credits).status)
    }

    @Test
    fun totalMatchingPlainSumStillInfersBecauseTheOfficialTotalIsKnownToBeWeighted() {
        val subjects = pair()
        val summary = summary("130", "65.0")
        val result = SimulatorCredits.infer(subjects, summary, mapOf("A" to 1))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, result.status)
        assertEquals(mapOf("A" to 1, "B" to 1), result.candidates.single())
        assertEquals(SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL, SimulatorCredits.verify(subjects, summary).status)
        assertEquals(SimulatorCredits.InferenceStatus.NO_SOLUTIONS, SimulatorCredits.infer(subjects, summary("130", "70")).status)
    }

    @Test
    fun fourIsAllowedButLargerOrZeroCombinedCreditCountsAreNotSolutions() {
        val subjects = listOf(subject("A", "100"))
        val maximum = SimulatorCredits.infer(subjects, summary("400", "100"))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, maximum.status)
        assertEquals(mapOf("A" to 4), maximum.candidates.single())
        assertEquals(SimulatorCredits.InferenceStatus.NO_SOLUTIONS, SimulatorCredits.infer(subjects, summary("500", "100")).status)
        assertEquals(SimulatorCredits.InferenceStatus.NO_SOLUTIONS, SimulatorCredits.infer(subjects, summary("0", "0")).status)
        assertEquals(SimulatorCredits.Issue.NO_INCLUDED_SUBJECTS, SimulatorCredits.infer(emptyList(), summary("0", "0")).issue)
    }

    @Test
    fun coarseDisplayPrecisionRetainsAllFeasibleIntegerCreditCounts() {
        val result = SimulatorCredits.infer(listOf(subject("A", "0.4")), summary("1", "0.4"))
        assertEquals(SimulatorCredits.InferenceStatus.AMBIGUOUS, result.status)
        assertEquals(setOf(2, 3), result.candidates.map { it.getValue("A") }.toSet())
    }

    @Test
    fun missingAndUnsafeSummariesAreUnavailable() {
        assertEquals(SimulatorCredits.Issue.MISSING_SUMMARY, SimulatorCredits.infer(pair(), null).issue)
        listOf("", "--", "1E100000", "9".repeat(1000), "1.123456789", "999999999999999999").forEach { total ->
            assertEquals(SimulatorCredits.Issue.INVALID_SUMMARY, SimulatorCredits.infer(pair(), summary(total, "70")).issue)
        }
        assertEquals(SimulatorCredits.Issue.INVALID_SUMMARY, SimulatorCredits.infer(pair(), summary("210", "101")).issue)
        assertEquals(SimulatorCredits.VerificationStatus.UNAVAILABLE, SimulatorCredits.verify(pair(), null).status)
    }

    @Test
    fun nodeLimitNeverTurnsPartialSearchIntoNoSolutionsOrUnique() {
        val result = SimulatorCredits.infer(pair(), summary("210", "70"), limits = SimulatorCredits.SearchLimits(1, 32))
        assertEquals(SimulatorCredits.InferenceStatus.SEARCH_LIMITED, result.status)
        assertEquals(SimulatorCredits.Truncation.NODE_CAP, result.truncation)
        assertEquals(1, result.visitedNodes)
    }

    @Test
    fun candidateLimitExplicitlyMarksTruncationButExactCapacityIsNotTruncated() {
        val subjects = listOf(subject("A", "50"), subject("B", "50"))
        val limited = SimulatorCredits.infer(subjects, summary("200", "50"), limits = SimulatorCredits.SearchLimits(50_000, 1))
        assertEquals(SimulatorCredits.InferenceStatus.SEARCH_LIMITED, limited.status)
        assertEquals(SimulatorCredits.Truncation.CANDIDATE_CAP, limited.truncation)
        assertEquals(1, limited.candidates.size)
        val complete = SimulatorCredits.infer(pair(), summary("210", "70"), limits = SimulatorCredits.SearchLimits(50_000, 1))
        assertEquals(SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS, complete.status)
        assertNull(complete.truncation)
    }

    @Test
    fun candidateMapsAreCompleteAndEveryCandidateVerifies() {
        val subjects = listOf(subject("A", "50"), subject("B", "50"), subject("C", "80"))
        val summary = summary("360", "60.000")
        val inferred = SimulatorCredits.infer(subjects, summary)
        assertFalse(inferred.candidates.isEmpty())
        inferred.candidates.forEach { candidate ->
            assertEquals(setOf("A", "B", "C"), candidate.keys)
            assertTrue(candidate.values.all { it in 1..4 })
            assertEquals(
                SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
                SimulatorCredits.verify(subjects, summary, candidate).status,
            )
        }
    }

    @Test
    fun oversizedSubjectListsAreRejectedBeforeRecursiveSearch() {
        val result = SimulatorCredits.infer((0..64).map { subject("S$it", "50") }, summary("200", "50"))
        assertEquals(SimulatorCredits.Issue.TOO_MANY_SUBJECTS, result.issue)
        assertEquals(0, result.visitedNodes)
    }

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertEquals(0, BigDecimal(expected).compareTo(checkNotNull(actual)))
    }

    private fun pair() = listOf(subject("A", "50"), subject("B", "80"))

    private fun subject(name: String, score: String) = SubjectScore(
        subjectName = name, scoreDisplay = score, score = null,
        classAverageDisplay = "", classAverage = null,
        classRank = null, classRankCount = null, yearRank = null, yearRankCount = null,
        yearTermDisplay = "", flunk = false, absent = false, cheating = false,
    )

    private fun summary(total: String, average: String) = ExamSummary(
        year = null, termText = "", examName = "", totalScoreDisplay = total, averageScoreDisplay = average,
        classRank = null, classCount = null, categoryRank = null, categoryRankCount = null, flunkCount = null,
    )
}
