package com.clhs.score.shared.platform

import com.clhs.score.data.BackgroundPause
import com.clhs.score.data.GradeChange
import com.clhs.score.data.GradeChangeField
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.shared.app.PocketClock
import com.clhs.score.shared.app.PocketEnvironment
import com.clhs.score.shared.app.PocketSchedules
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.schoolDateTimeAt

/**
 * Swift-facing entry points for widgets, reminders and background refresh.
 *
 * The demo functions stand in for the production schedule, grade and
 * announcement repositories until the iOS session layer exists; they use the
 * same synthetic fixtures as the rest of the demo app.
 */
object PocketPlatform {
    /** JSON for the App Group snapshot, or null when no valid timetable exists. */
    fun demoScheduleSnapshotJson(scheduleNowMillis: Long, deviceNowMillis: Long): String? =
        demoScheduleSnapshot(scheduleNowMillis, deviceNowMillis)?.encode()

    /**
     * [scheduleNowMillis] is the instant the timetable is evaluated at and
     * [deviceNowMillis] the real clock. They differ only when a demo clock is
     * pinned; the result is then replayed relative to the real clock.
     */
    fun demoScheduleSnapshot(scheduleNowMillis: Long, deviceNowMillis: Long): WidgetScheduleSnapshot? {
        val today = schoolDateTimeAt(scheduleNowMillis).datePart()
        // The active source: the synthetic timetable in demo mode, the signed-in student's
        // loaded timetable otherwise (null until it has been fetched).
        val report = PocketSchedules.currentWeek(today)
        return buildWidgetScheduleSnapshot(report, scheduleNowMillis)?.shiftedBy(deviceNowMillis - scheduleNowMillis)
    }

    fun decodeSnapshot(json: String): WidgetScheduleSnapshot? = decodeWidgetScheduleSnapshot(json)

    fun courseReminders(
        snapshotJson: String?,
        nowMillis: Long,
        settings: PlatformNotificationSettings,
        pausedUntilMillis: Long,
    ): List<PlannedNotification> = planCourseReminders(
        snapshot = snapshotJson?.let(::decodeWidgetScheduleSnapshot),
        nowMillis = nowMillis,
        notificationsEnabled = settings.notificationsEnabled && settings.courseReminders,
        pausedUntilMillis = pausedUntilMillis,
    )

    fun coursePauseEndMillis(nowMillis: Long): Long = coursePauseEnd(nowMillis)

    /** Synthetic change set; production will diff two fetched reports with `GradeReportDiffer`. */
    fun demoGradeNotification(nowMillis: Long, settings: PlatformNotificationSettings): PlannedNotification? =
        planGradeNotification(
            changeSet = GradeChangeSet(
                studentNo = "DEMO-000",
                yearValue = "113_2",
                examValue = "113_2_E2",
                examName = "第二次段考",
                checkedAtMillis = nowMillis,
                changes = listOf(
                    GradeChange("數學", "數學", GradeChangeField.SUBJECT_SCORE),
                    GradeChange("英語文", "英語文", GradeChangeField.SUBJECT_CLASS_RANK),
                    GradeChange("總覽", null, GradeChangeField.SUMMARY_CLASS_RANK),
                ),
            ),
            nowMillis = nowMillis,
            notificationsEnabled = settings.notificationsEnabled && settings.gradeUpdates,
            pause = settings.pause(),
            utcOffsetSeconds = settings.utcOffsetSeconds,
        )

    fun demoAnnouncementHeadlines(clock: PocketClock): List<AnnouncementHeadline> =
        PocketEnvironment.demo().campus(clock).announcements.map { AnnouncementHeadline(it.id, it.title) }

    fun announcementPlan(
        seenIds: List<String>?,
        latest: List<AnnouncementHeadline>,
        nowMillis: Long,
        settings: PlatformNotificationSettings,
    ): AnnouncementPlan = planAnnouncementNotification(
        seenIds = seenIds,
        latest = latest,
        nowMillis = nowMillis,
        notificationsEnabled = settings.notificationsEnabled && settings.announcements,
        pause = settings.pause(),
        utcOffsetSeconds = settings.utcOffsetSeconds,
    )

    /** Whether best-effort background refresh should be skipped at [nowMillis]. */
    fun isBackgroundPaused(nowMillis: Long, settings: PlatformNotificationSettings): Boolean =
        settings.pause().containsInstant(nowMillis, settings.utcOffsetSeconds)
}

/** Primitive mirror of the notification-related `AppSettings` fields for Swift callers. */
data class PlatformNotificationSettings(
    val notificationsEnabled: Boolean,
    val courseReminders: Boolean,
    val gradeUpdates: Boolean,
    val announcements: Boolean,
    val pauseEnabled: Boolean,
    val pauseStartMinute: Int,
    val pauseEndMinute: Int,
    val utcOffsetSeconds: Int,
) {
    /** Invalid minutes fall back to the shared default window instead of crashing a background task. */
    fun pause(): BackgroundPause = runCatching {
        BackgroundPause(pauseEnabled, pauseStartMinute, pauseEndMinute)
    }.getOrElse { BackgroundPause(enabled = pauseEnabled) }
}
