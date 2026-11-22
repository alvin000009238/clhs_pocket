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
    fun simulatorCreditsArePendingUntilStoredAndComeBackForTheSameSubjects() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        assertEquals(false, simulator.state().creditsPending)
        val row = simulator.state().rows.first()
        val changed = if (row.credits == 4) 3 else row.credits + 1

        simulator.setCredits(row.name, row.credits)
        assertEquals(false, simulator.state().creditsPending, "setting the same value is not an edit")
        simulator.setCredits(row.name, changed)
        assertEquals(true, simulator.state().creditsPending)
        assertEquals("自訂・未儲存", simulator.state().creditSourceText)

        val stored = assertNotNull(simulator.creditsJson())
        simulator.markCreditsSaved()
        assertEquals(false, simulator.state().creditsPending)
        assertEquals("自訂・已儲存", simulator.state().creditSourceText)

        // A new simulator for the same exam picks the stored credits up.
        val reopened = assertNotNull(PocketGrades.simulator(exam))
        assertEquals(true, reopened.restoreCredits(stored))
        assertEquals(changed, reopened.state().rows.first().credits)
        assertEquals(false, reopened.state().creditsPending)

        // Damaged text, or credits saved for other subjects or out of range, change nothing.
        val fresh = assertNotNull(PocketGrades.simulator(exam))
        assertEquals(false, fresh.restoreCredits(null))
        assertEquals(false, fresh.restoreCredits("{"))
        assertEquals(false, fresh.restoreCredits(stored.replace(row.name, row.name + "X")))
        assertEquals(false, fresh.restoreCredits(stored.replace("\"${row.name}\":$changed", "\"${row.name}\":9")))
        assertEquals(row.credits, fresh.state().rows.first().credits)

        // Going back to the defaults replaces the stored credits, so it has to be saved too.
        reopened.reset()
        assertEquals("預設・未儲存", reopened.state().creditSourceText)
        assertEquals(true, reopened.state().creditsPending)
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
    fun excludingEverySubjectShowsNoAverageAndNoDelta() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        simulator.state().rows.forEach { simulator.setIncluded(it.name, false) }
        val empty = simulator.state()
        assertEquals("--", empty.averageText)
        assertEquals(0, empty.deltaSign)
        assertEquals("", empty.deltaText)
    }

    @Test
    fun revertingStoredCustomCreditsToTheDefaultsIsPendingSoTheDefaultsCanBeStored() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        val row = simulator.state().rows.first()
        simulator.setCredits(row.name, if (row.credits == 4) 3 else row.credits + 1)
        simulator.markCreditsSaved()

        simulator.reset()
        assertEquals(true, simulator.state().creditsPending, "the stored custom credits no longer match")
        val defaults = assertNotNull(simulator.creditsJson())
        simulator.markCreditsSaved()
        assertEquals(false, simulator.state().creditsPending)

        val reopened = assertNotNull(PocketGrades.simulator(exam))
        assertTrue(reopened.restoreCredits(defaults))
        assertEquals(row.credits, reopened.state().rows.first().credits)
        assertEquals(false, reopened.state().creditsPending)
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
    fun computedInferenceChangesNothingUntilApplied() {
        val simulator = assertNotNull(PocketGrades.simulator(exam))
        val before = simulator.state()
        val result = simulator.computeInference()
        assertEquals(before, simulator.state(), "a cancelled search must leave the simulator untouched")
        simulator.applyInference(result)
        assertTrue(simulator.state().inferenceText.isNotEmpty())
        val direct = assertNotNull(PocketGrades.simulator(exam)).apply { inferCredits() }.state()
        assertEquals(direct, simulator.state())
    }

    @Test
    fun shareTextOmitsTheStudentNumber() {
        val text = PocketGrades.shareText(exam)
        assertTrue("加權平均" in text)
        assertFalse(FakeData.latestReport().studentInfo.studentNo in text)
    }
}
