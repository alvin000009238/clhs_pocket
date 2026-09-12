package com.clhs.score.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationActionCapabilitiesInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun forgedExtrasAreRejectedAndIssuedCapabilityIsSingleUse() {
        context.deleteSharedPreferences("notification_action_capabilities")
        val forged = Intent()
            .putExtra("from", "/topics/app_updates")
            .putExtra("check_update", true)
            .putExtra("open_grade_reminder", true)
            .putExtra("grade_reminder_year_value", "114_1")
            .putExtra("grade_reminder_exam_value", "E1")
        assertNull(consumeNotificationAction(context, forged))
        assertNull(
            consumeNotificationAction(
                context,
                Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, "forged-token"),
            ),
        )

        val token = NotificationActionCapabilities.issueGradeReminder(context, "114_1", "E1")
        val legitimate = Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token)
        assertEquals(NotificationAction.OpenGradeReminder("114_1", "E1"), consumeNotificationAction(context, legitimate))
        assertNull(consumeNotificationAction(context, Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token)))
    }

    @Test
    fun announcementCapabilityCarriesOnlyValidatedDestinationAndIsSingleUse() {
        context.deleteSharedPreferences("notification_action_capabilities")
        val token = NotificationActionCapabilities.issueAnnouncement(context, "12345", "行政")
        val intent = Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token)

        assertEquals(
            NotificationAction.OpenAnnouncement("12345", "行政"),
            consumeNotificationAction(context, intent),
        )
        assertNull(
            consumeNotificationAction(
                context,
                Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token),
            ),
        )
    }

    @Test
    fun announcementListCapabilityCannotBeForgedOrReplayed() {
        context.deleteSharedPreferences("notification_action_capabilities")
        assertNull(
            consumeNotificationAction(
                context,
                Intent().putExtra("open_announcements", true),
            ),
        )

        val token = NotificationActionCapabilities.issueAnnouncements(context)
        val intent = Intent().putExtra(NotificationActionCapabilities.EXTRA_CAPABILITY, token)
        assertEquals(NotificationAction.OpenAnnouncements, consumeNotificationAction(context, intent))
        assertNull(consumeNotificationAction(context, intent))
    }
}
