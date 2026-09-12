package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolCalendarSnapshot
import com.clhs.score.data.SCHOOL_CALENDAR_SEARCH_MAX_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

data class SchoolCalendarUiState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val events: List<SchoolCalendarEvent> = emptyList(),
    val lastUpdatedAt: Instant? = null,
    val skippedRecurringEvents: Int = 0,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val searchQuery: String = "",
) {
    val visibleEvents: List<SchoolCalendarEvent>
        get() = if (searchQuery.isEmpty()) {
            events
        } else {
            events.filter { event ->
                event.title.contains(searchQuery, ignoreCase = true) ||
                    event.location?.contains(searchQuery, ignoreCase = true) == true
            }
        }
}

class SchoolCalendarViewModel(
    private val loadCached: suspend () -> SchoolCalendarSnapshot?,
    private val loadSnapshot: suspend (forceRefresh: Boolean) -> SchoolCalendarSnapshot,
    private val todayProvider: () -> LocalDate = LocalDate::now,
    private val targetEventId: String? = null,
) : ViewModel() {
    private var loadJob: Job? = null
    private val _uiState = MutableStateFlow(SchoolCalendarUiState())
    val uiState: StateFlow<SchoolCalendarUiState> = _uiState.asStateFlow()

    init {
        load(forceRefresh = false, includeCache = true)
    }

    fun refresh() {
        load(forceRefresh = true, includeCache = false)
    }

    fun consumeNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    fun search(keyword: String) {
        val normalizedKeyword = keyword.trim().take(SCHOOL_CALENDAR_SEARCH_MAX_LENGTH)
        if (normalizedKeyword == _uiState.value.searchQuery) return
        _uiState.update { it.copy(searchQuery = normalizedKeyword) }
    }

    fun clearSearch() {
        search("")
    }

    private fun load(forceRefresh: Boolean, includeCache: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (includeCache) {
                val cached = try {
                    loadCached()
                } catch (error: Exception) {
                    error.throwIfCancellation()
                    null
                }
                cached?.let(::showSnapshot)
            }

            val hasLoadedData = _uiState.value.lastUpdatedAt != null
            _uiState.update {
                it.copy(
                    isInitialLoading = !hasLoadedData,
                    isRefreshing = hasLoadedData,
                    errorMessage = null,
                    noticeMessage = null,
                )
            }

            try {
                showSnapshot(loadSnapshot(forceRefresh))
            } catch (error: Exception) {
                error.throwIfCancellation()
                _uiState.update { state ->
                    if (state.lastUpdatedAt != null) {
                        state.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            noticeMessage = "無法更新，暫時顯示已儲存的行事曆",
                        )
                    } else {
                        state.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            errorMessage = "暫時無法取得行事曆，請檢查網路後再試一次。",
                        )
                    }
                }
            }
        }
    }

    private fun showSnapshot(snapshot: SchoolCalendarSnapshot) {
        val startOfToday = todayProvider().atStartOfDay()
        _uiState.update {
            it.copy(
                isInitialLoading = false,
                isRefreshing = false,
                events = snapshot.feed.events
                    .filter { event -> event.id == targetEventId || event.endExclusive.isAfter(startOfToday) }
                    .sortedBy(SchoolCalendarEvent::start),
                lastUpdatedAt = snapshot.fetchedAt,
                skippedRecurringEvents = snapshot.feed.skippedRecurringEvents,
                errorMessage = null,
            )
        }
    }

    private fun Throwable.throwIfCancellation() {
        if (this is CancellationException) throw this
    }

    companion object {
        fun factory(context: Context, targetEventId: String? = null): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = NetworkSchoolCalendarRepository(context.applicationContext.cacheDir)
                return SchoolCalendarViewModel(
                    targetEventId = targetEventId,
                    loadCached = repository::loadCached,
                    loadSnapshot = repository::load,
                ) as T
            }
        }
    }
}
