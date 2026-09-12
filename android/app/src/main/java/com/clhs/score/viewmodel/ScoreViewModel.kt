package com.clhs.score.viewmodel

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParameterSanitizer
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.FirebaseAnalyticsLogger
import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.ExamSelection
import com.clhs.score.data.FakeData
import com.clhs.score.data.GradeAnalysis
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.GradeExporter
import com.clhs.score.data.GradeReminderRepository
import com.clhs.score.data.GradeReminderIdentity
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeReportDiffer
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.GradeTrend
import com.clhs.score.data.SchoolException
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SchoolGradeRepository
import com.clhs.score.data.SessionCleanupFailure
import com.clhs.score.data.SessionCleanupException
import com.clhs.score.data.DeveloperDiagnostics
import com.clhs.score.data.SessionStore
import com.clhs.score.data.SessionStorageException
import com.clhs.score.data.SimulationHistorySource
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.buildGradeAnalysis
import com.clhs.score.data.buildGradeTrend
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.latestExam
import com.clhs.score.data.latestYearTerm
import com.clhs.score.data.identity
import com.clhs.score.data.sameTermHistorySource
import com.clhs.score.data.sameTermTrendSource
import com.clhs.score.data.simulationHistorySource
import com.clhs.score.reminders.GradeReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

private data class HistoricalExamRequest(
    val yearValue: String,
    val examValue: String,
    val examName: String,
)

data class LoginUiState(
    val isWebViewLoginInProgress: Boolean = false,
    val errorMessage: String? = null,
)

data class GradesUiState(
    val studentNo: String = "",
    val studentInfo: StudentInfo? = null,
    val isLoadingStructure: Boolean = false,
    val isLoadingGrades: Boolean = false,
    val isLoadingComparison: Boolean = false,
    val isLoadingTrend: Boolean = false,
    val structure: List<YearTermOption> = emptyList(),
    val selectedYearValue: String? = null,
    val selectedExamValue: String? = null,
    val report: GradeReport? = null,
    val comparisonReport: GradeReport? = null,
    val comparisonExamName: String? = null,
    val comparisonError: String? = null,
    val trendReports: List<GradeReport> = emptyList(),
    val trendError: String? = null,
    val trendHistoryLabel: String? = null,
    val trend: GradeTrend? = null,
    val isLoadingSimulatorHistory: Boolean = false,
    val simulatorHistoryReports: List<GradeReport> = emptyList(),
    val simulatorHistoryLabel: String? = null,
    val analysis: GradeAnalysis? = null,
    val expandedSubjectKeys: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    val exportResult: String? = null,
    val gradeReminderState: GradeReminderState = GradeReminderState(),
    val isStartingGradeReminder: Boolean = false,
    val gradeReminderError: String? = null,
    val gradeReminderChangeSet: GradeChangeSet? = null,
)

class ScoreViewModel(
    private val repository: GradeRepository,
    private val appContext: Context? = null,
    private val sessionStore: SessionStore? = null,
    private val gradeReminderRepository: GradeReminderRepository? = null,
    private val gradeReminderScheduler: GradeReminderScheduler? = null,
    private val analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    private val onAuthenticationExpired: () -> Unit = {},
    private val demoMode: Boolean = false,
) : ViewModel() {
    private var session: AuthenticatedSession? = null
    private var authGeneration = 0L
    private var structureRequestId = 0
    private var gradeRequestId = 0
    private var pendingReminderTarget: Pair<String, String>? = null
    private var ensuredGradeReminderWorkKey: String? = null
    private var restoreJob: Job? = null
    private var loginJob: Job? = null
    private var structureJob: Job? = null
    private var studentInfoJob: Job? = null
    private var gradeJob: Job? = null
    private var historyJob: Job? = null
    private var exportJob: Job? = null
    private var reminderStartJob: Job? = null
    private var logoutJob: Job? = null

    private val _cleanupFailures = MutableStateFlow<Set<SessionCleanupFailure>>(emptySet())
    internal val cleanupFailures: StateFlow<Set<SessionCleanupFailure>> = _cleanupFailures

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState: StateFlow<LoginUiState> = _loginState

    private val _authState = MutableStateFlow<AuthState>(AuthState.Restoring)
    val authState: StateFlow<AuthState> = _authState

    private val _gradesState = MutableStateFlow(GradesUiState())
    val gradesState: StateFlow<GradesUiState> = _gradesState

    private val subjectTrendOwner = SubjectTrendStateOwner(
        repository = repository,
        analyticsLogger = analyticsLogger,
        scope = viewModelScope,
        onAuthenticationExpired = ::handleSessionExpired,
    )
    val subjectTrendState: StateFlow<SubjectTrendUiState> = subjectTrendOwner.state

    init {
        if (!demoMode) {
            viewModelScope.launch {
                sessionStore?.authorizationRevoked?.collect { revoked ->
                    if (revoked) handleSessionExpired()
                }
            }
        }
        observeGradeReminderState()
        if (demoMode) {
            activateDemoSession()
        } else {
            restoreSession()
        }
    }

    private fun activateDemoSession() {
        repository.activateSession(FakeData.session)
        publishAuthenticated(FakeData.session)
        _gradesState.update { it.copy(studentNo = FakeData.session.studentNo) }
        loadStructure()
    }

    private fun observeGradeReminderState() {
        val reminderRepository = gradeReminderRepository ?: return
        viewModelScope.launch {
            reminderRepository.state.collect { reminderState ->
                val now = System.currentTimeMillis()
                if (reminderState.enabled && !reminderState.isActive(now)) {
                    ensuredGradeReminderWorkKey = null
                    val stopped = stopGradeReminderPersistence(
                        reason = "段考更新提醒已超過 48 小時",
                        expectedIdentity = reminderState.identity(),
                    )
                    if (stopped) {
                        _gradesState.update {
                            it.copy(
                                gradeReminderState = GradeReminderState(stoppedReason = "段考更新提醒已超過 48 小時"),
                                gradeReminderChangeSet = null,
                            )
                        }
                    }
                    return@collect
                }

                val activeWorkKey = reminderState.activeWorkKey(now)
                if (activeWorkKey != null && activeWorkKey != ensuredGradeReminderWorkKey) {
                    gradeReminderScheduler?.schedule()
                    ensuredGradeReminderWorkKey = activeWorkKey
                } else if (activeWorkKey == null) {
                    ensuredGradeReminderWorkKey = null
                }
                _gradesState.update { it.copy(gradeReminderState = reminderState) }
            }
        }
    }



    fun selectYear(value: String) {
        val year = _gradesState.value.structure.firstOrNull { it.value == value } ?: return
        val latestExam = year.latestExam()
        gradeRequestId++
        if (latestExam == null) {
            structureRequestId++
            structureJob?.cancel()
            gradeJob?.cancel()
            historyJob?.cancel()
            _gradesState.update {
                it.copy(
                    selectedYearValue = value,
                    selectedExamValue = null,
                    isLoadingStructure = false,
                    isLoadingGrades = false,
                    isLoadingComparison = false,
                    comparisonReport = null,
                    comparisonExamName = null,
                    comparisonError = null,
                    isLoadingTrend = false,
                    isLoadingSimulatorHistory = false,
                    trendReports = emptyList(),
                    trendError = null,
                    trendHistoryLabel = null,
                    trend = null,
                    simulatorHistoryReports = emptyList(),
                    simulatorHistoryLabel = null,
                    report = null,
                    analysis = null,
                    expandedSubjectKeys = emptySet(),
                    errorMessage = null,
                    gradeReminderChangeSet = null,
                )
            }
            return
        }
        _gradesState.update {
            it.copy(
                isLoadingTrend = false,
                isLoadingSimulatorHistory = false,
                expandedSubjectKeys = emptySet(),
                errorMessage = null,
                gradeReminderChangeSet = null,
            )
        }
        fetchGrades(value, latestExam.value, analyticsTrigger = AnalyticsValues.TRIGGER_YEAR_SELECT)
    }

    fun selectExam(value: String) {
        val yearValue = _gradesState.value.selectedYearValue ?: return
        gradeRequestId++
        _gradesState.update {
            it.copy(
                isLoadingTrend = false,
                isLoadingSimulatorHistory = false,
                expandedSubjectKeys = emptySet(),
                errorMessage = null,
                gradeReminderChangeSet = null,
            )
        }
        fetchGrades(yearValue, value, analyticsTrigger = AnalyticsValues.TRIGGER_EXAM_SELECT)
    }

    fun toggleSubjectExpanded(subjectName: String) {
        val key = cleanSubjectName(subjectName)
        _gradesState.update { state ->
            val next = if (key in state.expandedSubjectKeys) {
                emptySet()
            } else {
                setOf(key)
            }
            state.copy(expandedSubjectKeys = next)
        }
    }

    fun reloadStructure() {
        if (_gradesState.value.let { it.isLoadingStructure || it.isLoadingGrades }) return
        loadStructure(forceRefresh = true)
    }

    fun exportGrades(selections: List<ExamSelection>, context: Context) {
        val currentSession = session ?: return
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _gradesState.update { it.copy(isExporting = true, exportResult = null) }
            runCatching {
                val reports = coroutineScope {
                    selections.map { sel ->
                        async {
                            sel to repository.fetchGrades(
                                currentSession, sel.yearValue, sel.examValue, false,
                            )
                        }
                    }.awaitAll()
                }
                withContext(Dispatchers.IO) {
                    val csvPairs = reports.map { (sel, report) -> sel.displayName to report }
                    val csv = GradeExporter.buildCsvContent(csvPairs)
                    GradeExporter.saveCsvToDownloads(
                        context = context,
                        csv = csv,
                        studentNo = currentSession.studentNo,
                        shouldPublish = { session === currentSession },
                    ).getOrThrow()
                }
            }.onSuccess { fileName ->
                analyticsLogger.logEvent(
                    AnalyticsEvents.EXPORT_GRADES,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                        AnalyticsParams.SELECTION_COUNT_BUCKET to AnalyticsParameterSanitizer.countBucket(selections.size),
                    ),
                )
                _gradesState.update {
                    it.copy(isExporting = false, exportResult = "已儲存至 Downloads/$fileName")
                }
            }.onFailure { error ->
                error.throwIfCancellation()
                if (error is SchoolAuthenticationException) {
                    handleSessionExpired()
                    return@onFailure
                }
                analyticsLogger.logEvent(
                    AnalyticsEvents.EXPORT_GRADES,
                    mapOf(
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                        AnalyticsParams.SELECTION_COUNT_BUCKET to AnalyticsParameterSanitizer.countBucket(selections.size),
                    ),
                )
                _gradesState.update {
                    it.copy(
                        isExporting = false,
                        exportResult = "匯出失敗：${error.message ?: "未知錯誤"}",
                    )
                }
            }
        }
    }

    fun dismissExportResult() {
        _gradesState.update { it.copy(exportResult = null) }
    }

    fun logout(source: String = AnalyticsValues.SOURCE_SETTINGS) {
        analyticsLogger.logEvent(
            AnalyticsEvents.LOGOUT,
            mapOf(AnalyticsParams.SOURCE to source),
        )
        clearSession("使用者登出")
    }

    fun getCurrentSession(): AuthenticatedSession? = session?.takeIf {
        demoMode || sessionStore?.isAuthorized(it) != false
    }

    fun handleSessionExpired() {
        if (_authState.value !is AuthState.Authenticated) return
        clearSession("登入狀態已失效")
        onAuthenticationExpired()
    }

    fun loginWithBiometricSession(restored: AuthenticatedSession) {
        restoreJob?.cancel()
        _authState.value = AuthState.Authenticating
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            logoutJob?.join()
            try {
                sessionStore?.validateSession(restored)
            } catch (_: SessionStorageException) {
                clearSession("登入狀態已失效")
                return@launch
            }
            repository.activateSession(restored)
            publishAuthenticated(restored)
            analyticsLogger.logEvent(
                AnalyticsEvents.LOGIN_RESULT,
                mapOf(
                    AnalyticsParams.METHOD to AnalyticsValues.METHOD_BIOMETRIC,
                    AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                ),
            )
            _gradesState.update {
                it.copy(
                    studentNo = restored.studentNo,
                    studentInfo = null,
                    errorMessage = null,
                )
            }
            studentInfoJob?.cancel()
            studentInfoJob = viewModelScope.launch {
                runCatching { repository.loadCachedStudentInfo(restored) }
                    .onSuccess { studentInfo ->
                        if (session == restored) {
                            _gradesState.update { it.copy(studentInfo = studentInfo) }
                        }
                    }
                    .onFailure { error -> error.throwIfCancellation() }
            }
            loadStructure()
        }
    }

    fun loginWithWebViewCookies(studentNo: String, cookieString: String) {
        val method = if (cookieString == "fake=cookie") {
            AnalyticsValues.METHOD_DEMO
        } else {
            AnalyticsValues.METHOD_WEBVIEW
        }
        analyticsLogger.logEvent(
            AnalyticsEvents.LOGIN_START,
            mapOf(AnalyticsParams.METHOD to method),
        )
        restoreJob?.cancel()
        _authState.value = AuthState.Authenticating
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            logoutJob?.join()
            _loginState.update { it.copy(isWebViewLoginInProgress = true, errorMessage = null) }
            runCatching {
                val cookies = parseCookieString(cookieString)
                if (cookies.isEmpty()) throw SchoolException("未取得有效的登入 cookies")
                if (sessionStore?.hasPendingCleanup() == true) {
                    finishLogout(null, "登入狀態已失效")
                }
                repository.loginWithCookies(studentNo, cookies)
            }.onSuccess { authenticatedSession ->
                resetSubjectTrendState()
                publishAuthenticated(authenticatedSession)
                _loginState.update {
                    it.copy(isWebViewLoginInProgress = false, errorMessage = null)
                }
                _gradesState.update {
                    it.copy(
                        studentNo = authenticatedSession.studentNo,
                        errorMessage = null,
                    )
                }
                loadStructure()
                analyticsLogger.logEvent(
                    AnalyticsEvents.LOGIN_RESULT,
                    mapOf(
                        AnalyticsParams.METHOD to method,
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                    ),
                )
            }.onFailure { error ->
                error.throwIfCancellation()
                if (session == null) _authState.value = AuthState.Guest
                analyticsLogger.logEvent(
                    AnalyticsEvents.LOGIN_RESULT,
                    mapOf(
                        AnalyticsParams.METHOD to method,
                        AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                        AnalyticsParams.ERROR_TYPE to error.toLoginErrorType(),
                    ),
                )
                _loginState.update {
                    it.copy(
                        isWebViewLoginInProgress = false,
                        errorMessage = error.message ?: "WebView 登入後處理失敗",
                    )
                }
            }
        }
    }

    private fun parseCookieString(cookieString: String): Map<String, String> {
        if (cookieString.isBlank()) return emptyMap()
        return cookieString.split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { part ->
                val idx = part.indexOf('=')
                part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }
            .filterKeys { it.isNotBlank() }
    }

    fun clearLoginError() {
        _loginState.update { it.copy(errorMessage = null) }
    }

    fun clearGradesError() {
        _gradesState.update { it.copy(errorMessage = null) }
    }

    fun clearGradeReminderError() {
        _gradesState.update { it.copy(gradeReminderError = null) }
    }

    fun reportGradeReminderPrerequisiteError(message: String) {
        analyticsLogger.logEvent(
            AnalyticsEvents.GRADE_REMINDER_START,
            mapOf(
                AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                AnalyticsParams.FAILURE_REASON to message.toGradeReminderFailureReason(),
            ),
        )
        setGradeReminderError(message)
    }

    fun dismissGradeReminderChanges() {
        viewModelScope.launch {
            gradeReminderRepository?.clearLatestChangeSet()
        }
        _gradesState.update { it.copy(gradeReminderChangeSet = null) }
    }

    fun startGradeReminder() {
        val currentSession = session ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_UNKNOWN)
            setGradeReminderError("請先登入後再啟用段考更新提醒")
            return
        }
        val context = appContext ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_UNKNOWN)
            setGradeReminderError("目前環境不支援背景段考更新提醒")
            return
        }
        val reminderRepository = gradeReminderRepository ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_UNKNOWN)
            setGradeReminderError("目前環境不支援背景段考更新提醒")
            return
        }
        val scheduler = gradeReminderScheduler ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_UNKNOWN)
            setGradeReminderError("目前環境不支援背景段考更新提醒")
            return
        }
        val state = _gradesState.value
        val yearValue = state.selectedYearValue ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_NO_EXAM)
            setGradeReminderError("請先選擇學期")
            return
        }
        val examValue = state.selectedExamValue ?: run {
            logGradeReminderStartFailure(AnalyticsValues.REASON_NO_EXAM)
            setGradeReminderError("請先選擇考試")
            return
        }
        val selectedYear = state.structure.firstOrNull { it.value == yearValue }
        val selectedExam = selectedYear?.exams?.firstOrNull { it.value == examValue }
        val requestId = ++gradeRequestId
        reminderStartJob?.cancel()
        reminderStartJob = viewModelScope.launch {
            _gradesState.update {
                it.copy(
                    isStartingGradeReminder = true,
                    isLoadingGrades = true,
                    gradeReminderError = null,
                    gradeReminderChangeSet = null,
                    errorMessage = null,
                )
            }
            runCatching {
                val cacheStore = GradeCacheStore(context)
                val oldReport = cacheStore.loadGradeReport(currentSession.studentNo, yearValue, examValue)
                val report = repository.fetchGrades(currentSession, yearValue, examValue, forceRefresh = true)
                check(requestId == gradeRequestId) { "提醒啟用請求已過期" }
                val now = System.currentTimeMillis()
                val oldSnapshot = oldReport?.let(GradeReportDiffer::snapshot)
                val newSnapshot = GradeReportDiffer.snapshot(report)
                val changeSet = oldSnapshot?.let { before ->
                    GradeReportDiffer.diff(
                        before = before,
                        after = newSnapshot,
                        studentNo = currentSession.studentNo,
                        yearValue = yearValue,
                        examValue = examValue,
                        examName = selectedExam?.text ?: report.examSummary?.examName.orEmpty().ifBlank { "本次考試" },
                        checkedAtMillis = now,
                    ).takeIf { it.hasChanges }
                }
                val expiresAtMillis = now + GRADE_REMINDER_DURATION_MILLIS
                val reminderState = GradeReminderState(
                    enabled = true,
                    studentNo = currentSession.studentNo,
                    yearValue = yearValue,
                    yearLabel = selectedYear?.text.orEmpty(),
                    examValue = examValue,
                    examName = selectedExam?.text ?: report.examSummary?.examName.orEmpty().ifBlank { "本次考試" },
                    activatedAtMillis = now,
                    expiresAtMillis = expiresAtMillis,
                    lastCheckedAtMillis = now,
                    snapshot = newSnapshot,
                    latestChangeSet = changeSet,
                )
                reminderRepository.mutate {
                    check(requestId == gradeRequestId) { "提醒啟用請求已過期" }
                    sessionStore?.saveReminderSession(currentSession, expiresAtMillis)
                    saveState(reminderState)
                    scheduler.schedule()
                }
                ensuredGradeReminderWorkKey = gradeReminderWorkKey(
                    studentNo = currentSession.studentNo,
                    yearValue = yearValue,
                    examValue = examValue,
                    expiresAtMillis = expiresAtMillis,
                )
                report to changeSet
            }.onSuccess { (report, changeSet) ->
                if (requestId != gradeRequestId) return@onSuccess
                analyticsLogger.logEvent(
                    AnalyticsEvents.GRADE_REMINDER_START,
                    mapOf(AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS),
                )
                applyFetchedReportAndLoadHistory(
                    requestId = requestId,
                    currentSession = currentSession,
                    yearValue = yearValue,
                    examValue = examValue,
                    report = report,
                    forceRefresh = true,
                    gradeReminderChangeSet = changeSet,
                )
                _gradesState.update {
                    it.copy(
                        isStartingGradeReminder = false,
                        gradeReminderError = null,
                    )
                }
            }.onFailure { error ->
                error.throwIfCancellation()
                if (requestId != gradeRequestId) return@onFailure
                if (error is SchoolAuthenticationException) {
                    handleSessionExpired()
                    return@onFailure
                }
                logGradeReminderStartFailure(AnalyticsValues.REASON_UNKNOWN)
                _gradesState.update {
                    it.copy(
                        isStartingGradeReminder = false,
                        isLoadingGrades = false,
                        gradeReminderError = if (error is SessionStorageException) {
                            "無法安全保存段考更新提醒登入資訊"
                        } else {
                            error.message ?: "啟用段考更新提醒失敗"
                        },
                    )
                }
            }
        }
    }

    fun stopGradeReminder() {
        analyticsLogger.logEvent(
            AnalyticsEvents.GRADE_REMINDER_STOP,
            mapOf(AnalyticsParams.REASON to AnalyticsValues.REASON_USER),
        )
        ensuredGradeReminderWorkKey = null
        gradeRequestId++
        val startJob = reminderStartJob
        startJob?.cancel()
        viewModelScope.launch {
            startJob?.cancelAndJoin()
            stopGradeReminderPersistence("使用者關閉")
        }
        _gradesState.update {
            it.copy(
                gradeReminderState = GradeReminderState(stoppedReason = "使用者關閉"),
                gradeReminderChangeSet = null,
            )
        }
    }

    fun openGradeReminderTarget(yearValue: String, examValue: String) {
        analyticsLogger.logEvent(AnalyticsEvents.GRADE_REMINDER_NOTIFICATION_OPEN)
        pendingReminderTarget = yearValue to examValue
        _gradesState.update {
            it.copy(gradeReminderChangeSet = it.gradeReminderState.latestChangeSet)
        }
        openPendingReminderTargetOrLoadStructure()
    }

    private fun openPendingReminderTargetOrLoadStructure() {
        val target = pendingReminderTarget ?: return
        val structure = _gradesState.value.structure
        val year = structure.firstOrNull { it.value == target.first }
        val exam = year?.exams?.firstOrNull { it.value == target.second }
        if (year != null && exam != null) {
            pendingReminderTarget = null
            gradeRequestId++
            _gradesState.update {
                it.copy(
                    selectedYearValue = year.value,
                    selectedExamValue = exam.value,
                    comparisonReport = null,
                    comparisonExamName = null,
                    comparisonError = null,
                    isLoadingTrend = false,
                    isLoadingSimulatorHistory = false,
                    trendReports = emptyList(),
                    trendError = null,
                    trendHistoryLabel = null,
                    trend = null,
                    simulatorHistoryReports = emptyList(),
                    simulatorHistoryLabel = null,
                    expandedSubjectKeys = emptySet(),
                    errorMessage = null,
                )
            }
            fetchGrades(
                year.value,
                exam.value,
                forceRefresh = true,
                analyticsTrigger = AnalyticsValues.TRIGGER_REMINDER_TARGET,
            )
        } else {
            loadStructure(forceRefresh = true)
        }
    }

    private fun setGradeReminderError(message: String) {
        _gradesState.update { it.copy(gradeReminderError = message) }
    }

    private fun restoreSession() {
        restoreJob = viewModelScope.launch {
            runCatching {
                if (sessionStore?.hasPendingCleanup() == true) {
                    clearSession("登入狀態已失效")
                    return@launch
                }
                repository.restoreSession()
            }
                .onSuccess { restored ->
                    if (restored == null) {
                        _authState.value = AuthState.Guest
                        return@onSuccess
                    }
                    publishAuthenticated(restored)
                    _gradesState.update {
                        it.copy(
                            studentNo = restored.studentNo,
                        )
                    }
                    viewModelScope.launch {
                        runCatching { repository.loadCachedStudentInfo(restored) }
                            .onSuccess { studentInfo ->
                                if (session == restored) {
                                    _gradesState.update { it.copy(studentInfo = studentInfo) }
                                }
                            }
                            .onFailure { error -> error.throwIfCancellation() }
                    }
                    loadStructure()
                }
                .onFailure { error ->
                    error.throwIfCancellation()
                    clearSession("登入狀態已失效")
                    _loginState.update {
                        it.copy(errorMessage = "無法讀取登入資訊，請重新登入")
                    }
                    return@launch
                }
            try {
                sessionStore?.retryReminderCleanup()
            } catch (error: SessionCleanupException) {
                _cleanupFailures.value = _cleanupFailures.value + error.failures
            } catch (_: SessionStorageException) {
                _cleanupFailures.value = _cleanupFailures.value + SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED
            }
        }
    }

    private fun loadStructure(forceRefresh: Boolean = false) {
        val currentSession = session ?: return
        val requestId = ++structureRequestId
        structureJob?.cancel()
        structureJob = viewModelScope.launch {
            _gradesState.update { it.copy(isLoadingStructure = true, errorMessage = null) }
            runCatching { repository.loadStructure(currentSession, forceRefresh) }
                .onSuccess { structure ->
                    if (requestId != structureRequestId) return@onSuccess
                    analyticsLogger.logEvent(
                        AnalyticsEvents.GRADE_STRUCTURE_LOAD,
                        mapOf(
                            AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                            AnalyticsParams.YEAR_COUNT to structure.size,
                            AnalyticsParams.EXAM_COUNT_BUCKET to AnalyticsParameterSanitizer.countBucket(
                                structure.sumOf { it.exams.size },
                            ),
                        ),
                    )
                    val pendingTarget = pendingReminderTarget
                    val pendingYear = pendingTarget?.let { target ->
                        structure.firstOrNull { it.value == target.first }
                    }
                    val pendingExam = if (pendingTarget != null) {
                        pendingYear?.exams?.firstOrNull { it.value == pendingTarget.second }
                    } else {
                        null
                    }
                    val currentState = _gradesState.value
                    val currentYear = structure.firstOrNull { it.value == currentState.selectedYearValue }
                    val currentExam = currentYear?.exams?.firstOrNull { it.value == currentState.selectedExamValue }
                    val selectedYear = pendingYear
                        ?: currentYear
                        ?: structure.filter { it.exams.isNotEmpty() }.latestYearTerm()
                        ?: structure.latestYearTerm()
                    val selectedExam = when {
                        pendingYear != null -> pendingExam ?: pendingYear.latestExam()
                        currentYear != null -> currentExam ?: currentYear.latestExam()
                        else -> selectedYear?.latestExam()
                    }
                    if (pendingYear != null && pendingExam != null) {
                        pendingReminderTarget = null
                    }
                    _gradesState.update {
                        it.copy(
                            isLoadingStructure = false,
                            structure = structure,
                            selectedYearValue = selectedYear?.value,
                            selectedExamValue = selectedExam?.value,
                            errorMessage = null,
                        )
                    }
                    if (selectedYear != null && selectedExam != null) {
                        val trigger = when {
                            pendingYear != null && pendingExam != null -> AnalyticsValues.TRIGGER_REMINDER_TARGET
                            forceRefresh -> AnalyticsValues.TRIGGER_REFRESH
                            else -> AnalyticsValues.TRIGGER_INITIAL
                        }
                        fetchGrades(
                            selectedYear.value,
                            selectedExam.value,
                            forceRefresh || (pendingYear != null && pendingExam != null),
                            analyticsTrigger = trigger,
                        )
                    }
                }
                .onFailure { error ->
                    error.throwIfCancellation()
                    if (requestId != structureRequestId) return@onFailure
                    if (error is SchoolAuthenticationException) {
                        handleSessionExpired()
                        return@onFailure
                    }
                    analyticsLogger.logEvent(
                        AnalyticsEvents.GRADE_STRUCTURE_LOAD,
                        mapOf(AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE),
                    )
                    _gradesState.update {
                        it.copy(
                            isLoadingStructure = false,
                            errorMessage = error.message ?: "載入可查詢考試失敗",
                        )
                    }
                }
        }
    }

    fun refreshStudentInfo() {
        val currentSession = session ?: return
        studentInfoJob?.cancel()
        studentInfoJob = viewModelScope.launch {
            runCatching { repository.fetchStudentInfo(currentSession) }
                .onSuccess { studentInfo ->
                    if (session == currentSession) {
                        _gradesState.update { it.copy(studentInfo = studentInfo) }
                    }
                }
                .onFailure { error ->
                    error.throwIfCancellation()
                    if (error is SchoolAuthenticationException) handleSessionExpired()
                }
        }
    }

    private fun fetchGrades(
        yearValue: String,
        examValue: String,
        forceRefresh: Boolean = false,
        analyticsTrigger: String = if (forceRefresh) {
            AnalyticsValues.TRIGGER_REFRESH
        } else {
            AnalyticsValues.TRIGGER_INITIAL
        },
    ) {
        val currentSession = session ?: return
        structureRequestId++
        val requestId = ++gradeRequestId
        structureJob?.cancel()
        gradeJob?.cancel()
        historyJob?.cancel()
        gradeJob = viewModelScope.launch {
            _gradesState.update {
                it.copy(
                    isLoadingStructure = false,
                    isLoadingGrades = true,
                    isLoadingComparison = false,
                    isLoadingTrend = false,
                    isLoadingSimulatorHistory = false,
                    errorMessage = null,
                )
            }
            runCatching { repository.fetchGrades(currentSession, yearValue, examValue, forceRefresh) }
                .onSuccess { report ->
                    applyFetchedReportAndLoadHistory(
                        requestId = requestId,
                        currentSession = currentSession,
                        yearValue = yearValue,
                        examValue = examValue,
                        report = report,
                        forceRefresh = forceRefresh,
                        analyticsTrigger = analyticsTrigger,
                    )
                }
                .onFailure { error ->
                    error.throwIfCancellation()
                    if (requestId != gradeRequestId) return@onFailure
                    if (error is SchoolAuthenticationException) {
                        handleSessionExpired()
                        return@onFailure
                    }
                    analyticsLogger.logEvent(
                        AnalyticsEvents.GRADE_QUERY,
                        mapOf(
                            AnalyticsParams.TRIGGER to analyticsTrigger,
                            AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                            AnalyticsParams.CACHED to !forceRefresh,
                        ),
                    )
                    _gradesState.update {
                        it.copy(
                            isLoadingGrades = false,
                            isLoadingComparison = false,
                            isLoadingTrend = false,
                            isLoadingSimulatorHistory = false,
                            errorMessage = error.message ?: "查詢成績失敗",
                        )
                    }
                }
        }
    }

    private fun applyFetchedReportAndLoadHistory(
        requestId: Int,
        currentSession: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        report: GradeReport,
        forceRefresh: Boolean = false,
        gradeReminderChangeSet: GradeChangeSet? = _gradesState.value.gradeReminderChangeSet,
        analyticsTrigger: String = if (forceRefresh) {
            AnalyticsValues.TRIGGER_REFRESH
        } else {
            AnalyticsValues.TRIGGER_INITIAL
        },
    ) {
        if (requestId != gradeRequestId) return
        analyticsLogger.logEvent(
            AnalyticsEvents.GRADE_QUERY,
            mapOf(
                AnalyticsParams.TRIGGER to analyticsTrigger,
                AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS,
                AnalyticsParams.CACHED to !forceRefresh,
                AnalyticsParams.SUBJECT_COUNT_BUCKET to AnalyticsParameterSanitizer.countBucket(report.subjects.size),
            ),
        )
        val analysis = buildGradeAnalysis(report)
        _gradesState.update {
            it.copy(
                isLoadingGrades = false,
                selectedYearValue = yearValue,
                selectedExamValue = examValue,
                report = report,
                comparisonReport = null,
                comparisonExamName = null,
                comparisonError = null,
                trendReports = emptyList(),
                trendError = null,
                trendHistoryLabel = null,
                trend = null,
                simulatorHistoryReports = emptyList(),
                simulatorHistoryLabel = null,
                analysis = analysis,
                errorMessage = null,
                gradeReminderChangeSet = gradeReminderChangeSet,
            )
        }
        loadHistoricalGrades(
            requestId = requestId,
            session = currentSession,
            yearValue = yearValue,
            examValue = examValue,
            report = report,
        )
    }

    private fun loadHistoricalGrades(
        requestId: Int,
        session: AuthenticatedSession,
        yearValue: String,
        examValue: String,
        report: GradeReport,
    ) {
        val structure = _gradesState.value.structure
        val comparisonSource = structure.sameTermHistorySource(yearValue, examValue)
        val trendSource = structure.sameTermTrendSource(yearValue)
        val simulatorSource = structure.simulationHistorySource(yearValue, examValue)
        if (comparisonSource == null && trendSource == null && simulatorSource == null) {
            _gradesState.update {
                if (requestId != gradeRequestId) it else it.copy(
                    isLoadingComparison = false,
                    isLoadingTrend = false,
                    isLoadingSimulatorHistory = false,
                    comparisonError = "尚無上一考可比較",
                    trendReports = emptyList(),
                    trend = null,
                    trendError = "尚無當學期歷次趨勢可比較",
                    trendHistoryLabel = null,
                    simulatorHistoryReports = emptyList(),
                    simulatorHistoryLabel = null,
                )
            }
            return
        }

        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            _gradesState.update {
                if (requestId != gradeRequestId) it else it.copy(
                    isLoadingComparison = comparisonSource != null,
                    isLoadingTrend = trendSource != null,
                    isLoadingSimulatorHistory = simulatorSource != null,
                    comparisonError = null,
                    trendError = if (trendSource == null) "尚無當學期歷次趨勢可比較" else null,
                )
            }
            runCatching {
                val requests = historicalRequests(comparisonSource, trendSource, simulatorSource)
                    .filterNot { it.yearValue == yearValue && it.examValue == examValue }
                val results = supervisorScope {
                    requests.map { request ->
                        async {
                            request to runCatching {
                                repository.fetchGrades(session, request.yearValue, request.examValue, false)
                            }.onFailure { error ->
                                error.throwIfCancellation()
                                if (error is SchoolAuthenticationException) handleSessionExpired()
                            }
                        }
                    }.awaitAll()
                }
                if (results.isNotEmpty() && results.all { (_, result) -> result.isFailure }) {
                    error("historical batch failed")
                }
                results
            }.onSuccess { results ->
                if (requestId != gradeRequestId) return@onSuccess
                val failedCount = results.count { (_, result) -> result.isFailure }
                val reportsByRequest = results.mapNotNull { (request, result) ->
                    result.getOrNull()?.let { request to it }
                }.toMap()
                val comparisonPairs = comparisonSource?.historyExams.orEmpty().mapNotNull { historyExam ->
                    val request = HistoricalExamRequest(historyExam.yearValue, historyExam.examValue, historyExam.examName)
                    reportsByRequest[request]?.let { historyExam.examName to it }
                }
                val trendPairs = trendSource?.historyExams.orEmpty().mapNotNull { historyExam ->
                    val request = HistoricalExamRequest(historyExam.yearValue, historyExam.examValue, historyExam.examName)
                    val trendReport = if (historyExam.yearValue == yearValue && historyExam.examValue == examValue) {
                        report
                    } else {
                        reportsByRequest[request]
                    }
                    trendReport?.let { historyExam.examName to it }
                }
                val simulatorReports = simulatorSource?.historyExams.orEmpty().mapNotNull { historyExam ->
                    val request = HistoricalExamRequest(historyExam.yearValue, historyExam.examValue, historyExam.examName)
                    reportsByRequest[request]
                }
                val comparison = comparisonPairs.lastOrNull()
                val trend = trendPairs.takeIf { it.size >= 2 }?.let(::buildGradeTrend)
                _gradesState.update {
                    val analysis = if (comparison != null) {
                        buildGradeAnalysis(
                            report = report,
                            comparisonReport = comparison.second,
                            previousExamName = comparison.first,
                        )
                    } else {
                        buildGradeAnalysis(report)
                    }
                    it.copy(
                        isLoadingComparison = false,
                        isLoadingTrend = false,
                        isLoadingSimulatorHistory = false,
                        comparisonReport = comparison?.second,
                        comparisonExamName = comparison?.first,
                        comparisonError = when {
                            failedCount > 0 -> "部分資料無法載入"
                            comparison == null -> "尚無上一考可比較"
                            else -> null
                        },
                        trendReports = trendPairs.map { pair -> pair.second },
                        trendError = when {
                            failedCount > 0 -> "部分資料無法載入"
                            trend == null -> "尚無當學期歷次趨勢可比較"
                            else -> null
                        },
                        trendHistoryLabel = trendSource?.label,
                        trend = trend,
                        simulatorHistoryReports = simulatorReports,
                        simulatorHistoryLabel = simulatorSource?.label,
                        analysis = analysis,
                    )
                }
            }.onFailure { error ->
                error.throwIfCancellation()
                if (requestId != gradeRequestId) return@onFailure
                _gradesState.update {
                    it.copy(
                        isLoadingComparison = false,
                        isLoadingTrend = false,
                        isLoadingSimulatorHistory = false,
                        comparisonError = "歷次資料載入失敗",
                        trendReports = emptyList(),
                        trend = null,
                        trendError = "歷次趨勢載入失敗",
                        trendHistoryLabel = null,
                        simulatorHistoryReports = emptyList(),
                        simulatorHistoryLabel = null,
                    )
                }
            }
        }
    }

    private fun historicalRequests(
        vararg sources: SimulationHistorySource?,
    ): List<HistoricalExamRequest> {
        val requests = sources.filterNotNull().flatMap { source ->
            source.historyExams.map { historyExam ->
                HistoricalExamRequest(historyExam.yearValue, historyExam.examValue, historyExam.examName)
            }
        }
        return requests.distinctBy { it.yearValue to it.examValue }
    }

    fun initSubjectTrend() {
        subjectTrendOwner.initialize(_gradesState.value.structure, session)
    }

    fun setSubjectTrendYears(yearValues: Set<String>) {
        subjectTrendOwner.setYears(yearValues, _gradesState.value.structure, session)
    }

    fun toggleSubjectTrendSubject(subjectKey: String) {
        subjectTrendOwner.toggleSubject(subjectKey)
    }

    companion object {
        private const val GRADE_REMINDER_DURATION_MILLIS = 48L * 60L * 60L * 1000L

        private fun GradeReminderState.activeWorkKey(nowMillis: Long): String? =
            if (isActive(nowMillis)) {
                gradeReminderWorkKey(studentNo, yearValue, examValue, expiresAtMillis)
            } else {
                null
            }

        private fun gradeReminderWorkKey(
            studentNo: String,
            yearValue: String,
            examValue: String,
            expiresAtMillis: Long,
        ): String = listOf(studentNo, yearValue, examValue, expiresAtMillis).joinToString("|")

        fun factory(
            context: Context,
            useFakeData: Boolean = false,
            onAuthenticationExpired: () -> Unit = {},
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                if (useFakeData) {
                    return ScoreViewModel(
                        repository = com.clhs.score.data.FakeGradeRepository(),
                        appContext = appContext,
                        analyticsLogger = FirebaseAnalyticsLogger(appContext),
                        onAuthenticationExpired = onAuthenticationExpired,
                        demoMode = true,
                    ) as T
                }
                val sessionStore = SessionStore(appContext)
                val cookieJar = com.clhs.score.data.SchoolCookieJar()
                val client = SchoolGradeClient(cookieJar = cookieJar)
                val repository = SchoolGradeRepository(client, sessionStore, GradeCacheStore(appContext))
                return ScoreViewModel(
                    repository = repository,
                    appContext = appContext,
                    sessionStore = sessionStore,
                    gradeReminderRepository = GradeReminderRepository(appContext),
                    gradeReminderScheduler = GradeReminderScheduler(appContext),
                    analyticsLogger = FirebaseAnalyticsLogger(appContext),
                    onAuthenticationExpired = onAuthenticationExpired,
                ) as T
            }
        }
    }

    private fun resetSubjectTrendState(): Job? = subjectTrendOwner.reset()

    private fun publishAuthenticated(authenticatedSession: AuthenticatedSession) {
        if (!demoMode && sessionStore?.isAuthorized(authenticatedSession) == false) {
            session = null
            _authState.value = AuthState.Guest
            _loginState.value = LoginUiState()
            throw CancellationException("Session revoked")
        }
        session = authenticatedSession
        _authState.value = AuthState.Authenticated(++authGeneration)
    }

    private fun clearSession(reason: String) {
        val sessionToClear = session
        session = null
        _authState.value = AuthState.Guest
        sessionStore?.revoke()
        structureRequestId++
        gradeRequestId++
        pendingReminderTarget = null
        ensuredGradeReminderWorkKey = null
        val subjectTrendJob = resetSubjectTrendState()
        val jobs = sessionJobs() + listOfNotNull(subjectTrendJob)
        jobs.forEach(Job::cancel)
        val previousLogout = logoutJob
        logoutJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                previousLogout?.join()
                jobs.forEach { it.cancelAndJoin() }
                finishLogout(sessionToClear, reason)
            }
        }
        _gradesState.value = GradesUiState()
        _loginState.value = LoginUiState()
    }

    private suspend fun finishLogout(sessionToClear: AuthenticatedSession?, reason: String) {
        val failures = linkedSetOf<SessionCleanupFailure>()
        suspend fun attempt(category: SessionCleanupFailure, block: suspend () -> Unit) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionCleanupException) {
                failures += error.failures
            } catch (_: Exception) {
                failures += category
            }
        }
        attempt(SessionCleanupFailure.SESSION_DELETE_FAILED) { repository.logout(sessionToClear) }
        attempt(SessionCleanupFailure.REMINDER_STATE_DELETE_FAILED) {
            gradeReminderRepository?.mutate { stop(reason) }
        }
        attempt(SessionCleanupFailure.BACKGROUND_WORK_CANCEL_FAILED) { gradeReminderScheduler?.cancel() }
        attempt(SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED) { sessionStore?.clearReminderSession() }
        attempt(SessionCleanupFailure.WIDGET_REFRESH_FAILED) {
            appContext?.let { com.clhs.score.widget.syncAllScheduleWidgets(it) }
        }
        if (failures.isEmpty()) {
            attempt(SessionCleanupFailure.CLEANUP_STATUS_WRITE_FAILED) { sessionStore?.completeCleanup() }
        }
        _cleanupFailures.value = failures.toSet()
        appContext?.let { context ->
            failures.forEach { failure ->
                DeveloperDiagnostics.recordEvent(context, "PrivacyCleanup", failure.name)
            }
        }
    }

    private fun sessionJobs(): List<Job> = listOfNotNull(
        restoreJob,
        loginJob,
        structureJob,
        studentInfoJob,
        gradeJob,
        historyJob,
        exportJob,
        reminderStartJob,
    ).distinct()

    private suspend fun stopGradeReminderPersistence(
        reason: String,
        expectedIdentity: GradeReminderIdentity? = null,
    ): Boolean {
        val reminderRepository = gradeReminderRepository
        if (reminderRepository == null) {
            gradeReminderScheduler?.cancel()
            return false
        }
        return reminderRepository.mutate {
            if (expectedIdentity != null && loadState().identity() != expectedIdentity) {
                return@mutate false
            }
            val failures = linkedSetOf<SessionCleanupFailure>()
            try {
                stop(reason)
            } catch (_: IOException) {
                failures += SessionCleanupFailure.REMINDER_STATE_DELETE_FAILED
            }
            try {
                sessionStore?.clearReminderSession()
            } catch (error: SessionCleanupException) {
                failures += error.failures
            } catch (_: SessionStorageException) {
                failures += SessionCleanupFailure.REMINDER_SESSION_DELETE_FAILED
            }
            try {
                gradeReminderScheduler?.cancel()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failures += SessionCleanupFailure.BACKGROUND_WORK_CANCEL_FAILED
            }
            if (failures.isNotEmpty()) {
                _cleanupFailures.value = _cleanupFailures.value + failures
                appContext?.let { context ->
                    failures.forEach { DeveloperDiagnostics.recordEvent(context, "PrivacyCleanup", it.name) }
                }
            }
            true
        }
    }

    private fun logGradeReminderStartFailure(reason: String) {
        analyticsLogger.logEvent(
            AnalyticsEvents.GRADE_REMINDER_START,
            mapOf(
                AnalyticsParams.RESULT to AnalyticsValues.RESULT_FAILURE,
                AnalyticsParams.FAILURE_REASON to reason,
            ),
        )
    }

    private fun Throwable.toLoginErrorType(): String = when (this) {
        is SchoolException -> AnalyticsValues.RESULT_ERROR
        else -> AnalyticsValues.REASON_UNKNOWN
    }

    private fun String.toGradeReminderFailureReason(): String = when {
        contains("通知") -> AnalyticsValues.REASON_PERMISSION
        contains("電池") -> AnalyticsValues.REASON_BATTERY
        contains("考試") || contains("學期") -> AnalyticsValues.REASON_NO_EXAM
        else -> AnalyticsValues.REASON_UNKNOWN
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }
}
