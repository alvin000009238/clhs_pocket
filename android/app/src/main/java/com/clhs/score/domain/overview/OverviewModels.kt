package com.clhs.score.domain.overview

import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.WeatherSource
import java.time.LocalDateTime
import java.time.Instant

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
)

data class OverviewState(
    val preferences: OverviewPreferences = OverviewPreferences(),
    val calendarEvents: List<SchoolCalendarEvent> = emptyList(),
    val now: LocalDateTime = LocalDateTime.now(),
    val countdowns: List<OverviewItem> = emptyList(),
    val context: OverviewContext = OverviewContext.NonSchoolDay,
    val items: List<OverviewItem> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasPartialFailure: Boolean = false,
    val weather: OverviewWeatherState = OverviewWeatherState.Loading,
    val hero: OverviewHero? = null,
    val isScheduleRefreshing: Boolean = false,
)

data class OverviewHero(
    val date: LocalDateTime,
    val headline: String,
    val courseLabel: String,
    val courseName: String,
    val trailingText: String?,
    val supportingText: String,
    val courseProgress: Float? = null,
)

sealed interface OverviewWeatherState {
    data object Loading : OverviewWeatherState
    data class Available(val snapshot: WeatherSnapshot, val isRefreshing: Boolean = false) : OverviewWeatherState
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
)

internal fun OverviewState.withPreferences(value: OverviewPreferences): OverviewState {
    val countdowns = calendarEvents.filter { it.id in value.countdownEventIds }
        .sortedWith(compareBy<SchoolCalendarEvent> { !now.isBefore(it.endExclusive) }.thenBy { it.start })
        .map {
            val label = when {
                !now.isBefore(it.endExclusive) -> "已結束"
                !now.isBefore(it.start) -> "進行中"
                it.isAllDay -> "${java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), it.start.toLocalDate())} 天"
                else -> java.time.Duration.between(now, it.start).let { remaining ->
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
                supportingText = "", label = label, icon = "calendar_today",
                destination = OverviewDestination.CalendarEvent(it.id), eventTime = it.start,
            )
        }
    val calendar = items.filter { it.kind == OverviewItemKind.CalendarEvent }
        .sortedBy { it.eventTime }.take(value.calendarLimit.coerceIn(1, 20))
    val announcements = items.filter { it.kind == OverviewItemKind.Announcement }
        .take(value.announcementLimit.coerceIn(1, 20))
    val other = items.filter { it.kind != OverviewItemKind.CalendarEvent && it.kind != OverviewItemKind.Announcement }
    return copy(preferences = value, countdowns = countdowns, items = other + calendar + announcements)
}
