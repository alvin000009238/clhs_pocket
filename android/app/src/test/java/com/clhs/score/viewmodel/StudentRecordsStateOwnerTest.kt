package com.clhs.score.viewmodel

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.FakeGradeRepository
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordCategoryCount
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.SchoolAuthenticationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StudentRecordsStateOwnerTest {
    @Test
    fun refreshDiscoversTermsPreservesSelectionAndClearsDetailsWhenTermsDisappear() = runTest {
        var semesters = listOf(OLDER, NEWEST)
        val repository = object : GradeRepository by RecordsRepository() {
            override suspend fun fetchRecordSemesters(session: AuthenticatedSession) = semesters
        }
        val owner = StudentRecordsStateOwner(repository, this, { SESSION }, {})
        owner.loadAttendance()
        runCurrent()
        owner.selectSemester(OLDER)
        runCurrent()
        val next = RecordSemester(115, 2, "115 下")
        semesters = semesters + next
        owner.loadAttendance(force = true)
        runCurrent()
        assertEquals(OLDER, owner.state.value.selectedSemester)
        assertTrue(next in owner.state.value.semesters)
        semesters = emptyList()
        owner.loadAttendance(force = true)
        runCurrent()
        assertNull(owner.state.value.selectedSemester)
        assertNull(owner.state.value.attendance.data)
        assertNull(owner.state.value.semestersError)
        assertFalse(owner.state.value.semestersLoading)
    }

    @Test
    fun loadsOnlyOpenedRecordsAndKeepsIndependentHistoryWhenSemesterFails() = runTest {
        val repository = RecordsRepository().apply { failAttendance = true }
        val owner = StudentRecordsStateOwner(repository, this, { SESSION }, {})
        runCurrent()
        assertEquals(0, repository.semesterCalls)
        owner.loadAttendance()
        owner.loadAttendance()
        runCurrent()
        assertEquals(1, repository.semesterCalls)
        assertEquals(0, repository.meritCalls)
        assertNotNull(owner.state.value.attendance.error)
        assertEquals("事假", owner.state.value.attendanceHistory.data?.single()?.category)
        repository.failAttendance = false
        owner.loadAttendance(force = true)
        runCurrent()
        assertNotNull(owner.state.value.attendance.data)
        assertNull(owner.state.value.attendance.error)
    }

    @Test
    fun selectingSemesterClearsOldDetailsAndRejectsLateCancelledResult() = runTest {
        val release = CompletableDeferred<Unit>()
        val repository = object : GradeRepository by RecordsRepository() {
            override suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord {
                if (semester == NEWEST) withContext(NonCancellable) { release.await() }
                return AttendanceRecord(listOf(RecordCategoryCount("事假", semester.term)), emptyList())
            }
        }
        val owner = StudentRecordsStateOwner(repository, this, { SESSION }, {})
        owner.loadAttendance()
        runCurrent()
        assertTrue(owner.state.value.attendance.loading)
        owner.selectSemester(OLDER)
        assertNull(owner.state.value.attendance.data)
        runCurrent()
        assertEquals(2, owner.state.value.attendance.data?.summary?.single()?.count)
        release.complete(Unit)
        runCurrent()
        assertEquals(OLDER, owner.state.value.selectedSemester)
        assertEquals(2, owner.state.value.attendance.data?.summary?.single()?.count)
    }

    @Test
    fun resetSynchronouslyErasesPrivateStateAndOldAuthErrorCannotExpireNewAccount() = runTest {
        var session: AuthenticatedSession? = SESSION
        var expirations = 0
        val release = CompletableDeferred<Unit>()
        val repository = object : GradeRepository by RecordsRepository() {
            override suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> {
                if (session == SESSION) {
                    withContext(NonCancellable) { release.await() }
                    throw SchoolAuthenticationException()
                }
                return emptyList()
            }
        }
        val owner = StudentRecordsStateOwner(repository, this, { session }, { expirations++ })
        owner.loadMerits()
        runCurrent()
        assertNotNull(owner.state.value.meritHistory.data)
        owner.reset()
        assertEquals(StudentRecordsState(), owner.state.value)
        session = SESSION.copy(apiToken = "replacement-token")
        owner.loadMerits()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(0, expirations)
        assertEquals(emptyList<MeritRecord>(), owner.state.value.merits.data)
        owner.reset()
        session = null
        owner.loadAttendance()
        assertEquals(StudentRecordsState(), owner.state.value)
    }

    @Test
    fun currentAuthenticationFailureIsForwardedAndHistoryFailureDoesNotHideDetails() = runTest {
        var expirations = 0
        val repository = object : GradeRepository by RecordsRepository() {
            override suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
                throw IllegalStateException("synthetic failure")
            override suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> =
                throw SchoolAuthenticationException()
        }
        val owner = StudentRecordsStateOwner(repository, this, { SESSION }, { expirations++ })
        owner.loadAttendance()
        runCurrent()
        assertNotNull(owner.state.value.attendance.data)
        assertNotNull(owner.state.value.attendanceHistory.error)
        assertFalse(owner.state.value.attendanceHistory.loading)
        owner.loadMerits()
        runCurrent()
        assertEquals(1, expirations)
    }

    private open class RecordsRepository : GradeRepository by FakeGradeRepository() {
        var semesterCalls = 0
        var meritCalls = 0
        var failAttendance = false
        override suspend fun fetchRecordSemesters(session: AuthenticatedSession): List<RecordSemester> {
            semesterCalls++
            return listOf(OLDER, NEWEST)
        }
        override suspend fun fetchAttendanceHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> =
            listOf(RecordHistoryStatistic("事假", listOf(1)))
        override suspend fun fetchMeritHistory(session: AuthenticatedSession): List<RecordHistoryStatistic> = emptyList()
        override suspend fun fetchAttendance(session: AuthenticatedSession, semester: RecordSemester): AttendanceRecord {
            if (failAttendance) error("synthetic failure")
            return AttendanceRecord(emptyList(), emptyList())
        }
        override suspend fun fetchMerits(session: AuthenticatedSession, semester: RecordSemester): List<MeritRecord> {
            meritCalls++
            return emptyList()
        }
    }

    private companion object {
        val SESSION = AuthenticatedSession("DEMO-001", "fixture-token", emptyMap())
        val NEWEST = RecordSemester(115, 1, "115 上")
        val OLDER = RecordSemester(114, 2, "114 下")
    }
}
