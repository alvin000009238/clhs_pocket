package com.clhs.score.notifications

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clhs.score.AlarmDebugLog
import com.clhs.score.MainActivity
import com.clhs.score.R
import com.clhs.score.data.*
import com.clhs.score.reminders.AlarmScheduleState
import com.clhs.score.reminders.CourseAlarmPrecision
import com.clhs.score.reminders.CourseAlarmPurpose
import com.clhs.score.reminders.readAlarmScheduleState
import com.clhs.score.reminders.selectCourseAlarmPrecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

/** One process-wide owner; receivers only read committed local data, never school credentials. */
internal class CurrentCourseNotifications private constructor(private val context: Application) {
    private val publicationLock = Any()
    private var invalidation = 0L
    private val preferences = CourseNotificationPreferences(context)
    val state = preferences.state
    private val cache = GradeCacheStore(context)
    private val session = SessionStore(context)
    private val clock = Clock.systemUTC()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun canPost(): Boolean = try {
        ensureChannel()
        context.canPostNotifications() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    } catch (_: Exception) {
        DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "PERMISSION_CHECK_FAILED")
        false
    }

    private var readAuthority = 0L
    private var readRevision = 0L
    private val coordinator = CourseNotificationCoordinator(
        preferences, clock,
        loadReport = {
            readAuthority = session.requestGeneration()
            readRevision = synchronized(publicationLock) { invalidation }
            cache.courseNotificationScheduleReportFlow().first()
        },
        canPost = ::canPost,
        render = { result, generation ->
            val target = result.nextBoundary?.toEpochMilli()?.takeIf { it > clock.millis() }
            val alarmState = if (target != null) readAlarmScheduleState(context) else null
            synchronized(publicationLock) {
                if (readRevision == invalidation) {
                    val published = session.withSnapshotGeneration(readAuthority) {
                        if (target != null) {
                            logAlarm("reschedule", "course_refresh", target, result.valid, result.nextBoundary)
                        }
                        cancelAlarm(
                            reason = if (target == null) "no_future_boundary" else "reschedule",
                            dataAvailable = result.valid,
                            nextBoundary = result.nextBoundary,
                        )
                        AlarmDebugLog.event(context, "course_notification", "resolved",
                            "currentCourse" to (result.current != null),
                            "currentPeriod" to result.current?.item?.period,
                            "nextBoundary" to result.nextBoundary,
                            "dataAvailable" to result.valid,
                        )
                        val course = result.current ?: result.upcoming
                        if (course == null) cancelNotification("no_current_course")
                        else publish(course, generation, result.nextBoundary, result.valid,
                            upcoming = result.current == null)
                        if (target != null && alarmState != null) {
                            scheduleAlarm(
                                target, "course_boundary", result.valid, result.nextBoundary,
                                CourseAlarmPurpose.BOUNDARY, alarmState,
                            )
                        }
                    }
                    if (!published) cancelOutputs("session_generation_changed")
                } else {
                    AlarmDebugLog.event(context, "course_notification", "publish_skipped",
                        "reason" to "invalidation_changed")
                }
            }
        },
        cancel = { safeCancel() },
        scheduleRecovery = ::scheduleRecoveryAlarm,
    )

    suspend fun available(): Boolean = coordinator.available()
    suspend fun setEnabled(enabled: Boolean): String? = guarded { coordinator.setEnabled(enabled) }
    suspend fun setRemindBeforeClass(enabled: Boolean): String? = guarded { coordinator.setRemindBeforeClass(enabled) }
    suspend fun refresh(fromRecovery: Boolean = false): CourseNotificationRefreshOutcome =
        coordinator.refresh(fromRecovery)
    suspend fun close(generation: Long): CourseNotificationRefreshOutcome = coordinator.closeToday(generation)
    suspend fun receiverTimedOut(fromRecovery: Boolean): CourseNotificationRefreshOutcome =
        coordinator.receiverTimedOut(fromRecovery)
    // Do not swallow persistence/privacy deletion errors from the repository's commit.
    suspend fun commitSnapshot(block: suspend () -> Unit) {
        var committed = false
        try {
            coordinator.commit { block(); committed = true }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!committed) throw error
            safeCancel()
            DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "POST_COMMIT_SYNC_FAILED")
        }
    }

    /** Synchronous revocation wins against an in-flight publisher before private cache cleanup. */
    fun invalidate() = synchronized(publicationLock) {
        invalidation++
        safeCancel("session_invalidated")
    }

    private fun safeCancel(reason: String = "terminal") {
        runCatching { synchronized(publicationLock) { cancelOutputs(reason) } }
            .onFailure { DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "CANCEL_FAILED") }
    }

    private suspend fun scheduleRecoveryAlarm() {
        val alarmState = readAlarmScheduleState(context)
        synchronized(publicationLock) {
            val maySchedule = shouldScheduleCourseRecovery(
                enabled = true,
                capturedInvalidation = readRevision,
                currentInvalidation = invalidation,
                capturedGeneration = readAuthority,
                currentGeneration = session.requestGeneration(),
                sessionRevoked = session.authorizationRevoked.value,
            )
            if (!maySchedule) {
                AlarmDebugLog.event(context, "course_notification", "schedule_skipped",
                    "reason" to "disabled_or_invalidated", "action" to BOUNDARY,
                    "requestCode" to REQUEST_CODE, "receiver" to CourseNotificationReceiver::class.java.name)
                return@synchronized
            }
            val target = clock.millis() + RECOVERY_DELAY_MILLIS
            scheduleAlarm(
                target, "transient_failure_recovery", dataAvailable = false, nextBoundary = null,
                CourseAlarmPurpose.RECOVERY, alarmState,
            )
        }
    }

    private fun scheduleAlarm(
        target: Long,
        reason: String,
        dataAvailable: Boolean,
        nextBoundary: Instant?,
        purpose: CourseAlarmPurpose,
        state: AlarmScheduleState,
    ) {
        var precision = selectCourseAlarmPrecision(
            state.exactUserPreference, state.exactSystemAccess, purpose, Build.VERSION.SDK_INT,
        )
        var effectiveState = state
        try {
            val pending = boundaryIntent(recovery = purpose == CourseAlarmPurpose.RECOVERY)
            if (precision == CourseAlarmPrecision.EXACT) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                } catch (error: SecurityException) {
                    effectiveState = state.copy(exactSystemAccess = false)
                    precision = CourseAlarmPrecision.INEXACT
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                    logAlarm("schedule", reason, target, dataAvailable, nextBoundary,
                        purpose, precision, effectiveState, "exact_access_revoked_fallback")
                    return
                }
            } else {
                // Default and recovery alarms remain best-effort inexact alarms.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
            }
            logAlarm("schedule", reason, target, dataAvailable, nextBoundary,
                purpose, precision, effectiveState, "success")
        } catch (error: Exception) {
            logAlarm("schedule", reason, target, dataAvailable, nextBoundary,
                purpose, precision, effectiveState, error.javaClass.simpleName)
            throw error
        }
    }

    private fun logAlarm(
        event: String,
        reason: String,
        target: Long,
        dataAvailable: Boolean,
        nextBoundary: Instant?,
        purpose: CourseAlarmPurpose? = null,
        precision: CourseAlarmPrecision? = null,
        state: AlarmScheduleState? = null,
        result: String = "success",
    ) {
        AlarmDebugLog.event(context, "course_notification", event,
            "reason" to reason,
            "action" to BOUNDARY,
            "requestCode" to REQUEST_CODE,
            "receiver" to CourseNotificationReceiver::class.java.name,
            "api" to when (precision) {
                CourseAlarmPrecision.EXACT -> "setExactAndAllowWhileIdle"
                CourseAlarmPrecision.INEXACT -> "setAndAllowWhileIdle"
                null -> "policy-selected"
            },
            "precision" to precision?.name?.lowercase(),
            "exactUserPreference" to (state?.exactUserPreference?.toString() ?: "unknown"),
            "exactSystemAccess" to state?.exactSystemAccess,
            "batteryOptimizationExempt" to state?.batteryOptimizationExempt,
            "alarmPurpose" to purpose?.name?.lowercase(),
            "recovery" to (purpose == CourseAlarmPurpose.RECOVERY),
            "targetEpoch" to target,
            "targetLocal" to Instant.ofEpochMilli(target).atZone(SCHOOL_COURSE_ZONE),
            "targetMinusNowMs" to (target - System.currentTimeMillis()),
            "dataAvailable" to dataAvailable,
            "nextBoundary" to nextBoundary,
            "result" to result,
        )
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "當前課程通知", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }
    }

    @SuppressLint("MissingPermission") // canPost checks runtime permission and app/channel blocking.
    private fun publish(course: CourseOccurrence, generation: Long, nextBoundary: Instant?, dataAvailable: Boolean,
        upcoming: Boolean = false) {
        if (!canPost()) { cancelNotification("permission_unavailable"); return }
        val remaining = course.end.toEpochMilli() - clock.millis()
        if (remaining <= 0) { cancelNotification("course_already_ended"); return }
        val click = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, NotificationActionCapabilities.issueSchedule(context))
        }
        val delete = Intent(context, CourseNotificationReceiver::class.java).apply {
            action = DELETE
        }
        val close = Intent(context, CourseNotificationReceiver::class.java).apply {
            action = CLOSE
            putExtra(EXTRA_GENERATION, generation)
        }
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(SCHOOL_COURSE_ZONE)
        val text = "${time.format(course.start)}–${time.format(course.end)}" +
            course.item.classroom.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_current_course)
            .setColor(ContextCompat.getColor(context,
                if (upcoming) R.color.course_notification_upcoming_yellow else R.color.clhs_pocket_icon_blue))
            .setContentTitle(if (upcoming) "即將上課 · 第 ${course.item.period} 節" else "第 ${course.item.period} 節")
            .setContentText("${course.item.subjectName} · $text")
            .setContentIntent(PendingIntent.getActivity(context, ID, click, FLAGS))
            .setDeleteIntent(PendingIntent.getBroadcast(context, ID, delete, FLAGS))
            .addAction(R.drawable.ic_stat_current_course, "暫停一日",
                PendingIntent.getBroadcast(context, ID + 1, close, FLAGS))
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(course.item.subjectName)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen((if (upcoming) course.start else course.end).toEpochMilli()).setShowWhen(true)
            .setUsesChronometer(true).setChronometerCountDown(true).setTimeoutAfter(remaining)
            .build().apply { flags = flags or Notification.FLAG_NO_CLEAR }
        try {
            manager.notify(ID, notification)
            AlarmDebugLog.event(context, "course_notification", "notification_result",
                "operation" to "notify", "result" to "success", "currentCourse" to true,
                "currentPeriod" to course.item.period, "nextBoundary" to nextBoundary,
                "dataAvailable" to dataAvailable)
        } catch (error: Exception) {
            AlarmDebugLog.event(context, "course_notification", "notification_result",
                "operation" to "notify", "result" to error.javaClass.simpleName,
                "currentCourse" to true, "currentPeriod" to course.item.period,
                "nextBoundary" to nextBoundary, "dataAvailable" to dataAvailable)
            throw error
        }
    }

    private fun boundaryIntent(recovery: Boolean = false) = PendingIntent.getBroadcast(context, REQUEST_CODE,
        Intent(context, CourseNotificationReceiver::class.java).setAction(BOUNDARY)
            .putExtra(EXTRA_RECOVERY, recovery), FLAGS)

    private fun cancelAlarm(reason: String, dataAvailable: Boolean = false, nextBoundary: Instant? = null) {
        try {
            alarms.cancel(boundaryIntent())
            AlarmDebugLog.event(context, "course_notification", "cancel",
                "reason" to reason, "action" to BOUNDARY, "requestCode" to REQUEST_CODE,
                "receiver" to CourseNotificationReceiver::class.java.name,
                "api" to "AlarmManager.cancel", "targetEpoch" to null,
                "targetLocal" to null, "targetMinusNowMs" to null,
                "dataAvailable" to dataAvailable, "nextBoundary" to nextBoundary,
                "result" to "success")
        } catch (error: Exception) {
            AlarmDebugLog.event(context, "course_notification", "cancel",
                "reason" to reason, "action" to BOUNDARY, "requestCode" to REQUEST_CODE,
                "receiver" to CourseNotificationReceiver::class.java.name,
                "api" to "AlarmManager.cancel", "targetEpoch" to null,
                "targetLocal" to null, "targetMinusNowMs" to null,
                "dataAvailable" to dataAvailable, "nextBoundary" to nextBoundary,
                "result" to error.javaClass.simpleName)
            throw error
        }
    }

    private fun cancelNotification(reason: String) {
        var success = false
        try {
            manager.cancel(ID)
            success = true
        } finally {
            AlarmDebugLog.event(context, "course_notification", "notification_result",
                "operation" to "cancel", "reason" to reason,
                "result" to if (success) "success" else "failure",
                "currentCourse" to false)
        }
    }

    private fun cancelOutputs(reason: String) {
        // Attempt both even when an OEM alarm service fails.
        try { cancelNotification(reason) } finally { cancelAlarm(reason) }
    }

    private suspend fun guarded(block: suspend () -> String?): String? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        runCatching { synchronized(publicationLock) { cancelOutputs("refresh_exception") } }
        DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "LOCAL_SYNC_FAILED")
        "當前課程通知更新失敗，請稍後重試"
    }

    companion object {
        private const val ID = 42031
        internal const val REQUEST_CODE = ID
        private const val RECOVERY_DELAY_MILLIS = 5 * 60 * 1_000L
        private const val CHANNEL = "current_course_v1"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        const val BOUNDARY = "com.clhs.score.CURRENT_COURSE_BOUNDARY"
        const val DELETE = "com.clhs.score.CURRENT_COURSE_DELETE"
        const val CLOSE = "com.clhs.score.CURRENT_COURSE_CLOSE"
        const val EXTRA_GENERATION = "course_notification_generation"
        const val EXTRA_RECOVERY = "course_notification_recovery"
        @Volatile private var instance: CurrentCourseNotifications? = null
        fun get(context: Context): CurrentCourseNotifications = instance ?: synchronized(this) {
            instance ?: CurrentCourseNotifications(context.applicationContext as Application).also { instance = it }
        }
    }
}

class CourseNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(CurrentCourseNotifications.BOUNDARY, CurrentCourseNotifications.DELETE,
                CurrentCourseNotifications.CLOSE,
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val action = intent.action.orEmpty()
        val fromRecovery = intent.getBooleanExtra(CurrentCourseNotifications.EXTRA_RECOVERY, false)
        AlarmDebugLog.event(context, "course_notification", "receiver_entry",
            "action" to action, "requestCode" to if (action == CurrentCourseNotifications.BOUNDARY) CurrentCourseNotifications.REQUEST_CODE else null,
            "receiver" to javaClass.name, "recovery" to fromRecovery)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val executionStart = android.os.SystemClock.elapsedRealtime()
            var outcome = "failure"
            var success = false
            var owner: CurrentCourseNotifications? = null
            try {
                AlarmDebugLog.event(context, "course_notification", "execution_start",
                    "action" to action, "receiver" to javaClass.name, "recovery" to fromRecovery)
                withTimeout(8_000) {
                    val currentOwner = CurrentCourseNotifications.get(context)
                    owner = currentOwner
                    val result = when (action) {
                        CurrentCourseNotifications.CLOSE ->
                            currentOwner.close(intent.getLongExtra(CurrentCourseNotifications.EXTRA_GENERATION, -1L))
                        else -> currentOwner.refresh(fromRecovery)
                    }
                    outcome = result.name.lowercase()
                    success = result != CourseNotificationRefreshOutcome.TRANSIENT_FAILURE
                }
            } catch (_: TimeoutCancellationException) {
                outcome = "timeout"
                owner?.let { withTimeoutOrNull(1_000) { it.receiverTimedOut(fromRecovery) } }
                DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "RECEIVER_SYNC_INCOMPLETE")
            } catch (error: CancellationException) {
                outcome = "cancelled"
                throw error
            } catch (error: Exception) {
                outcome = error.javaClass.simpleName
                DeveloperDiagnostics.recordEvent(context, "CurrentCourse", "RECEIVER_SYNC_INCOMPLETE")
            } finally {
                val duration = android.os.SystemClock.elapsedRealtime() - executionStart
                AlarmDebugLog.event(context, "course_notification", "execution_end",
                    "action" to action, "receiver" to javaClass.name, "durationMs" to duration,
                    "result" to outcome, "success" to success, "timeout" to (outcome == "timeout"),
                    "recovery" to fromRecovery)
                pending.finish()
            }
        }
    }
}
