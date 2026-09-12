package com.clhs.score.viewmodel

import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.FakeData
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.YearTermOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubjectTrendStateOwnerTest {
    @Test
    fun initializeLoadsAllYearsAndSubjectSelectionStaysLocal() = runTest {
        val repository = RecordingGradeRepository()
        val owner = SubjectTrendStateOwner(repository, NoOpAnalyticsLogger, this)

        owner.initialize(FakeData.structure, SESSION)
        runCurrent()
        val callsAfterLoad = repository.fetchCalls.size

        assertEquals(FakeData.structure.map { it.value }.toSet(), owner.state.value.selectedYearValues)
        assertEquals(FakeData.structure.sumOf { it.exams.size }, owner.state.value.reports.size)

        owner.toggleSubject("國文")
        owner.toggleSubject("英文")
        owner.toggleSubject("國文")

        assertEquals(setOf("英文"), owner.state.value.selectedSubjectKeys)
        assertEquals(callsAfterLoad, repository.fetchCalls.size)
    }

    @Test
    fun emptyYearSelectionAndResetClearOwnedResults() = runTest {
        val repository = RecordingGradeRepository()
        val owner = SubjectTrendStateOwner(repository, NoOpAnalyticsLogger, this)
        owner.initialize(FakeData.structure, SESSION)
        runCurrent()

        owner.setYears(emptySet(), FakeData.structure, SESSION)

        assertEquals(emptySet<String>(), owner.state.value.selectedYearValues)
        assertEquals(emptyList<GradeReport>(), owner.state.value.reports)

        owner.toggleSubject("英文")
        owner.reset()

        assertEquals(SubjectTrendUiState(), owner.state.value)
    }

    private class RecordingGradeRepository : GradeRepository {
        val fetchCalls = mutableListOf<Pair<String, String>>()

        override suspend fun restoreSession(): AuthenticatedSession = SESSION

        override fun activateSession(session: AuthenticatedSession) = Unit

        override suspend fun fetchStudentInfo(session: AuthenticatedSession): StudentInfo =
            FakeData.reportFor("114_1", "114_1_E4").studentInfo

        override suspend fun loadStructure(
            session: AuthenticatedSession,
            forceRefresh: Boolean,
        ): List<YearTermOption> = FakeData.structure

        override suspend fun fetchGrades(
            session: AuthenticatedSession,
            yearValue: String,
            examValue: String,
            forceRefresh: Boolean,
        ): GradeReport {
            fetchCalls += yearValue to examValue
            return FakeData.reportFor(yearValue, examValue)
        }

        override suspend fun logout(currentSession: AuthenticatedSession?) = Unit

        override suspend fun loginWithCookies(
            studentNo: String,
            cookies: Map<String, String>,
        ): AuthenticatedSession = SESSION
    }

    private companion object {
        val SESSION = AuthenticatedSession("DEMO-000", "token", emptyMap())
    }
}
