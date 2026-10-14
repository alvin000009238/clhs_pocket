package com.clhs.score.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.*
import com.clhs.score.notifications.CurrentCourseNotifications
import com.clhs.score.notifications.CourseNotificationRefreshOutcome
import com.clhs.score.widget.syncAllScheduleWidgets
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SettingsTransferUiState(
    val text: String = "",
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val preview: SettingsTransferDocument? = null,
    val canImportSubjects: Boolean = false,
)

internal class SettingsTransferViewModel(
    private val application: Application,
    private val repository: SettingsTransferRepository,
    private val currentSession: () -> AuthenticatedSession?,
    private val onLayoutRestored: () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsTransferUiState())
    val state = mutableState.asStateFlow()
    private var previewSession: AuthenticatedSession? = null
    private var pendingExport: String? = null
    private var operation: Job? = null
    private var observedSession = currentSession()

    init { loadCurrent() }

    fun onAccountChanged() {
        val session = currentSession()
        if (session == observedSession) return
        observedSession = session
        pendingExport = null
        previewSession = null
        mutableState.value = SettingsTransferUiState()
        val previous = operation
        viewModelScope.launch {
            previous?.cancelAndJoin()
            loadCurrent()
        }
    }

    fun edit(text: String) {
        if (mutableState.value.busy || mutableState.value.loading) return
        if (text.length > SettingsTransferCodec.MAX_BYTES) {
            mutableState.value = mutableState.value.copy(error = "設定超過 1 MiB")
            return
        }
        mutableState.value = mutableState.value.copy(text = text, preview = null, error = null, message = null)
    }

    fun loadCurrent() = work("無法讀取目前設定") {
        val text = SettingsTransferCodec.encode(repository.export())
        mutableState.value = mutableState.value.copy(text = text, preview = null)
    }

    fun previewImport() {
        if (mutableState.value.busy || mutableState.value.loading) return
        try {
            val document = SettingsTransferCodec.decode(mutableState.value.text)
            previewSession = currentSession()
            mutableState.value = mutableState.value.copy(preview = document, error = null,
                canImportSubjects = previewSession != null && document.subjects != null)
        } catch (error: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = error.message)
        }
    }

    fun dismissPreview() { mutableState.value = mutableState.value.copy(preview = null) }
    fun consumeMessage() { mutableState.value = mutableState.value.copy(message = null) }

    fun importSettings(includeSubjects: Boolean) {
        val document = mutableState.value.preview ?: return
        work("還原失敗，請重試") {
            try {
                repository.restore(document, includeSubjects, previewSession)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { onLayoutRestored() }
            }
            mutableState.value = mutableState.value.copy(preview = null, message = "設定已還原")
            // Stored preferences are committed; synchronization failure must not suggest reimporting.
            try {
                syncAllScheduleWidgets(application)
                if (CurrentCourseNotifications.get(application).refresh() == CourseNotificationRefreshOutcome.TRANSIENT_FAILURE)
                    throw java.io.IOException("Reminder synchronization failed")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(message = "設定已還原，提醒同步失敗，請至排程可靠性頁面重試")
            }
        }
    }

    fun readFile(uri: Uri) = work("無法讀取檔案") {
        val text = application.contentResolver.openInputStream(uri)?.use(SettingsTransferCodec::read)
            ?: throw java.io.IOException("Cannot open document")
        val document = SettingsTransferCodec.decode(text)
        previewSession = currentSession()
        mutableState.value = mutableState.value.copy(text = text, preview = document,
            canImportSubjects = previewSession != null && document.subjects != null)
    }

    fun exportCurrent(onReady: () -> Unit) = work("無法建立備份") {
        pendingExport = SettingsTransferCodec.encode(repository.export())
        withContext(Dispatchers.Main) { onReady() }
    }

    fun writeFile(uri: Uri?) {
        val text = pendingExport ?: run {
            if (uri != null) mutableState.value = mutableState.value.copy(error = "備份操作已中斷，請重新儲存")
            return
        }
        pendingExport = null
        if (uri == null) return
        work("備份失敗，目的地可能留下未完成檔案，請重新儲存") {
            application.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("Cannot open document")
            mutableState.value = mutableState.value.copy(message = "備份已儲存")
        }
    }

    private fun work(fallback: String, action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, message = null)
        operation = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (error: CancellationException) {
                mutableState.value = mutableState.value.copy(preview = null, error = "帳號或操作已變更，請重新載入設定")
                throw error
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(error = when (error) {
                    is SettingsTransferRestoreException -> if (error.rollbackFailed)
                        "還原失敗，部分設定可能已變更；請重新載入並檢查" else "還原失敗，已還原原設定"
                    is IllegalArgumentException -> error.message ?: fallback
                    else -> fallback
                })
            } finally { mutableState.value = mutableState.value.copy(busy = false, loading = false) }
        }
    }

    companion object {
        fun factory(context: Context, schedule: ScheduleRepository, currentSession: () -> AuthenticatedSession?,
            onLayoutRestored: () -> Unit) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = context.applicationContext as Application
                return SettingsTransferViewModel(app, SettingsTransferRepository(SettingsRepository(app),
                    OverviewLayoutRepository.get(app), AnnouncementReminderRepository(app),
                    CourseNotificationPreferences(app), schedule, currentSession, restoreCoursePreference = { enabled ->
                        val error = CurrentCourseNotifications.get(app).setRemindBeforeClass(enabled)
                        if (error != null) throw java.io.IOException("Course preference restore failed")
                    }), currentSession, onLayoutRestored) as T
            }
        }
    }
}
