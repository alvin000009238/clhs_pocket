package com.clhs.score.reminders

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clhs.score.MainActivity
import com.clhs.score.R
import com.clhs.score.data.ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES
import com.clhs.score.data.AnnouncementReminderRepository
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.loadAnnouncementReminderSnapshot
import com.clhs.score.notifications.NotificationActionCapabilities
import com.clhs.score.notifications.NotificationAction
import com.clhs.score.notifications.NotificationChannels
import com.clhs.score.notifications.canPostNotifications
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnnouncementReminderScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule(intervalMinutes: Int) {
        require(intervalMinutes in ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES)
        val request = PeriodicWorkRequestBuilder<AnnouncementReminderWorker>(
            intervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "school_announcement_poll"
        const val WORK_TAG = "school_announcement"
    }
}

class AnnouncementReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val reminderRepository = AnnouncementReminderRepository(appContext)
    private val announcementsRepository = NetworkSchoolAnnouncementsRepository(appContext.cacheDir)
    private val notifier = AnnouncementReminderNotifier(appContext)

    override suspend fun doWork(): Result {
        val state = try {
            reminderRepository.loadState()
        } catch (_: IOException) {
            return Result.retry()
        }
        if (!state.enabled) return Result.success()
        return try {
            if (!applicationContext.canPostNotifications()) {
                reminderRepository.setEnabled(false)
                AnnouncementReminderScheduler(applicationContext).cancel()
                return Result.success()
            }
            val announcements = loadAnnouncementReminderSnapshot(
                selectedUnitIds = state.selectedUnitIds,
                knownIds = state.knownIds,
                loadPage = { pageIndex, unitId ->
                    announcementsRepository.loadPage(pageIndex = pageIndex, unitId = unitId)
                },
            )
            val newAnnouncements = reminderRepository.recordCheck(
                expectedUnitIds = state.selectedUnitIds,
                announcements = announcements,
                checkedAtMillis = System.currentTimeMillis(),
            )
            if (newAnnouncements.isNotEmpty()) notifier.show(newAnnouncements)
            Result.success()
        } catch (_: IOException) {
            Result.retry()
        }
    }
}

private class AnnouncementReminderNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun show(announcements: List<SchoolAnnouncement>) {
        if (!appContext.canPostNotifications()) return
        NotificationChannels.ensureCreated(appContext)
        val distinct = announcements.distinctBy(SchoolAnnouncement::id)
        val title = "${distinct.size} 則新學校公告"
        val notification = Notification.Builder(
            appContext,
            NotificationChannels.SCHOOL_ANNOUNCEMENTS_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(distinct.first().title)
            .setStyle(
                Notification.InboxStyle()
                    .also { style -> distinct.take(5).forEach { style.addLine(it.title) } }
            )
            .setContentIntent(openAnnouncementsIntent(distinct))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun openAnnouncementsIntent(announcements: List<SchoolAnnouncement>): PendingIntent {
        val target = announcementNotificationAction(announcements)
        val token = if (target is NotificationAction.OpenAnnouncement) {
            NotificationActionCapabilities.issueAnnouncement(appContext, target.id, target.category)
        } else {
            NotificationActionCapabilities.issueAnnouncements(appContext)
        }
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token)
        }
        return PendingIntent.getActivity(
            appContext,
            token.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 3_200
    }
}

internal fun announcementNotificationAction(announcements: List<SchoolAnnouncement>): NotificationAction =
    announcements.distinctBy(SchoolAnnouncement::id).singleOrNull()?.let {
        NotificationAction.OpenAnnouncement(it.id, it.category)
    } ?: NotificationAction.OpenAnnouncements
