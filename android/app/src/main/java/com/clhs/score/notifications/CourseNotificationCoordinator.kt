package com.clhs.score.notifications

import com.clhs.score.data.CourseNotificationPreferences
import com.clhs.score.data.CourseResolution
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.resolveCourses
import com.clhs.score.data.SCHOOL_COURSE_ZONE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock

internal enum class CourseNotificationRefreshOutcome { SUCCESS, TERMINAL, TRANSIENT_FAILURE }

internal fun shouldScheduleCourseRecovery(
    enabled: Boolean,
    capturedInvalidation: Long,
    currentInvalidation: Long,
    capturedGeneration: Long,
    currentGeneration: Long,
    sessionRevoked: Boolean = false,
): Boolean = enabled && !sessionRevoked && capturedInvalidation == currentInvalidation && capturedGeneration == currentGeneration

/** Serializes opt-in, dismissal, and snapshot commits with publication. No Android or network I/O. */
internal class CourseNotificationCoordinator(
    private val preferences: CourseNotificationPreferences,
    private val clock: Clock,
    private val loadReport: suspend () -> ScheduleReport?,
    private val canPost: () -> Boolean,
    private val render: suspend (CourseResolution, Long) -> Unit,
    private val cancel: () -> Unit,
    private val scheduleRecovery: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private var lastKnownEnabled = false

    suspend fun available(): Boolean = mutex.withLock { resolveCourses(loadReport(), clock).valid }

    suspend fun setEnabled(enabled: Boolean): String? = mutex.withLock {
        if (!enabled) {
            try {
                preferences.setEnabled(false)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                lastKnownEnabled = false
                cancel()
                throw error
            }
            lastKnownEnabled = false
            cancel()
            return@withLock null
        }
        if (!resolveCourses(loadReport(), clock).valid) return@withLock "請先取得有效週課表或學期課表"
        if (!canPost()) return@withLock "需要開啟通知權限"
        preferences.setEnabled(true)
        lastKnownEnabled = true
        refreshLocked(fromRecovery = false)
        null
    }

    suspend fun refresh(fromRecovery: Boolean = false): CourseNotificationRefreshOutcome =
        mutex.withLock { refreshLocked(fromRecovery) }

    suspend fun setRemindBeforeClass(enabled: Boolean): String? = mutex.withLock {
        preferences.setRemindBeforeClass(enabled)
        refreshLocked(fromRecovery = false)
        null
    }

    suspend fun closeToday(generation: Long): CourseNotificationRefreshOutcome = mutex.withLock {
        val preference = try {
            preferences.state.first()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // An explicit close must not leave a recovery alarm behind when preferences are unavailable.
            lastKnownEnabled = false
            cancel()
            return@withLock CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
        }
        lastKnownEnabled = preference.enabled
        if (preference.enabled && preference.generation == generation) {
            try {
                preferences.pauseUntil(clock.instant().atZone(SCHOOL_COURSE_ZONE).toLocalDate()
                    .plusDays(1).atStartOfDay(SCHOOL_COURSE_ZONE).toInstant().toEpochMilli())
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                lastKnownEnabled = false
                cancel()
                return@withLock CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
            }
            cancel()
            refreshLocked(fromRecovery = false)
        } else {
            refreshLocked(fromRecovery = false)
        }
    }

    suspend fun commit(block: suspend () -> Unit) = mutex.withLock {
        cancel()
        block()
        refreshLocked(fromRecovery = false)
    }

    suspend fun receiverTimedOut(fromRecovery: Boolean): CourseNotificationRefreshOutcome = mutex.withLock {
        val enabled = try {
            withTimeoutOrNull(1_000) { preferences.state.first().enabled } ?: lastKnownEnabled
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            lastKnownEnabled
        }
        lastKnownEnabled = enabled
        if (!enabled) {
            cancel()
            return@withLock CourseNotificationRefreshOutcome.TERMINAL
        }
        if (!fromRecovery) scheduleRecoverySafely()
        CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
    }

    private suspend fun refreshLocked(fromRecovery: Boolean): CourseNotificationRefreshOutcome {
        try {
            val pref = preferences.state.first()
            lastKnownEnabled = pref.enabled
            if (!pref.enabled || !canPost()) {
                cancel()
                return CourseNotificationRefreshOutcome.TERMINAL
            }
            repeat(2) {
                val report = loadReport()
                val result = if (pref.pausedUntil > clock.millis() && report != null) {
                    CourseResolution(nextBoundary = java.time.Instant.ofEpochMilli(pref.pausedUntil), valid = true)
                } else resolveCourses(report, clock, pref.remindBeforeClass)
                render(result, pref.generation)
                if (!result.valid) return CourseNotificationRefreshOutcome.TERMINAL
                if (result.nextBoundary == null || result.nextBoundary > clock.instant()) {
                    return CourseNotificationRefreshOutcome.SUCCESS
                }
            }
            // A clock repeatedly jumping during publication must not create a past-alarm loop.
            cancel()
            return CourseNotificationRefreshOutcome.TERMINAL
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            if (lastKnownEnabled && !fromRecovery) scheduleRecoverySafely()
            return CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
        }
    }

    private suspend fun scheduleRecoverySafely() {
        try {
            scheduleRecovery()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // A failed recovery registration is best-effort; the next foreground or system event retries.
        }
    }
}
