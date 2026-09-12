package com.clhs.score.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clhs.score.data.GradeCacheStore
import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class WidgetUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UPDATE_WIDGET) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scheduleNextUpdate(context)
                syncAllScheduleWidgets(context)
            } catch (e: Exception) {
                Log.e("WidgetUpdateReceiver", "Failed to update widget", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WIDGET = "com.clhs.score.ACTION_UPDATE_WIDGET"
        
        suspend fun scheduleNextUpdate(context: Context) {
            scheduleNextUpdate(context, GradeCacheStore(context).loadWidgetScheduleReport())
        }

        fun scheduleNextUpdate(
            context: Context,
            report: ScheduleReport?,
            now: LocalDateTime = LocalDateTime.now(),
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetUpdateReceiver::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextTriggerMillis = nextScheduleWidgetUpdateAt(report, now)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() + 5_000L

            try {
                // 課程邊界需要在休眠時更新；一般 RTC alarm 會等裝置醒來才處理。
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerMillis,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("WidgetUpdateReceiver", "Failed to schedule alarm", e)
            }
        }
        
        fun cancelUpdate(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WidgetUpdateReceiver::class.java).apply {
                action = ACTION_UPDATE_WIDGET
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
    }
}

internal fun nextScheduleWidgetUpdateAt(
    report: ScheduleReport?,
    now: LocalDateTime,
): LocalDateTime {
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
): LocalDate? {
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
