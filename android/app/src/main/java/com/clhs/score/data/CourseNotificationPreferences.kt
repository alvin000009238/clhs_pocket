package com.clhs.score.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.courseNotificationStore by preferencesDataStore("current_course_notification")

internal data class CourseNotificationPreference(
    val enabled: Boolean = false,
    val generation: Long = 0,
    val pausedUntil: Long = 0,
    val remindBeforeClass: Boolean = false,
)

internal class CourseNotificationPreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.courseNotificationStore)
    val state = store.data.map {
        CourseNotificationPreference(it[ENABLED] ?: false, it[GENERATION] ?: 0, it[PAUSED_UNTIL] ?: 0,
            it[REMIND_BEFORE_CLASS] ?: false)
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit {
            it[ENABLED] = enabled
            it[GENERATION] = (it[GENERATION] ?: 0) + 1
            it.remove(PAUSED_UNTIL)
        }
    }

    suspend fun pauseUntil(until: Long) {
        store.edit {
            it[PAUSED_UNTIL] = until
            it[GENERATION] = (it[GENERATION] ?: 0) + 1
        }
    }

    suspend fun setRemindBeforeClass(enabled: Boolean) {
        store.edit {
            it[REMIND_BEFORE_CLASS] = enabled
            it[GENERATION] = (it[GENERATION] ?: 0) + 1
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val GENERATION = longPreferencesKey("generation")
        val PAUSED_UNTIL = longPreferencesKey("paused_until")
        val REMIND_BEFORE_CLASS = booleanPreferencesKey("remind_before_class")
    }
}
