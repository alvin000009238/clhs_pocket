package com.clhs.score.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FakeDataTest {
    @Test
    fun allDemoExamsMatchDefaultCredits() {
        assertEquals(6, FakeData.structure.sumOf { it.exams.size })
        FakeData.structure.forEach { year ->
            year.exams.forEach { exam ->
                val report = FakeData.reportFor(year.value, exam.value)
                assertEquals(
                    exam.value,
                    SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
                    SimulatorCredits.verify(
                        report.subjects, report.examSummary,
                        checkNotNull(SimulatorCredits.defaultCredits(report.subjects)),
                    ).status,
                )
            }
        }
        val history = FakeData.reportFor("114_1", "114_1_E1").subjects.last()
        assertEquals("71.00", history.scoreDisplay)
        assertFalse(history.absent)
    }

    @Test
    fun latestDemoExamHasWeightedOfficialSummary() {
        val report = FakeData.latestReport()
        val credits = checkNotNull(SimulatorCredits.defaultCredits(report.subjects))
        assertEquals(20, credits.values.sum())
        assertEquals("1640.00", report.examSummary?.totalScoreDisplay)
        assertEquals("82.00", report.examSummary?.averageScoreDisplay)
    }

    @Test
    fun ordinaryGeneratedScenariosMatchDefaultCredits() {
        listOf(StudentScenario.NORMAL, StudentScenario.EXCELLENT, StudentScenario.STRUGGLING).forEach { scenario ->
            val report = MockGradeSystem.generateReport(scenario)
            assertEquals(
                scenario.name,
                SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
                SimulatorCredits.verify(
                    report.subjects, report.examSummary,
                    checkNotNull(SimulatorCredits.defaultCredits(report.subjects)),
                ).status,
            )
        }
    }

    @Test
    fun generatedSummaryUsesDisplayedScoresAndRemainingRenamedSubjects() {
        val report = MockGradeSystem.generateReport(
            customScores = listOf(80.004, -999.0, 70.004),
            customSubjectNames = listOf("選修數學甲", "略過", "公民"),
        )
        assertEquals(listOf("選修數學甲", "公民"), report.subjects.map { it.subjectName })
        assertEquals(listOf("80.00", "70.00"), report.subjects.map { it.scoreDisplay })
        assertEquals("460.00", report.examSummary?.totalScoreDisplay)
        assertEquals("76.67", report.examSummary?.averageScoreDisplay)
        assertEquals(
            SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL,
            SimulatorCredits.verify(
                report.subjects, report.examSummary,
                checkNotNull(SimulatorCredits.defaultCredits(report.subjects)),
            ).status,
        )
    }

    @Test
    fun explicitOfficialSummaryOverridesRemainIndependent() {
        val totalOnly = MockGradeSystem.generateReport(customTotalScore = 12.34)
        val averageOnly = MockGradeSystem.generateReport(customAverageScore = 56.78)
        val both = MockGradeSystem.generateReport(customTotalScore = 12.34, customAverageScore = 56.78)
        assertEquals("12.34", totalOnly.examSummary?.totalScoreDisplay)
        assertEquals("76.50", totalOnly.examSummary?.averageScoreDisplay)
        assertEquals("1530.00", averageOnly.examSummary?.totalScoreDisplay)
        assertEquals("56.78", averageOnly.examSummary?.averageScoreDisplay)
        assertEquals("12.34", both.examSummary?.totalScoreDisplay)
        assertEquals("56.78", both.examSummary?.averageScoreDisplay)
    }

    @Test
    fun generatedDecimalDisplaysDoNotDependOnDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val report = MockGradeSystem.generateReport(customTotalScore = 12.34, customAverageScore = 56.78)
            assertEquals("78.00", report.subjects.first().scoreDisplay)
            assertEquals("76.20", report.subjects.first().classAverageDisplay)
            assertEquals("12.34", report.examSummary?.totalScoreDisplay)
            assertEquals("56.78", report.examSummary?.averageScoreDisplay)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun fakeDataBuildsAnalysisTrendAndSimulatorInputs() {
        val report = FakeData.latestReport()
        val analysis = buildGradeAnalysis(report, FakeData.previousReport(), "第三次段考")
        val trend = buildGradeTrend(
            currentExamName = "期末考",
            currentReport = report,
            previousReports = FakeData.trendReports(),
        )

        assertEquals(7, report.subjects.size)
        assertNotNull(report.examSummary?.classRank)
        assertFalse(analysis.subjects.isEmpty())
        assertTrue(trend.points.size >= 4)
    }

    @Test
    fun fakeRepositoryCanServeAppWithoutApiSession() = runTest {
        val repository = FakeGradeRepository()
        val session = repository.loginWithCookies("DEMO-000", emptyMap())

        assertNotNull(session)
        val structure = repository.loadStructure(session)
        val report = repository.fetchGrades(session, FakeData.CURRENT_YEAR_VALUE, FakeData.CURRENT_EXAM_VALUE)

        assertTrue(structure.isNotEmpty())
        assertEquals("範例學生", report.studentInfo.studentName)
        assertEquals("DEMO-000", report.studentInfo.studentNo)
        assertEquals("期末考", report.examSummary?.examName)
    }

    @Test
    fun fakeScheduleIncludesEighthPeriod() {
        val report = FakeScheduleData.report("114_2", "230")

        assertTrue(report.items.any { it.period == 8 })
    }

    @Test
    fun mockSystemGeneratesNormalScenario() {
        val report = MockGradeSystem.generateReport(StudentScenario.NORMAL)
        assertEquals(7, report.subjects.size)
        
        // Check actual API fields alignment
        val firstSubject = report.subjects.first()
        assertNotNull(firstSubject.yearRank)
        assertNotNull(firstSubject.yearRankCount)
        assertNotNull(report.examSummary?.categoryRank)
        assertEquals(38, report.examSummary?.classCount)
        assertEquals(226, report.examSummary?.categoryRankCount)
        
        assertFalse(firstSubject.absent)
        assertFalse(firstSubject.cheating)
    }

    @Test
    fun mockSystemGeneratesSpecialScenarioWithAbsentAndCheating() {
        val report = MockGradeSystem.generateReport(StudentScenario.SPECIAL)
        
        // SPECIAL scenario: 國文=80(normal), 英文=-1(absent), 數學=90, 自然=-2(cheating)
        val english = report.subjects.first { it.subjectName == "英文" }
        assertTrue(english.absent)
        assertFalse(english.cheating)
        assertEquals("缺考", english.scoreDisplay)
        assertNull(english.score)
        assertEquals(38, english.classRank)
        assertEquals(226, english.yearRank)
        
        val science = report.subjects.first { it.subjectName == "自然" }
        assertFalse(science.absent)
        assertTrue(science.cheating)
        assertEquals("作弊", science.scoreDisplay)
        assertNull(science.score)
        assertEquals("1140.00", report.examSummary?.totalScoreDisplay)
        assertEquals("57.00", report.examSummary?.averageScoreDisplay)
        val verification = SimulatorCredits.verify(
            report.subjects, report.examSummary,
            checkNotNull(SimulatorCredits.defaultCredits(report.subjects)),
        )
        assertEquals(SimulatorCredits.VerificationStatus.UNAVAILABLE, verification.status)
        assertEquals(SimulatorCredits.Issue.INVALID_SCORE, verification.issue)
    }
}
