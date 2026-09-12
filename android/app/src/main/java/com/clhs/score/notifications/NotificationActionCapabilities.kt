package com.clhs.score.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import java.util.UUID

internal sealed interface NotificationAction {
    data object CheckUpdate : NotificationAction
    data class OpenGradeReminder(val yearValue: String, val examValue: String) : NotificationAction
    data class OpenAnnouncement(val id: String, val category: String) : NotificationAction
    data object OpenAnnouncements : NotificationAction
}

internal object NotificationActionCapabilities {
    const val EXTRA_CAPABILITY = "notification_action_capability"

    private const val PREFS_NAME = "notification_action_capabilities"
    private const val KEY_PREFIX = "capability."
    private const val LAST_ACTION = "last_action"
    private const val LAST_CONSUMED_AT = "last_consumed_at"
    private const val TYPE_UPDATE = "update"
    private const val TYPE_REMINDER = "reminder"
    private const val TYPE_ANNOUNCEMENT = "announcement"
    private const val TYPE_ANNOUNCEMENTS = "announcements"
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val COOLDOWN_MILLIS = 15_000L
    private val lock = Any()

    fun issueUpdate(context: Context): String = issue(context, TYPE_UPDATE)

    fun issueGradeReminder(context: Context, yearValue: String, examValue: String): String =
        issue(context, TYPE_REMINDER, yearValue, examValue)

    fun issueAnnouncement(context: Context, id: String, category: String): String =
        issue(context, TYPE_ANNOUNCEMENT, announcementId = id, category = category)

    fun issueAnnouncements(context: Context): String = issue(context, TYPE_ANNOUNCEMENTS)

    fun consume(context: Context, token: String?): NotificationAction? = synchronized(lock) {
        if (token.isNullOrBlank()) return@synchronized null
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "$KEY_PREFIX$token."
        val type = prefs.getString("${prefix}type", null) ?: return@synchronized null
        val issuedAt = prefs.getLong("${prefix}issued_at", Long.MIN_VALUE)
        val yearValue = prefs.getString("${prefix}year", null)
        val examValue = prefs.getString("${prefix}exam", null)
        val announcementId = prefs.getString("${prefix}announcement_id", null)
        val category = prefs.getString("${prefix}category", null).orEmpty()
        val now = System.currentTimeMillis()
        val action = when {
            issuedAt !in (now - MAX_AGE_MILLIS)..now -> null
            type == TYPE_UPDATE -> NotificationAction.CheckUpdate
            type == TYPE_REMINDER && !yearValue.isNullOrBlank() && !examValue.isNullOrBlank() ->
                NotificationAction.OpenGradeReminder(yearValue, examValue)
            type == TYPE_ANNOUNCEMENT && !announcementId.isNullOrBlank() ->
                NotificationAction.OpenAnnouncement(announcementId, category)
            type == TYPE_ANNOUNCEMENTS -> NotificationAction.OpenAnnouncements
            else -> null
        }
        val fingerprint = when (action) {
            NotificationAction.CheckUpdate -> TYPE_UPDATE
            is NotificationAction.OpenGradeReminder -> "$TYPE_REMINDER|${action.yearValue}|${action.examValue}"
            is NotificationAction.OpenAnnouncement -> "$TYPE_ANNOUNCEMENT|${action.id}|${action.category}"
            NotificationAction.OpenAnnouncements -> TYPE_ANNOUNCEMENTS
            null -> null
        }
        val isCoolingDown = fingerprint != null &&
            prefs.getString(LAST_ACTION, null) == fingerprint &&
            now - prefs.getLong(LAST_CONSUMED_AT, Long.MIN_VALUE) in 0 until COOLDOWN_MILLIS
        val consumed = prefs.edit()
            .remove("${prefix}type")
            .remove("${prefix}issued_at")
            .remove("${prefix}year")
            .remove("${prefix}exam")
            .remove("${prefix}announcement_id")
            .remove("${prefix}category")
            .apply {
                if (fingerprint != null && !isCoolingDown) {
                    putString(LAST_ACTION, fingerprint)
                    putLong(LAST_CONSUMED_AT, now)
                }
            }
            .commit()
        if (!consumed || isCoolingDown) null else action
    }

    private fun issue(
        context: Context,
        type: String,
        yearValue: String? = null,
        examValue: String? = null,
        announcementId: String? = null,
        category: String? = null,
    ): String = synchronized(lock) {
        val token = UUID.randomUUID().toString()
        val prefix = "$KEY_PREFIX$token."
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString("${prefix}type", type)
                putLong("${prefix}issued_at", System.currentTimeMillis())
                if (yearValue != null) putString("${prefix}year", yearValue)
                if (examValue != null) putString("${prefix}exam", examValue)
                if (announcementId != null) putString("${prefix}announcement_id", announcementId)
                if (category != null) putString("${prefix}category", category)
            }
        token
    }
}

internal fun consumeNotificationAction(context: Context, intent: Intent?): NotificationAction? {
    val token = intent?.getStringExtra(NotificationActionCapabilities.EXTRA_CAPABILITY)
    intent?.removeExtra(NotificationActionCapabilities.EXTRA_CAPABILITY)
    return NotificationActionCapabilities.consume(context, token)
}
