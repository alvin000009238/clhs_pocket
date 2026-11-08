package com.clhs.score.viewmodel

import com.clhs.score.data.AttendanceRecord
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordHistoryStatistic
import com.clhs.score.data.RecordSemester
import com.clhs.score.data.SchoolAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecordLoadState<T>(val loading: Boolean = false, val data: T? = null, val error: String? = null)
data class StudentRecordsState(
    val semesters: List<RecordSemester> = emptyList(),
    val selectedSemester: RecordSemester? = null,
    val semestersLoading: Boolean = false,
    val semestersError: String? = null,
    val attendance: RecordLoadState<AttendanceRecord> = RecordLoadState(),
    val merits: RecordLoadState<List<MeritRecord>> = RecordLoadState(),
    val attendanceHistory: RecordLoadState<List<RecordHistoryStatistic>> = RecordLoadState(),
    val meritHistory: RecordLoadState<List<RecordHistoryStatistic>> = RecordLoadState(),
)

internal class StudentRecordsStateOwner(
    private val repository: GradeRepository,
    private val scope: CoroutineScope,
    private val currentSession: () -> AuthenticatedSession?,
    private val onAuthenticationExpired: () -> Unit,
) {
    private enum class Request { SEMESTERS, ATTENDANCE, MERITS, ATTENDANCE_HISTORY, MERIT_HISTORY }
    private val jobs = mutableMapOf<Request, Job>()
    private val requestIds = mutableMapOf<Request, Long>()
    private var generation = 0L
    private var attendanceOpened = false
    private var meritsOpened = false
    private var semestersLoaded = false
    private val mutableState = MutableStateFlow(StudentRecordsState())
    val state: StateFlow<StudentRecordsState> = mutableState

    fun reset(): List<Job> {
        generation++
        val previousJobs = jobs.values.toList()
        previousJobs.forEach(Job::cancel)
        jobs.clear()
        requestIds.clear()
        attendanceOpened = false
        meritsOpened = false
        semestersLoaded = false
        mutableState.value = StudentRecordsState()
        return previousJobs
    }

    fun loadAttendance(force: Boolean = false) {
        if (currentSession() == null) return
        attendanceOpened = true
        loadSemesters(force)
        val history = state.value.attendanceHistory
        if (force || (!history.loading && history.data == null)) {
            mutableState.update { it.copy(attendanceHistory = it.attendanceHistory.copy(loading = true, error = null)) }
            launch(Request.ATTENDANCE_HISTORY, { repository.fetchAttendanceHistory(it) }) { data, error ->
                mutableState.update { it.copy(attendanceHistory = RecordLoadState(data = data ?: it.attendanceHistory.data, error = error)) }
            }
        }
        loadAttendanceDetails(force)
    }

    fun loadMerits(force: Boolean = false) {
        if (currentSession() == null) return
        meritsOpened = true
        loadSemesters(force)
        val history = state.value.meritHistory
        if (force || (!history.loading && history.data == null)) {
            mutableState.update { it.copy(meritHistory = it.meritHistory.copy(loading = true, error = null)) }
            launch(Request.MERIT_HISTORY, { repository.fetchMeritHistory(it) }) { data, error ->
                mutableState.update { it.copy(meritHistory = RecordLoadState(data = data ?: it.meritHistory.data, error = error)) }
            }
        }
        loadMeritDetails(force)
    }

    fun selectSemester(semester: RecordSemester) {
        if (semester !in state.value.semesters || semester == state.value.selectedSemester) return
        cancel(Request.ATTENDANCE)
        cancel(Request.MERITS)
        mutableState.update { it.copy(selectedSemester = semester, attendance = RecordLoadState(), merits = RecordLoadState()) }
        if (attendanceOpened) loadAttendanceDetails()
        if (meritsOpened) loadMeritDetails()
    }

    private fun loadSemesters(force: Boolean = false) {
        if (state.value.semestersLoading || (!force && semestersLoaded)) return
        mutableState.update { it.copy(semestersLoading = true, semestersError = null) }
        launch(Request.SEMESTERS, { repository.fetchRecordSemesters(it) }) { data, error ->
            if (error != null) {
                mutableState.update { it.copy(semestersLoading = false, semestersError = error) }
                return@launch
            }
            semestersLoaded = true
            val semesters = data.orEmpty().sortedWith(compareByDescending<RecordSemester> { it.year }.thenByDescending { it.term })
            val previous = state.value.selectedSemester
            val selected = semesters.firstOrNull { it.year == previous?.year && it.term == previous.term } ?: semesters.firstOrNull()
            val changed = previous?.let { it.year to it.term } != selected?.let { it.year to it.term }
            if (changed) {
                cancel(Request.ATTENDANCE)
                cancel(Request.MERITS)
            }
            mutableState.update { it.copy(
                semesters = semesters, selectedSemester = selected, semestersLoading = false, semestersError = null,
                attendance = if (changed) RecordLoadState() else it.attendance,
                merits = if (changed) RecordLoadState() else it.merits,
            ) }
            if (attendanceOpened) loadAttendanceDetails()
            if (meritsOpened) loadMeritDetails()
        }
    }

    private fun loadAttendanceDetails(force: Boolean = false) {
        val semester = state.value.selectedSemester ?: return
        val current = state.value.attendance
        if (!force && (current.loading || current.data != null)) return
        mutableState.update { it.copy(attendance = current.copy(loading = true, error = null)) }
        launch(Request.ATTENDANCE, { repository.fetchAttendance(it, semester) }) { data, error ->
            mutableState.update { it.copy(attendance = RecordLoadState(data = data ?: it.attendance.data, error = error)) }
        }
    }

    private fun loadMeritDetails(force: Boolean = false) {
        val semester = state.value.selectedSemester ?: return
        val current = state.value.merits
        if (!force && (current.loading || current.data != null)) return
        mutableState.update { it.copy(merits = current.copy(loading = true, error = null)) }
        launch(Request.MERITS, { repository.fetchMerits(it, semester) }) { data, error ->
            mutableState.update { it.copy(merits = RecordLoadState(data = data ?: it.merits.data, error = error)) }
        }
    }

    private fun cancel(request: Request) {
        requestIds[request] = (requestIds[request] ?: 0) + 1
        jobs.remove(request)?.cancel()
    }

    private fun <T> launch(request: Request, fetch: suspend (AuthenticatedSession) -> T, publish: (T?, String?) -> Unit) {
        val session = currentSession() ?: return
        cancel(request)
        val requestId = requestIds.getValue(request)
        val capturedGeneration = generation
        fun isCurrent() = capturedGeneration == generation && requestIds[request] == requestId && currentSession() == session
        jobs[request] = scope.launch {
            try {
                val result = fetch(session)
                if (isCurrent()) publish(result, null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCurrent()) return@launch
                if (error is SchoolAuthenticationException) {
                    onAuthenticationExpired()
                } else {
                    publish(null, "在校紀錄載入失敗，請稍後重試")
                }
            }
        }
    }
}
