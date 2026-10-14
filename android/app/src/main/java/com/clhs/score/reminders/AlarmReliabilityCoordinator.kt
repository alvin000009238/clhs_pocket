package com.clhs.score.reminders

import android.content.Context
import android.util.Log
import com.clhs.score.notifications.CurrentCourseNotifications
import com.clhs.score.notifications.CourseNotificationRefreshOutcome
import com.clhs.score.widget.syncAllScheduleWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Re-arms the two existing alarm owners after user or system policy changes. */
internal object AlarmReliabilityCoordinator {
    private val mutex = Mutex()
    private var lastState: AlarmScheduleState? = null

    suspend fun onAppResume(context: Context) = mutex.withLock {
        val appContext = context.applicationContext
        val state = readAlarmScheduleState(appContext)
        val previous = lastState
        if (shouldRearmAfterStateChange(previous, state)) {
            rearmBoth(appContext, state, "app_resume")
        } else {
            runCourseRefresh(appContext, "app_resume")
            lastState = state
        }
    }

    suspend fun onSystemStateChanged(context: Context) = mutex.withLock {
        val appContext = context.applicationContext
        val state = readAlarmScheduleState(appContext)
        val previous = lastState
        if (shouldRearmAfterStateChange(previous, state)) {
            rearmBoth(appContext, state, "settings_returned")
        } else {
            lastState = state
        }
    }

    suspend fun onExactAlarmPermissionChanged(context: Context) = mutex.withLock {
        val appContext = context.applicationContext
        val state = readAlarmScheduleState(appContext)
        val previous = lastState
        if (shouldRearmAfterStateChange(previous, state) && state.exactUserPreference == true) {
            rearmBoth(appContext, state, "exact_access_changed")
        }
    }

    suspend fun onPreferenceChanged(context: Context) = mutex.withLock {
        val appContext = context.applicationContext
        val state = readAlarmScheduleState(appContext)
        rearmBoth(appContext, state, "exact_preference_changed")
    }

    private fun shouldRearmAfterStateChange(
        previous: AlarmScheduleState?,
        current: AlarmScheduleState,
    ): Boolean {
        if (previous == null) return true
        if (previous.exactUserPreference != current.exactUserPreference) return true
        if (previous.batteryOptimizationExempt != current.batteryOptimizationExempt) return true
        return previous.exactSystemAccess != current.exactSystemAccess &&
            (previous.exactUserPreference == true || current.exactUserPreference == true)
    }

    private suspend fun rearmBoth(context: Context, state: AlarmScheduleState, reason: String) {
        // Invalidate even a forced re-arm of the same state until both owners complete.
        lastState = null
        val courseSucceeded = runCourseRefresh(context, reason)
        try {
            syncAllScheduleWidgets(context, alarmReason = reason)
            if (courseSucceeded) lastState = state
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("AlarmReliability", "Widget re-arm failed (${error.javaClass.simpleName})")
        }
    }

    private suspend fun runCourseRefresh(context: Context, reason: String): Boolean {
        return try {
            CurrentCourseNotifications.get(context).refresh() != CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("AlarmReliability", "Course alarm re-arm failed: reason=$reason (${error.javaClass.simpleName})")
            false
        }
    }
}
