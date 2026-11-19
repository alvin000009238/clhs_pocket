package com.clhs.score.shared.app

import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.OverviewLayoutEngine
import com.clhs.score.domain.overview.OverviewLayoutJson
import com.clhs.score.domain.overview.OverviewPlacement
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.countdownItem
import com.clhs.score.domain.overview.defaultOverviewWidgets
import com.clhs.score.shared.time.parseLocalDateOrNull
import com.clhs.score.shared.time.parseLocalTimeOrNull
import kotlinx.serialization.builtins.ListSerializer

enum class PocketWidgetSettings { NONE, WEATHER, YOUBIKE, COUNTDOWN, LIST }

data class PocketWidgetSizeOption(val columns: Int, val rows: Int, val label: String, val isSelected: Boolean)

data class PocketAlertRow(val id: String, val title: String, val detail: String, val symbol: String)

data class PocketChoice(val id: String, val title: String, val detail: String)

/** One placed home widget with everything SwiftUI needs to draw and edit it. */
data class PocketWidget(
    val id: String,
    val type: String,
    val title: String,
    val symbol: String,
    val column: Int,
    val row: Int,
    val columns: Int,
    val rows: Int,
    val sizes: List<PocketWidgetSizeOption>,
    val settings: PocketWidgetSettings,
    /** One-line description of the current configuration for the editor. */
    val summary: String,
    /** Selected location, station or school event; empty for a custom countdown. */
    val selectionId: String,
    val limit: Int,
    val customTitle: String,
    val customDate: String,
    val customTime: String,
    /** True when the widget shows personal data and the viewer is a guest. */
    val needsSignIn: Boolean,
    val hero: PocketCourseHero?,
    val weather: PocketWeather?,
    val youBike: PocketYouBikeStation?,
    val countdown: PocketCountdown?,
    val events: List<PocketEventRow>,
    val announcements: List<PocketAnnouncement>,
    val alerts: List<PocketAlertRow>,
)

data class PocketWidgetType(val type: String, val title: String, val symbol: String, val detail: String, val canAdd: Boolean)

/**
 * Home dashboard editing and rendering on top of the shared widget model.
 *
 * The layout travels as the JSON the shared [OverviewWidgetInstance] serializer
 * produces, so a layout stored on iOS has the same shape as Android's. All
 * packing, validation and ordering is delegated to [OverviewLayoutEngine].
 */
object PocketDashboard {
    private val serializer = ListSerializer(OverviewWidgetInstance.serializer())

    private val symbols = mapOf(
        "schedule" to "calendar.day.timeline.left", "weather" to "cloud.sun.fill", "youbike" to "bicycle",
        "countdown" to "hourglass", "alerts" to "bell.badge.fill", "calendar" to "calendar", "announcements" to "megaphone.fill",
    )
    private val details = mapOf(
        "schedule" to "現在與下一堂課", "weather" to "指定地點的即時天氣", "youbike" to "指定站點的可借車輛",
        "countdown" to "學校行程或自訂日期", "alerts" to "調課與成績更新", "calendar" to "接下來的學校行程",
        "announcements" to "未讀、置頂與收藏的公告",
    )
    private val privateTypes = setOf("schedule", "alerts")

    private fun decode(layout: String): List<OverviewWidgetInstance> =
        runCatching { OverviewLayoutJson.decodeFromString(serializer, layout) }.getOrNull() ?: demoWidgets()

    private fun encode(widgets: List<OverviewWidgetInstance>): String = OverviewLayoutJson.encodeToString(serializer, widgets)

    /** Android's default widgets plus one weather tile and two countdowns so the demo shows every kind. */
    private fun demoWidgets(): List<OverviewWidgetInstance> {
        val defaults = defaultOverviewWidgets()
        val extras = listOf(
            OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()),
            OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.SchoolEvent("demo-e3", "第二次段考")),
            OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.SchoolEvent("demo-e5", "期末考")),
        )
        return defaults.take(1) + extras + defaults.drop(1)
    }

    fun defaultLayout(): String = encode(demoWidgets())

    fun columns(widthPoints: Double, fontScale: Double): Int =
        OverviewLayoutEngine.columns(widthPoints.toFloat(), fontScale.toFloat())

    fun widgets(
        layout: String, columns: Int, clock: PocketClock, signedIn: Boolean,
        unreadIds: Set<String>, bookmarkedIds: Set<String>,
    ): List<PocketWidget> {
        val instances = decode(layout).filter { it.enabled && OverviewWidgetDefinitions[it.type] != null && it.type in symbols }
        val placements = fillTrailingColumns(OverviewLayoutEngine.pack(instances, columns), columns).associateBy { it.id }
        val overview = DemoCampus.overview(clock)
        return instances.mapNotNull { widget ->
            val place = placements[widget.id] ?: return@mapNotNull null
            val definition = OverviewWidgetDefinitions[widget.type] ?: return@mapNotNull null
            val config = widget.config()
            val locked = widget.type in privateTypes && !signedIn
            val list = config as? OverviewWidgetConfig.ListItems
            val shown = list?.let { if (place.rows == 1) 1 else it.limit } ?: 0
            val countdown = widget.countdownItem(overview)
            PocketWidget(
                id = widget.id, type = widget.type, title = definition.title, symbol = symbols.getValue(widget.type),
                column = place.column, row = place.row, columns = place.columns, rows = place.rows,
                sizes = definition.sizes.map {
                    PocketWidgetSizeOption(it.columns, it.rows, sizeLabel(it), it == widget.size)
                },
                settings = when (widget.type) {
                    "weather" -> PocketWidgetSettings.WEATHER
                    "youbike" -> PocketWidgetSettings.YOUBIKE
                    "countdown" -> PocketWidgetSettings.COUNTDOWN
                    "calendar", "announcements" -> PocketWidgetSettings.LIST
                    else -> PocketWidgetSettings.NONE
                },
                summary = when (config) {
                    is OverviewWidgetConfig.Weather -> config.location.name
                    is OverviewWidgetConfig.YouBike -> config.station.name
                    is OverviewWidgetConfig.SchoolEvent -> countdown?.title ?: config.title.ifBlank { "尚未選擇行程" }
                    is OverviewWidgetConfig.CustomCountdown -> "${config.title} · ${config.date}"
                    is OverviewWidgetConfig.ListItems -> "最多 ${config.limit} 則"
                    else -> details[widget.type].orEmpty()
                },
                selectionId = when (config) {
                    is OverviewWidgetConfig.Weather -> config.location.id
                    is OverviewWidgetConfig.YouBike -> config.station.id
                    is OverviewWidgetConfig.SchoolEvent -> config.eventId
                    else -> ""
                },
                limit = list?.limit ?: 0,
                customTitle = (config as? OverviewWidgetConfig.CustomCountdown)?.title.orEmpty(),
                customDate = (config as? OverviewWidgetConfig.CustomCountdown)?.date.orEmpty(),
                customTime = (config as? OverviewWidgetConfig.CustomCountdown)?.time.orEmpty(),
                needsSignIn = locked,
                hero = if (widget.type == "schedule" && !locked) DemoSchedule.hero(clock) else null,
                weather = (config as? OverviewWidgetConfig.Weather)?.let { PocketData.current.weather(it.location) },
                youBike = (config as? OverviewWidgetConfig.YouBike)?.let { PocketData.current.youBike(it.station) },
                countdown = countdown?.let {
                    PocketCountdown(it.id, it.label, it.title, it.supportingText.removeSuffix(" 開始").slashDate())
                },
                events = if (widget.type == "calendar" && list != null) DemoCampus.events(clock, list, shown) else emptyList(),
                announcements = if (widget.type == "announcements" && list != null)
                    DemoCampus.importantAnnouncements(clock, list, unreadIds, bookmarkedIds).take(shown) else emptyList(),
                alerts = if (widget.type == "alerts" && !locked) DemoSchedule.alerts(clock).take(place.rows * 2) else emptyList(),
            )
        }
    }

    private fun sizeLabel(size: OverviewWidgetSize): String = when {
        size.columns <= 2 && size.rows == 1 -> "小"
        size.columns <= 2 -> "中"
        size.rows == 1 -> "寬"
        size.rows == 2 -> "大"
        else -> "特大"
    }

    /** Widget kinds for the "add" list; single-instance kinds already on the dashboard cannot be added again. */
    private val isoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun String.slashDate(): String = isoDate.replace(this) { match ->
        val (year, month, day) = match.destructured
        "$year/${month.toInt()}/${day.toInt()}"
    }

    /**
     * On wide grids the shared reading-order packing leaves the columns to the right of a
     * row's last widget empty. Rendering stretches that widget across them so tablets get
     * full-width rows; stored sizes and the packing itself are unchanged.
     */
    internal fun fillTrailingColumns(placements: List<OverviewPlacement>, columns: Int): List<OverviewPlacement> {
        if (columns <= 4) return placements
        val occupied = placements.flatMap { place ->
            (place.row until place.row + place.rows).flatMap { y -> (place.column until place.column + place.columns).map { x -> x to y } }
        }.toMutableSet()
        return placements.map { place ->
            var width = place.columns
            while (place.column + width < columns &&
                (place.row until place.row + place.rows).none { y -> (place.column + width) to y in occupied }
            ) {
                for (y in place.row until place.row + place.rows) occupied += (place.column + width) to y
                width++
            }
            place.copy(columns = width)
        }
    }

    fun widgetTypes(layout: String): List<PocketWidgetType> {
        val present = decode(layout).map { it.type }.toSet()
        return OverviewWidgetDefinitions.all.map {
            PocketWidgetType(it.type, it.title, symbols.getValue(it.type), details.getValue(it.type), it.multiple || it.type !in present)
        }
    }

    fun add(layout: String, type: String, clock: PocketClock): String {
        val widgets = decode(layout)
        val definition = OverviewWidgetDefinitions[type] ?: return layout
        if (!definition.multiple && widgets.any { it.type == type }) return layout
        val config = when (type) {
            "weather" -> OverviewWidgetConfig.Weather()
            "youbike" -> OverviewWidgetConfig.YouBike()
            "countdown" -> {
                val used = widgets.mapNotNull { (it.config() as? OverviewWidgetConfig.SchoolEvent)?.eventId }.toSet()
                val event = DemoCampus.upcomingEvents(clock).let { events -> events.firstOrNull { it.id !in used } ?: events.first() }
                OverviewWidgetConfig.SchoolEvent(event.id, event.title)
            }
            "calendar" -> OverviewWidgetConfig.ListItems(3)
            "announcements" -> OverviewWidgetConfig.ListItems(2)
            else -> OverviewWidgetConfig.None
        }
        return encode(widgets + OverviewWidgetInstance.create(type, config))
    }

    fun remove(layout: String, id: String): String = encode(decode(layout).filterNot { it.id == id })

    /** Moves a widget to [index] in reading order. */
    fun move(layout: String, id: String, index: Int): String = encode(OverviewLayoutEngine.move(decode(layout), id, index))

    fun resize(layout: String, id: String, columns: Int, rows: Int): String = update(layout, id) { widget ->
        val size = OverviewWidgetSize(columns, rows)
        if (size in OverviewWidgetDefinitions[widget.type]?.sizes.orEmpty()) widget.copy(size = size) else widget
    }

    fun setWeatherLocation(layout: String, id: String, locationId: String): String = update(layout, id) { widget ->
        val location = PocketData.current.weatherLocations.firstOrNull { it.id == locationId } ?: return@update widget
        val current = widget.config() as? OverviewWidgetConfig.Weather ?: return@update widget
        widget.withConfig(current.copy(location = location))
    }

    fun setYouBikeStation(layout: String, id: String, stationId: String): String = update(layout, id) { widget ->
        val station = PocketData.current.youBikeStations.firstOrNull { it.id == stationId } ?: return@update widget
        if (widget.type == "youbike") widget.withConfig(OverviewWidgetConfig.YouBike(station)) else widget
    }

    fun setSchoolEvent(layout: String, id: String, eventId: String, clock: PocketClock): String = update(layout, id) { widget ->
        val event = DemoCampus.sharedEvents(clock).firstOrNull { it.id == eventId } ?: return@update widget
        if (widget.type == "countdown") widget.withConfig(OverviewWidgetConfig.SchoolEvent(event.id, event.title)) else widget
    }

    /** [date] is `yyyy-MM-dd`; [time] is `HH:mm` or blank for a whole-day countdown. Invalid input is ignored. */
    fun setCustomCountdown(layout: String, id: String, title: String, date: String, time: String): String =
        update(layout, id) { widget ->
            val valid = title.isNotBlank() && parseLocalDateOrNull(date) != null &&
                (time.isBlank() || parseLocalTimeOrNull(time) != null)
            if (widget.type == "countdown" && valid) {
                widget.withConfig(OverviewWidgetConfig.CustomCountdown(title.trim(), date, time.ifBlank { null }))
            } else widget
        }

    fun setListLimit(layout: String, id: String, limit: Int): String = update(layout, id) { widget ->
        val current = widget.config() as? OverviewWidgetConfig.ListItems ?: return@update widget
        widget.withConfig(current.copy(limit = limit.coerceIn(1, 20)))
    }

    fun weatherLocations(): List<PocketChoice> = PocketData.current.weatherLocations.map { PocketChoice(it.id, it.name, it.county) }

    fun youBikeStations(): List<PocketChoice> = PocketData.current.youBikeStations.map { PocketChoice(it.id, it.name, it.district + it.address) }

    fun countdownEvents(clock: PocketClock): List<PocketChoice> = DemoCampus.upcomingEvents(clock).map {
        val row = DemoCampus.eventRow(it, clock.date())
        PocketChoice(it.id, it.title, row.subtitle)
    }

    private fun update(layout: String, id: String, change: (OverviewWidgetInstance) -> OverviewWidgetInstance): String =
        encode(decode(layout).map { if (it.id == id) change(it) else it })
}

/** Fixed stand-ins for the live weather and YouBike feeds, which are not shared yet. */
internal object DemoLive {
    val locations = listOf(
        WeatherLocation.SCHOOL,
        WeatherLocation("taoyuan", "桃園區", 24.9937, 121.3010, county = "桃園市", cwaDataset = "F-D0047-005", cwaLocationName = "桃園區"),
        WeatherLocation("pingzhen", "平鎮區", 24.9459, 121.2182, county = "桃園市", cwaDataset = "F-D0047-005", cwaLocationName = "平鎮區"),
        WeatherLocation("zhongzheng", "中正區", 25.0324, 121.5199, county = "臺北市", cwaDataset = "F-D0047-061", cwaLocationName = "中正區"),
    )

    val stations = listOf(
        YouBikeStation.SCHOOL.copy(district = "中壢區", address = "三光路"),
        YouBikeStation("500304011", "中壢火車站(後站)", 24.9527, 121.2262, "中壢區", "中和路"),
        YouBikeStation("500304025", "中原大學", 24.9573, 121.2409, "中壢區", "中北路"),
        YouBikeStation("500304040", "光明公園", 24.9652, 121.2188, "中壢區", "民族路"),
    )

    fun weather(location: WeatherLocation): PocketWeather {
        val index = locations.indexOfFirst { it.id == location.id }.coerceAtLeast(0)
        val kinds = listOf(
            PocketWeatherKind.PARTLY_CLOUDY to "多雲時晴", PocketWeatherKind.CLOUDY to "陰天",
            PocketWeatherKind.SUNNY to "晴", PocketWeatherKind.RAIN to "短暫雨",
        )
        val (kind, condition) = kinds[index % kinds.size]
        val temperature = 24 - index
        return PocketWeather(
            location = location.name, temperatureText = "$temperature°", condition = condition, kind = kind,
            highText = "${temperature + 3}°", lowText = "${temperature - 5}°", rainText = "${20 + index * 20}%",
            apparentText = "${temperature + 1}°",
        )
    }

    fun youBike(station: YouBikeStation): PocketYouBikeStation {
        val index = stations.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        val electric = 2 + index * 3
        val ordinary = 2 + index * 5
        return PocketYouBikeStation(
            station.id, station.name, "${electric + ordinary}", "${44 - index * 7}", "電輔車 $electric · 一般 $ordinary",
        )
    }
}
