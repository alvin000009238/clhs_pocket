package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.AnnouncementBookmarkRepository
import com.clhs.score.data.SchoolAnnouncement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnnouncementBookmarksUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val bookmarks: List<AnnouncementBookmark> = emptyList(),
    val message: String? = null,
)

class AnnouncementBookmarksViewModel(
    private val repository: AnnouncementBookmarkRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AnnouncementBookmarksUiState())
    val uiState: StateFlow<AnnouncementBookmarksUiState> = _uiState.asStateFlow()
    private var observeJob: Job? = null

    init {
        observeBookmarks()
    }

    fun toggle(announcement: SchoolAnnouncement) {
        viewModelScope.launch {
            try {
                repository.toggle(announcement)
            } catch (error: Exception) {
                error.rethrowIfBookmarkCancellation()
                _uiState.update { it.copy(message = "無法更新公告收藏，請稍後再試。") }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(isLoading = true, loadFailed = false, message = null) }
        observeBookmarks()
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun observeBookmarks() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.bookmarks
                .catch { error ->
                    error.rethrowIfBookmarkCancellation()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadFailed = true,
                            message = "無法讀取公告收藏，請重試。",
                        )
                    }
                }
                .collect { bookmarks ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadFailed = false,
                            bookmarks = bookmarks,
                        )
                    }
                }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AnnouncementBookmarksViewModel(AnnouncementBookmarkRepository.get(context)) as T
        }
    }
}

private fun Throwable.rethrowIfBookmarkCancellation() {
    if (this is CancellationException) throw this
}
