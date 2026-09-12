package com.clhs.score.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeAnalysisTest {
    @Test
    fun percentileFormatsRankAsTopPercent() {
        assertEquals("前 35%", percentile(13.0, 37)?.percentLabel)
        assertEquals("前 27%", percentile(60.0, 219)?.percentLabel)
    }

    @Test
    fun strengthAndWeaknessAreSortedByClassAverageDiff() {
        val analysis = buildGradeAnalysis(MockGradeSystem.generateReport(StudentScenario.NORMAL))

        assertEquals("數學", analysis.strengths.first().subjectName)
        assertEquals("社會", analysis.weaknesses.first().subjectName)
    }

    @Test
    fun summaryTextHandlesRankAndSubjectHighlights() {
        val summary = buildGradeAnalysis(MockGradeSystem.generateReport(StudentScenario.NORMAL)).summaryText

        assertTrue(summary.contains("本次班排 15/38"))
        assertTrue(summary.contains("優勢科目為數學"))
        assertTrue(summary.contains("待加強為社會"))
    }

    @Test
    fun previousExamLookupStaysInsideSameTerm() {
        val year = YearTermOption(
            text = "114上",
            value = "114_1",
            exams = listOf(
                ExamOption("第一次段考", "E1"),
                ExamOption("第二次段考", "E2"),
                ExamOption("期末考", "E3"),
            ),
        )

        assertEquals("E2", year.previousExamOf("E3")?.value)
        assertNull(year.previousExamOf("E1"))
        assertNull(year.previousExamOf("missing"))
    }

    @Test
    fun previousExamsLookupReturnsAtMostTwoInChronologicalOrder() {
        val year = YearTermOption(
            text = "114上",
            value = "114_1",
            exams = listOf(
                ExamOption("第一次段考", "E1"),
                ExamOption("第二次段考", "E2"),
                ExamOption("期末考", "E3"),
            ),
        )

        assertEquals(listOf("E1", "E2"), year.previousExamsOf("E3").map { it.value })
        assertEquals(listOf("E1"), year.previousExamsOf("E2").map { it.value })
        assertTrue(year.previousExamsOf("E1").isEmpty())
    }

    @Test
    fun simulatorHistoryUsesAllPreviousExamsInSameTerm() {
        val structure = listOf(
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(
                    ExamOption("第一次段考", "E1"),
                    ExamOption("第二次段考", "E2"),
                    ExamOption("第三次段考", "E3"),
                    ExamOption("第四次段考", "E4"),
                    ExamOption("期末考", "E5"),
                ),
            ),
        )

        val source = structure.simulationHistorySource("114_1", "E5")

        assertEquals(false, source?.usesPreviousTerm)
        assertEquals(listOf("E1", "E2", "E3", "E4"), source?.exams?.map { it.value })
    }

    @Test
    fun simulatorHistoryCanUsePreviousTermWhenSameTermIsEmpty() {
        val structure = listOf(
            YearTermOption(
                text = "113下",
                value = "113_2",
                exams = listOf(
                    ExamOption("第一次段考", "P1"),
                    ExamOption("期末考", "P2"),
                ),
            ),
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(ExamOption("第一次段考", "E1")),
            ),
        )

        val source = structure.simulationHistorySource("114_1", "E1")

        assertEquals(true, source?.usesPreviousTerm)
        assertEquals("113_2", source?.yearTerm?.value)
        assertEquals(listOf("P1", "P2"), source?.exams?.map { it.value })
    }

    @Test
    fun simulatorHistoryUsesAllPreviousExamsAcrossTerms() {
        val structure = listOf(
            YearTermOption(
                text = "113上",
                value = "113_1",
                exams = listOf(ExamOption("期末考", "A3")),
            ),
            YearTermOption(
                text = "113下",
                value = "113_2",
                exams = listOf(
                    ExamOption("第一次段考", "P1"),
                    ExamOption("期末考", "P2"),
                ),
            ),
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(ExamOption("第一次段考", "E1")),
            ),
        )

        val source = structure.simulationHistorySource("114_1", "E1")

        assertEquals(true, source?.usesPreviousTerm)
        assertEquals(listOf("113_1", "113_2", "113_2"), source?.historyExams?.map { it.yearValue })
        assertEquals(listOf("A3", "P1", "P2"), source?.historyExams?.map { it.examValue })
    }

    @Test
    fun simulatorHistoryMatchesDisplayedSubjectName() {
        assertEquals("化學", shortenSubjectName("化學"))
        assertEquals("選修物理", shortenSubjectName("選修物理-力學"))
        assertEquals("選修物理", shortenSubjectName("選修物理-電磁學"))
    }

    @Test
    fun sameTermHistoryDoesNotFallBackToPreviousTerm() {
        val structure = listOf(
            YearTermOption(
                text = "113下",
                value = "113_2",
                exams = listOf(
                    ExamOption("第一次段考", "P1"),
                    ExamOption("期末考", "P2"),
                ),
            ),
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(ExamOption("期中考1", "E1")),
            ),
        )

        assertNull(structure.sameTermHistorySource("114_1", "E1"))
        assertEquals("113_2", structure.simulationHistorySource("114_1", "E1")?.yearTerm?.value)
    }

    @Test
    fun sameTermTrendUsesEveryExamInSelectedYearTerm() {
        val structure = listOf(
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(
                    ExamOption("期中考1", "E1"),
                    ExamOption("期中考2", "E2"),
                    ExamOption("期末考", "E3"),
                ),
            ),
        )

        val source = structure.sameTermTrendSource("114_1")

        assertEquals(false, source?.usesPreviousTerm)
        assertEquals(listOf("E1", "E2", "E3"), source?.historyExams?.map { it.examValue })
    }

    @Test
    fun latestYearTermAndExamPreferNewestOptions() {
        val structure = listOf(
            YearTermOption(
                text = "113下",
                value = "113_2",
                exams = listOf(ExamOption("第一次段考", "E1")),
            ),
            YearTermOption(
                text = "114上",
                value = "114_1",
                exams = listOf(
                    ExamOption("第一次段考", "E1"),
                    ExamOption("第二次段考", "E2"),
                    ExamOption("期末考", "E3"),
                ),
            ),
            YearTermOption(
                text = "113上",
                value = "113_1",
                exams = listOf(ExamOption("期末考", "E3")),
            ),
        )

        val latestYear = structure.latestYearTerm()

        assertEquals("114_1", latestYear?.value)
        assertEquals("E3", latestYear?.latestExam()?.value)
    }

    @Test
    fun comparisonCalculatesAverageRankAndSubjectDeltas() {
        val current = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val previous = MockGradeSystem.generateReport(
            customScores = listOf(70.0, 78.0, 62.0, 70.0, 70.0, 70.0, 70.0),
            customClassRank = 18.0,
            customAverageScore = 70.0,
        )

        val comparison = buildGradeComparison(current, previous, previousExamName = "第二次段考")

        assertEquals("第二次段考", comparison.previousExamName)
        assertTrue(comparison.averageDelta > 0.0)
        assertEquals(3, comparison.classRankDelta)
        assertEquals(18.0, comparison.subjectComparisons["數學"]?.scoreDelta ?: 0.0, 0.001)
        assertNotNull(buildGradeAnalysis(current, previous, "第二次段考").comparison)
    }

    @Test
    fun gradeTrendKeepsOldToNewOrder() {
        val trend = buildGradeTrend(
            currentExamName = "期末考",
            currentReport = MockGradeSystem.generateReport(customScores = listOf(84.0, 84.0, 84.0, 84.0, 84.0, 84.0, 84.0)),
            previousReports = listOf(
                "第一次段考" to MockGradeSystem.generateReport(customClassRank = 22.0),
                "第二次段考" to MockGradeSystem.generateReport(customClassRank = 18.0),
            ),
        )

        assertEquals(listOf("第一次段考", "第二次段考", "期末考"), trend.points.map { it.examName })
        assertEquals(3, trend.points.size)
        assertTrue(trend.averageLine.contains("→"))
    }

    @Test
    fun weightedAverageDefaultsToCurrentReportAverage() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)

        assertEquals(report.weightedAverage(), weightedAverageFor(report.subjects), 0.001)
    }

    @Test
    fun weightedAverageUsesAdjustedScoresAndIncludedSubjects() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val includedSubjects = setOf("國文", "數學", "社會")
        val adjustedScores = mapOf(
            "國文" to 88.0,
            "數學" to 84.0,
            "社會" to 75.0,
        )

        val activeSubjects = report.subjects.filter { cleanSubjectName(it.subjectName) in includedSubjects }
        val expected = activeSubjects.sumOf { subject ->
            adjustedScores[cleanSubjectName(subject.subjectName)]!! * subjectWeight(subject.subjectName)
        } / activeSubjects.sumOf { subjectWeight(it.subjectName) }.toDouble()

        assertEquals(
            expected,
            weightedAverageFor(
                subjects = report.subjects,
                adjustedScores = adjustedScores,
                includedSubjects = includedSubjects,
            ),
            0.001,
        )
    }

    @Test
    fun weightedTotalUsesSubjectWeightsAdjustedScoresAndIncludedSubjects() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val includedSubjects = setOf("國文", "數學", "社會")
        val adjustedScores = mapOf(
            "國文" to 88.0,
            "數學" to 84.0,
            "社會" to 75.0,
        )

        val activeSubjects = report.subjects.filter { cleanSubjectName(it.subjectName) in includedSubjects }
        val expected = activeSubjects.sumOf { subject ->
            adjustedScores[cleanSubjectName(subject.subjectName)]!! * subjectWeight(subject.subjectName)
        }

        assertEquals(
            expected,
            weightedTotalFor(
                subjects = report.subjects,
                adjustedScores = adjustedScores,
                includedSubjects = includedSubjects,
            ),
            0.001,
        )
    }

    @Test
    fun weightedAverageClampsAdjustedScoresAndHandlesEmptyIncludedGroup() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val includedSubjects = setOf("國文", "英文")
        val adjustedScores = mapOf(
            "國文" to 150.0,
            "英文" to -10.0,
        )

        val activeSubjects = report.subjects.filter { cleanSubjectName(it.subjectName) in includedSubjects }
        val expected = activeSubjects.sumOf { subject ->
            val score = adjustedScores[cleanSubjectName(subject.subjectName)]!!.coerceIn(0.0, 100.0)
            score * subjectWeight(subject.subjectName)
        } / activeSubjects.sumOf { subjectWeight(it.subjectName) }.toDouble()

        assertEquals(
            expected,
            weightedAverageFor(report.subjects, adjustedScores, includedSubjects),
            0.001,
        )
        assertEquals(
            0.0,
            weightedAverageFor(report.subjects, includedSubjects = setOf("不存在的科目")),
            0.001,
        )
    }

    @Test
    fun weightedTotalClampsAdjustedScoresAndHandlesEmptyIncludedGroup() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        val includedSubjects = setOf("國文", "英文")
        val adjustedScores = mapOf(
            "國文" to 150.0,
            "英文" to -10.0,
        )

        val activeSubjects = report.subjects.filter { cleanSubjectName(it.subjectName) in includedSubjects }
        val expected = activeSubjects.sumOf { subject ->
            val score = adjustedScores[cleanSubjectName(subject.subjectName)]!!.coerceIn(0.0, 100.0)
            score * subjectWeight(subject.subjectName)
        }

        assertEquals(
            expected,
            weightedTotalFor(report.subjects, adjustedScores, includedSubjects),
            0.001,
        )
        assertEquals(
            0.0,
            weightedTotalFor(report.subjects, includedSubjects = setOf("不存在的科目")),
            0.001,
        )
    }

}
