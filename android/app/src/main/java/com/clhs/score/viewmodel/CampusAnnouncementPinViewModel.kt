package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.AnnouncementBookmark
import com.clhs.score.data.AnnouncementBookmarkRepository
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CampusAnnouncementPinState(
    val pin: AnnouncementBookmark? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
)

internal class CampusAnnouncementPinViewModel(
    private val repository: AnnouncementBookmarkRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CampusAnnouncementPinState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.campusPin.retryWhen { error, _ ->
                if (error !is IOException) return@retryWhen false
                _uiState.update { it.copy(isLoading = false) }
                delay(5_000)
                true
            }.collect { pin ->
                _uiState.update { it.copy(pin = pin, isLoading = false) }
            }
        }
    }

    fun pin(id: String) = save {
        if (repository.pinToCampus(id)) "已釘選到校園頁。" else "請先收藏這則公告。"
    }

    fun remove(id: String) = save {
        repository.removeCampusPin(id)
        "已取消校園頁釘選。"
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun save(edit: suspend () -> String) {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val message = edit()
                _uiState.update { it.copy(message = message) }
            } catch (_: IOException) {
                _uiState.update { it.copy(message = "無法更新校園頁釘選，請稍後再試。") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CampusAnnouncementPinViewModel(AnnouncementBookmarkRepository.get(context)) as T
        }
    }
}
