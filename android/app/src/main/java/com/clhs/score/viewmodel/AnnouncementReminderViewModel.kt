package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.analytics.AnalyticsEvents
import com.clhs.score.analytics.AnalyticsLogger
import com.clhs.score.analytics.AnalyticsParams
import com.clhs.score.analytics.AnalyticsValues
import com.clhs.score.analytics.NoOpAnalyticsLogger
import com.clhs.score.data.AnnouncementReminderRepository
import com.clhs.score.data.AnnouncementReminderState
import com.clhs.score.data.AnnouncementUnit
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.defaultAnnouncementUnits
import com.clhs.score.data.loadAnnouncementReminderSnapshot
import com.clhs.score.reminders.AnnouncementReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

data class AnnouncementReminderUiState(
    val isStateAvailable: Boolean = false,
    val stateLoadFailed: Boolean = false,
    val reminder: AnnouncementReminderState = AnnouncementReminderState(),
    val units: List<AnnouncementUnit> = defaultAnnouncementUnits(),
    val isLoadingUnits: Boolean = true,
    val unitLoadFailed: Boolean = false,
    val isChecking: Boolean = false,
    val checkResult: AnnouncementReminderCheckResult? = null,
    val isApplyingUnits: Boolean = false,
    val unitApplySucceeded: Boolean? = null,
)

data class AnnouncementReminderCheckResult(
    val newAnnouncementCount: Int?,
    val completedAtMillis: Long,
)

class AnnouncementReminderViewModel internal constructor(
    private val repository: AnnouncementReminderRepository,
    private val announcementsRepository: NetworkSchoolAnnouncementsRepository,
    private val schedule: (Int) -> Unit,
    private val cancel: () -> Unit,
    private val analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
) : ViewModel() {
    constructor(
        repository: AnnouncementReminderRepository,
        announcementsRepository: NetworkSchoolAnnouncementsRepository,
        scheduler: AnnouncementReminderScheduler,
        analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
    ) : this(repository, announcementsRepository, scheduler::schedule, scheduler::cancel, analyticsLogger)

    private val _uiState = MutableStateFlow(AnnouncementReminderUiState())
    val uiState: StateFlow<AnnouncementReminderUiState> = _uiState.asStateFlow()
    private var unitLoadJob: Job? = null
    private var syncJob: Job? = null
    private var unitApplyJob: Job? = null
    private var scheduledState: Pair<Boolean, Int>? = null

    init {
        viewModelScope.launch {
            repository.state.retryWhen { error, _ ->
                if (error !is IOException) return@retryWhen false
                _uiState.update { it.copy(isStateAvailable = false, stateLoadFailed = true) }
                delay(5_000.milliseconds)
                true
            }.collect { reminder ->
                _uiState.update { it.copy(reminder = reminder, isStateAvailable = true, stateLoadFailed = false) }
                val next = reminder.enabled to reminder.intervalMinutes
                if (scheduledState != next) {
                    if (reminder.enabled) schedule(reminder.intervalMinutes) else cancel()
                    scheduledState = next
                }
            }
        }
    }

    fun refreshUnits() {
        unitLoadJob?.cancel()
        unitLoadJob = viewModelScope.launch {
            val cached = announcementsRepository.loadCachedUnits()
            _uiState.update { it.copy(units = cached, isLoadingUnits = true, unitLoadFailed = false) }
            try {
                val units = announcementsRepository.loadUnits()
                _uiState.update { it.copy(units = units, isLoadingUnits = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(isLoadingUnits = false, unitLoadFailed = true) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            syncJob?.cancel()
            _uiState.update { it.copy(isChecking = false) }
            launchUpdate { repository.setEnabled(false) }
            return
        }
        if (_uiState.value.reminder.enabled || _uiState.value.isChecking) return

        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, checkResult = null) }
            try {
                val state = repository.loadState()
                val announcements = loadSnapshot(state.selectedUnitIds, emptySet())
                repository.enableWithBaseline(announcements, System.currentTimeMillis())
                analyticsLogger.logEvent(
                    AnalyticsEvents.ANNOUNCEMENT_REMINDER_START,
                    mapOf(AnalyticsParams.RESULT to AnalyticsValues.RESULT_SUCCESS),
                )
                _uiState.update { it.copy(isChecking = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        checkResult = AnnouncementReminderCheckResult(null, System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    fun setIntervalMinutes(intervalMinutes: Int) = launchUpdate {
        repository.setIntervalMinutes(intervalMinutes)
    }

    fun applyUnits(unitIds: Set<String>) {
        if (_uiState.value.isApplyingUnits) return
        unitApplyJob?.cancel()
        unitApplyJob = viewModelScope.launch {
            _uiState.update { it.copy(isApplyingUnits = true, unitApplySucceeded = null) }
            try {
                val announcements = loadSnapshot(unitIds, emptySet())
                repository.applySelectedUnitIds(unitIds, announcements, System.currentTimeMillis())
                _uiState.update { it.copy(isApplyingUnits = false, unitApplySucceeded = true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(isApplyingUnits = false, unitApplySucceeded = false) }
            }
        }
    }

    fun consumeUnitApplyResult() {
        _uiState.update { it.copy(unitApplySucceeded = null) }
    }

    fun markRead(id: String) = launchUpdate { repository.markRead(id) }

    fun consumeCheckResult() {
        _uiState.update { it.copy(checkResult = null) }
    }

    private suspend fun loadSnapshot(
        selectedUnitIds: Set<String>,
        knownIds: Set<String>,
    ) = loadAnnouncementReminderSnapshot(
        selectedUnitIds = selectedUnitIds,
        knownIds = knownIds,
        loadPage = { pageIndex, unitId ->
            announcementsRepository.loadPage(pageIndex = pageIndex, unitId = unitId)
        },
    )

    private fun launchUpdate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: IOException) {
                _uiState.update {
                    it.copy(checkResult = AnnouncementReminderCheckResult(null, System.currentTimeMillis()))
                }
            }
        }
    }

    companion object {
        fun factory(
            context: Context,
            analyticsLogger: AnalyticsLogger = NoOpAnalyticsLogger,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return AnnouncementReminderViewModel(
                    repository = AnnouncementReminderRepository(appContext),
                    announcementsRepository = NetworkSchoolAnnouncementsRepository(appContext.cacheDir),
                    scheduler = AnnouncementReminderScheduler(appContext),
                    analyticsLogger = analyticsLogger,
                ) as T
            }
        }
    }
}
