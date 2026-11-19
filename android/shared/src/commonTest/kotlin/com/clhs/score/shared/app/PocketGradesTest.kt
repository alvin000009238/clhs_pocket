package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PocketGradesTest {
    private val exam = FakeData.CURRENT_EXAM_VALUE

    @Test
    fun subjectDetailCarriesStandardsDistributionAndTrend() {
        val name = PocketEnvironment.demo().grades(exam).subjects.first().name
        val detail = assertNotNull(PocketGrades.subjectDetail(exam, name))
        assertEquals(5, detail.standards.size)
        assertEquals(1, detail.distribution.count { it.isMine })
        assertTrue(detail.trend.size > 1)
    }

    @Test
    fun simulatorStartsFromOfficialAverageAndRespondsToEdits() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        val initial = simulator.state()
        assertFalse(initial.hasChanges)
        assertEquals("", initial.deltaText)
        val row = initial.rows.first { it.canSimulate && it.score < 100.0 }
        simulator.setScore(row.name, 100.0)
        val raised = simulator.state()
        assertEquals(1, raised.deltaSign)
        assertTrue(raised.rows.first { it.name == row.name }.isAdjusted)
        simulator.setIncluded(row.name, false)
        assertEquals(initial.totalCredits - row.credits, simulator.state().totalCredits)
        simulator.setCredits(row.name, 9)
        assertEquals(4, simulator.state().rows.first { it.name == row.name }.credits)
        simulator.reset()
        assertEquals(initial, simulator.state())
    }

    @Test
    fun inferredCreditsVerifyAgainstTheOfficialSummary() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        if (simulator.state().check == PocketCreditCheck.MATCHES) return
        simulator.inferCredits()
        val inferred = simulator.state()
        if (inferred.candidates.isEmpty()) return
        simulator.applyCandidate(0)
        assertEquals(PocketCreditCheck.MATCHES, simulator.state().check)
    }

    @Test
    fun shareTextOmitsTheStudentNumber() {
        val text = PocketGrades.shareText(exam)
        assertTrue("加權平均" in text)
        assertFalse(FakeData.latestReport().studentInfo.studentNo in text)
    }
}
