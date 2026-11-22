package com.clhs.score.shared.platform

import com.clhs.score.data.PERIOD_TIMES
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.displayItems
import com.clhs.score.data.getSubjectColors
import com.clhs.score.data.occurrenceOn
import com.clhs.score.data.resolveCourses
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.isoNumber
import com.clhs.score.shared.time.localTimeOf
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.schoolEpochMillis
import com.clhs.score.shared.time.shiftDays
import com.clhs.score.shared.time.weekday
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val WIDGET_SNAPSHOT_SCHEMA_VERSION = 1

/** Minutes before a lesson at which widgets, reminders and the Live Activity switch to "starting soon". */
const val COURSE_ADVANCE_MINUTES = 5

/**
 * The only payload the iOS WidgetKit extension and Live Activity may read.
 *
 * It deliberately carries no student identity, credentials, cookies, grades or
 * teacher names: only what a lock-screen glance needs. Every instant in
 * [entries] is a reload boundary produced by [resolveCourses], so the
 * extension renders without re-deriving schedule rules in Swift.
 */
@Serializable
data class WidgetScheduleSnapshot(
    val schemaVersion: Int = WIDGET_SNAPSHOT_SCHEMA_VERSION,
    val generatedAtMillis: Long,
    /** After this instant the snapshot must be shown as expired. */
    val validUntilMillis: Long,
    val weekLabel: String = "",
    val lessons: List<WidgetLesson>,
    val entries: List<WidgetTimelineEntry>,
)

@Serializable
data class WidgetLesson(
    val id: Int,
    val epochDay: Long,
    /** 1 = Monday … 7 = Sunday. */
    val dayOfWeek: Int,
    val period: Int,
    val subject: String,
    val classroom: String,
    val startMillis: Long,
    val endMillis: Long,
    val startText: String,
    val endText: String,
    val colorLight: Long,
    val colorDark: Long,
)

@Serializable
enum class WidgetPhase {
    /** A lesson is in progress. */
    IN_CLASS,

    /** The next lesson starts within [COURSE_ADVANCE_MINUTES]. */
    STARTING_SOON,

    /** Between lessons, with another lesson later today. */
    BEFORE_CLASS,

    /** Today's lessons have all ended. */
    DAY_DONE,

    /** No lessons are scheduled today. */
    FREE_DAY,
}

@Serializable
data class WidgetTimelineEntry(
    val atMillis: Long,
    val epochDay: Long,
    val phase: WidgetPhase,
    val currentLessonId: Int? = null,
    /** The next lesson to start, possibly on a later day. */
    val nextLessonId: Int? = null,
)

private val snapshotJson get() = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun WidgetScheduleSnapshot.encode(): String = snapshotJson.encodeToString(WidgetScheduleSnapshot.serializer(), this)

fun decodeWidgetScheduleSnapshot(text: String): WidgetScheduleSnapshot? = runCatching {
    snapshotJson.decodeFromString(WidgetScheduleSnapshot.serializer(), text)
}.getOrNull()?.takeIf { it.schemaVersion == WIDGET_SNAPSHOT_SCHEMA_VERSION }

/** Moves every instant by [deltaMillis]; used only to replay a pinned demo clock in real time. */
fun WidgetScheduleSnapshot.shiftedBy(deltaMillis: Long): WidgetScheduleSnapshot = if (deltaMillis == 0L) this else copy(
    generatedAtMillis = generatedAtMillis + deltaMillis,
    validUntilMillis = validUntilMillis + deltaMillis,
    lessons = lessons.map { it.copy(startMillis = it.startMillis + deltaMillis, endMillis = it.endMillis + deltaMillis) },
    entries = entries.map { it.copy(atMillis = it.atMillis + deltaMillis) },
)

private const val SEMESTER_HORIZON_DAYS = 7L
private const val MAX_ENTRIES = 400

private fun LocalDate.startMillis(): Long = at(localTimeOf(0, 0)).schoolEpochMillis()

/**
 * Builds the snapshot for [report] starting at [nowMillis].
 *
 * Returns null when the shared resolver refuses the report (expired week,
 * malformed or overlapping slots), so the widget falls back to its prompt
 * instead of showing data the Android widget would also reject.
 */
fun buildWidgetScheduleSnapshot(report: ScheduleReport?, nowMillis: Long): WidgetScheduleSnapshot? =
    buildWidgetScheduleSnapshot(report, nowMillis, MAX_ENTRIES)

internal fun buildWidgetScheduleSnapshot(report: ScheduleReport?, nowMillis: Long, maxEntries: Int): WidgetScheduleSnapshot? {
    if (report == null) return null
    val first = resolveCourses(report, instantOfEpochMilli(nowMillis), remindBeforeClass = true)
    if (!first.scheduleAvailable) return null

    val today = schoolDateTimeAt(nowMillis).datePart()
    // A week fetched ahead of time starts on its own first day, never on today's weekday.
    val firstDay = if (report.scope == ScheduleScope.CURRENT_WEEK) {
        val weekStart = report.weekStartDate?.let(::parseLocalDateOrNull)
        if (weekStart != null && weekStart.epochDay() > today.epochDay()) weekStart else today
    } else {
        today
    }
    val lastDay = if (report.scope == ScheduleScope.CURRENT_WEEK) {
        report.weekEndDate?.let(::parseLocalDateOrNull) ?: return null
    } else {
        today.shiftDays(SEMESTER_HORIZON_DAYS - 1)
    }
    val horizon = lastDay.shiftDays(1).startMillis()

    val items = report.displayItems()
    val colorsLight = getSubjectColors(items.map { it.subjectName }, isDarkTheme = false)
    val colorsDark = getSubjectColors(items.map { it.subjectName }, isDarkTheme = true)
    val dayCount = lastDay.epochDay() - firstDay.epochDay()
    val lessons = (0..dayCount).flatMap { offset ->
        val date = firstDay.shiftDays(offset)
        items.filter { it.dayOfWeek == date.weekday().isoNumber }.mapNotNull { item ->
            item.occurrenceOn(date)?.let { Triple(date, item, it) }
        }
    }.sortedBy { it.third.start }.mapIndexed { index, (date, item, occurrence) ->
        val times = PERIOD_TIMES[item.period - 1]
        WidgetLesson(
            id = index,
            epochDay = date.epochDay(),
            dayOfWeek = item.dayOfWeek,
            period = item.period,
            subject = item.subjectName,
            classroom = item.classroom,
            startMillis = occurrence.start.epochMillis(),
            endMillis = occurrence.end.epochMillis(),
            startText = times.start,
            endText = times.end,
            colorLight = colorsLight[item.subjectName] ?: 0xFF4A6FA5,
            colorDark = colorsDark[item.subjectName] ?: 0xFF9DB8E0,
        )
    }

    val entries = mutableListOf<WidgetTimelineEntry>()
    var at = nowMillis
    while (at < horizon && entries.size < maxEntries) {
        val resolution = resolveCourses(report, instantOfEpochMilli(at), remindBeforeClass = true)
        val day = schoolDateTimeAt(at).datePart()
        val dayEpoch = day.epochDay()
        val current = resolution.current?.let { c -> lessons.firstOrNull { it.startMillis == c.start.epochMillis() } }
        val next = lessons.firstOrNull { it.startMillis > at }
        val todayLessons = lessons.filter { it.epochDay == dayEpoch }
        val phase = when {
            current != null -> WidgetPhase.IN_CLASS
            resolution.upcoming != null -> WidgetPhase.STARTING_SOON
            next != null && next.epochDay == dayEpoch -> WidgetPhase.BEFORE_CLASS
            todayLessons.isNotEmpty() -> WidgetPhase.DAY_DONE
            else -> WidgetPhase.FREE_DAY
        }
        entries += WidgetTimelineEntry(at, dayEpoch, phase, current?.id, next?.id)
        val midnight = day.shiftDays(1).startMillis()
        val boundary = resolution.nextBoundary?.epochMillis()?.takeIf { it > at }
        at = minOf(boundary ?: midnight, midnight)
    }

    return WidgetScheduleSnapshot(
        generatedAtMillis = nowMillis,
        // When the entry limit cut the timeline short it is only valid up to the first missing boundary.
        validUntilMillis = minOf(at, horizon),
        weekLabel = report.weekNo?.takeIf { it.isNotBlank() }?.let { "第 $it 週" }.orEmpty(),
        lessons = lessons,
        entries = entries,
    )
}

/** The entry that applies at [nowMillis], or null when the snapshot is not usable then. */
fun WidgetScheduleSnapshot.entryAt(nowMillis: Long): WidgetTimelineEntry? {
    if (nowMillis >= validUntilMillis) return null
    return entries.lastOrNull { it.atMillis <= nowMillis }
}
