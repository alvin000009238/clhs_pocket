package com.clhs.score.viewmodel

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.FakeData
import com.clhs.score.data.FakeGradeRepository
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.SavedSimulatorCredits
import com.clhs.score.data.SimulatorCredits
import com.clhs.score.data.cleanSubjectName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorCreditStateOwnerTest {
    private val session = FakeData.session
    private val base = MockGradeSystem.generateReport()
    private val report = base.copy(
        subjects = base.subjects.take(2).mapIndexed { index, subject ->
            subject.copy(scoreDisplay = if (index == 0) "50" else "80", absent = false, cheating = false)
        },
        examSummary = base.examSummary!!.copy(totalScoreDisplay = "340", averageScoreDisplay = "68"),
    )
    private val names = report.subjects.map { cleanSubjectName(it.subjectName) }

    @Test
    fun uniqueCandidateIsPreparedButOnlyExplicitConfirmationPersistsIt() = runTest {
        val repository = RecordingRepository()
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        assertEquals("--", owner.state.value.summaryDisplay(report.copy(examSummary = null), session.studentNo, "114_1", "E1").averageText)
        owner.beginEditing()
        runCurrent()

        assertEquals(mapOf(names[0] to 2, names[1] to 3), owner.state.value.credits)
        assertEquals("inferred", owner.state.value.source)
        assertFalse(owner.state.value.confirmed)
        assertNull(repository.saved)
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.appliedCredits)
        assertTrue(owner.state.value.summaryDisplay(report, session.studentNo, "114_1", "E1").needsCreditAttention)

        owner.save()
        runCurrent()
        assertTrue(owner.state.value.confirmed)
        assertEquals(owner.state.value.credits, repository.saved?.credits)
        assertEquals(repository.saved?.credits, owner.state.value.appliedCredits)
        assertFalse(owner.state.value.summaryDisplay(report, session.studentNo, "114_1", "E1").needsCreditAttention)
        assertFalse(owner.state.value.isEditing)
        owner.reset()
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.confirmed)
        assertEquals(1, repository.writes)
    }

    @Test
    fun ambiguousCandidatesKeepDefaultsAndKnownCreditsNarrowTheSearch() = runTest {
        val ambiguous = report.copy(
            subjects = report.subjects.map { it.copy(scoreDisplay = "50") },
            examSummary = report.examSummary!!.copy(totalScoreDisplay = "200", averageScoreDisplay = "50"),
        )
        val owner = SimulatorCreditStateOwner(RecordingRepository(), this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", ambiguous) { true }
        owner.beginEditing()
        runCurrent()
        assertEquals(SimulatorCredits.defaultCredits(ambiguous.subjects), owner.state.value.credits)
        assertTrue(owner.state.value.candidates.size > 1)
        owner.edit(names[0], "1")
        owner.toggleFixed(names[0])
        owner.infer()
        runCurrent()
        assertEquals(1, owner.state.value.candidates.size)
        owner.applyCandidate(0)
        assertEquals(mapOf(names[0] to 1, names[1] to 3), owner.state.value.credits)
        assertFalse(owner.state.value.confirmed)
        assertEquals(SimulatorCredits.defaultCredits(ambiguous.subjects), owner.state.value.appliedCredits)
    }

    @Test
    fun scoreRefreshReusesConfirmedMapButNewSubjectConfigurationDoesNot() = runTest {
        val repository = RecordingRepository().apply {
            saved = SavedSimulatorCredits(names.sorted(), mapOf(names[0] to 2, names[1] to 3), "manual")
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        val changedScore = report.copy(subjects = report.subjects.map { it.copy(scoreDisplay = "90") })
        owner.open(session, "114_1", "E1", changedScore) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.confirmed)
        assertEquals(SimulatorCredits.VerificationStatus.MISMATCH, SimulatorCredits.verify(changedScore.subjects, changedScore.examSummary, owner.state.value.credits!!).status)
        val changedSubjects = report.copy(subjects = report.subjects.map { it.copy(subjectName = it.subjectName + "-新") })
        owner.open(session, "114_1", "E1", changedSubjects) { true }
        owner.beginEditing()
        runCurrent()
        assertFalse(owner.state.value.confirmed)
        assertTrue(owner.state.value.notice!!.contains("科目配置"))
        assertEquals(0, repository.writes)
    }

    @Test
    fun invalidDraftCannotSaveAndResetCanRecoverIt() = runTest {
        val repository = RecordingRepository()
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        owner.edit(names[0], "")
        assertNull(owner.state.value.credits)
        owner.save()
        runCurrent()
        assertEquals(0, repository.writes)
        owner.edit(names[0], "0")
        owner.toggleFixed(names[0])
        owner.infer()
        runCurrent()
        assertNull(owner.state.value.credits)
        assertTrue(owner.state.value.error != null)
        owner.save()
        runCurrent()
        assertEquals(0, repository.writes)
        owner.edit(names[0], "5")
        assertNull(owner.state.value.credits)
        owner.save()
        runCurrent()
        assertEquals(0, repository.writes)
        owner.restoreDefaults()
        runCurrent()
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.credits)
        assertNull(repository.saved)
        assertEquals(0, repository.writes)
        owner.save()
        runCurrent()
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), repository.saved?.credits)
    }

    @Test
    fun readAndWriteFailuresRemainVisibleAndCanBeRetried() = runTest {
        val repository = RecordingRepository().apply { failRead = true }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.error != null)
        assertTrue(owner.state.value.candidates.isEmpty())
        repository.failRead = false
        owner.retryLoad()
        runCurrent()
        assertEquals("inferred", owner.state.value.source)
        repository.failWrite = true
        owner.save()
        runCurrent()
        assertFalse(owner.state.value.confirmed)
        assertTrue(owner.state.value.error != null)
        repository.failWrite = false
        owner.save()
        runCurrent()
        assertTrue(owner.state.value.confirmed)
    }

    @Test
    fun revokedOwnerCannotSaveOrApplyDelayedLoads() = runTest {
        val pending = CompletableDeferred<SavedSimulatorCredits?>()
        val repository = object : GradeRepository by RecordingRepository() {
            override suspend fun loadSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String) = pending.await()
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        var authorized = true
        owner.open(session, "114_1", "E1", report) { authorized }
        owner.beginEditing()
        runCurrent()
        authorized = false
        owner.reset()
        pending.complete(SavedSimulatorCredits(names.sorted(), names.associateWith { 4 }, "manual"))
        runCurrent()
        owner.save()
        assertEquals(SimulatorCreditUiState(), owner.state.value)
    }

    @Test
    fun returningToExamRestartsAnAbandonedLoadInsteadOfRemainingBusy() = runTest {
        val pending = CompletableDeferred<SavedSimulatorCredits?>()
        var reads = 0
        var selectedExam = "E1"
        val repository = object : GradeRepository by RecordingRepository() {
            override suspend fun loadSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String): SavedSimulatorCredits? {
                reads++
                return pending.await()
            }
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { selectedExam == "E1" }
        owner.beginEditing()
        runCurrent()
        selectedExam = "E2"
        pending.complete(null)
        runCurrent()
        selectedExam = "E1"
        owner.open(session, "114_1", "E1", report) { selectedExam == "E1" }
        owner.beginEditing()
        runCurrent()
        assertEquals(2, reads)
        assertFalse(owner.state.value.isLoading)
        assertEquals("inferred", owner.state.value.source)
    }

    @Test
    fun returningToExamReloadsCompletedSaveWhoseUiResultWasDiscarded() = runTest {
        val pending = CompletableDeferred<Unit>()
        val stored = RecordingRepository()
        var selectedExam = "E1"
        val repository = object : GradeRepository by stored {
            override suspend fun saveSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String, settings: SavedSimulatorCredits?) {
                pending.await()
                stored.saveSimulatorCredits(session, yearValue, examValue, settings)
            }
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { selectedExam == "E1" }
        owner.beginEditing()
        runCurrent()
        owner.save()
        runCurrent()
        selectedExam = "E2"
        pending.complete(Unit)
        runCurrent()
        selectedExam = "E1"
        owner.open(session, "114_1", "E1", report) { selectedExam == "E1" }
        owner.beginEditing()
        runCurrent()
        assertFalse(owner.state.value.isSaving)
        assertTrue(owner.state.value.confirmed)
    }

    @Test
    fun matchingDefaultsSkipInferenceAndCanBeConfirmedAndRestored() = runTest {
        val defaults = SimulatorCredits.defaultCredits(report.subjects)!!
        val calculation = SimulatorCredits.calculate(report.subjects, defaults)
        val matching = report.copy(examSummary = report.examSummary!!.copy(
            totalScoreDisplay = calculation.weightedTotal!!.toPlainString(),
            averageScoreDisplay = calculation.weightedAverage!!.stripTrailingZeros().toPlainString(),
        ))
        val repository = RecordingRepository()
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", matching) { true }
        owner.beginEditing()
        runCurrent()
        assertEquals(defaults, owner.state.value.credits)
        assertEquals("default", owner.state.value.source)
        assertTrue(owner.state.value.candidates.isEmpty())
        assertNull(owner.state.value.inferenceMessage)
        assertEquals(0, repository.writes)
        owner.save()
        runCurrent()
        assertEquals("default", repository.saved!!.source)
        owner.reset()
        owner.open(session, "114_1", "E1", matching) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.confirmed)
        assertEquals("default", owner.state.value.source)
    }

    @Test
    fun matchingTotalButDifferentAverageStillTriggersInference() = runTest {
        val defaults = SimulatorCredits.defaultCredits(report.subjects)!!
        val total = SimulatorCredits.calculate(report.subjects, defaults).weightedTotal!!
        val differentAverage = report.copy(examSummary = report.examSummary!!.copy(totalScoreDisplay = total.toPlainString(), averageScoreDisplay = "99"))
        val owner = SimulatorCreditStateOwner(RecordingRepository(), this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", differentAverage) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.inferenceMessage != null)
        assertFalse(owner.state.value.confirmed)
    }

    @Test
    fun closingUnsavedInferenceAndManualEditsRestoresDefaults() = runTest {
        val repository = RecordingRepository()
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        assertEquals("inferred", owner.state.value.source)
        owner.edit(names[0], "1")
        owner.toggleFixed(names[0])
        owner.discardDraft()
        val defaults = SimulatorCredits.defaultCredits(report.subjects)
        assertEquals(defaults, owner.state.value.credits)
        assertEquals(defaults, owner.state.value.appliedCredits)
        assertTrue(owner.state.value.fixedSubjects.isEmpty())
        assertTrue(owner.state.value.candidates.isEmpty())
        assertFalse(owner.state.value.isEditing)
        assertEquals(0, repository.writes)
    }

    @Test
    fun resetAndCandidateAreDraftOnlyAndCancelRestoresSavedMap() = runTest {
        val saved = names.associateWith { 4 }
        val repository = RecordingRepository().apply { this.saved = SavedSimulatorCredits(names.sorted(), saved, "manual") }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        owner.infer()
        runCurrent()
        owner.applyCandidate(0)
        assertEquals(mapOf(names[0] to 2, names[1] to 3), owner.state.value.credits)
        assertEquals(saved, owner.state.value.appliedCredits)
        owner.restoreDefaults()
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.credits)
        assertEquals(saved, owner.state.value.appliedCredits)
        assertEquals(0, repository.writes)
        owner.discardDraft()
        assertEquals(saved, owner.state.value.credits)
        assertEquals(saved, owner.state.value.appliedCredits)
        assertTrue(owner.state.value.confirmed)
    }

    @Test
    fun pendingSaveCannotDismissAndAppliesOnlyAfterSuccessfulWrite() = runTest {
        val pending = CompletableDeferred<Unit>()
        val stored = RecordingRepository()
        val repository = object : GradeRepository by stored {
            override suspend fun saveSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String, settings: SavedSimulatorCredits?) {
                pending.await()
                stored.saveSimulatorCredits(session, yearValue, examValue, settings)
            }
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        val draft = owner.state.value.credits
        owner.save()
        runCurrent()
        owner.discardDraft()
        assertTrue(owner.state.value.isSaving)
        assertTrue(owner.state.value.isEditing)
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.appliedCredits)
        assertTrue(owner.state.value.summaryDisplay(report, session.studentNo, "114_1", "E1").needsCreditAttention)
        pending.complete(Unit)
        runCurrent()
        assertEquals(draft, owner.state.value.appliedCredits)
        assertFalse(owner.state.value.summaryDisplay(report, session.studentNo, "114_1", "E1").needsCreditAttention)
        assertFalse(owner.state.value.isEditing)
    }

    @Test
    fun failedSaveRetainsEffectiveMapAndDraftForRetry() = runTest {
        val saved = names.associateWith { 4 }
        val repository = RecordingRepository().apply {
            this.saved = SavedSimulatorCredits(names.sorted(), saved, "manual")
            failWrite = true
        }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        owner.edit(names[0], "1")
        val draft = owner.state.value.inputs
        owner.save()
        runCurrent()
        assertEquals(saved, owner.state.value.appliedCredits)
        assertEquals(saved, repository.saved?.credits)
        assertEquals(draft, owner.state.value.inputs)
        assertTrue(owner.state.value.isEditing)
        assertTrue(owner.state.value.error != null)
        assertFalse(owner.state.value.isSaving)
        repository.failWrite = false
        owner.save()
        runCurrent()
        assertEquals(draft.mapValues { it.value.toInt() }, owner.state.value.appliedCredits)
        assertFalse(owner.state.value.isEditing)
    }

    @Test
    fun discardCancelsPendingInferenceWithoutApplyingLateCandidate() = runTest {
        val inferenceWork = mutableListOf<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { inferenceWork += block }
        }
        val owner = SimulatorCreditStateOwner(RecordingRepository(), this, dispatcher)
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        assertTrue(owner.state.value.isInferring)
        owner.discardDraft()
        inferenceWork.toList().forEach { it.run() }
        runCurrent()
        assertFalse(owner.state.value.isInferring)
        assertFalse(owner.state.value.isEditing)
        assertTrue(owner.state.value.candidates.isEmpty())
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.credits)
        assertEquals(owner.state.value.credits, owner.state.value.appliedCredits)
    }

    @Test
    fun failedRefreshRetainsLoadedMapAndBlocksOverwritingUnknownStorage() = runTest {
        val saved = names.associateWith { 3 }
        val repository = RecordingRepository().apply { this.saved = SavedSimulatorCredits(names.sorted(), saved, "manual") }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        runCurrent()
        repository.failRead = true
        val refreshed = report.copy(subjects = report.subjects.map { it.copy(scoreDisplay = "90") })
        owner.open(session, "114_1", "E1", refreshed) { true }
        owner.beginEditing()
        runCurrent()
        owner.edit(names[0], "1")
        owner.restoreDefaults()
        owner.save()
        runCurrent()
        assertEquals(saved, owner.state.value.appliedCredits)
        assertEquals(saved, repository.saved?.credits)
        assertFalse(owner.state.value.isLoaded)
        assertFalse(owner.state.value.isAppliedStateReliable)
        val display = owner.state.value.summaryDisplay(refreshed.copy(examSummary = null), session.studentNo, "114_1", "E1")
        assertEquals("540*", display.totalText)
        assertEquals("90.00*", display.averageText)
        assertTrue(display.needsCreditAttention)
        assertTrue(owner.state.value.error != null)
        assertEquals(0, repository.writes)
    }

    @Test
    fun invalidStoredSettingsRequireExplicitDefaultRestoreAndSaveAndCancelRestoresError() = runTest {
        listOf(kotlinx.serialization.SerializationException("invalid JSON"), IllegalArgumentException("invalid credits")).forEach { failure ->
            val stored = RecordingRepository()
            val repository = object : GradeRepository by stored {
                override suspend fun loadSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String): SavedSimulatorCredits? {
                    throw failure
                }
            }
            val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
            owner.open(session, "114_1", "E1", report) { true }
            owner.beginEditing()
            runCurrent()
            val failed = owner.state.value
            assertFalse(failed.isLoaded)
            assertTrue(failed.error != null)
            owner.save()
            runCurrent()
            assertEquals(0, stored.writes)

            owner.restoreDefaults()
            assertTrue(owner.state.value.isLoaded)
            assertFalse(owner.state.value.isAppliedStateReliable)
            assertNull(owner.state.value.error)
            assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.credits)
            assertEquals(failed.appliedCredits, owner.state.value.appliedCredits)
            assertEquals(0, stored.writes)
            owner.discardDraft()
            assertEquals(failed.copy(isEditing = false), owner.state.value)

            owner.beginEditing()
            owner.restoreDefaults()
            owner.save()
            runCurrent()
            assertEquals(1, stored.writes)
            assertEquals(SimulatorCredits.defaultCredits(report.subjects), stored.saved?.credits)
            assertEquals(stored.saved?.credits, owner.state.value.appliedCredits)
            assertTrue(owner.state.value.isAppliedStateReliable)
            assertFalse(owner.state.value.isEditing)
        }
    }

    @Test
    fun ioLoadFailureCannotBeOverwrittenByRestoreDefaultsAndSave() = runTest {
        val repository = RecordingRepository().apply { failRead = true }
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        val failed = owner.state.value
        owner.restoreDefaults()
        owner.edit(names[0], "1")
        owner.save()
        runCurrent()
        assertEquals(failed, owner.state.value)
        assertEquals(0, repository.writes)
    }

    @Test
    fun scopeAndReportChangesCloseDraftAndLoadOnlyTheirOwnSavedCredits() = runTest {
        val repository = FakeGradeRepository()
        val saved = names.associateWith { 3 }
        repository.saveSimulatorCredits(session, "114_1", "E1", SavedSimulatorCredits(names.sorted(), saved, "manual"))
        val owner = SimulatorCreditStateOwner(repository, this, StandardTestDispatcher(testScheduler))
        owner.open(session, "114_1", "E1", report) { true }
        owner.beginEditing()
        runCurrent()
        owner.edit(names[0], "1")
        owner.open(session, "114_1", "E2", report) { true }
        runCurrent()
        assertFalse(owner.state.value.isEditing)
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.appliedCredits)
        owner.open(session, "114_1", "E1", report) { true }
        runCurrent()
        assertEquals(saved, owner.state.value.appliedCredits)
        owner.beginEditing()
        owner.edit(names[0], "1")
        owner.open(session, "114_1", "E1", report.copy(subjects = report.subjects.map { it.copy(scoreDisplay = "90") })) { true }
        runCurrent()
        assertFalse(owner.state.value.isEditing)
        assertEquals(saved, owner.state.value.credits)
        owner.open(session, "114_2", "E1", report) { true }
        runCurrent()
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.appliedCredits)
        val otherSession = session.copy(studentNo = session.studentNo + "-other")
        repository.activateSession(otherSession)
        owner.open(otherSession, "114_1", "E1", report) { true }
        runCurrent()
        assertEquals(SimulatorCredits.defaultCredits(report.subjects), owner.state.value.appliedCredits)
    }

    @Test
    fun displayRejectsStaleAccountExamYearAndSubjectMapsAndRecalculatesRefreshedScores() {
        val missingSummary = report.copy(examSummary = null)
        val state = SimulatorCreditUiState(
            identity = simulatorCreditIdentity(session.studentNo, "114_1", "E1", missingSummary),
            appliedCredits = mapOf(names[0] to 2, names[1] to 3),
            isAppliedStateReliable = true,
        )
        assertEquals("68.00*", state.summaryDisplay(missingSummary, session.studentNo, "114_1", "E1").averageText)
        listOf(
            Triple(session.studentNo + "other", "114_1", "E1"),
            Triple(session.studentNo, "114_2", "E1"),
            Triple(session.studentNo, "114_1", "E2"),
            Triple(session.studentNo, null, "E1"),
        ).forEach { (student, year, exam) ->
            val display = state.summaryDisplay(missingSummary, student, year, exam)
            assertEquals("--", display.averageText)
            assertTrue(display.needsCreditAttention)
        }
        val changedSubjects = missingSummary.copy(subjects = missingSummary.subjects.map { it.copy(subjectName = it.subjectName + "-新") })
        assertEquals("--", state.summaryDisplay(changedSubjects, session.studentNo, "114_1", "E1").averageText)
        val refreshed = missingSummary.copy(subjects = missingSummary.subjects.map { it.copy(scoreDisplay = "90") })
        assertEquals("90.00*", state.summaryDisplay(refreshed, session.studentNo, "114_1", "E1").averageText)
    }

    @Test
    fun editingRestoringAndSavingMissingSummaryNeverDismissAttentionOrApplyDraftEarly() = runTest {
        val missingSummary = report.copy(examSummary = null)
        val owner = SimulatorCreditStateOwner(RecordingRepository(), this, StandardTestDispatcher(testScheduler))
        fun display() = owner.state.value.summaryDisplay(missingSummary, session.studentNo, "114_1", "E1")
        owner.open(session, "114_1", "E1", missingSummary) { true }
        runCurrent()
        val applied = display()
        owner.beginEditing()
        owner.edit(names[0], "1")
        assertEquals(applied, display())
        owner.restoreDefaults()
        assertEquals(applied, display())
        owner.edit(names[0], "2")
        owner.edit(names[1], "3")
        assertEquals(applied, display())
        owner.save()
        assertEquals(applied, display())
        runCurrent()
        assertEquals("68.00*", display().averageText)
        assertTrue(display().needsCreditAttention)
    }

    private class RecordingRepository : GradeRepository by FakeGradeRepository() {
        var saved: SavedSimulatorCredits? = null
        var writes = 0
        var failRead = false
        var failWrite = false
        override suspend fun loadSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String): SavedSimulatorCredits? {
            if (failRead) throw IOException("read failure")
            return saved
        }
        override suspend fun saveSimulatorCredits(session: AuthenticatedSession, yearValue: String, examValue: String, settings: SavedSimulatorCredits?) {
            if (failWrite) throw IOException("write failure")
            writes++
            saved = settings
        }
    }
}
