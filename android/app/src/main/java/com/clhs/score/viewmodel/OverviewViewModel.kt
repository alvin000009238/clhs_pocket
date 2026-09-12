package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.CwaApiKeyStore
import com.clhs.score.data.FakeScheduleRepository
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.GradeReminderRepository
import com.clhs.score.data.NetworkScheduleRepository
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.SchoolGradeClient
import com.clhs.score.data.SessionStore
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.WeatherRepository
import com.clhs.score.domain.overview.OverviewCoordinator
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.withPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class OverviewViewModel(
    private val coordinator: OverviewCoordinator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverviewState())
    val uiState: StateFlow<OverviewState> = _uiState.asStateFlow()
    private var observeJob: Job? = null

    init {
        observe()
    }

    fun refresh() {
        observe()
    }

    private fun observe() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch(Dispatchers.Default) {
            combine(
                coordinator.observe(),
                settingsRepository.settings.retryWhen { error, _ ->
                    if (error !is IOException) throw error
                    delay(5_000.milliseconds)
                    true
                },
            ) { state, settings -> state.withPreferences(settings.overview) }.collect(_uiState::emit)
        }
    }

    suspend fun savePreferences(value: OverviewPreferences): Boolean = try {
        settingsRepository.setOverviewPreferences(value)
        true
    } catch (_: IOException) {
        false
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
                val scheduleRepository = sharedScheduleRepository ?: if (useFakeData) {
                    FakeScheduleRepository(GradeCacheStore(appContext))
                } else {
                    NetworkScheduleRepository(
                        client = SchoolGradeClient(),
                        sessionStore = SessionStore(appContext),
                        cacheStore = GradeCacheStore(appContext),
                        activeSessionProvider = activeSessionProvider,
                    )
                }
                return OverviewViewModel(
                    OverviewCoordinator(
                        scheduleRepository = scheduleRepository,
                        calendarRepository = NetworkSchoolCalendarRepository(appContext.cacheDir),
                        announcementsRepository = NetworkSchoolAnnouncementsRepository(appContext.cacheDir),
                        reminderRepository = GradeReminderRepository(appContext),
                        weatherRepository = WeatherRepository(
                            cacheDirectory = appContext.cacheDir,
                            keyStore = CwaApiKeyStore(appContext),
                        ),
                        weatherConfiguration = SettingsRepository(appContext).weatherConfiguration,
                    ),
                    settingsRepository = SettingsRepository(appContext),
                ) as T
            }
        }
    }
}
