package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.clhs.score.data.OverviewLayoutRepository
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherLocationCatalog
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.OverviewLayoutEngine
import com.clhs.score.domain.overview.OverviewLayoutJson
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.OverviewPlacement
import com.clhs.score.domain.overview.defaultOverviewWidgets
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
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
import com.clhs.score.data.YouBikeRepository
import com.clhs.score.domain.overview.OverviewCoordinator
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.withPreferences
import com.clhs.score.domain.overview.forGeneration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeMark

data class OverviewEditorState(
    val committed: List<OverviewWidgetInstance>? = null,
    val draft: List<OverviewWidgetInstance>? = null,
    val resetUndo: List<OverviewWidgetInstance>? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val layoutError: String? = null,
    val legacyOrder: Boolean = false,
) {
    val editing: Boolean get() = draft != null
    val hasChanges: Boolean get() = draft != null && draft != committed
    val canUndoReset: Boolean get() = resetUndo != null
}

class OverviewViewModel(
    private val coordinator: OverviewCoordinator,
    private val settingsRepository: SettingsRepository,
    private val authState: StateFlow<AuthState>,
    private val activeSessionProvider: () -> AuthenticatedSession?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val layoutRepository: OverviewLayoutRepository? = null,
    private val savedStateHandle: SavedStateHandle? = null,
    private val locationSearch: suspend (String) -> List<WeatherLocation> = { emptyList() },
    private val stationSearch: suspend (String) -> List<YouBikeStation> = { emptyList() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(OverviewState())
    val uiState: StateFlow<OverviewState> = _uiState.asStateFlow()
    private val refreshVersion = MutableStateFlow(0L)
    private val clockEnabled = MutableStateFlow(false)
    private val youBikeRefreshVersion = MutableStateFlow(0L)
    private var lastYouBikeRefreshAt: TimeMark? = null

    private fun restore(key: String): List<OverviewWidgetInstance>? = savedStateHandle?.get<String>(key)?.let {
        runCatching { OverviewLayoutJson.decodeFromString<List<OverviewWidgetInstance>>(it) }.getOrNull()
    }
    private val _editor = MutableStateFlow(OverviewEditorState(draft = restore("overview_draft"), resetUndo = restore("overview_reset_undo")))
    val editor: StateFlow<OverviewEditorState> = _editor.asStateFlow()
    private val widgetLayout = MutableStateFlow(_editor.value.draft.orEmpty())
    private val weatherRefreshVersion = MutableStateFlow(0L)
    private var layoutJob: Job? = null

    fun retryLayout() {
        layoutJob?.cancel()
        _editor.value = _editor.value.copy(loading = true, layoutError = null)
        layoutJob = viewModelScope.launch {
            try {
                (layoutRepository?.observeLayout() ?: kotlinx.coroutines.flow.flowOf(
                    com.clhs.score.data.OverviewLayoutSnapshot(defaultOverviewWidgets(), legacyOrder = false),
                )).collect { layout ->
                    val prepared = savedStateHandle?.get<List<String>>("overview_visual_order")
                    val committed = if (layout.legacyOrder && prepared != null)
                        OverviewLayoutEngine.inVisualOrder(layout.widgets, prepared) else layout.widgets
                    _editor.value = _editor.value.copy(committed = committed, loading = false, layoutError = null,
                        legacyOrder = layout.legacyOrder && prepared == null)
                    widgetLayout.value = _editor.value.draft ?: committed
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _editor.value = _editor.value.copy(loading = false, layoutError = "首頁排列暫時無法讀取")
            }
        }
    }

    private fun updateEditor(value: OverviewEditorState) {
        _editor.value = value
        widgetLayout.value = value.draft ?: value.committed.orEmpty()
        savedStateHandle?.set("overview_draft", value.draft?.let { OverviewLayoutJson.encodeToString(it) })
        savedStateHandle?.set("overview_reset_undo", value.resetUndo?.let { OverviewLayoutJson.encodeToString(it) })
    }
    fun prepareLayoutOrder(columns: Int, ids: List<String>) {
        val current = _editor.value
        if (!current.legacyOrder || current.loading || current.saving) return
        if (ids.isEmpty() && (current.draft ?: current.committed).orEmpty().any { it.enabled && it.type != "date_time" }) return
        fun normalize(widgets: List<OverviewWidgetInstance>): List<OverviewWidgetInstance> {
            val visible = widgets.filter { it.enabled && it.type != "date_time" && (current.editing || it.id in ids) }
                .map { if (OverviewWidgetDefinitions[it.type]?.multiple == false) it.copy(size = OverviewWidgetSize(4, 1)) else it }
            val order = OverviewLayoutEngine.pack(visible, columns, legacyOrder = true)
                .sortedWith(compareBy<OverviewPlacement> { it.row }.thenBy { it.column }).map { it.id }
            return OverviewLayoutEngine.inVisualOrder(widgets, order)
        }
        val committed = current.committed?.let(::normalize)
        savedStateHandle?.set("overview_visual_order", committed.orEmpty().map { it.id })
        updateEditor(current.copy(
            committed = committed,
            draft = current.draft?.let(::normalize),
            resetUndo = current.resetUndo?.let(::normalize),
            legacyOrder = false,
        ))
    }
    fun onLayoutRestored() {
        savedStateHandle?.remove<List<String>>("overview_visual_order")
        updateEditor(_editor.value.copy(draft = null, resetUndo = null))
        retryLayout()
    }

    fun beginEditing() {
        val current = _editor.value
        if (current.editing || current.loading || current.layoutError != null || current.saving) return
        updateEditor(current.copy(draft = current.committed.orEmpty(), error = null))
    }
    fun cancelEditing() {
        if (_editor.value.saving) return
        updateEditor(_editor.value.copy(draft = null, resetUndo = null, error = null))
    }
    fun addCalendarCountdown(eventId: String, title: String): Boolean {
        val current = _editor.value
        if (eventId.isBlank() || current.loading || current.saving || current.layoutError != null) return false
        beginEditing()
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.SchoolEvent(eventId, title))
        putWidget(widget)
        moveWidget(widget.id, 0)
        return true
    }
    private fun edit(transform: (List<OverviewWidgetInstance>) -> List<OverviewWidgetInstance>) {
        val current = _editor.value
        val draft = current.draft ?: return
        if (current.saving || current.loading) return
        updateEditor(current.copy(draft = transform(draft), error = null))
    }
    fun putWidget(widget: OverviewWidgetInstance) = edit { draft ->
        val definition = OverviewWidgetDefinitions[widget.type]
        if (definition == null || widget.config() == null || widget.size !in definition.sizes) draft else {
            val existing = draft.indexOfFirst { it.id == widget.id || (!definition.multiple && it.type == widget.type) }
            if (existing < 0) draft + widget else draft.toMutableList().apply { this[existing] = widget.copy(id = draft[existing].id) }
        }
    }
    fun removeWidget(id: String) = edit { draft -> draft.filterNot { it.id == id } }
    fun moveWidget(id: String, index: Int) = edit { draft -> OverviewLayoutEngine.move(draft, id, index) }
    fun resetLayout() {
        val current = _editor.value
        if (current.draft == null || current.saving || current.loading) return
        updateEditor(current.copy(draft = defaultOverviewWidgets(), resetUndo = current.draft, error = null))
    }
    fun undoReset() {
        val current = _editor.value
        if (current.resetUndo == null || current.saving || current.loading) return
        updateEditor(current.copy(draft = current.resetUndo, resetUndo = null, error = null))
    }
    suspend fun saveLayout(): Boolean {
        val current = _editor.value
        val draft = current.draft ?: return true
        if (current.saving || current.loading || current.layoutError != null) return false
        _editor.value = current.copy(saving = true, error = null)
        return try {
            layoutRepository?.save(draft)
            updateEditor(_editor.value.copy(committed = draft, draft = null, resetUndo = null, saving = false, error = null))
            true
        } catch (error: Exception) {
            _editor.value = _editor.value.copy(saving = false)
            if (error is CancellationException) throw error
            _editor.value = _editor.value.copy(error = "排列未能儲存，請重試")
            false
        }
    }
    suspend fun searchLocations(query: String): List<WeatherLocation> = locationSearch(query)
    suspend fun searchStations(query: String): List<YouBikeStation> = stationSearch(query)
    fun refreshWeather() { weatherRefreshVersion.value++ }

    init {
        retryLayout()
        viewModelScope.launch {
            var observation: Job? = null
            authState.collect { auth ->
                val generation = (auth as? AuthState.Authenticated)?.generation
                val session = if (generation != null) activeSessionProvider() else null
                _uiState.value = _uiState.value.forGeneration(generation)
                observation?.cancel()
                observation = launch {
                    combine(
                        coordinator.observe(
                            refreshVersion.map { Unit }, session, clockEnabled,
                            youBikeEnabled = if (layoutRepository != null) clockEnabled else combine(clockEnabled, settingsRepository.settings) { visible, settings ->
                                visible && settings.overview.shows("youbike")
                            }.retryWhen { error, _ ->
                                if (error !is IOException) throw error
                                delay(5_000.milliseconds)
                                true
                            },
                            youBikeRefreshes = youBikeRefreshVersion.map { Unit },
                            widgets = widgetLayout.takeIf { layoutRepository != null },
                            weatherRefreshes = weatherRefreshVersion.map { Unit }.drop(1),
                        ),
                        settingsRepository.settings.retryWhen { error, _ ->
                            if (error !is IOException) throw error
                            delay(5_000.milliseconds)
                            true
                        },
                    ) { state, settings ->
                        if (layoutRepository == null) state.withPreferences(settings.overview).copy(authGeneration = generation) else state.copy(preferences = settings.overview, authGeneration = generation)
                    }.flowOn(dispatcher).collect { state ->
                        // Cancellation alone cannot reject values already buffered across flowOn.
                        if (authState.value == auth && (generation == null || activeSessionProvider() == session)) {
                            _uiState.value = state.forGeneration(generation)
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        refreshVersion.value++
    }

    fun setClockEnabled(enabled: Boolean) {
        if (clockEnabled.value == enabled) return
        clockEnabled.value = enabled
        if (enabled) refresh()
    }

    fun refreshYouBike() {
        if (_uiState.value.youBikeStations.values.any { it.isRefreshing } || lastYouBikeRefreshAt?.elapsedNow()?.let { it < 1.seconds } == true) return
        lastYouBikeRefreshAt = TimeSource.Monotonic.markNow()
        youBikeRefreshVersion.value++
    }

    suspend fun markYouBikeWidgetSeen(): Boolean = try {
        settingsRepository.markYouBikeWidgetSeen()
        true
    } catch (_: IOException) {
        false
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
            authState: StateFlow<AuthState>,
            activeSessionProvider: () -> AuthenticatedSession? = { null },
            sharedScheduleRepository: ScheduleRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = createModel(null)
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = createModel(extras.createSavedStateHandle())
            @Suppress("UNCHECKED_CAST")
            private fun <T : ViewModel> createModel(handle: SavedStateHandle?): T {
                val appContext = context.applicationContext
                val scheduleRepository = sharedScheduleRepository ?: if (useFakeData) {
                    FakeScheduleRepository(
                        GradeCacheStore(appContext), activeSessionProvider, SessionStore(appContext),
                    )
                } else {
                    NetworkScheduleRepository(
                        client = SchoolGradeClient(),
                        sessionStore = SessionStore(appContext),
                        cacheStore = GradeCacheStore(appContext),
                        activeSessionProvider = activeSessionProvider,
                        sessionAccessAllowedProvider = { authState.value is AuthState.Authenticated },
                    )
                }
                val bikes = YouBikeRepository()
                val locations = WeatherLocationCatalog(appContext)
                return OverviewViewModel(
                    OverviewCoordinator(
                        scheduleRepository = scheduleRepository,
                        calendarRepository = NetworkSchoolCalendarRepository(appContext.cacheDir),
                        announcementsRepository = NetworkSchoolAnnouncementsRepository(
                            appContext.cacheDir,
                            readRepository = com.clhs.score.data.AnnouncementReadRepository.get(appContext),
                        ),
                        reminderState = GradeReminderRepository(appContext).state,
                        weatherRepository = WeatherRepository(
                            cacheDirectory = appContext.cacheDir,
                            keyStore = CwaApiKeyStore(appContext),
                        ),
                        weatherConfiguration = SettingsRepository(appContext).weatherConfiguration,
                        youBikeRepository = bikes,
                    ),
                    settingsRepository = SettingsRepository(appContext),
                    authState = authState,
                    activeSessionProvider = activeSessionProvider,
                    layoutRepository = OverviewLayoutRepository.get(appContext), savedStateHandle = handle,
                    locationSearch = locations::search, stationSearch = bikes::searchStations,
                ) as T
            }
        }
    }
}
