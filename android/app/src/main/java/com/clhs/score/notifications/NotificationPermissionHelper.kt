package com.clhs.score.notifications

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

internal fun Context.hasPostNotificationsPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

internal fun Context.areNotificationsEnabled(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

internal fun Context.canPostNotifications(): Boolean =
    hasPostNotificationsPermission() && areNotificationsEnabled()

internal fun Context.canPostNotifications(channelId: String): Boolean {
    if (!canPostNotifications()) return false
    val manager = getSystemService(NotificationManager::class.java)
    // A channel that has not been created yet uses the app's default availability.
    val channel = manager.getNotificationChannel(channelId) ?: return true
    return channel.importance != NotificationManager.IMPORTANCE_NONE &&
        channel.group?.let(manager::getNotificationChannelGroup)?.isBlocked != true
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Context.shouldShowPostNotificationsRationale(): Boolean =
    findActivity()?.let { activity ->
        ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } == true

internal fun Context.openAppNotificationSettings(): Boolean {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    return startSettingsActivity(intent) ||
        startSettingsActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            },
        )
}

internal fun Context.openNotificationChannelSettings(channelId: String): Boolean {
    if (!canPostNotifications()) return openAppNotificationSettings()
    NotificationChannels.ensureCreated(this)
    return startSettingsActivity(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        },
    ) || openAppNotificationSettings()
}

private fun Context.startSettingsActivity(intent: Intent): Boolean =
    runCatching {
        if (this !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.isSuccess

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
