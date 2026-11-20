package com.clhs.score.shared.platform

import com.clhs.score.data.BackgroundPause
import com.clhs.score.data.GradeChangeSet
import com.clhs.score.data.GradeReminderText
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.schoolEpochMillis
import com.clhs.score.shared.time.shiftDays

/** A local notification the platform should hand to its scheduler verbatim. */
data class PlannedNotification(
    val identifier: String,
    /** Null means "deliver now". */
    val fireAtMillis: Long?,
    val title: String,
    val body: String,
    /** Deep link opened on tap; never contains personal data. */
    val link: String,
)

object PocketLinks {
    const val SCHEME = "clhspocket"
    const val HOME = "$SCHEME://home"
    const val SCHEDULE = "$SCHEME://schedule"
    const val GRADES = "$SCHEME://grades"
    const val ANNOUNCEMENTS = "$SCHEME://announcements"
    const val CALENDAR = "$SCHEME://calendar"
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_DAY = 24 * 60 * MILLIS_PER_MINUTE

/** Wall-clock minute (0..1439) of [epochMillis] at [utcOffsetSeconds]. */
fun minuteOfDayAt(epochMillis: Long, utcOffsetSeconds: Int): Int {
    val local = epochMillis + utcOffsetSeconds * 1000L
    return (((local % MILLIS_PER_DAY) + MILLIS_PER_DAY) % MILLIS_PER_DAY / MILLIS_PER_MINUTE).toInt()
}

fun BackgroundPause.containsInstant(epochMillis: Long, utcOffsetSeconds: Int): Boolean =
    containsMinute(minuteOfDayAt(epochMillis, utcOffsetSeconds))

const val COURSE_REMINDER_PREFIX = "course-reminder-"

/**
 * iOS keeps at most 64 pending local notifications per app; staying well
 * below leaves room for grade and announcement alerts.
 */
const val MAX_PENDING_COURSE_REMINDERS = 40

/**
 * Pre-class reminders for the lessons in [snapshot] that have not reached
 * their reminder time yet.
 *
 * As on Android, the background quiet window does not apply here: it only
 * stops background grade and announcement checks. Course reminders are
 * silenced by [pausedUntilMillis] instead (the "暫停一日" action).
 */
fun planCourseReminders(
    snapshot: WidgetScheduleSnapshot?,
    nowMillis: Long,
    notificationsEnabled: Boolean,
    pausedUntilMillis: Long = 0L,
    limit: Int = MAX_PENDING_COURSE_REMINDERS,
): List<PlannedNotification> {
    if (snapshot == null || !notificationsEnabled || nowMillis >= snapshot.validUntilMillis) return emptyList()
    return snapshot.lessons.asSequence()
        .map { it to it.startMillis - COURSE_ADVANCE_MINUTES * MILLIS_PER_MINUTE }
        .filter { (_, fireAt) -> fireAt > nowMillis && fireAt < snapshot.validUntilMillis }
        .filter { (_, fireAt) -> fireAt >= pausedUntilMillis }
        .take(limit.coerceAtLeast(0))
        .map { (lesson, fireAt) ->
            PlannedNotification(
                identifier = "$COURSE_REMINDER_PREFIX${lesson.epochDay}-${lesson.period}",
                fireAtMillis = fireAt,
                title = "即將上課 · 第 ${lesson.period} 節",
                body = listOf(lesson.subject, "${lesson.startText}–${lesson.endText}", lesson.classroom)
                    .filter { it.isNotBlank() }.joinToString(" · "),
                link = PocketLinks.SCHEDULE,
            )
        }
        .toList()
}

/** Start of the next school day after [nowMillis]: where "暫停一日" ends, as on Android. */
fun coursePauseEnd(nowMillis: Long): Long =
    schoolDateTimeAt(nowMillis).datePart().shiftDays(1).at(localTimeOf(0, 0)).schoolEpochMillis()

const val GRADE_NOTIFICATION_ID = "grade-update"
const val ANNOUNCEMENT_NOTIFICATION_ID = "announcement-update"

/** Mirrors Android: the alert names what changed but never carries a score or rank. */
fun planGradeNotification(
    changeSet: GradeChangeSet,
    nowMillis: Long,
    notificationsEnabled: Boolean,
    pause: BackgroundPause,
    utcOffsetSeconds: Int,
): PlannedNotification? {
    if (!notificationsEnabled || !changeSet.hasChanges) return null
    if (pause.containsInstant(nowMillis, utcOffsetSeconds)) return null
    return PlannedNotification(
        identifier = GRADE_NOTIFICATION_ID,
        fireAtMillis = null,
        title = GradeReminderText.notificationTitle(changeSet),
        body = GradeReminderText.notificationBody(changeSet),
        link = PocketLinks.GRADES,
    )
}

data class AnnouncementHeadline(val id: String, val title: String)

data class AnnouncementPlan(
    /** Persist this as the new baseline whether or not a notification is shown. */
    val seenIds: List<String>,
    val notification: PlannedNotification?,
)

/**
 * [seenIds] null means no baseline exists yet: the first check only records
 * the current list, so installing the app never floods the user.
 */
fun planAnnouncementNotification(
    seenIds: List<String>?,
    latest: List<AnnouncementHeadline>,
    nowMillis: Long,
    notificationsEnabled: Boolean,
    pause: BackgroundPause,
    utcOffsetSeconds: Int,
): AnnouncementPlan {
    val distinct = latest.distinctBy { it.id }
    val ids = distinct.map { it.id }
    if (seenIds == null) return AnnouncementPlan(ids, null)
    val fresh = distinct.filter { it.id !in seenIds }
    // While paused or disabled the baseline is left untouched so the items are announced later.
    if (fresh.isEmpty()) return AnnouncementPlan(seenIds, null)
    if (!notificationsEnabled || pause.containsInstant(nowMillis, utcOffsetSeconds)) return AnnouncementPlan(seenIds, null)
    return AnnouncementPlan(
        // The current list always stays in the baseline, so a long-pinned item is never evicted and re-announced.
        seenIds = (ids + seenIds.filter { it !in ids }).take(MAX_SEEN_ANNOUNCEMENTS),
        notification = PlannedNotification(
            identifier = ANNOUNCEMENT_NOTIFICATION_ID,
            fireAtMillis = null,
            title = "${fresh.size} 則新學校公告",
            body = fresh.take(3).joinToString("\n") { it.title },
            link = PocketLinks.ANNOUNCEMENTS,
        ),
    )
}

private const val MAX_SEEN_ANNOUNCEMENTS = 200
