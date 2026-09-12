package com.clhs.score.data

import java.io.IOException
import kotlinx.coroutines.flow.combine
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val Context.gradeReminderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "grade_reminder",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal class GradeReminderMutationScope(
    private val repository: GradeReminderRepository,
) {
    suspend fun loadState(): GradeReminderState = repository.loadState()
    suspend fun saveState(state: GradeReminderState) = repository.writeStateLocked(state)
    suspend fun stop(reason: String) = saveState(GradeReminderState(stoppedReason = reason))
    suspend fun updateIfCurrent(
        expected: GradeReminderIdentity,
        transform: (GradeReminderState) -> GradeReminderState,
    ): Boolean = repository.updateIfCurrentLocked(expected, transform)
}

class GradeReminderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val state: Flow<GradeReminderState> = combine(
        appContext.gradeReminderDataStore.data,
        SessionStore(appContext).privateSnapshotAccess,
    ) { prefs, readable ->
        if (!readable) return@combine GradeReminderState()
        prefs[KEY_STATE]?.let { serialized ->
            runCatching { json.decodeFromString<GradeReminderState>(serialized) }.getOrNull()
        } ?: GradeReminderState()
    }.catch { error ->
        if (error !is IOException && error !is SessionStorageException) throw error
        DeveloperDiagnostics.recordEvent(appContext, "PrivacyCleanup", "REMINDER_STATE_UNAVAILABLE")
        emit(GradeReminderState())
    }

    suspend fun loadState(): GradeReminderState = state.first()

    internal suspend fun <T> mutate(block: suspend GradeReminderMutationScope.() -> T): T =
        mutationMutex.withLock { block.invoke(GradeReminderMutationScope(this)) }

    internal suspend fun writeStateLocked(state: GradeReminderState) {
        appContext.gradeReminderDataStore.edit { prefs ->
            prefs[KEY_STATE] = json.encodeToString(state)
        }
    }

    suspend fun clearLatestChangeSet() {
        mutate {
            val current = loadState()
            saveState(current.copy(latestChangeSet = null))
        }
    }

    internal suspend fun updateIfCurrentLocked(
        expected: GradeReminderIdentity,
        transform: (GradeReminderState) -> GradeReminderState,
    ): Boolean {
        var updated = false
        appContext.gradeReminderDataStore.edit { prefs ->
            val current = prefs[KEY_STATE]?.let { serialized ->
                runCatching { json.decodeFromString<GradeReminderState>(serialized) }.getOrNull()
            } ?: GradeReminderState()
            val next = mutateReminderStateIfCurrent(current, expected, transform) ?: return@edit
            prefs[KEY_STATE] = json.encodeToString(next)
            updated = true
        }
        return updated
    }

    private companion object {
        val mutationMutex = Mutex()
        val KEY_STATE = stringPreferencesKey("state")
    }
}
