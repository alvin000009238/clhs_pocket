package com.clhs.score.viewmodel

import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.parseYearTerm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class SubjectTrendUiState(
    val selectedYearValues: Set<String> = emptySet(),
    val selectedSubjectKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val reports: List<GradeReport> = emptyList(),
    val errorMessage: String? = null,
)

internal class SubjectTrendStateOwner(
    private val repository: GradeRepository,
    private val analyticsLogger: AnalyticsLogger,
    private val scope: CoroutineScope,
    private val onAuthenticationExpired: () -> Unit = {},
) {
    private var requestId = 0
    private var loadJob: Job? = null
    private var isInitialized = false
    private val _state = MutableStateFlow(SubjectTrendUiState())

    val state: StateFlow<SubjectTrendUiState> = _state

    fun initialize(
        structure: List<YearTermOption>,
        session: AuthenticatedSession?,
    ) {
        if (isInitialized) return
        if (structure.isEmpty()) {
            _state.update {
                it.copy(isLoading = false, reports = emptyList(), errorMessage = null)
            }
            return
        }

        isInitialized = true
        _state.update {
            it.copy(
                selectedYearValues = structure.map { year -> year.value }.toSet(),
                selectedSubjectKeys = emptySet(),
            )
        }
        load(structure, session)
    }

    fun setYears(
        yearValues: Set<String>,
        structure: List<YearTermOption>,
        session: AuthenticatedSession?,
    ) {
        if (yearValues == _state.value.selectedYearValues) return
        _state.update { it.copy(selectedYearValues = yearValues) }
        load(structure, session)
    }

    fun toggleSubject(subjectKey: String) {
        _state.update { state ->
            val selectedSubjects = if (subjectKey in state.selectedSubjectKeys) {
                state.selectedSubjectKeys - subjectKey
            } else {
                state.selectedSubjectKeys + subjectKey
            }
            state.copy(selectedSubjectKeys = selectedSubjects)
        }
    }

    fun reset(): Job? {
        requestId++
        val jobToJoin = loadJob
        jobToJoin?.cancel()
        isInitialized = false
        _state.value = SubjectTrendUiState()
        return jobToJoin
    }

    private fun load(
        structure: List<YearTermOption>,
        session: AuthenticatedSession?,
    ) {
        val currentSession = session ?: return
        val selectedYears = _state.value.selectedYearValues
        val currentRequestId = ++requestId
        loadJob?.cancel()
        val requests = structure
            .filter { it.value in selectedYears }
            .sortedBy { yearTerm ->
                val (year, term) = parseYearTerm(yearTerm.value, "0", "0")
                (year.toIntOrNull() ?: 0) * 10 + (term.toIntOrNull() ?: 0)
            }
            .flatMap { yearTerm ->
                yearTerm.exams.map { exam -> SubjectTrendRequest(yearTerm.value, exam.value) }
            }

        if (requests.isEmpty()) {
            logResult(AnalyticsValues.RESULT_EMPTY, selectedYears.size)
            _state.update {
                if (currentRequestId != requestId) it else it.copy(
                    isLoading = false,
                    reports = emptyList(),
                    errorMessage = null,
                )
            }
            return
        }

        loadJob = scope.launch {
            _state.update {
                if (currentRequestId != requestId) it else it.copy(isLoading = true, errorMessage = null)
            }
            runCatching {
                supervisorScope {
                    requests.map { request ->
                        async {
                            runCatching {
                                repository.fetchGrades(
                                    currentSession,
                                    request.yearValue,
                                    request.examValue,
                                    false,
                                )
                            }.onFailure { error ->
                                error.throwIfCancellation()
                                if (error is SchoolAuthenticationException) onAuthenticationExpired()
                            }
                        }
                    }.awaitAll()
                }
            }.onSuccess { results ->
                if (currentRequestId != requestId) return@onSuccess
                val reports = results.mapNotNull { it.getOrNull() }
                logResult(
                    if (reports.isEmpty()) AnalyticsValues.RESULT_FAILURE else AnalyticsValues.RESULT_SUCCESS,
                    selectedYears.size,
                )
                _state.update {
                    it.copy(
                        isLoading = false,
                        reports = reports,
                        errorMessage = when {
                            reports.isEmpty() -> "載入折線圖資料失敗"
                            reports.size != results.size -> "部分資料無法載入"
                            else -> null
                        },
                    )
                }
            }.onFailure { error ->
                error.throwIfCancellation()
                if (currentRequestId != requestId) return@onFailure
                logResult(AnalyticsValues.RESULT_FAILURE, selectedYears.size)
                _state.update {
                    it.copy(
                        isLoading = false,
                        reports = emptyList(),
                        errorMessage = "載入折線圖資料失敗",
                    )
                }
            }
        }
    }

    private fun logResult(result: String, selectedYearCount: Int) {
        analyticsLogger.logEvent(
            AnalyticsEvents.SUBJECT_TREND_LOAD,
            mapOf(
                AnalyticsParams.RESULT to result,
                AnalyticsParams.YEAR_COUNT to selectedYearCount,
                AnalyticsParams.SUBJECT_COUNT to _state.value.selectedSubjectKeys.size,
            ),
        )
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    private data class SubjectTrendRequest(
        val yearValue: String,
        val examValue: String,
    )
}
