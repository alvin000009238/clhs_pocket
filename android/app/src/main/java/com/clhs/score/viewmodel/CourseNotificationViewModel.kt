package com.clhs.score.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.CourseNotificationPreference
import com.clhs.score.notifications.CurrentCourseNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CourseNotificationUiState(
    val preference: CourseNotificationPreference = CourseNotificationPreference(),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val blocked: Boolean = false,
    val available: Boolean = false,
    val message: String? = null,
)

internal class CourseNotificationViewModel(private val owner: CurrentCourseNotifications) : ViewModel() {
    private val mutableState = MutableStateFlow(CourseNotificationUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                owner.state.collect { preference ->
                    mutableState.value = mutableState.value.copy(preference = preference, ready = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(ready = false, message = "無法讀取當前課程通知設定")
            }
        }
    }

    fun sync() = viewModelScope.launch {
        val available = try { withContext(Dispatchers.IO) { owner.available() } }
            catch (error: Exception) { if (error is CancellationException) throw error; false }
        mutableState.value = mutableState.value.copy(blocked = !owner.canPost(), available = available)
    }

    fun prepareEnable(allowed: Boolean, requestPermission: () -> Unit) {
        if (mutableState.value.busy || !mutableState.value.ready) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                if (!allowed || !withContext(Dispatchers.IO) { owner.available() }) {
                    message("請先登入並取得有效週課表或學期課表")
                } else requestPermission()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message("無法讀取本機課表，請稍後重試")
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        mutableState.value = mutableState.value.copy(busy = true)
        try {
            val message = withContext(Dispatchers.IO) { owner.setEnabled(enabled) }
            mutableState.value = mutableState.value.copy(message = message, blocked = !owner.canPost())
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    fun message(value: String?) { mutableState.value = mutableState.value.copy(message = value) }

    fun setRemindBeforeClass(enabled: Boolean) = viewModelScope.launch {
        if (!mutableState.value.ready || mutableState.value.busy) return@launch
        mutableState.value = mutableState.value.copy(busy = true)
        try {
            val message = withContext(Dispatchers.IO) { owner.setRemindBeforeClass(enabled) }
            mutableState.value = mutableState.value.copy(message = message)
        } finally { mutableState.value = mutableState.value.copy(busy = false) }
    }

    companion object {
        fun factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CourseNotificationViewModel(CurrentCourseNotifications.get(context)) as T
        }
    }
}
