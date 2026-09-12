package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class SchoolAnnouncementsUiState(
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val announcements: List<SchoolAnnouncement> = emptyList(),
    val pageIndex: Int = 0,
    val hasMore: Boolean = false,
    val lastUpdatedAt: Instant? = null,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val loadMoreError: String? = null,
    val searchQuery: String = "",
)

class SchoolAnnouncementsViewModel(
    private val loadCached: suspend () -> SchoolAnnouncementPage?,
    private val loadPage: suspend (Int, String) -> SchoolAnnouncementPage,
) : ViewModel() {
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private val _uiState = MutableStateFlow(SchoolAnnouncementsUiState())
    val uiState: StateFlow<SchoolAnnouncementsUiState> = _uiState.asStateFlow()

    init {
        loadFirstPage(includeCache = true)
    }

    fun refresh() {
        loadFirstPage(includeCache = false, keyword = _uiState.value.searchQuery)
    }

    fun search(keyword: String) {
        val normalizedKeyword = keyword.trim().take(SCHOOL_ANNOUNCEMENT_SEARCH_MAX_LENGTH)
        if (normalizedKeyword == _uiState.value.searchQuery) {
            if (normalizedKeyword.isNotEmpty()) refresh()
            return
        }
        _uiState.update {
            it.copy(
                announcements = emptyList(),
                pageIndex = 0,
                hasMore = false,
                lastUpdatedAt = null,
                errorMessage = null,
                noticeMessage = null,
                loadMoreError = null,
                searchQuery = normalizedKeyword,
            )
        }
        loadFirstPage(includeCache = normalizedKeyword.isEmpty(), keyword = normalizedKeyword)
    }

    fun clearSearch() {
        if (_uiState.value.searchQuery.isNotEmpty()) search("")
    }

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingMore || state.isRefreshing || state.isInitialLoading) return
        val nextPage = state.pageIndex + 1
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                val page = loadPage(nextPage, state.searchQuery)
                _uiState.update { current ->
                    current.copy(
                        isLoadingMore = false,
                        announcements = (current.announcements + page.announcements)
                            .distinctBy(SchoolAnnouncement::id),
                        pageIndex = page.pageIndex,
                        hasMore = page.pageIndex + 1 < page.totalPages,
                        lastUpdatedAt = page.fetchedAt,
                    )
                }
            } catch (error: Exception) {
                error.throwIfAnnouncementCancellation()
                _uiState.update {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreError = "無法載入更多消息，請稍後再試。",
                    )
                }
            }
        }
    }

    fun consumeNotice() {
        _uiState.update { it.copy(noticeMessage = null) }
    }

    private fun loadFirstPage(
        includeCache: Boolean,
        keyword: String = _uiState.value.searchQuery,
    ) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            if (includeCache) {
                val cached = try {
                    loadCached()
                } catch (error: Exception) {
                    error.throwIfAnnouncementCancellation()
                    null
                }
                cached?.let(::showFirstPage)
            }

            val hasLoadedData = _uiState.value.lastUpdatedAt != null
            _uiState.update {
                it.copy(
                    isInitialLoading = !hasLoadedData,
                    isRefreshing = hasLoadedData,
                    isLoadingMore = false,
                    errorMessage = null,
                    noticeMessage = null,
                    loadMoreError = null,
                )
            }
            try {
                showFirstPage(loadPage(0, keyword))
            } catch (error: Exception) {
                error.throwIfAnnouncementCancellation()
                _uiState.update { state ->
                    if (state.lastUpdatedAt != null) {
                        state.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            noticeMessage = "無法更新，暫時顯示已儲存的學校消息",
                        )
                    } else {
                        state.copy(
                            isInitialLoading = false,
                            isRefreshing = false,
                            errorMessage = "暫時無法取得學校消息，請檢查網路後再試一次。",
                        )
                    }
                }
            }
        }
    }

    private fun showFirstPage(page: SchoolAnnouncementPage) {
        _uiState.update {
            it.copy(
                isInitialLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                announcements = page.announcements,
                pageIndex = page.pageIndex,
                hasMore = page.pageIndex + 1 < page.totalPages,
                lastUpdatedAt = page.fetchedAt,
                errorMessage = null,
                loadMoreError = null,
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val repository = NetworkSchoolAnnouncementsRepository(context.applicationContext.cacheDir)
                return SchoolAnnouncementsViewModel(
                    loadCached = repository::loadCached,
                    loadPage = repository::loadPage,
                ) as T
            }
        }
    }
}

data class SchoolAnnouncementDetailUiState(
    val isLoading: Boolean = true,
    val detail: SchoolAnnouncementDetail? = null,
    val errorMessage: String? = null,
)

class SchoolAnnouncementDetailViewModel(
    private val loadDetail: suspend () -> SchoolAnnouncementDetail,
) : ViewModel() {
    private var loadJob: Job? = null
    private val _uiState = MutableStateFlow(SchoolAnnouncementDetailUiState())
    val uiState: StateFlow<SchoolAnnouncementDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun retry() {
        load()
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = SchoolAnnouncementDetailUiState(isLoading = true)
            try {
                _uiState.value = SchoolAnnouncementDetailUiState(
                    isLoading = false,
                    detail = loadDetail(),
                )
            } catch (error: Exception) {
                error.throwIfAnnouncementCancellation()
                _uiState.value = SchoolAnnouncementDetailUiState(
                    isLoading = false,
                    errorMessage = "暫時無法取得消息內容，請檢查網路後再試一次。",
                )
            }
        }
    }

    companion object {
        fun factory(context: Context, announcementId: String, category: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repository = NetworkSchoolAnnouncementsRepository(context.applicationContext.cacheDir)
                    return SchoolAnnouncementDetailViewModel {
                        repository.loadDetail(announcementId, category)
                    } as T
                }
            }
    }
}

private fun Throwable.throwIfAnnouncementCancellation() {
    if (this is CancellationException) throw this
}
