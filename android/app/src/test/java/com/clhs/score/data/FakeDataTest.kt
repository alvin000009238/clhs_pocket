package com.clhs.score.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDataTest {
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
    }
}
