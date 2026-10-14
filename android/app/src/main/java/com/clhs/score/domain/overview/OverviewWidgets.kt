package com.clhs.score.domain.overview

import com.clhs.score.data.AppSettings
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeStation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

internal val OverviewLayoutJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun OverviewWidgetConfig.Weather.resourceKey(): String = OverviewLayoutJson.encodeToString(this)

@Serializable
data class OverviewWidgetSize(val columns: Int, val rows: Int)

@Serializable
sealed interface OverviewWidgetConfig {
    @Serializable @SerialName("none") data object None : OverviewWidgetConfig
    @Serializable @SerialName("weather") data class Weather(
        val location: WeatherLocation = WeatherLocation.SCHOOL,
        val source: WeatherSource = WeatherSource.OPEN_METEO,
    ) : OverviewWidgetConfig
    @Serializable @SerialName("youbike") data class YouBike(val station: YouBikeStation = YouBikeStation.SCHOOL) : OverviewWidgetConfig
    @Serializable @SerialName("school_event") data class SchoolEvent(val eventId: String, val title: String = "") : OverviewWidgetConfig
    @Serializable @SerialName("custom_countdown") data class CustomCountdown(
        val title: String, val date: String, val time: String? = null,
    ) : OverviewWidgetConfig
    @Serializable @SerialName("list") data class ListItems(
        val limit: Int = 3,
        val announcementPriority: List<AnnouncementPriority> = AnnouncementPriority.entries,
        val maxEventDurationDays: Int? = null,
    ) : OverviewWidgetConfig
}

@Serializable
enum class AnnouncementPriority(val title: String) { Unread("未讀"), Pinned("置頂"), Bookmarked("收藏") }

/** Raw type/config preserve future entries when an older app opens this layout. */
@Serializable
data class OverviewWidgetInstance(
    val id: String,
    val type: String,
    val size: OverviewWidgetSize,
    val enabled: Boolean = true,
    val configVersion: Int = 1,
    val configuration: String = OverviewLayoutJson.encodeToString<OverviewWidgetConfig>(OverviewWidgetConfig.None),
    val ownRow: Boolean = false,
) {
    fun config(): OverviewWidgetConfig? = if (configVersion != 1) null else runCatching {
        OverviewLayoutJson.decodeFromString<OverviewWidgetConfig>(configuration)
    }.getOrNull()?.let { config ->
        if (config is OverviewWidgetConfig.YouBike && config.station.id == YouBikeStation.SCHOOL.id &&
            config.station.name == "中壢高中 YouBike") config.copy(station = config.station.copy(name = YouBikeStation.SCHOOL.name))
        else config
    }?.takeIf { config -> config.isValid() &&
        when (type) {
            "weather" -> config is OverviewWidgetConfig.Weather
            "youbike" -> config is OverviewWidgetConfig.YouBike
            "countdown" -> config is OverviewWidgetConfig.SchoolEvent || config is OverviewWidgetConfig.CustomCountdown
            "calendar", "announcements" -> config is OverviewWidgetConfig.ListItems
            "schedule", "alerts", "date_time" -> config is OverviewWidgetConfig.None
            else -> false
        }
    }

    fun withConfig(config: OverviewWidgetConfig): OverviewWidgetInstance =
        copy(configVersion = 1, configuration = OverviewLayoutJson.encodeToString(config))

    companion object {
        fun create(type: String, config: OverviewWidgetConfig = OverviewWidgetConfig.None): OverviewWidgetInstance {
            val definition = requireNotNull(OverviewWidgetDefinitions[type])
            return OverviewWidgetInstance(UUID.randomUUID().toString(), type, definition.defaultSize).withConfig(config)
        }
    }
}

/** Invalid or newer stored settings stay opaque instead of being silently reset. */
private fun OverviewWidgetConfig.isValid(): Boolean = when (this) {
    OverviewWidgetConfig.None -> true
    is OverviewWidgetConfig.Weather -> location.id.isNotBlank() && location.name.isNotBlank() &&
        location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
        location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
        runCatching { ZoneId.of(location.timezoneId) }.isSuccess &&
        (source != WeatherSource.CWA || (location.supportsCwa &&
            !location.cwaDataset.isNullOrBlank() && !location.cwaLocationName.isNullOrBlank()))
    is OverviewWidgetConfig.YouBike -> station.id.isNotBlank() && station.name.isNotBlank() &&
        (station.latitude == null || (station.latitude.isFinite() && station.latitude in -90.0..90.0)) &&
        (station.longitude == null || (station.longitude.isFinite() && station.longitude in -180.0..180.0))
    is OverviewWidgetConfig.SchoolEvent -> eventId.isNotBlank()
    is OverviewWidgetConfig.CustomCountdown -> title.isNotBlank() && runCatching {
        LocalDate.parse(date)
        time?.let(LocalTime::parse)
    }.isSuccess
    is OverviewWidgetConfig.ListItems -> limit in 1..20 &&
        announcementPriority.size == AnnouncementPriority.entries.size &&
        announcementPriority.toSet() == AnnouncementPriority.entries.toSet() &&
        (maxEventDurationDays == null || maxEventDurationDays > 0)
}

data class OverviewWidgetDefinition(val type: String, val title: String, val icon: String,
    val sizes: List<OverviewWidgetSize>, val defaultSize: OverviewWidgetSize, val multiple: Boolean = false)

object OverviewWidgetDefinitions {
    private fun size(columns: Int, rows: Int) = OverviewWidgetSize(columns, rows)
    val all = listOf(
        OverviewWidgetDefinition("schedule", "課表摘要", "calendar_view_week", listOf(size(4, 1), size(4, 2), size(4, 3)), size(4, 2)),
        OverviewWidgetDefinition("weather", "天氣", "partly_cloudy_day", listOf(size(2, 1), size(2, 2), size(4, 1), size(4, 2)), size(2, 2), true),
        OverviewWidgetDefinition("youbike", "YouBike", "pedal_bike", listOf(size(2, 1), size(2, 2), size(4, 1)), size(2, 2), true),
        OverviewWidgetDefinition("countdown", "倒數", "calendar_today", listOf(size(2, 1), size(2, 2), size(4, 1)), size(2, 1), true),
        OverviewWidgetDefinition("alerts", "課程／成績異動", "notifications", listOf(size(4, 1), size(4, 2), size(4, 3)), size(4, 2)),
        OverviewWidgetDefinition("calendar", "近期行程", "event", listOf(size(4, 1), size(4, 2), size(4, 3)), size(4, 2)),
        OverviewWidgetDefinition("announcements", "重要公告", "campaign", listOf(size(4, 1), size(4, 2), size(4, 3)), size(4, 2)),
    )
    // Decode historical rows without offering the retired clock as an editable widget.
    private val legacyDateTime = OverviewWidgetDefinition("date_time", "日期與時間", "schedule", listOf(size(4, 1)), size(4, 1))
    operator fun get(type: String): OverviewWidgetDefinition? = all.firstOrNull { it.type == type }
        ?: legacyDateTime.takeIf { it.type == type }
}

fun defaultOverviewWidgets(): List<OverviewWidgetInstance> = listOf(
    OverviewWidgetInstance.create("schedule"),
    OverviewWidgetInstance.create("alerts"),
    OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems(3)),
    OverviewWidgetInstance.create("announcements", OverviewWidgetConfig.ListItems(2)),
)

fun importOverviewWidgets(settings: AppSettings, events: List<SchoolCalendarEvent> = emptyList()): List<OverviewWidgetInstance> {
    val eventsById = events.associateBy { it.id }
    // v2.0.0 had no YouBike section; orderedSections() also appends newer defaults.
    return settings.overview.orderedSections().filterNot { it == "youbike" }.flatMap { section ->
        val prefs = settings.overview
        val widgets = when (section) {
            "weather" -> listOf(OverviewWidgetInstance.create(section, OverviewWidgetConfig.Weather(source = settings.weatherSource))
                .copy(size = OverviewWidgetSize(4, 1)))
            "calendar" -> listOf(OverviewWidgetInstance.create(section, OverviewWidgetConfig.ListItems(prefs.calendarLimit.coerceIn(1, 20))))
            "announcements" -> listOf(OverviewWidgetInstance.create(section, OverviewWidgetConfig.ListItems(prefs.announcementLimit.coerceIn(1, 20))))
            "countdown" -> prefs.countdownEventIds.sortedWith(compareBy<String> { it !in eventsById }
                .thenBy { eventsById[it]?.start }
                .thenBy { it }).map { id -> OverviewWidgetInstance.create(section,
                    OverviewWidgetConfig.SchoolEvent(id, eventsById[id]?.title.orEmpty())) }
            else -> listOf(OverviewWidgetInstance.create(section))
        }
        widgets.map { it.copy(enabled = prefs.shows(section)) }
    }
}

data class OverviewPlacement(val id: String, val column: Int, val row: Int, val columns: Int, val rows: Int)

/** Reading-order packing. Legacy first-fit is used only to recover existing visual order. */
object OverviewLayoutEngine {
    fun columns(widthDp: Float, fontScale: Float): Int {
        val effectiveWidth = widthDp / fontScale.coerceAtLeast(1f)
        return when { widthDp < 320f || effectiveWidth < 280f -> 2; effectiveWidth < 600f -> 4; effectiveWidth < 840f -> 6; else -> 8 }
    }

    fun pack(widgets: List<OverviewWidgetInstance>, columns: Int, legacyOrder: Boolean = false): List<OverviewPlacement> {
        require(columns in setOf(2, 4, 6, 8))
        val occupied = mutableSetOf<Pair<Int, Int>>()
        var minimumRow = 0
        var minimumColumn = 0
        return widgets.filter { it.enabled }.map { widget ->
            val validSize = widget.size.takeIf { it in OverviewWidgetDefinitions[widget.type]?.sizes.orEmpty() }
                ?: OverviewWidgetSize(4, 1)
            val width = validSize.columns.coerceIn(1, columns)
            val height = validSize.rows
            var row = if (widget.ownRow) (occupied.maxOfOrNull { it.second } ?: -1) + 1 else minimumRow
            var column = if (widget.ownRow || legacyOrder) 0 else minimumColumn
            while (column + width > columns || (row until row + height).any { y -> (column until column + width).any { x -> x to y in occupied } }) {
                column++
                if (column + width > columns) { column = 0; row++ }
            }
            for (y in row until row + height) for (x in if (widget.ownRow) 0 until columns else column until column + width) occupied.add(x to y)
            if (widget.ownRow) { minimumRow = row + height; minimumColumn = 0 }
            else if (!legacyOrder) { minimumRow = row; minimumColumn = column + width }
            OverviewPlacement(widget.id, column, row, width, height)
        }
    }

    /** Replace visible slots only; hidden/unknown rows keep their identities and settings. */
    fun inVisualOrder(widgets: List<OverviewWidgetInstance>, ids: List<String>): List<OverviewWidgetInstance> {
        val byId = widgets.associateBy { it.id }
        val ordered = ids.distinct().mapNotNull(byId::get)
        val visibleIds = ordered.map { it.id }.toSet()
        var next = 0
        return widgets.map { if (it.id in visibleIds) ordered[next++] else it }
    }

    fun move(widgets: List<OverviewWidgetInstance>, id: String, index: Int): List<OverviewWidgetInstance> {
        val result = widgets.toMutableList()
        val from = result.indexOfFirst { it.id == id }
        if (from == -1) return widgets
        val item = result.removeAt(from)
        result.add(index.coerceIn(0, result.size), item)
        return result
    }
}

fun OverviewWidgetInstance.countdownItem(state: OverviewState): OverviewItem? = when (val config = config()) {
    is OverviewWidgetConfig.SchoolEvent -> state.withPreferences(OverviewPreferences(countdownEventIds = setOf(config.eventId)))
        .countdowns.firstOrNull()?.copy(id = id)
    is OverviewWidgetConfig.CustomCountdown -> runCatching {
        val date = LocalDate.parse(config.date)
        val time = config.time?.let(LocalTime::parse)
        val label = if (time == null) {
            val days = ChronoUnit.DAYS.between(state.now.toLocalDate(), date)
            when { days < 0 -> "已到期"; days == 0L -> "今天"; else -> "$days 天" }
        } else {
            val remaining = Duration.between(state.now, date.atTime(time))
            when { remaining.isNegative || remaining.isZero -> "已到期"
                remaining.toDays() > 0 -> "${remaining.toDays()} 天"
                remaining.toHours() > 0 -> "${remaining.toHours()} 小時"
                else -> "${remaining.toMinutes().coerceAtLeast(1)} 分鐘" }
        }
        OverviewItem(id, OverviewPriority.P1, OverviewItemKind.CalendarEvent, config.title,
            config.date + (config.time?.let { " $it" } ?: ""), label, "calendar_today", OverviewDestination.Calendar,
            displayDate = date)
    }.getOrNull()
    else -> null
}
