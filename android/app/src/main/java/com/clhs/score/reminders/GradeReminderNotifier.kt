package com.clhs.score.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.clhs.score.MainActivity
import com.clhs.score.R
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.GradeReminderText
import com.clhs.score.notifications.NotificationChannels
import com.clhs.score.notifications.NotificationActionCapabilities
import com.clhs.score.notifications.canPostNotifications
import java.util.concurrent.atomic.AtomicInteger

class GradeReminderNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun showChangedNotification(changeSet: GradeChangeSet) {
        if (!appContext.canPostNotifications()) return
        NotificationChannels.ensureCreated(appContext)

        val notification = Notification.Builder(appContext, NotificationChannels.GRADE_REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(GradeReminderText.notificationTitle(changeSet))
            .setContentText(GradeReminderText.notificationBody(changeSet))
            .setStyle(Notification.BigTextStyle().bigText(GradeReminderText.notificationBody(changeSet)))
            .setContentIntent(openReminderIntent(changeSet.yearValue, changeSet.examValue))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(nextNotificationId(), notification)
    }

    fun showStoppedNotification(reason: String) {
        if (!appContext.canPostNotifications()) return
        NotificationChannels.ensureCreated(appContext)

        val notification = Notification.Builder(appContext, NotificationChannels.GRADE_REMINDERS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("段考更新提醒已停止")
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setContentIntent(openAppIntent())
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

        appContext.getSystemService(NotificationManager::class.java)
            .notify(nextNotificationId(), notification)
    }

    private fun openReminderIntent(yearValue: String, examValue: String): PendingIntent {
        val capabilityToken = NotificationActionCapabilities.issueGradeReminder(
            appContext,
            yearValue,
            examValue,
        )
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(
                NotificationActionCapabilities.EXTRA_CAPABILITY,
                capabilityToken,
            )
        }
        return PendingIntent.getActivity(
            appContext,
            capabilityToken.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            nextRequestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private val idCounter = AtomicInteger(3000)

        private fun nextNotificationId(): Int = idCounter.getAndIncrement()

        private fun nextRequestCode(): Int = idCounter.getAndIncrement()
    }
}
