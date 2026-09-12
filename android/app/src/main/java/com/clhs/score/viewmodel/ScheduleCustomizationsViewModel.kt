package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.NetworkScheduleRepository
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SessionStore
import com.clhs.score.data.normalizedSubjectOverrides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScheduleCustomizationsUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val overrides: List<ScheduleSubjectOverride> = emptyList(),
    val currentItems: List<ScheduleItem> = emptyList(),
    val loadFailed: Boolean = false,
    val noticeMessage: String? = null,
)

class ScheduleCustomizationsViewModel(
    private val repository: ScheduleRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScheduleCustomizationsUiState())
    val uiState: StateFlow<ScheduleCustomizationsUiState> = _uiState.asStateFlow()
    private var overridesJob: Job? = null

    init {
        observeOverrides()
        viewModelScope.launch {
            runCatching { repository.getLatestSchedule()?.items.orEmpty() }
                .onSuccess { items -> _uiState.update { it.copy(currentItems = items) } }
        }
    }

    fun retry() = observeOverrides()

    fun consumeNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun replaceOverrides(
        overrides: List<ScheduleSubjectOverride>,
        onSaved: () -> Unit = {},
    ) {
        val normalized = overrides.normalizedSubjectOverrides()
        if (normalized == _uiState.value.overrides) {
            onSaved()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                repository.saveSubjectOverrides(normalized)
                _uiState.update {
                    it.copy(isSaving = false, overrides = normalized, loadFailed = false)
                }
                onSaved()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        noticeMessage = ScheduleViewModel.SUBJECT_OVERRIDE_SAVE_ERROR,
                    )
                }
            }
        }
    }

    fun saveOverride(override: ScheduleSubjectOverride, onSaved: () -> Unit = {}) {
        replaceOverrides(
            _uiState.value.overrides.filterNot {
                it.originalSubjectName == override.originalSubjectName
            } + override,
            onSaved,
        )
    }

    fun removeOverride(originalSubjectName: String, onSaved: () -> Unit = {}) {
        replaceOverrides(
            _uiState.value.overrides.filterNot { it.originalSubjectName == originalSubjectName },
            onSaved,
        )
    }

    private fun observeOverrides() {
        overridesJob?.cancel()
        overridesJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                repository.subjectOverridesFlow().collect { overrides ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            overrides = overrides,
                            loadFailed = false,
                        )
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(isLoading = false, loadFailed = true)
                }
            }
        }
    }

    companion object {
        fun factory(
            context: Context,
            useFakeData: Boolean,
            activeSessionProvider: () -> AuthenticatedSession? = { null },
            sharedScheduleRepository: ScheduleRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val repository = sharedScheduleRepository ?: run {
                    val cacheStore = GradeCacheStore(appContext)
                    if (useFakeData) {
                        FakeScheduleRepository(cacheStore)
                    } else {
                        NetworkScheduleRepository(
                            SchoolGradeClient(),
                            SessionStore(appContext),
                            cacheStore,
                            activeSessionProvider = activeSessionProvider,
                        )
                    }
                }
                return ScheduleCustomizationsViewModel(repository) as T
            }
        }
    }
}
