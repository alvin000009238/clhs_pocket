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
        runCatching { OverviewLayoutJson.decodeFromString(serializer, layout) }.getOrNull()
            ?: if (PocketData.isDemo) demoWidgets() else defaultOverviewWidgets()

    private fun encode(widgets: List<OverviewWidgetInstance>): String = OverviewLayoutJson.encodeToString(serializer, widgets)

    /** The layout as stored in the shared settings, and back. */
    fun instances(layout: String): List<OverviewWidgetInstance> = decode(layout)
    fun layout(instances: List<OverviewWidgetInstance>): String = encode(instances)

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

    /** Demo mode shows every widget kind; real data starts from the same default as Android. */
    fun defaultLayout(): String = encode(if (PocketData.isDemo) demoWidgets() else defaultOverviewWidgets())

    fun columns(widthPoints: Double, fontScale: Double): Int =
        OverviewLayoutEngine.columns(widthPoints.toFloat(), fontScale.toFloat())

    private val listTypes = setOf("alerts", "calendar", "announcements")

    /**
     * Phone-width grids use Android's packing unchanged. On wider grids (iPad) the widgets flow in
     * reading order into rows without holes: each row's widgets widen to share the full width, a
     * shorter widget stacks under an equally wide neighbour or grows to the row's height, and list
     * widgets take half the grid so two sit side by side, or the whole row when alone. A [locked]
     * list is a one-line sign-in note, so it spans the row at one row high instead of being paired
     * with a tall list. Stored sizes are untouched: the same layout packs the Android way on a phone.
     */
    internal fun place(
        instances: List<OverviewWidgetInstance>, columns: Int, locked: Set<String> = emptySet(),
    ): List<OverviewPlacement> {
        val packed = OverviewLayoutEngine.pack(instances, columns)
        if (columns <= 4) return packed
        val types = instances.associate { it.id to it.type }
        val ownRow = instances.filter { it.ownRow }.map { it.id }.toSet()
        fun isList(item: OverviewPlacement) = types[item.id] in listTypes
        fun alone(item: OverviewPlacement) = item.id in ownRow || (isList(item) && item.id in locked)

        // A line is a row of slots; a slot is one widget or equally wide widgets stacked.
        class Slot(var width: Int, val items: MutableList<OverviewPlacement>) { val height get() = items.sumOf { it.rows } }
        val lines = mutableListOf<MutableList<Slot>>()
        for (packedItem in packed) {
            val item = when {
                isList(packedItem) && packedItem.id in locked -> packedItem.copy(columns = columns, rows = 1)
                isList(packedItem) -> packedItem.copy(columns = columns / 2)
                else -> packedItem
            }
            val line = lines.lastOrNull()
            // Lists pair only with an equally tall list, so none is stretched beyond its own size.
            val fits = line != null && !alone(item) && line.none { slot ->
                slot.items.any { alone(it) || isList(it) != isList(item) || (isList(item) && it.rows != item.rows) }
            }
            val last = line?.lastOrNull()
            // Once a slot holds a stack, a new slot beside it would put a later widget above an
            // earlier one, so the line only accepts more of the stack.
            val stacked = line?.any { it.items.size > 1 } == true
            if (fits && !isList(item) && last != null && last.width == item.columns &&
                last.height + item.rows <= line!!.maxOf { it.height }
            ) {
                last.items += item
            } else if (fits && !stacked && line!!.sumOf { it.width } + item.columns <= columns) {
                line += Slot(item.columns, mutableListOf(item))
            } else {
                lines += mutableListOf(Slot(item.columns, mutableListOf(item)))
            }
        }
        val result = mutableListOf<OverviewPlacement>()
        var row = 0
        for (line in lines) {
            var spare = columns - line.sumOf { it.width }
            var index = 0
            while (spare > 0) { line[index % line.size].width++; spare--; index++ }
            val height = line.maxOf { it.height }
            var column = 0
            for (slot in line) {
                var y = row
                slot.items.forEachIndexed { i, item ->
                    // The last widget of a slot grows to the bottom of the line.
                    val rows = if (i == slot.items.lastIndex) row + height - y else item.rows
                    result += item.copy(column = column, row = y, columns = slot.width, rows = rows)
                    y += rows
                }
                column += slot.width
            }
            row += height
        }
        return result
    }

    fun widgets(
        layout: String, columns: Int, clock: PocketClock, signedIn: Boolean,
        unreadIds: Set<String>, bookmarkedIds: Set<String>,
    ): List<PocketWidget> {
        val instances = decode(layout).filter { it.enabled && OverviewWidgetDefinitions[it.type] != null && it.type in symbols }
        val lockedIds = if (signedIn) emptySet() else instances.filter { it.type in privateTypes }.map { it.id }.toSet()
        val placements = place(instances, columns, lockedIds).associateBy { it.id }
        val ownSizes = OverviewLayoutEngine.pack(instances, columns).associateBy { it.id }
        val overview = DemoCampus.overview(clock)
        return instances.mapNotNull { widget ->
            val place = placements[widget.id] ?: return@mapNotNull null
            val definition = OverviewWidgetDefinitions[widget.type] ?: return@mapNotNull null
            val config = widget.config()
            val locked = widget.type in privateTypes && !signedIn
            val list = config as? OverviewWidgetConfig.ListItems
            // Counts follow the size the student chose, not a placement widened or grown to fill a row.
            val ownRows = ownSizes[widget.id]?.rows ?: place.rows
            val shown = list?.let { if (ownRows == 1) 1 else it.limit } ?: 0
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
                alerts = if (widget.type == "alerts" && !locked) DemoSchedule.alerts(clock).take(ownRows * 2) else emptyList(),
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

    fun widgetTypes(layout: String): List<PocketWidgetType> {
        // A widget switched off on Android is still stored; it is not on screen and can be added.
        val present = decode(layout).filter { it.enabled }.map { it.type }.toSet()
        return OverviewWidgetDefinitions.all.map {
            PocketWidgetType(it.type, it.title, symbols.getValue(it.type), details.getValue(it.type), it.multiple || it.type !in present)
        }
    }

    fun add(layout: String, type: String, clock: PocketClock): String {
        val widgets = decode(layout)
        val definition = OverviewWidgetDefinitions[type] ?: return layout
        if (!definition.multiple) {
            val existing = widgets.firstOrNull { it.type == type }
            if (existing?.enabled == true) return layout
            // Adding a switched-off single widget turns it back on, at the end like any new one.
            if (existing != null) return encode(widgets.filterNot { it.id == existing.id } + existing.copy(enabled = true))
        }
        val config = when (type) {
            "weather" -> OverviewWidgetConfig.Weather()
            "youbike" -> OverviewWidgetConfig.YouBike()
            "countdown" -> {
                val used = widgets.mapNotNull { (it.config() as? OverviewWidgetConfig.SchoolEvent)?.eventId }.toSet()
                val events = DemoCampus.upcomingEvents(clock)
                val event = events.firstOrNull { it.id !in used } ?: events.firstOrNull()
                // The calendar may not be loaded yet (offline, first launch) or hold nothing upcoming:
                // the widget then starts as a custom countdown the student can edit.
                if (event != null) OverviewWidgetConfig.SchoolEvent(event.id, event.title)
                else OverviewWidgetConfig.CustomCountdown("倒數日", clock.date().toString())
            }
            "calendar" -> OverviewWidgetConfig.ListItems(3)
            "announcements" -> OverviewWidgetConfig.ListItems(2)
            else -> OverviewWidgetConfig.None
        }
        return encode(widgets + OverviewWidgetInstance.create(type, config))
    }

    fun remove(layout: String, id: String): String = encode(decode(layout).filterNot { it.id == id })

    /** Moves a widget to [index] in reading order. */
    fun move(layout: String, id: String, index: Int): String {
        val all = decode(layout)
        // [index] counts the widgets on screen; hidden ones (disabled, or unknown to this version)
        // keep their place in the stored list, so the position is translated first.
        val visible = all.filter { it.enabled && OverviewWidgetDefinitions[it.type] != null && it.type in symbols && it.id != id }
        val rest = all.filterNot { it.id == id }
        val target = visible.getOrNull(index.coerceAtLeast(0))?.let { anchor -> rest.indexOfFirst { it.id == anchor.id } } ?: rest.size
        return encode(OverviewLayoutEngine.move(all, id, target))
    }

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
