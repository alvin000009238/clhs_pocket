package com.clhs.score.domain.overview

import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeState
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.ZoneId

internal val SCHOOL_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

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

internal fun OverviewItemKind.isPrivate(): Boolean = when (this) {
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
    internal val order: Int = 0,
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
    val now: LocalDateTime = LocalDateTime.now(SCHOOL_ZONE),
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
    val authGeneration: Long? = null,
    val hasSchedule: Boolean = false,
    val calendarLoading: Boolean = false,
    val calendarFailed: Boolean = false,
    val announcementsLoading: Boolean = false,
    val announcementsFailed: Boolean = false,
)

/** Also used at the render boundary before asynchronous auth collectors can run. */
internal fun OverviewState.forGeneration(generation: Long?): OverviewState =
    if (generation != null && authGeneration == generation) this else copy(
        hero = null,
        items = items.filterNot { it.kind.isPrivate() },
        context = OverviewContext.NonSchoolDay,
        isScheduleRefreshing = false,
        scheduleFailed = false,
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

internal fun OverviewState.withPreferences(value: OverviewPreferences): OverviewState {
    val countdowns = if (value.countdownEventIds.isEmpty()) emptyList() else calendarEvents.filter { it.id in value.countdownEventIds }
        .sortedWith(compareBy<SchoolCalendarEvent> { !now.isBefore(it.endExclusive) }.thenBy { it.start })
        .map {
            val ended = !now.isBefore(it.endExclusive)
            val ongoing = !now.isBefore(it.start)
            val target = if (ongoing || ended) it.endExclusive else it.start
            // ICS all-day DTEND is exclusive; show the last active calendar day.
            val displayTarget = if ((ongoing || ended) && it.isAllDay) target.minusNanos(1) else target
            val label = when {
                ended -> "已結束"
                ongoing && it.isAllDay -> "進行中"
                it.isAllDay -> ChronoUnit.DAYS.between(now.toLocalDate(), displayTarget.toLocalDate()).let { days ->
                    if (days == 0L) "今天" else "$days 天"
                }
                else -> Duration.between(now, target).let { remaining ->
                    when {
                        remaining.toDays() > 0 -> "${remaining.toDays()} 天"
                        remaining.toHours() > 0 -> "${remaining.toHours()} 小時"
                        else -> "${remaining.toMinutes().coerceAtLeast(1)} 分鐘"
                    }
                }
            }
            OverviewItem(
                id = "countdown-${it.id}", priority = OverviewPriority.P1,
                kind = OverviewItemKind.CalendarEvent, title = it.title,
                supportingText = displayTarget.format(DateTimeFormatter.ofPattern(
                    (if (displayTarget.year != now.year) "yyyy/M/d" else "M/d") + if (it.isAllDay) "" else " HH:mm",
                )) + if (ongoing || ended) " 結束" else " 開始",
                label = label, icon = "calendar_today",
                destination = OverviewDestination.CalendarEvent(it.id), eventTime = it.start,
                displayDate = displayTarget.toLocalDate(),
            )
        }
    val calendar = items.filter { it.kind == OverviewItemKind.CalendarEvent }
        .sortedBy { it.eventTime }.take(value.calendarLimit.coerceIn(1, 20))
    val announcements = items.filter { it.kind == OverviewItemKind.Announcement }
        .take(value.announcementLimit.coerceIn(1, 20))
    val other = items.filter { it.kind != OverviewItemKind.CalendarEvent && it.kind != OverviewItemKind.Announcement }
    return copy(preferences = value, countdowns = countdowns, items = other + calendar + announcements)
}
