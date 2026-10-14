package com.clhs.score.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.clhs.score.AlarmDebugLog
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.SCHOOL_COURSE_ZONE
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
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
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_UPDATE_WIDGET && !isWidgetRearmAction(action)) return
        val fromRecovery = action == ACTION_UPDATE_WIDGET && intent.getBooleanExtra(EXTRA_RECOVERY, false)

        AlarmDebugLog.event(context, "widget", "receiver_entry", "action" to action,
            "requestCode" to if (action == ACTION_UPDATE_WIDGET) REQUEST_CODE else null,
            "receiver" to javaClass.name)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var result = "success"
            try {
                AlarmDebugLog.event(context, "widget", "execution_start", "action" to action,
                    "receiver" to javaClass.name)
                withTimeout(8_000) { syncAllScheduleWidgets(context) }
            } catch (_: TimeoutCancellationException) {
                result = "timeout"
                if (!fromRecovery) scheduleRecoveryUpdate(context, "sync_timeout")
            } catch (error: CancellationException) {
                result = "cancelled"
                throw error
            } catch (error: Exception) {
                result = error.javaClass.simpleName
                Log.e("WidgetUpdateReceiver", "Widget synchronization failed (${error.javaClass.simpleName})")
                if (!fromRecovery) scheduleRecoveryUpdate(context, "sync_failure")
            } finally {
                AlarmDebugLog.event(context, "widget", "execution_end", "action" to action,
                    "receiver" to javaClass.name,
                    "durationMs" to (android.os.SystemClock.elapsedRealtime() - startedAt),
                    "result" to result, "success" to (result == "success"))
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "com.clhs.score.ACTION_UPDATE_WIDGET"
        private const val REQUEST_CODE = 0
        private const val BOUNDARY_DELAY_MILLIS = 5_000L
        private const val RECOVERY_DELAY_MILLIS = 5 * 60 * 1_000L
        private const val EXTRA_RECOVERY = "widget_recovery"
        private val alarmLock = Any()

        internal fun scheduleRecoveryUpdate(context: Context, reason: String) {
            runCatching {
                synchronized(alarmLock) {
                    if (activeWidgetIds(context).isEmpty()) return
                    val pending = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        fromRecovery = true)
                        ?: return
                    val target = System.currentTimeMillis() + RECOVERY_DELAY_MILLIS
                    context.getSystemService(AlarmManager::class.java)
                        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                    AlarmDebugLog.event(context, "widget", "schedule", "reason" to reason,
                        "targetEpoch" to target, "result" to "recovery")
                }
            }.onFailure { Log.e("WidgetUpdateReceiver", "Unable to schedule widget recovery", it) }
        }

        suspend fun scheduleNextUpdate(
            context: Context,
            report: ScheduleReport?,
            now: Instant = Instant.now(),
            reason: String = "widget_sync",
        ) {
            if (!shouldScheduleWidgetAlarm(activeWidgetIds(context).size)) {
                cancelUpdate(context, "no_active_widget")
                return
            }

            val scheduleState = readAlarmScheduleState(context)
            val boundary = nextScheduleWidgetUpdateAt(report, now)
            val target = boundary.toEpochMilli() + BOUNDARY_DELAY_MILLIS
            synchronized(alarmLock) {
                val activeCount = activeWidgetIds(context).size
                if (!shouldScheduleWidgetAlarm(activeCount)) {
                    cancelUpdateLocked(context, "no_active_widget")
                    return@synchronized
                }
                val pending = pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    ?: return@synchronized
                val alarmManager = context.getSystemService(AlarmManager::class.java)
                var effectiveState = scheduleState
                var precision = selectCourseAlarmPrecision(
                    scheduleState.exactUserPreference,
                    scheduleState.exactSystemAccess,
                    CourseAlarmPurpose.BOUNDARY,
                    Build.VERSION.SDK_INT,
                )
                try {
                    try {
                        if (precision == CourseAlarmPrecision.EXACT) {
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                        } else {
                            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                        }
                    } catch (error: SecurityException) {
                        if (precision != CourseAlarmPrecision.EXACT) throw error
                        effectiveState = scheduleState.copy(exactSystemAccess = false)
                        precision = CourseAlarmPrecision.INEXACT
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pending)
                    }
                    logSchedule(context, reason, target, boundary, activeCount, report != null,
                        effectiveState, precision, "success")
                } catch (error: Exception) {
                    logSchedule(context, reason, target, boundary, activeCount, report != null,
                        effectiveState, precision, error.javaClass.simpleName)
                    throw error
                }
            }
        }

        private fun logSchedule(
            context: Context,
            reason: String,
            target: Long,
            boundary: Instant,
            activeCount: Int,
            dataAvailable: Boolean,
            state: AlarmScheduleState,
            precision: CourseAlarmPrecision,
            result: String,
        ) {
            AlarmDebugLog.event(context, "widget", "schedule", "reason" to reason,
                "action" to ACTION_UPDATE_WIDGET, "requestCode" to REQUEST_CODE,
                "receiver" to WidgetUpdateReceiver::class.java.name,
                "api" to if (precision == CourseAlarmPrecision.EXACT) "setExactAndAllowWhileIdle" else "setAndAllowWhileIdle",
                "precision" to precision.name.lowercase(),
                "exactUserPreference" to (state.exactUserPreference?.toString() ?: "unknown"),
                "exactSystemAccess" to state.exactSystemAccess,
                "batteryOptimizationExempt" to state.batteryOptimizationExempt,
                "alarmPurpose" to CourseAlarmPurpose.BOUNDARY.name.lowercase(),
                "targetEpoch" to target,
                "targetLocal" to Instant.ofEpochMilli(target).atZone(SCHOOL_COURSE_ZONE),
                "targetMinusNowMs" to (target - System.currentTimeMillis()),
                "dataAvailable" to dataAvailable, "nextBoundary" to boundary,
                "activeWidgetCount" to activeCount,
                "result" to result, "samePendingIntentReplaced" to true)
        }

        fun cancelUpdate(context: Context, reason: String = "widget_removed") = synchronized(alarmLock) {
            cancelUpdateLocked(context, reason)
        }

        private fun cancelUpdateLocked(context: Context, reason: String) {
            val pending = pendingIntent(context, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            var result = "not_registered"
            if (pending != null) {
                val alarmCancel = runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending) }
                val pendingCancel = runCatching { pending.cancel() }
                val failure = alarmCancel.exceptionOrNull() ?: pendingCancel.exceptionOrNull()
                result = failure?.javaClass?.simpleName ?: "success"
            }
            AlarmDebugLog.event(context, "widget", "cancel", "reason" to reason,
                "action" to ACTION_UPDATE_WIDGET, "requestCode" to REQUEST_CODE,
                "receiver" to WidgetUpdateReceiver::class.java.name,
                "api" to "AlarmManager.cancel", "targetEpoch" to null,
                "targetLocal" to null, "targetMinusNowMs" to null,
                "dataAvailable" to false, "nextBoundary" to null,
                "activeWidgetCount" to activeWidgetIds(context).size, "result" to result)
        }

        private fun pendingIntent(context: Context, flags: Int, fromRecovery: Boolean = false): PendingIntent? {
            val intent = Intent(context, WidgetUpdateReceiver::class.java).setAction(ACTION_UPDATE_WIDGET)
                .putExtra(EXTRA_RECOVERY, fromRecovery)
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }

        private fun activeWidgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ScheduleWidgetReceiver::class.java))
    }
}

internal fun isWidgetRearmAction(action: String): Boolean = action in setOf(
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIME_CHANGED,
    Intent.ACTION_TIMEZONE_CHANGED,
)

internal fun shouldScheduleWidgetAlarm(activeWidgetCount: Int): Boolean = activeWidgetCount > 0

internal fun nextScheduleWidgetUpdateAt(report: ScheduleReport?, now: Instant): Instant {
    val schoolNow = now.atZone(SCHOOL_COURSE_ZONE).toLocalDateTime()
    return nextScheduleWidgetLocalBoundary(report, schoolNow).atZone(SCHOOL_COURSE_ZONE).toInstant()
}

private fun nextScheduleWidgetLocalBoundary(report: ScheduleReport?, now: LocalDateTime): LocalDateTime {
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
    val nextCourseBoundary = report
        ?.nextKnownWidgetBoundaries(now)
        ?.minOrNull()
        ?: nextMidnight
    return minOf(nextMidnight, nextCourseBoundary)
}

private fun ScheduleReport.nextKnownWidgetBoundaries(now: LocalDateTime): Sequence<LocalDateTime> =
    items.asSequence().flatMap { item ->
        val dayOfWeek = runCatching { DayOfWeek.of(item.dayOfWeek) }.getOrNull()
            ?: return@flatMap emptySequence()
        val periodTime = PERIOD_TIMES.getOrNull(item.period - 1)
            ?: return@flatMap emptySequence()
        val date = nextWidgetDate(dayOfWeek, now)
            ?: return@flatMap emptySequence()
        sequenceOf(periodTime.start, periodTime.end)
            .mapNotNull { value -> runCatching { LocalTime.parse(value) }.getOrNull() }
            .mapNotNull { time ->
                val candidate = date.atTime(time)
                val next = if (scope == ScheduleScope.SEMESTER && !candidate.isAfter(now)) {
                    candidate.plusWeeks(1)
                } else {
                    candidate
                }
                next.takeIf { it.isAfter(now) }
            }
    }

private fun ScheduleReport.nextWidgetDate(
    dayOfWeek: DayOfWeek,
    now: LocalDateTime,
) : LocalDate? {
    return when (scope) {
        ScheduleScope.SEMESTER -> now.toLocalDate().with(TemporalAdjusters.nextOrSame(dayOfWeek))
        ScheduleScope.CURRENT_WEEK -> {
            val start = weekStartDate
                ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                ?: return null
            val end = weekEndDate
                ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                ?: return null
            if (end < start) return null
            start.with(TemporalAdjusters.nextOrSame(dayOfWeek)).takeIf { it <= end }
        }
    }
}
