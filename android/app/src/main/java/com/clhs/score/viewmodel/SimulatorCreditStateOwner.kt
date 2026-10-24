package com.clhs.score.viewmodel

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReport
import com.clhs.score.data.GradeSummaryDisplay
import com.clhs.score.data.GradeRepository
import com.clhs.score.data.SavedSimulatorCredits
import com.clhs.score.data.SchoolAuthenticationException
import com.clhs.score.data.SimulatorCredits
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.gradeSummaryDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SimulatorCreditUiState(
    val identity: String = "",
    val inputs: Map<String, String> = emptyMap(),
    val appliedCredits: Map<String, Int> = emptyMap(),
    val isLoaded: Boolean = false,
    val isAppliedStateReliable: Boolean = false,
    val canRestoreDefaults: Boolean = false,
    val isEditing: Boolean = false,
    val fixedSubjects: Set<String> = emptySet(),
    val source: String = "default",
    val confirmed: Boolean = false,
    val isLoading: Boolean = false,
    val isInferring: Boolean = false,
    val isSaving: Boolean = false,
    val candidates: List<Map<String, Int>> = emptyList(),
    val inferenceMessage: String? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val credits: Map<String, Int>?
        get() {
            val values = inputs.mapValues { (_, value) -> value.toIntOrNull() ?: return null }
            return values.takeIf { it.isNotEmpty() && it.values.all { value -> value in 1..SimulatorCredits.MAX_CREDITS } }
        }
}

internal fun simulatorCreditIdentity(studentNo: String, year: String?, exam: String?, report: GradeReport): String =
    listOf(studentNo, year.orEmpty(), exam.orEmpty(), report.subjects.map { cleanSubjectName(it.subjectName) }.sorted().joinToString("\u0000")).joinToString("\u0001")

internal fun SimulatorCreditUiState.summaryDisplay(
    report: GradeReport?, studentNo: String, year: String?, exam: String?,
): GradeSummaryDisplay {
    report ?: return GradeSummaryDisplay()
    val compatible = year != null && exam != null && identity == simulatorCreditIdentity(studentNo, year, exam, report)
    return gradeSummaryDisplay(report, if (compatible) appliedCredits else emptyMap(), compatible && isAppliedStateReliable)
}

internal class SimulatorCreditStateOwner(
    private val repository: GradeRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onAuthenticationExpired: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(SimulatorCreditUiState())
    val state: StateFlow<SimulatorCreditUiState> = mutableState
    private var job: Job? = null
    private var generation = 0
    private var report: GradeReport? = null
    private var session: AuthenticatedSession? = null
    private var year = ""
    private var exam = ""
    private var isCurrent: () -> Boolean = { false }
    private var committedState: SimulatorCreditUiState? = null

    fun open(session: AuthenticatedSession, year: String, exam: String, report: GradeReport, isCurrent: () -> Boolean) {
        val names = report.subjects.map { cleanSubjectName(it.subjectName) }.sorted()
        val identity = simulatorCreditIdentity(session.studentNo, year, exam, report)
        val state = mutableState.value
        val abandonedWork = (state.isLoading || state.isSaving || state.isInferring) && job?.isActive != true
        if (this.report == report && this.session == session && state.identity == identity && !abandonedWork) return
        val previous = committedState?.takeIf { it.identity == identity && this.session == session }
        reset()
        this.report = report
        this.session = session
        this.year = year
        this.exam = exam
        this.isCurrent = isCurrent
        val defaults = SimulatorCredits.defaultCredits(report.subjects) ?: names.associateWith { 2 }
        committedState = previous
        val initial = (previous ?: SimulatorCreditUiState(identity = identity, inputs = defaults.mapValues { it.value.toString() }))
            .copy(isLoaded = false, isAppliedStateReliable = false, canRestoreDefaults = false, isLoading = true, error = null)
        mutableState.value = initial
        val request = generation
        job = scope.launch {
            try {
                val saved = repository.loadSimulatorCredits(session, year, exam)
                if (!current(request)) return@launch
                val compatible = saved?.takeIf { it.subjectNames == names && it.credits.keys == names.toSet() }
                val loaded = initial.copy(
                    inputs = (compatible?.credits ?: defaults).mapValues { it.value.toString() },
                    appliedCredits = compatible?.credits?.toMap() ?: defaults,
                    source = compatible?.source ?: "default",
                    confirmed = compatible != null,
                    isLoaded = true,
                    isAppliedStateReliable = true,
                    isLoading = false,
                    isEditing = mutableState.value.isEditing,
                    notice = if (saved != null && compatible == null) "科目配置已變更，請確認學分" else null,
                )
                committedState = loaded.copy(isEditing = false)
                mutableState.value = loaded
                if (loaded.isEditing) inferDefaultsIfNeeded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val recoverable = error is kotlinx.serialization.SerializationException || error is IllegalArgumentException
                val message = if (recoverable) {
                    "舊設定不適用，請重新確認學分"
                } else "學分載入失敗"
                fail(request, error, message)
                if (current(request)) {
                    val failed = mutableState.value.copy(canRestoreDefaults = recoverable)
                    committedState = failed.copy(isEditing = false)
                    mutableState.value = failed
                }
            }
        }
    }

    fun beginEditing() {
        if (!isCurrent() || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(isEditing = true)
        inferDefaultsIfNeeded()
    }

    fun discardDraft(leavingRoute: Boolean = false) {
        val state = mutableState.value
        if (state.isSaving) {
            if (leavingRoute) reset()
            return
        }
        if (state.isLoading) {
            mutableState.value = state.copy(isEditing = false)
            return
        }
        cancelInference()
        mutableState.value = (committedState ?: state).copy(isEditing = false)
    }

    fun restoreDefaults() {
        val report = report ?: return
        val state = mutableState.value
        if (!isCurrent() || (!state.isLoaded && !state.canRestoreDefaults) || state.isLoading || state.isSaving) return
        cancelInference()
        val defaults = SimulatorCredits.defaultCredits(report.subjects) ?: return
        mutableState.value = mutableState.value.copy(
            inputs = defaults.mapValues { it.value.toString() }, source = "default", confirmed = false,
            isLoaded = true, canRestoreDefaults = false,
            fixedSubjects = emptySet(), candidates = emptyList(), inferenceMessage = null, error = null, notice = null,
        )
    }

    private fun inferDefaultsIfNeeded() {
        val report = report ?: return
        val state = mutableState.value
        if (state.isLoaded && state.source == "default" && !state.confirmed && state.inferenceMessage == null &&
            !state.isInferring && SimulatorCredits.verify(report.subjects, report.examSummary, state.appliedCredits).status !=
            SimulatorCredits.VerificationStatus.MATCHES_OFFICIAL_WEIGHTED_TOTAL
        ) infer()
    }

    fun edit(subject: String, value: String) {
        if (!isCurrent() || subject !in mutableState.value.inputs || !mutableState.value.isLoaded || mutableState.value.isSaving) return
        cancelInference()
        mutableState.value = mutableState.value.copy(
            inputs = mutableState.value.inputs + (subject to value), source = "manual", confirmed = false,
            candidates = emptyList(), inferenceMessage = null, error = null, notice = null,
        )
    }

    fun retryLoad() {
        val report = report ?: return
        val session = session ?: return
        if (!isCurrent() || mutableState.value.isSaving) return
        val guard = isCurrent
        val editing = mutableState.value.isEditing
        this.report = null
        open(session, year, exam, report, guard)
        mutableState.value = mutableState.value.copy(isEditing = editing)
    }

    fun toggleFixed(subject: String) {
        if (!isCurrent() || !mutableState.value.isLoaded || mutableState.value.isSaving || subject !in mutableState.value.inputs) return
        cancelInference()
        val fixed = mutableState.value.fixedSubjects
        mutableState.value = mutableState.value.copy(
            fixedSubjects = if (subject in fixed) fixed - subject else fixed + subject,
            candidates = emptyList(), inferenceMessage = null,
        )
    }

    fun applyCandidate(index: Int) {
        if (!isCurrent() || !mutableState.value.isLoaded || mutableState.value.isSaving) return
        val candidate = mutableState.value.candidates.getOrNull(index) ?: return
        mutableState.value = mutableState.value.copy(
            inputs = candidate.mapValues { it.value.toString() }, source = "inferred", confirmed = false, error = null, notice = null,
        )
    }

    fun infer() {
        val report = report ?: return
        if (!isCurrent() || !mutableState.value.isLoaded || mutableState.value.isSaving) return
        val state = mutableState.value
        val fixed = state.fixedSubjects.associateWith { state.inputs[it]?.toIntOrNull() ?: -1 }
        if (fixed.values.any { it !in 1..SimulatorCredits.MAX_CREDITS }) {
            mutableState.value = state.copy(error = "固定學分需為 1～${SimulatorCredits.MAX_CREDITS}")
            return
        }
        cancelInference()
        val request = generation
        mutableState.value = mutableState.value.copy(isInferring = true, error = null, candidates = emptyList())
        job = scope.launch {
            try {
                val result = withContext(dispatcher) { SimulatorCredits.infer(report.subjects, report.examSummary, fixed) }
                if (!current(request)) return@launch
                val now = mutableState.value
                val single = result.candidates.singleOrNull()?.takeIf { result.status == SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS }
                mutableState.value = now.copy(
                    isInferring = false, candidates = result.candidates, inferenceMessage = result.description(),
                    inputs = if (single != null && now.source == "default" && !now.confirmed) single.mapValues { it.value.toString() } else now.inputs,
                    source = if (single != null && now.source == "default" && !now.confirmed) "inferred" else now.source,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(request, error, "學分推算失敗")
            }
        }
    }

    fun save() {
        val session = session ?: return
        val state = mutableState.value
        val credits = state.credits
        if (credits == null) return
        if (report?.let { SimulatorCredits.defaultCredits(it.subjects) } == null) {
            mutableState.value = state.copy(error = "科目名稱重複或空白，無法儲存")
            return
        }
        if (!isCurrent() || !state.isLoaded || state.isSaving) return
        cancelInference()
        val request = generation
        mutableState.value = state.copy(isSaving = true, isInferring = false, error = null, notice = null)
        job = scope.launch {
            try {
                repository.saveSimulatorCredits(session, year, exam, SavedSimulatorCredits(
                    subjectNames = state.inputs.keys.sorted(), credits = credits, source = state.source,
                ))
                if (!current(request)) return@launch
                val committed = state.copy(
                    appliedCredits = credits.toMap(), isAppliedStateReliable = true, confirmed = true, isEditing = false,
                    fixedSubjects = emptySet(), candidates = emptyList(), inferenceMessage = null,
                    isSaving = false, isInferring = false, error = null, notice = null,
                )
                committedState = committed
                mutableState.value = committed
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(request, error, "學分儲存失敗")
            }
        }
    }

    fun reset(): Job? {
        generation++
        val previous = job
        previous?.cancel()
        job = null
        report = null
        session = null
        committedState = null
        isCurrent = { false }
        mutableState.value = SimulatorCreditUiState()
        return previous
    }

    private fun cancelInference() {
        generation++
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(isInferring = false)
    }

    private fun current(request: Int): Boolean = request == generation && isCurrent()

    private fun fail(request: Int, error: Exception, message: String) {
        if (!current(request)) return
        if (error is SchoolAuthenticationException) onAuthenticationExpired()
        else mutableState.value = mutableState.value.copy(isLoading = false, isSaving = false, isInferring = false, error = message)
    }
}

private fun SimulatorCredits.Inference.description(): String = when (status) {
    SimulatorCredits.InferenceStatus.UNIQUE_UNDER_BOUNDS -> "找到 1 組方案・待確認"
    SimulatorCredits.InferenceStatus.AMBIGUOUS -> "有 ${candidates.size} 組可能方案"
    SimulatorCredits.InferenceStatus.SEARCH_LIMITED -> "找到 ${candidates.size} 組方案・搜尋未完成"
    SimulatorCredits.InferenceStatus.NO_SOLUTIONS -> "沒有符合的方案"
    SimulatorCredits.InferenceStatus.UNAVAILABLE -> when (issue) {
        SimulatorCredits.Issue.INVALID_SCORE -> "缺少有效分數，無法推算"
        SimulatorCredits.Issue.INVALID_SUBJECT_NAMES -> "科目名稱重複或空白"
        else -> "資料不足，無法推算"
    }
}
