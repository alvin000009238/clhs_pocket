package com.clhs.score.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeSummaryDisplayTest {
    private val base = MockGradeSystem.generateReport()
    private val subjects = listOf(subject("數學-甲", "50"), subject("數學-乙", "80"))
    private val credits = mapOf("數學-甲" to 2, "數學-乙" to 3)

    @Test
    fun officialStringsKeepTheirExactPrecisionEvenWhenCreditsAreUnavailableOrMismatch() {
        val report = report(" 00340.0000 ", "68.00000000")
        listOf(emptyMap(), credits, credits.mapValues { 1 }).forEach { applied ->
            val display = gradeSummaryDisplay(report, applied, true)
            assertEquals(" 00340.0000 ", display.totalText)
            assertEquals("68.00000000", display.averageText)
            assertFalse(display.isTotalEstimated)
            assertFalse(display.isAverageEstimated)
            assertTrue(display.hasOfficialAverage)
        }
        assertEquals(" 00340.0000 ", report.examSummary!!.totalScoreDisplay)
        assertEquals(68.0, report.weightedAverage(), 0.0)
    }

    @Test
    fun onlyUnavailableSummaryFieldsFallBackIndividually() {
        listOf("", " ", "--", "未公布", "NaN", "1e2").forEach { missing ->
            val totalMissing = gradeSummaryDisplay(report(missing, "68.000"), credits, true)
            assertEquals("340*", totalMissing.totalText)
            assertEquals("68.000", totalMissing.averageText)
            assertTrue(totalMissing.isTotalEstimated)
            assertFalse(totalMissing.isAverageEstimated)
            assertTrue(totalMissing.needsCreditAttention)

            val averageMissing = gradeSummaryDisplay(report("340.000", missing), credits, true)
            assertEquals("340.000", averageMissing.totalText)
            assertEquals("68.00*", averageMissing.averageText)
            assertFalse(averageMissing.hasOfficialAverage)
            assertTrue(averageMissing.isAverageEstimated)
            assertTrue(averageMissing.needsCreditAttention)
        }
    }

    @Test
    fun missingSummaryDoesNotChangeOfficialAnalysisAndUsesExactTotalAndHalfUpAverage() {
        val report = report().copy(subjects = listOf(subject("A", "0.505")), examSummary = null)
        val display = gradeSummaryDisplay(report, mapOf("A" to 2), true)
        assertEquals("1.01*", display.totalText)
        assertEquals("0.51*", display.averageText)
        assertEquals(0.0, report.weightedAverage(), 0.0)
        assertEquals(0.0, buildGradeAnalysis(report).weightedAverage, 0.0)
        val trailingZeros = gradeSummaryDisplay(report.copy(subjects = listOf(subject("A", "50.000"))), mapOf("A" to 2), true)
        assertEquals("100*", trailingZeros.totalText)
    }

    @Test
    fun zeroIsPublishedAndZeroSubjectScoresParticipateInEstimates() {
        val report = report("0", "0.000").copy(subjects = subjects.map { it.copy(scoreDisplay = "0") })
        val official = gradeSummaryDisplay(report, credits, true)
        assertEquals("0", official.totalText)
        assertEquals("0.000", official.averageText)
        assertFalse(official.needsCreditAttention)
        val estimated = gradeSummaryDisplay(report.copy(examSummary = null), credits, true)
        assertEquals("0*", estimated.totalText)
        assertEquals("0.00*", estimated.averageText)
        assertTrue(estimated.needsCreditAttention)
    }

    @Test
    fun rollingScoresUseOnlyValidPublishedSubjectsAndTheirOwnCredits() {
        val rolling = listOf(
            subject("A", "90"), subject("B", "0"), subject("C", "--"),
            subject("D", "bad").copy(score = 99.0), subject("E", "80").copy(absent = true),
            subject("F", "100").copy(cheating = true), subject("G", "101"), subject("H", ""),
        )
        val applied = rolling.associate { it.subjectName to 4 } + ("B" to 2)
        val display = gradeSummaryDisplay(report().copy(subjects = rolling, examSummary = null), applied, true)
        assertEquals("360*", display.totalText)
        assertEquals("60.00*", display.averageText)
        assertTrue(display.needsCreditAttention)
    }

    @Test
    fun noValidScoresShowsUnavailableWithoutAsterisk() {
        val report = report().copy(subjects = subjects.map { it.copy(scoreDisplay = "--", score = 80.0) }, examSummary = null)
        val display = gradeSummaryDisplay(report, credits, true)
        assertEquals("--", display.totalText)
        assertEquals("--", display.averageText)
        assertFalse(display.isTotalEstimated)
        assertFalse(display.isAverageEstimated)
        assertTrue(display.needsCreditAttention)
    }

    @Test
    fun invalidOrPartialCreditMapsNeverSupplyImplicitWeightsEvenForUnpublishedSubjects() {
        val report = report().copy(subjects = listOf(subject("數學-甲", "50"), subject("數學-乙", "--")), examSummary = null)
        listOf(
            emptyMap(), mapOf("數學-甲" to 4), mapOf("數學" to 4),
            credits + ("額外" to 2), credits + ("數學-乙" to 0), credits + ("數學-乙" to 5),
        ).forEach { applied ->
            val display = gradeSummaryDisplay(report, applied, true)
            assertEquals("--", display.totalText)
            assertEquals("--", display.averageText)
            assertTrue(display.needsCreditAttention)
        }
        val duplicate = report.copy(subjects = listOf(subject("數學<br/>-甲", "50"), subject("數學-甲", "80")))
        assertEquals("--", gradeSummaryDisplay(duplicate, mapOf("數學-甲" to 4), true).averageText)
    }

    @Test
    fun badgeRequiresReliableAppliedCreditsMatchingBothOfficialRoundingIntervals() {
        assertFalse(gradeSummaryDisplay(report("340", "68.0"), credits, true).needsCreditAttention)
        assertTrue(gradeSummaryDisplay(report("340", "68.0"), credits, false).needsCreditAttention)
        assertTrue(gradeSummaryDisplay(report("341", "68.0"), credits, true).needsCreditAttention)
        assertTrue(gradeSummaryDisplay(report("340", "69.0"), credits, true).needsCreditAttention)
        assertTrue(gradeSummaryDisplay(report("340", "68.0"), emptyMap(), true).needsCreditAttention)
        val rounded = report("400", "66.67").copy(subjects = listOf(subject("A", "50"), subject("B", "75")))
        assertFalse(gradeSummaryDisplay(rounded, mapOf("A" to 2, "B" to 4), true).needsCreditAttention)
    }

    @Test
    fun retainedValidMapCanEstimateDuringFailedReloadButCannotClearBadge() {
        val display = gradeSummaryDisplay(report().copy(examSummary = null), credits, false)
        assertEquals("340*", display.totalText)
        assertEquals("68.00*", display.averageText)
        assertTrue(display.needsCreditAttention)
    }

    private fun report(total: String = "--", average: String = "--") = base.copy(
        subjects = subjects,
        examSummary = base.examSummary!!.copy(totalScoreDisplay = total, averageScoreDisplay = average),
    )

    private fun subject(name: String, score: String) = base.subjects.first().copy(
        subjectName = name, scoreDisplay = score, score = null, absent = false, cheating = false,
    )
}
