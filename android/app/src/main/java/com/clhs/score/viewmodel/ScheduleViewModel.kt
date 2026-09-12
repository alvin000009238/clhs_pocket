package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.FirebaseAnalyticsLogger
import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.NetworkScheduleRepository
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SessionStore
import com.clhs.score.data.parseYearTermOrNull
import com.clhs.score.data.normalizedOrNull
import com.clhs.score.data.normalizedSubjectOverrides
import com.clhs.score.data.refreshTargetDateAt
import com.clhs.score.data.shouldRefreshAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class ScheduleUiState(
    val isLoading: Boolean = false,
    val isInitialLoading: Boolean = true,
    val isError: Boolean = false,
    val errorMessage: String = "",
    val availableYears: List<ScheduleYearTermOption> = emptyList(),
    val selectedYearValue: String? = null,
    val availableClasses: List<ScheduleClassOption> = emptyList(),
    val selectedClassValue: String? = null,
    val selectedScope: ScheduleScope = ScheduleScope.SEMESTER,
    val report: ScheduleReport? = null,
    val noticeMessage: String? = null,
    val isSavingSubjectOverride: Boolean = false,
)

private data class ScheduleQuery(
    val yearValue: String,
    val classNo: String,
    val scope: ScheduleScope,
    val targetDate: LocalDate,
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    private val nowProvider: () -> LocalDateTime = LocalDateTime::now,
) : ViewModel() {
    private var scheduleRequestId = 0
    private var scheduleJob: Job? = null
    private var lastScheduleRequest: ScheduleQuery? = null

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.subjectOverridesFlow().collect { overrides ->
                    _uiState.update { state ->
                        state.copy(report = state.report?.copy(subjectOverrides = overrides))
                    }
                }
            } catch (error: Exception) {
                error.throwIfCancellation()
                _uiState.update { it.copy(noticeMessage = SUBJECT_OVERRIDE_LOAD_ERROR) }
            }
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isInitialLoading = true, isError = false) }
            try {
                val latest = repository.getLatestSchedule()
                if (latest != null) {
                    val now = nowProvider()
                    val query = ScheduleQuery(
                        latest.yearTermValue,
                        latest.classNo,
                        latest.scope,
                        now.toLocalDate(),
                    )
                    lastScheduleRequest = query
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isInitialLoading = false,
                            selectedYearValue = latest.yearTermValue,
                            selectedClassValue = latest.classNo,
                            selectedScope = latest.scope,
                            report = latest,
                        )
                    }
                    if (latest.shouldRefreshAt(now)) {
                        loadSchedule(
                            query.copy(targetDate = latest.refreshTargetDateAt(now)),
                            preserveExistingReport = true,
                        )
                    }
                } else {
                    _uiState.update { it.copy(isInitialLoading = false) }
                    loadYears()
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                _uiState.update { it.copy(isInitialLoading = false) }
                loadYears()
            }
        }
    }

    fun clearSelection() {
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        _uiState.update {
            it.copy(
                report = null,
                selectedScope = ScheduleScope.SEMESTER,
                noticeMessage = null,
                isLoading = false,
                isError = false,
            )
        }
        if (_uiState.value.availableYears.isEmpty()) {
            loadYears()
        }
    }

    private fun loadYears() {
        val requestId = ++scheduleRequestId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false) }
            try {
                val years = repository.getScheduleYears()
                if (requestId != scheduleRequestId) return@launch
                val selected = years.firstOrNull()?.value
                _uiState.update { 
                    it.copy(
                        availableYears = years,
                        selectedYearValue = selected
                    )
                }
                if (selected != null) {
                    loadClasses(selected)
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                if (requestId != scheduleRequestId) return@launch
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMessage = e.message ?: "載入學期清單失敗"
                    )
                }
            }
        }
    }

    fun selectYear(yearValue: String) {
        if (_uiState.value.selectedYearValue == yearValue) return
        scheduleJob?.cancel()
        scheduleJob = null
        lastScheduleRequest = null
        _uiState.update {
            it.copy(
                selectedYearValue = yearValue,
                availableClasses = emptyList(),
                selectedClassValue = null,
                report = null,
            )
        }
        loadClasses(yearValue)
    }
    
    fun selectClass(classValue: String) {
        if (_uiState.value.selectedClassValue == classValue) return
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        _uiState.update { it.copy(selectedClassValue = classValue, isLoading = false) }
    }

    fun selectScope(scope: ScheduleScope) {
        if (_uiState.value.selectedScope == scope) return
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        _uiState.update {
            it.copy(selectedScope = scope, noticeMessage = null, isLoading = false)
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun saveSubjectOverride(
        override: ScheduleSubjectOverride,
        onSaved: () -> Unit = {},
    ) {
        val current = _uiState.value.report?.subjectOverrides.orEmpty()
        val normalized = override.normalizedOrNull()
        val updated = (
            current.filterNot { it.originalSubjectName == override.originalSubjectName.trim() } +
                listOfNotNull(normalized)
            ).normalizedSubjectOverrides()
        if (updated == current) {
            onSaved()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingSubjectOverride = true) }
            try {
                repository.saveSubjectOverrides(updated)
                _uiState.update { state ->
                    state.copy(
                        isSavingSubjectOverride = false,
                        report = state.report?.copy(subjectOverrides = updated),
                    )
                }
                onSaved()
            } catch (error: Exception) {
                error.throwIfCancellation()
                _uiState.update {
                    it.copy(
                        isSavingSubjectOverride = false,
                        noticeMessage = SUBJECT_OVERRIDE_SAVE_ERROR,
                    )
                }
            }
        }
    }

    fun confirmSelection() {
        val yearValue = _uiState.value.selectedYearValue ?: return
        val classValue = _uiState.value.selectedClassValue ?: ""
        loadSchedule(
            ScheduleQuery(
                yearValue,
                classValue,
                _uiState.value.selectedScope,
                nowProvider().toLocalDate(),
            ),
        )
    }

    private fun loadClasses(yearValue: String) {
        val requestId = ++scheduleRequestId
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false) }
            try {
                val (year, term) = requireNotNull(parseYearTermOrNull(yearValue)) {
                    "學期資料格式錯誤"
                }
                
                val classes = repository.getScheduleClasses(year, term)
                if (requestId != scheduleRequestId) return@launch
                val selected = classes.firstOrNull()?.value
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        availableClasses = classes,
                        selectedClassValue = selected
                    )
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                if (requestId != scheduleRequestId) return@launch
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        isError = true,
                        errorMessage = e.message ?: "載入班級清單失敗"
                    )
                }
            }
        }
    }

    private fun loadSchedule(
        query: ScheduleQuery,
        preserveExistingReport: Boolean = false,
    ) {
        scheduleJob?.cancel()
        lastScheduleRequest = query
        val requestId = ++scheduleRequestId
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false, noticeMessage = null) }
            try {
                val (year, term) = requireNotNull(parseYearTermOrNull(query.yearValue)) {
                    "學期資料格式錯誤"
                }
                
                val report = repository.fetchSchedule(
                    query.yearValue,
                    year,
                    term,
                    query.classNo,
                    query.scope,
                    query.targetDate,
                )
                if (requestId != scheduleRequestId) return@launch
                val now = nowProvider()
                if (report.shouldRefreshAt(now)) {
                    lastScheduleRequest = query.copy(targetDate = report.refreshTargetDateAt(now))
                }
                analyticsLogger.logEvent(
                    AnalyticsEvents.SCHEDULE_QUERY,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                        AnalyticsParams.MODE to AnalyticsValues.MODE_CLASS,
                    ),
                )
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        selectedScope = report.scope,
                        report = report,
                        noticeMessage = if (query.scope == ScheduleScope.CURRENT_WEEK && report.scope == ScheduleScope.SEMESTER) {
                            CURRENT_WEEK_FALLBACK_NOTICE
                        } else if (report.scope == ScheduleScope.CURRENT_WEEK && report.changes == null) {
                            CURRENT_WEEK_COMPARISON_NOTICE
                        } else {
                            null
                        },
                    )
                }
            } catch (e: Exception) {
                e.throwIfCancellation()
                if (requestId != scheduleRequestId) return@launch
                analyticsLogger.logEvent(
                    AnalyticsEvents.SCHEDULE_QUERY,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                        AnalyticsParams.MODE to AnalyticsValues.MODE_CLASS,
                    ),
                )
                _uiState.update {
                    if (preserveExistingReport && it.report != null) {
                        it.copy(
                            isLoading = false,
                            isError = false,
                            noticeMessage = "無法更新，暫時顯示已儲存的課表",
                        )
                    } else {
                        it.copy(
                            isLoading = false,
                            isError = true,
                            errorMessage = e.message ?: "載入課表失敗",
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        lastScheduleRequest?.let { query ->
            loadSchedule(query, preserveExistingReport = _uiState.value.report != null)
            return
        }
        val st = _uiState.value
        val y = st.selectedYearValue
        val c = st.selectedClassValue
        if (st.report != null && y != null && c != null) {
            loadSchedule(
                ScheduleQuery(y, c, st.report.scope, nowProvider().toLocalDate()),
                preserveExistingReport = true,
            )
        } else if (y != null) {
            loadClasses(y)
        } else {
            loadYears()
        }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    companion object {
        const val CURRENT_WEEK_FALLBACK_NOTICE = "無法取得週課表，已改顯示學期課表"
        const val CURRENT_WEEK_COMPARISON_NOTICE = "已取得週課表，但無法確認是否有調課"
        const val SUBJECT_OVERRIDE_LOAD_ERROR = "無法載入自訂科目，請稍後再試"
        const val SUBJECT_OVERRIDE_SAVE_ERROR = "無法儲存自訂科目，請稍後再試"

        fun factory(
            context: Context,
            useFakeData: Boolean,
            activeSessionProvider: () -> AuthenticatedSession? = { null },
            sharedScheduleRepository: ScheduleRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val repository = sharedScheduleRepository ?: if (useFakeData) {
                    FakeScheduleRepository(GradeCacheStore(appContext))
                } else {
                    NetworkScheduleRepository(
                        SchoolGradeClient(),
                        SessionStore(appContext),
                        GradeCacheStore(appContext),
                        activeSessionProvider = activeSessionProvider,
                    )
                }
                return ScheduleViewModel(repository, FirebaseAnalyticsLogger(appContext)) as T
            }
        }
    }
}
