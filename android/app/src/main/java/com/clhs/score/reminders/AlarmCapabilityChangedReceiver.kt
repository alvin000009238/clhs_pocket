package com.clhs.score.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.clhs.score.AlarmDebugLog
import com.clhs.score.notifications.CurrentCourseNotifications
import com.clhs.score.widget.WidgetUpdateReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class AlarmCapabilityChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXACT_ALARM_PERMISSION_CHANGED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmDebugLog.event(context, "alarm_reliability", "capability_changed", "action" to intent.action)
                withTimeout(8_000) {
                    AlarmReliabilityCoordinator.onExactAlarmPermissionChanged(context)
                }
            } catch (_: TimeoutCancellationException) {
                Log.w("AlarmReliability", "Capability reconciliation timed out")
                WidgetUpdateReceiver.scheduleRecoveryUpdate(context, "capability_sync_timeout")
                withTimeoutOrNull(1_000) {
                    CurrentCourseNotifications.get(context).receiverTimedOut(fromRecovery = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("AlarmReliability", "Capability reconciliation failed (${error.javaClass.simpleName})")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
