package com.clhs.score.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.clhs.score.R

object NotificationChannels {
    const val UPDATES_CHANNEL_ID = "score_updates"
    const val GRADE_REMINDERS_CHANNEL_ID = "grade_reminders"
    const val SCHOOL_ANNOUNCEMENTS_CHANNEL_ID = "school_announcements"

    fun ensureCreated(context: Context) {
        val updatesChannel = NotificationChannel(
            UPDATES_CHANNEL_ID,
            context.getString(R.string.notification_channel_updates_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_updates_description)
        }
        val gradeRemindersChannel = NotificationChannel(
            GRADE_REMINDERS_CHANNEL_ID,
            context.getString(R.string.notification_channel_grade_reminders_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_grade_reminders_description)
        }
        val schoolAnnouncementsChannel = NotificationChannel(
            SCHOOL_ANNOUNCEMENTS_CHANNEL_ID,
            context.getString(R.string.notification_channel_school_announcements_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_school_announcements_description)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(updatesChannel)
        manager.createNotificationChannel(gradeRemindersChannel)
        manager.createNotificationChannel(schoolAnnouncementsChannel)
    }
}
