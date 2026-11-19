package com.clhs.score.domain.overview

import com.clhs.score.shared.time.LOCAL_DATE_TIME_ORDER
import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.NANOS_PER_DAY
import com.clhs.score.shared.time.NANOS_PER_HOUR
import com.clhs.score.shared.time.NANOS_PER_MINUTE
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.dayNumber
import com.clhs.score.shared.time.daysUntil
import com.clhs.score.shared.time.epochDay
import com.clhs.score.shared.time.hourMinuteText
import com.clhs.score.shared.time.isValidTimeZoneId
import com.clhs.score.shared.time.monthNumber
import com.clhs.score.shared.time.nanosUntil
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.parseLocalTime
import com.clhs.score.shared.time.randomUuidString
import com.clhs.score.shared.time.schoolNow
import com.clhs.score.shared.time.shiftNanos
import com.clhs.score.shared.time.timePart
import com.clhs.score.shared.time.yearNumber
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeState

enum class OverviewPriority { P0, P1, P2, P3 }

enum class OverviewContext {
    InClass,
    BetweenClasses,
    AfterSchool,
    Evening,
    NonSchoolDay,
}

enum class OverviewItemKind {
    CurrentClass,
    UpcomingClass,
    ScheduleSetup,
    ScheduleChange,
    CalendarEvent,
    GradeUpdate,
    Announcement,
}

fun OverviewItemKind.isPrivate(): Boolean = when (this) {
    OverviewItemKind.CalendarEvent, OverviewItemKind.Announcement -> false
    else -> true
}

sealed interface OverviewDestination {
    data object Schedule : OverviewDestination
    data object Calendar : OverviewDestination
    data class CalendarEvent(val id: String) : OverviewDestination
    data object Announcements : OverviewDestination
    data class GradeExam(val yearValue: String, val examValue: String) : OverviewDestination
    data class Announcement(val id: String, val category: String, val externalUrl: String? = null) : OverviewDestination
}

data class OverviewItem(
    val id: String,
    val priority: OverviewPriority,
    val kind: OverviewItemKind,
    val title: String,
    val supportingText: String,
    val label: String,
    val icon: String,
    val destination: OverviewDestination,
    val eventTime: LocalDateTime? = null,
    val order: Int = 0,
    val displayDate: LocalDate? = null,
)

data class OverviewState(
    val widgets: List<OverviewWidgetInstance>? = null,
    val weatherLocations: Map<String, OverviewWeatherState> = emptyMap(),
    val manuallyRefreshingWeatherKeys: Set<String> = emptySet(),
    val youBikeStations: Map<String, YouBikeState> = emptyMap(),
    val preferences: OverviewPreferences = OverviewPreferences(),
    val calendarEvents: List<SchoolCalendarEvent> = emptyList(),
    val announcements: List<SchoolAnnouncement> = emptyList(),
    val now: LocalDateTime = schoolNow(),
    val countdowns: List<OverviewItem> = emptyList(),
    val context: OverviewContext = OverviewContext.NonSchoolDay,
    val items: List<OverviewItem> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasPartialFailure: Boolean = false,
    val weather: OverviewWeatherState = OverviewWeatherState.Loading,
    val youBike: YouBikeState = YouBikeState(),
    val hero: OverviewHero? = null,
    val isScheduleRefreshing: Boolean = false,
    val scheduleFailed: Boolean = false,
    val scheduleUnpublished: Boolean = false,
    val scheduleScope: com.clhs.score.data.ScheduleScope? = null,
    val authGeneration: Long? = null,
    val hasSchedule: Boolean = false,
    val calendarLoading: Boolean = false,
    val calendarFailed: Boolean = false,
    val announcementsLoading: Boolean = false,
    val announcementsFailed: Boolean = false,
)

/** Also used at the render boundary before asynchronous auth collectors can run. */
fun OverviewState.forGeneration(generation: Long?): OverviewState =
    if (generation != null && authGeneration == generation) this else copy(
        hero = null,
        items = items.filterNot { it.kind.isPrivate() },
        context = OverviewContext.NonSchoolDay,
        isScheduleRefreshing = false,
        scheduleFailed = false,
        scheduleUnpublished = false,
        scheduleScope = null,
        authGeneration = null,
        hasSchedule = false,
    )

data class OverviewHero(
    val date: LocalDateTime,
    val headline: String,
    val courseLabel: String,
    val courseName: String,
    val supportingText: String,
    val courseProgress: Float? = null,
    val startsAt: LocalDateTime? = null,
    val endsAt: LocalDateTime? = null,
)

sealed interface OverviewWeatherState {
    data object Loading : OverviewWeatherState
    data class Available(val snapshot: WeatherSnapshot, val isRefreshing: Boolean = false, val failed: Boolean = false) : OverviewWeatherState
    data class Unavailable(val needsCwaApiKey: Boolean = false) : OverviewWeatherState
}

data class WeatherSnapshot(
    val source: WeatherSource,
    val locationLabel: String,
    val temperatureCelsius: Int,
    val apparentTemperatureCelsius: Int?,
    val condition: String,
    val precipitationProbability: Int?,
    val precipitationStart: LocalDateTime?,
    val precipitationEndExclusive: LocalDateTime?,
    val fetchedAt: Instant,
    val timezoneId: String = "Asia/Taipei",
    val forecast: List<WeatherForecastPeriod> = emptyList(),
    val maximumTemperatureCelsius: Int? = null,
    val minimumTemperatureCelsius: Int? = null,
)

fun OverviewState.withPreferences(value: OverviewPreferences): OverviewState {
    val countdowns = if (value.countdownEventIds.isEmpty()) emptyList() else calendarEvents.filter { it.id in value.countdownEventIds }
        .sortedWith(compareBy<SchoolCalendarEvent> { now >= it.endExclusive }.thenBy(LOCAL_DATE_TIME_ORDER) { it.start })
        .map {
            val ended = now >= it.endExclusive
            val ongoing = now >= it.start
            val target = if (ongoing || ended) it.endExclusive else it.start
            // ICS all-day DTEND is exclusive; show the last active calendar day.
            val displayTarget = if ((ongoing || ended) && it.isAllDay) target.shiftNanos(-1) else target
            val label = when {
                ended -> "已結束"
                ongoing && it.isAllDay -> "進行中"
                it.isAllDay -> now.datePart().daysUntil(displayTarget.datePart()).let { days ->
                    if (days == 0L) "今天" else "$days 天"
                }
                else -> remainingLabel(now.nanosUntil(target))
            }
            OverviewItem(
                id = "countdown-${it.id}", priority = OverviewPriority.P1,
                kind = OverviewItemKind.CalendarEvent, title = it.title,
                supportingText = displayTarget.countdownText(includeYear = displayTarget.datePart().yearNumber() != now.datePart().yearNumber(), includeTime = !it.isAllDay) +
                    if (ongoing || ended) " 結束" else " 開始",
                label = label, icon = "calendar_today",
                destination = OverviewDestination.CalendarEvent(it.id), eventTime = it.start,
                displayDate = displayTarget.datePart(),
            )
        }
    val calendar = items.filter { it.kind == OverviewItemKind.CalendarEvent }
        .sortedWith(compareBy(nullsFirst(LOCAL_DATE_TIME_ORDER)) { it.eventTime }).take(value.calendarLimit.coerceIn(1, 20))
    val announcements = items.filter { it.kind == OverviewItemKind.Announcement }
        .take(value.announcementLimit.coerceIn(1, 20))
    val other = items.filter { it.kind != OverviewItemKind.CalendarEvent && it.kind != OverviewItemKind.Announcement }
    return copy(preferences = value, countdowns = countdowns, items = other + calendar + announcements)
}

/** Positive remaining time as whole days, else whole hours, else at least one minute. */
fun remainingLabel(remainingNanos: Long): String = when {
    remainingNanos / NANOS_PER_DAY > 0 -> "${remainingNanos / NANOS_PER_DAY} 天"
    remainingNanos / NANOS_PER_HOUR > 0 -> "${remainingNanos / NANOS_PER_HOUR} 小時"
    else -> "${(remainingNanos / NANOS_PER_MINUTE).coerceAtLeast(1)} 分鐘"
}

/** `M/d`, optionally prefixed with the year and followed by ` HH:mm`. */
private fun LocalDateTime.countdownText(includeYear: Boolean, includeTime: Boolean): String {
    val date = datePart()
    return (if (includeYear) "${date.yearNumber().toString().padStart(4, '0')}/" else "") +
        "${date.monthNumber()}/${date.dayNumber()}" + if (includeTime) " ${timePart().hourMinuteText()}" else ""
}
