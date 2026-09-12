package com.clhs.score.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.net.toUri
import com.clhs.score.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.atomic.AtomicInteger

class ScoreFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        showNotification(message)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        // Topic subscriptions are restored by Firebase and SettingsViewModel on app launch.
    }

    private fun showNotification(message: RemoteMessage) {
        if (!canPostNotifications()) return

        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return
        val url = message.data["url"].orEmpty()
        val isUpdateTopic = message.from == "/topics/${NotificationTopicManager.APP_UPDATES_TOPIC}" ||
            message.data["check_update"] == "true" ||
            message.data["action"] == "check_update"
        val announcementId = message.data["announcement_id"]
            ?.takeIf {
                message.data["action"] == "open_announcement" &&
                    it.length in 1..32 &&
                    it.all(Char::isDigit)
            }
        val announcementCategory = message.data["announcement_category"].orEmpty().take(80)

        NotificationChannels.ensureCreated(this)

        val notification = Notification.Builder(this, NotificationChannels.UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(buildContentIntent(url, isUpdateTopic, announcementId, announcementCategory))
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationIdCounter.getAndIncrement(), notification)
    }

    private fun buildContentIntent(
        url: String,
        isUpdateTopic: Boolean,
        announcementId: String?,
        announcementCategory: String,
    ): PendingIntent {
        val safeUri = if (url.isNotBlank() && !isUpdateTopic && announcementId == null) {
            url.toUri().takeIf { it.scheme in listOf("http", "https") }
        } else {
            null
        }

        val intent = if (safeUri != null) {
            Intent(Intent.ACTION_VIEW, safeUri)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        val capabilityToken = when {
            isUpdateTopic -> NotificationActionCapabilities.issueUpdate(this)
            announcementId != null -> NotificationActionCapabilities.issueAnnouncement(
                this,
                announcementId,
                announcementCategory,
            )
            else -> null
        }
        if (capabilityToken != null) {
            intent.putExtra(
                NotificationActionCapabilities.EXTRA_CAPABILITY,
                capabilityToken,
            )
        }

        return PendingIntent.getActivity(
            this,
            capabilityToken?.hashCode() ?: notificationIdCounter.get(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private val notificationIdCounter = AtomicInteger(1000)
    }
}
