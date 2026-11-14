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
import com.clhs.score.data.CachedScheduleReport
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.NetworkScheduleRepository
import com.clhs.score.data.ScheduleWeekOption
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleDisplayMode
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.SCHOOL_COURSE_ZONE
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SessionStore
import com.clhs.score.data.SettingsRepository
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
    val refreshFailed: Boolean = false,
    val errorMessage: String = "",
    val availableYears: List<ScheduleYearTermOption> = emptyList(),
    val selectedYearValue: String? = null,
    val availableClasses: List<ScheduleClassOption> = emptyList(),
    val selectedClassValue: String? = null,
    val selectedScope: ScheduleScope = ScheduleScope.SEMESTER,
    val displayMode: ScheduleDisplayMode = ScheduleDisplayMode.WEEK,
    val weekOptions: List<ScheduleWeekOption> = emptyList(),
    val isWeekPickerOpen: Boolean = false,
    val isLoadingWeeks: Boolean = false,
    val weekPickerError: String? = null,
    val availability: com.clhs.score.data.ScheduleAvailability = com.clhs.score.data.ScheduleAvailability.AVAILABLE,
    val weekTargetDate: LocalDate? = null,
    val report: ScheduleReport? = null,
    val noticeMessage: String? = null,
    val isSavingSubjectOverride: Boolean = false,
)

private data class ScheduleQuery(
    val yearValue: String,
    val classNo: String,
    val scope: ScheduleScope,
    val targetDate: LocalDate,
    val automaticWeek: Boolean = true,
)

private data class ScheduleTargetKey(
    val yearValue: String,
    val classNo: String,
    val scope: ScheduleScope,
    val targetDate: LocalDate,
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now(SCHOOL_COURSE_ZONE) },
    private val settingsRepository: SettingsRepository? = null,
) : ViewModel() {
    private var scheduleRequestId = 0
    private var scheduleJob: Job? = null
    private var lastScheduleRequest: ScheduleQuery? = null
    private var automaticSnapshot: CachedScheduleReport? = null
    private val loadedSnapshots = mutableListOf<CachedScheduleReport>()
    private val failedAutomaticTargets = mutableMapOf<ScheduleTargetKey, com.clhs.score.data.ScheduleAvailability>()
    private var subjectOverrides: List<ScheduleSubjectOverride>? = null

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        settingsRepository?.let { settings ->
            viewModelScope.launch {
                try {
                    settings.settings.collect { value ->
                        _uiState.update { it.copy(displayMode = value.scheduleDisplayMode) }
                    }
                } catch (error: Exception) {
                    error.throwIfCancellation()
                    _uiState.update { it.copy(noticeMessage = DISPLAY_MODE_LOAD_ERROR) }
                }
            }
        }
        viewModelScope.launch {
            try {
                repository.subjectOverridesFlow().collect { overrides ->
                    subjectOverrides = overrides
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
                val snapshot = repository.getLatestScheduleSnapshot()
                val overviewSnapshot = runCatching { repository.getOverviewScheduleSnapshot() }
                    .onFailure { it.throwIfCancellation() }
                    .getOrNull()
                val latest = snapshot?.report
                if (latest != null) {
                    rememberSnapshot(snapshot)
                    val matchingOverview = overviewSnapshot?.takeIf {
                        it.report.scope == ScheduleScope.CURRENT_WEEK &&
                            it.report.yearTermValue == latest.yearTermValue &&
                            it.report.classNo == latest.classNo
                    }
                    val now = nowProvider()
                    val today = now.toLocalDate()
                    val latestStart = latest.weekStartDate?.let { start ->
                        runCatching { LocalDate.parse(start) }.getOrNull()
                    }
                    val isFutureWithoutOverview = matchingOverview == null &&
                        latest.scope == ScheduleScope.CURRENT_WEEK &&
                        latestStart != null &&
                        latestStart > today
                    if (matchingOverview != null) {
                        rememberSnapshot(matchingOverview)
                        automaticSnapshot = matchingOverview
                    } else if (latest.scope == ScheduleScope.CURRENT_WEEK && !isFutureWithoutOverview) {
                        automaticSnapshot = snapshot
                    }
                    val manualWeekTarget = if (
                        latest.scope == ScheduleScope.CURRENT_WEEK &&
                        matchingOverview != null &&
                        latest.weekStartDate != matchingOverview.report.weekStartDate
                    ) {
                        latestStart
                    } else {
                        null
                    }
                    val query = ScheduleQuery(
                        latest.yearTermValue,
                        latest.classNo,
                        latest.scope,
                        manualWeekTarget ?: today,
                        automaticWeek = manualWeekTarget == null,
                    )
                    lastScheduleRequest = query
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isInitialLoading = false,
                            selectedYearValue = latest.yearTermValue,
                            selectedClassValue = latest.classNo,
                            selectedScope = latest.scope,
                            weekTargetDate = manualWeekTarget,
                            report = latest,
                        )
                    }
                    val automaticCandidate = matchingOverview ?: snapshot
                    if (manualWeekTarget == null &&
                        latest.scope == ScheduleScope.CURRENT_WEEK &&
                        (isFutureWithoutOverview || needsAutomaticConfirmation(automaticCandidate, now))
                    ) {
                        loadSchedule(
                            query.copy(
                                targetDate = if (isFutureWithoutOverview) {
                                    today
                                } else {
                                    canonicalTargetDate(automaticCandidate.report, today, now)
                                },
                            ),
                            preserveExistingReport = true,
                            useLoadedReport = !isFutureWithoutOverview,
                            revalidate = true,
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
        automaticSnapshot = null
        failedAutomaticTargets.clear()
        _uiState.update {
            it.copy(
                report = null,
                selectedScope = ScheduleScope.SEMESTER,
                weekTargetDate = null,
                availability = com.clhs.score.data.ScheduleAvailability.AVAILABLE,
                noticeMessage = null,
                isLoading = false,
                isError = false,
                refreshFailed = false,
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
        dismissWeekPicker()
        if (_uiState.value.selectedYearValue == yearValue) return
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        automaticSnapshot = null
        failedAutomaticTargets.clear()
        _uiState.update {
            it.copy(
                selectedYearValue = yearValue,
                availableClasses = emptyList(),
                selectedClassValue = null,
                report = null,
                weekTargetDate = null,
                availability = com.clhs.score.data.ScheduleAvailability.AVAILABLE,
                refreshFailed = false,
                noticeMessage = null,
            )
        }
        loadClasses(yearValue)
    }
    
    fun selectClass(classValue: String) {
        dismissWeekPicker()
        if (_uiState.value.selectedClassValue == classValue) return
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        automaticSnapshot = null
        failedAutomaticTargets.clear()
        _uiState.update {
            it.copy(
                selectedClassValue = classValue,
                weekTargetDate = null,
                availability = com.clhs.score.data.ScheduleAvailability.AVAILABLE,
                isLoading = false,
                isError = false,
                refreshFailed = false,
                noticeMessage = null,
            )
        }
    }

    fun selectScope(scope: ScheduleScope) {
        dismissWeekPicker()
        if (_uiState.value.selectedScope == scope) return
        scheduleJob?.cancel()
        scheduleJob = null
        scheduleRequestId++
        lastScheduleRequest = null
        val state = _uiState.value
        _uiState.update {
            it.copy(selectedScope = scope, weekTargetDate = null, noticeMessage = null, isLoading = false)
        }
        if (state.report != null && state.selectedYearValue != null) {
            loadSchedule(
                ScheduleQuery(state.selectedYearValue, state.selectedClassValue.orEmpty(), scope, nowProvider().toLocalDate()),
                preserveExistingReport = true,
                useLoadedReport = true,
            )
        }
    }

    fun setDisplayMode(mode: ScheduleDisplayMode) {
        val settings = settingsRepository
        if (settings == null) {
            _uiState.update { it.copy(displayMode = mode) }
            return
        }
        viewModelScope.launch {
            try {
                settings.setScheduleDisplayMode(mode)
            } catch (error: Exception) {
                error.throwIfCancellation()
                _uiState.update { it.copy(noticeMessage = DISPLAY_MODE_SAVE_ERROR) }
            }
        }
    }

    private val weekOptionsByYear = mutableMapOf<String, List<ScheduleWeekOption>>()
    private var weekOptionsJob: Job? = null

    fun openWeekPicker() {
        val report = _uiState.value.report ?: return
        if (report.scope != ScheduleScope.CURRENT_WEEK || _uiState.value.isLoading) return
        val yearValue = report.yearTermValue
        val (year, term) = parseYearTermOrNull(yearValue) ?: return
        weekOptionsJob?.cancel()
        _uiState.update { it.copy(isWeekPickerOpen = true, weekOptions = weekOptionsByYear[yearValue].orEmpty(),
            isLoadingWeeks = yearValue !in weekOptionsByYear, weekPickerError = null) }
        if (yearValue in weekOptionsByYear) return
        weekOptionsJob = viewModelScope.launch {
            try {
                val options = repository.getScheduleWeeks(year, term)
                if (_uiState.value.report?.yearTermValue != yearValue) return@launch
                weekOptionsByYear[yearValue] = options
                _uiState.update { it.copy(weekOptions = options, isLoadingWeeks = false) }
            } catch (error: Exception) {
                error.throwIfCancellation()
                _uiState.update { it.copy(isLoadingWeeks = false, weekPickerError = "無法取得週次，請重試") }
            }
        }
    }

    fun dismissWeekPicker() {
        weekOptionsJob?.cancel()
        _uiState.update { it.copy(isWeekPickerOpen = false, isLoadingWeeks = false) }
    }

    fun selectWeek(option: ScheduleWeekOption) {
        val state = _uiState.value
        val report = state.report ?: return
        if (option !in state.weekOptions || report.scope != ScheduleScope.CURRENT_WEEK) return
        val target = runCatching { LocalDate.parse(option.startDate) }.getOrNull() ?: return
        dismissWeekPicker()
        _uiState.update { it.copy(weekTargetDate = target) }
        loadSchedule(ScheduleQuery(report.yearTermValue, report.classNo, ScheduleScope.CURRENT_WEEK, target, automaticWeek = false),
            preserveExistingReport = true, useLoadedReport = true)
    }

    fun previousWeek() = browseWeek(-1)

    fun nextWeek() = browseWeek(1)

    private fun browseWeek(offset: Long) {
        val state = _uiState.value
        val report = state.report ?: return
        if (report.scope != ScheduleScope.CURRENT_WEEK) return
        val start = report.weekStartDate
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: state.weekTargetDate
            ?: return
        val target = start.plusWeeks(offset)
        _uiState.update { it.copy(selectedScope = ScheduleScope.CURRENT_WEEK, weekTargetDate = target) }
        loadSchedule(
            ScheduleQuery(report.yearTermValue, report.classNo, ScheduleScope.CURRENT_WEEK, target, automaticWeek = false),
            preserveExistingReport = true,
            useLoadedReport = true,
        )
    }

    fun currentWeek() {
        val state = _uiState.value
        if (state.weekTargetDate == null && state.selectedScope == ScheduleScope.CURRENT_WEEK &&
            state.report?.scope == ScheduleScope.CURRENT_WEEK
        ) {
            ensureCurrentWeek()
            return
        }
        val year = state.selectedYearValue ?: return
        _uiState.update { it.copy(selectedScope = ScheduleScope.CURRENT_WEEK, weekTargetDate = null) }
        loadSchedule(
            ScheduleQuery(year, state.selectedClassValue.orEmpty(), ScheduleScope.CURRENT_WEEK, nowProvider().toLocalDate()),
            preserveExistingReport = state.report != null,
            useLoadedReport = true,
        )
    }

    fun ensureCurrentWeek() {
        val state = _uiState.value
        val report = state.report ?: return
        if (state.isLoading || state.weekTargetDate != null || state.selectedScope != ScheduleScope.CURRENT_WEEK ||
            report.scope != ScheduleScope.CURRENT_WEEK
        ) return
        val now = nowProvider()
        val currentSnapshot = automaticSnapshot?.takeIf {
            it.report.yearTermValue == report.yearTermValue &&
                it.report.classNo == report.classNo &&
                it.report.scope == ScheduleScope.CURRENT_WEEK
        } ?: CachedScheduleReport(report, null)
        if (!needsAutomaticConfirmation(currentSnapshot, now)) return
        val target = canonicalTargetDate(currentSnapshot.report, now.toLocalDate(), now)
        val targetKey = ScheduleTargetKey(report.yearTermValue, report.classNo, ScheduleScope.CURRENT_WEEK, target)
        if (targetKey in failedAutomaticTargets) return
        loadSchedule(
            ScheduleQuery(report.yearTermValue, report.classNo, ScheduleScope.CURRENT_WEEK, target),
            preserveExistingReport = true,
            useLoadedReport = true,
            revalidate = true,
        )
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
        _uiState.update { it.copy(weekTargetDate = null) }
        loadSchedule(
            ScheduleQuery(
                yearValue,
                classValue,
                _uiState.value.selectedScope,
                nowProvider().toLocalDate(),
            ),
            explicitRefresh = true,
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
        useLoadedReport: Boolean = false,
        revalidate: Boolean = false,
        explicitRefresh: Boolean = false,
    ) {
        scheduleJob?.cancel()
        val now = nowProvider()
        val matchingAutomatic = automaticSnapshot?.takeIf {
            query.automaticWeek && query.scope == ScheduleScope.CURRENT_WEEK &&
                it.report.yearTermValue == query.yearValue &&
                it.report.classNo == query.classNo &&
                it.report.scope == ScheduleScope.CURRENT_WEEK
        }
        val resolvedQuery = if (matchingAutomatic != null) {
            val targetDate = if (matchingAutomatic.report.shouldRefreshAt(now)) {
                matchingAutomatic.report.refreshTargetDateAt(now)
            } else {
                matchingAutomatic.report.weekStartDate
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: query.targetDate
            }
            query.copy(targetDate = targetDate)
        } else {
            query
        }
        val cached = if (useLoadedReport) findLoadedSnapshot(resolvedQuery) else null
        val effectiveQuery = if (resolvedQuery.automaticWeek && cached?.report?.shouldRefreshAt(now) == true) {
            resolvedQuery.copy(targetDate = cached.report.refreshTargetDateAt(now))
        } else {
            resolvedQuery
        }
        var currentTargetKey = ScheduleTargetKey(
            effectiveQuery.yearValue,
            effectiveQuery.classNo,
            effectiveQuery.scope,
            effectiveQuery.targetDate,
        )
        if (explicitRefresh) {
            failedAutomaticTargets.remove(currentTargetKey)
        }
        lastScheduleRequest = effectiveQuery
        val requestId = ++scheduleRequestId
        val reusable = if (useLoadedReport) findLoadedSnapshot(effectiveQuery) else null
        if (effectiveQuery.automaticWeek && !explicitRefresh && currentTargetKey in failedAutomaticTargets) {
            val retained = reusable ?: matchingAutomatic
            if (retained != null) {
                showReport(effectiveQuery, retained)
                val failure = failedAutomaticTargets.getValue(currentTargetKey)
                _uiState.update { it.copy(availability = failure,
                    refreshFailed = failure == com.clhs.score.data.ScheduleAvailability.FAILED,
                    noticeMessage = if (failure == com.clhs.score.data.ScheduleAvailability.UNPUBLISHED)
                        "學校尚未提供這一週的課表，保留目前資料" else "無法更新，暫時顯示已儲存的課表") }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
            return
        }
        val autoNeedsConfirm = matchingAutomatic != null && needsAutomaticConfirmation(matchingAutomatic, now)
        val mustRevalidate = explicitRefresh || revalidate ||
            (effectiveQuery.automaticWeek && (autoNeedsConfirm || effectiveQuery != resolvedQuery ||
                reusable?.let { needsAutomaticConfirmation(it, now) } == true))
        if (reusable != null && !mustRevalidate &&
            (!effectiveQuery.automaticWeek || !needsAutomaticConfirmation(reusable, now))
        ) {
            showReport(effectiveQuery, reusable)
            scheduleJob = viewModelScope.launch {
                try { repository.selectCachedReport(reusable.report, effectiveQuery.automaticWeek && effectiveQuery.scope == ScheduleScope.CURRENT_WEEK) }
                catch (error: Exception) {
                    error.throwIfCancellation()
                    if (requestId == scheduleRequestId) _uiState.update { it.copy(noticeMessage = "無法儲存目前課表選擇") }
                }
            }
            return
        }
        val previewSnapshot = reusable ?: matchingAutomatic.takeIf { effectiveQuery.automaticWeek }
        if (previewSnapshot != null) showReport(effectiveQuery, previewSnapshot)
        scheduleJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isError = false, refreshFailed = false, noticeMessage = null) }
            var fetchedSnapshot: CachedScheduleReport? = null
            try {
                val (year, term) = requireNotNull(parseYearTermOrNull(effectiveQuery.yearValue)) {
                    "學期資料格式錯誤"
                }

                var report = repository.fetchSchedule(
                    effectiveQuery.yearValue,
                    year,
                    term,
                    effectiveQuery.classNo,
                    effectiveQuery.scope,
                    effectiveQuery.targetDate,
                    updateOverviewSnapshot = effectiveQuery.automaticWeek && effectiveQuery.scope == ScheduleScope.CURRENT_WEEK,
                    forceRefresh = !useLoadedReport || mustRevalidate,
                )
                if (requestId != scheduleRequestId) return@launch
                var activeQuery = effectiveQuery
                var activeSnapshot = fetchedSnapshot(report, !useLoadedReport || mustRevalidate)
                rememberSnapshot(activeSnapshot)
                clearFailedAutomaticTarget(effectiveQuery, report, currentTargetKey)
                fetchedSnapshot = activeSnapshot
                val afterFetchNow = nowProvider()
                if (effectiveQuery.automaticWeek && report.shouldRefreshAt(afterFetchNow)) {
                    val nextQuery = effectiveQuery.copy(targetDate = report.refreshTargetDateAt(afterFetchNow))
                    val nextTargetKey = ScheduleTargetKey(
                        nextQuery.yearValue,
                        nextQuery.classNo,
                        nextQuery.scope,
                        nextQuery.targetDate,
                    )
                    if (nextQuery.targetDate > effectiveQuery.targetDate &&
                        (explicitRefresh || nextTargetKey !in failedAutomaticTargets)
                    ) {
                        lastScheduleRequest = nextQuery
                        currentTargetKey = nextTargetKey
                        if (explicitRefresh) failedAutomaticTargets.remove(nextTargetKey)
                        report = repository.fetchSchedule(
                            nextQuery.yearValue,
                            year,
                            term,
                            nextQuery.classNo,
                            nextQuery.scope,
                            nextQuery.targetDate,
                            updateOverviewSnapshot = nextQuery.automaticWeek && nextQuery.scope == ScheduleScope.CURRENT_WEEK,
                        )
                        if (requestId != scheduleRequestId) return@launch
                        activeQuery = nextQuery
                        activeSnapshot = fetchedSnapshot(report, true)
                        rememberSnapshot(activeSnapshot)
                        clearFailedAutomaticTarget(nextQuery, report, nextTargetKey)
                    }
                }
                analyticsLogger.logEvent(
                    AnalyticsEvents.SCHEDULE_QUERY,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                        AnalyticsParams.MODE to AnalyticsValues.MODE_CLASS,
                    ),
                )
                showReport(activeQuery, activeSnapshot)
            } catch (e: Exception) {
                e.throwIfCancellation()
                if (requestId != scheduleRequestId) return@launch
                if (effectiveQuery.automaticWeek && effectiveQuery.scope == ScheduleScope.CURRENT_WEEK) {
                    failedAutomaticTargets[currentTargetKey] = if (e is com.clhs.score.data.ScheduleFetchException && e.result is com.clhs.score.data.ScheduleFetchResult.Unpublished) com.clhs.score.data.ScheduleAvailability.UNPUBLISHED else com.clhs.score.data.ScheduleAvailability.FAILED
                }
                analyticsLogger.logEvent(
                    AnalyticsEvents.SCHEDULE_QUERY,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                        AnalyticsParams.MODE to AnalyticsValues.MODE_CLASS,
                    ),
                )
                _uiState.update {
                    val unavailable = e is com.clhs.score.data.ScheduleFetchException && e.result is com.clhs.score.data.ScheduleFetchResult.Unpublished
                    val fallback = (e as? com.clhs.score.data.ScheduleFetchException)?.fallback
                    val retainedReport = fetchedSnapshot?.report
                        ?: (reusable ?: matchingAutomatic.takeIf { effectiveQuery.automaticWeek })?.report
                        ?: it.report
                        ?: fallback
                    if (retainedReport != null && (preserveExistingReport || fetchedSnapshot != null || fallback != null)) {
                        val restoredWeekTarget = when {
                            retainedReport.scope != ScheduleScope.CURRENT_WEEK -> null
                            effectiveQuery.automaticWeek -> null
                            automaticSnapshot?.report?.let { auto ->
                                auto.yearTermValue == retainedReport.yearTermValue &&
                                    auto.classNo == retainedReport.classNo &&
                                    auto.scope == retainedReport.scope &&
                                    auto.weekStartDate == retainedReport.weekStartDate
                            } == true -> null
                            else -> retainedReport.weekStartDate?.let { start ->
                                runCatching { LocalDate.parse(start) }.getOrNull()
                            }
                        }
                        it.copy(
                            isLoading = false,
                            isError = false,
                            refreshFailed = !unavailable,
                            availability = if (unavailable) com.clhs.score.data.ScheduleAvailability.UNPUBLISHED else com.clhs.score.data.ScheduleAvailability.FAILED,
                            weekTargetDate = restoredWeekTarget,
                            selectedScope = retainedReport.scope,
                            report = retainedReport.copy(subjectOverrides = subjectOverrides ?: retainedReport.subjectOverrides),
                            noticeMessage = if (unavailable) "學校尚未提供這一週的課表，保留目前資料" else "無法更新，暫時顯示已儲存的課表",
                        )
                    } else {
                        it.copy(
                            isLoading = false,
                            isError = true,
                            availability = if (unavailable) com.clhs.score.data.ScheduleAvailability.UNPUBLISHED else com.clhs.score.data.ScheduleAvailability.FAILED,
                            errorMessage = e.message ?: "載入課表失敗",
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchedSnapshot(report: ScheduleReport, confirmedNetwork: Boolean): CachedScheduleReport {
        if (confirmedNetwork) return CachedScheduleReport(report, nowProvider().toEpochMillis())
        val stored = repository.getLatestScheduleSnapshot()?.takeIf {
            it.report.yearTermValue == report.yearTermValue && it.report.classNo == report.classNo &&
                it.report.scope == report.scope && it.report.weekStartDate == report.weekStartDate &&
                it.report.items == report.items
        }
        return CachedScheduleReport(report, if (stored != null) stored.fetchedAtMillis else nowProvider().toEpochMillis())
    }

    private fun canonicalTargetDate(report: ScheduleReport?, targetDate: LocalDate, now: LocalDateTime): LocalDate {
        if (report == null || report.scope != ScheduleScope.CURRENT_WEEK) return targetDate
        if (report.shouldRefreshAt(now)) return report.refreshTargetDateAt(now)
        if (report.isValidOn(targetDate)) {
            return report.weekStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: targetDate
        }
        return targetDate
    }

    private fun clearFailedAutomaticTarget(
        query: ScheduleQuery,
        report: ScheduleReport,
        targetKey: ScheduleTargetKey,
    ) {
        failedAutomaticTargets.remove(targetKey)
        report.weekStartDate?.let { start ->
            runCatching { LocalDate.parse(start) }.getOrNull()?.let { canonicalStart ->
                failedAutomaticTargets.remove(
                    ScheduleTargetKey(query.yearValue, query.classNo, query.scope, canonicalStart)
                )
            }
        }
    }

    private fun needsAutomaticConfirmation(snapshot: CachedScheduleReport, now: LocalDateTime): Boolean {
        val report = snapshot.report
        if (report.scope != ScheduleScope.CURRENT_WEEK) return false
        if (report.shouldRefreshAt(now)) return true
        if (!report.isValidOn(now.toLocalDate())) return false
        val weekStartMillis = report.weekStartDate?.let { start ->
            runCatching {
                LocalDate.parse(start).atStartOfDay(SCHOOL_COURSE_ZONE).toInstant().toEpochMilli()
            }.getOrNull()
        } ?: return true
        return (snapshot.fetchedAtMillis ?: Long.MIN_VALUE) < weekStartMillis
    }

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(SCHOOL_COURSE_ZONE).toInstant().toEpochMilli()

    private fun rememberSnapshot(snapshot: CachedScheduleReport) {
        val report = snapshot.report
        loadedSnapshots.removeAll {
            it.report.yearTermValue == report.yearTermValue && it.report.classNo == report.classNo &&
                it.report.scope == report.scope && it.report.weekStartDate == report.weekStartDate
        }
        loadedSnapshots += snapshot
        if (loadedSnapshots.size > MAX_LOADED_REPORTS) loadedSnapshots.removeAt(0)
    }

    private fun findLoadedSnapshot(query: ScheduleQuery): CachedScheduleReport? {
        loadedSnapshots.lastOrNull {
            it.report.yearTermValue == query.yearValue && it.report.classNo == query.classNo &&
                it.report.scope == query.scope && it.report.isValidOn(query.targetDate)
        }?.let { return it }
        return automaticSnapshot?.takeIf {
            query.automaticWeek &&
                it.report.yearTermValue == query.yearValue &&
                it.report.classNo == query.classNo &&
                it.report.scope == query.scope &&
                it.report.isValidOn(query.targetDate)
        }
    }

    private fun showReport(query: ScheduleQuery, snapshot: CachedScheduleReport) {
        val report = snapshot.report
        if (query.automaticWeek && report.scope == ScheduleScope.CURRENT_WEEK) automaticSnapshot = snapshot
        _uiState.update {
            it.copy(
                isLoading = false,
                isError = false,
                refreshFailed = false,
                selectedScope = report.scope,
                weekTargetDate = if (query.automaticWeek || report.scope != ScheduleScope.CURRENT_WEEK) null
                    else report.weekStartDate?.let { start -> runCatching { LocalDate.parse(start) }.getOrNull() } ?: query.targetDate,
                report = report.copy(subjectOverrides = subjectOverrides ?: report.subjectOverrides),
                availability = if (report.scope == ScheduleScope.CURRENT_WEEK && report.items.isEmpty()) com.clhs.score.data.ScheduleAvailability.EMPTY else com.clhs.score.data.ScheduleAvailability.AVAILABLE,
                noticeMessage = if (query.scope == ScheduleScope.CURRENT_WEEK && report.scope == ScheduleScope.SEMESTER) {
                    CURRENT_WEEK_FALLBACK_NOTICE
                } else if (report.scope == ScheduleScope.CURRENT_WEEK && report.changes == null) {
                    CURRENT_WEEK_COMPARISON_NOTICE
                } else null,
            )
        }
    }

    fun refresh() {
        lastScheduleRequest?.let { query ->
            loadSchedule(query, preserveExistingReport = _uiState.value.report != null, explicitRefresh = true)
            return
        }
        val st = _uiState.value
        val y = st.selectedYearValue
        val c = st.selectedClassValue
        if (st.report != null && y != null && c != null) {
            loadSchedule(
                ScheduleQuery(
                    y,
                    c,
                    st.report.scope,
                    st.weekTargetDate ?: nowProvider().toLocalDate(),
                    automaticWeek = st.weekTargetDate == null,
                ),
                preserveExistingReport = true,
                explicitRefresh = true,
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
        private const val MAX_LOADED_REPORTS = 16
        const val CURRENT_WEEK_FALLBACK_NOTICE = "無法取得週課表，已改顯示學期課表"
        const val CURRENT_WEEK_COMPARISON_NOTICE = "已取得週課表，但無法確認是否有調課"
        const val SUBJECT_OVERRIDE_LOAD_ERROR = "無法載入自訂科目，請稍後再試"
        const val SUBJECT_OVERRIDE_SAVE_ERROR = "無法儲存自訂科目，請稍後再試"
        const val DISPLAY_MODE_LOAD_ERROR = "無法載入課表顯示偏好，請稍後再試"
        const val DISPLAY_MODE_SAVE_ERROR = "無法儲存課表顯示偏好，請稍後再試"

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
                    FakeScheduleRepository(
                        GradeCacheStore(appContext), activeSessionProvider, SessionStore(appContext),
                    )
                } else {
                    NetworkScheduleRepository(
                        SchoolGradeClient(),
                        SessionStore(appContext),
                        GradeCacheStore(appContext),
                        activeSessionProvider = activeSessionProvider,
                    )
                }
                return ScheduleViewModel(
                    repository,
                    FirebaseAnalyticsLogger(appContext),
                    settingsRepository = SettingsRepository(appContext),
                ) as T
            }
        }
    }
}
