package com.clhs.score.shared.app

import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeSnapshot
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.AnnouncementPriority
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.shared.time.instantOfEpochMilli
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketDashboardSettingsTest {
    private val clock = PocketClock(2026, 11, 18, 3, 635)

    private fun layoutOf(vararg widgets: OverviewWidgetInstance) = PocketDashboard.layout(widgets.toList())
    private fun config(layout: String, id: String) = PocketDashboard.instances(layout).first { it.id == id }.config()

    private val catalogue = """[
      {"id":"tw-1","name":"中壢區","latitude":24.96,"longitude":121.22,"county":"桃園市","cwaDataset":"F-D0047-005","cwaLocationName":"中壢區"},
      {"id":"tw-2","name":"大安區","latitude":25.03,"longitude":121.54,"county":"臺北市","cwaDataset":"F-D0047-061","cwaLocationName":"大安區"},
      {"id":"tw-3","name":"大安區","latitude":24.35,"longitude":120.59,"county":"臺中市"},
      {"id":"jp-1","name":"東京","latitude":35.6,"longitude":139.7,"timezoneId":"Asia/Tokyo","countryCode":"JP"}
    ]"""

    /** Android's catalogue search: 台 equals 臺, all terms must match, foreign places are excluded. */
    @Test
    fun weatherSearchMatchesTheAndroidCatalogueRules() {
        assertTrue(PocketWeatherCatalog.load(catalogue))
        assertEquals(listOf("tw-2", "tw-3"), PocketDashboard.searchWeatherLocations("大安").map { it.id })
        assertEquals(listOf("tw-2"), PocketDashboard.searchWeatherLocations("台北 大安").map { it.id })
        assertTrue(PocketDashboard.searchWeatherLocations("東京").isEmpty())
        assertTrue(PocketDashboard.searchWeatherLocations("  ").isEmpty())
        assertTrue(PocketDashboard.searchWeatherLocations("大".repeat(121)).isEmpty())
        assertEquals(false, PocketWeatherCatalog.load("not json"))
        assertEquals(listOf("tw-2", "tw-3"), PocketDashboard.searchWeatherLocations("大安").map { it.id })
    }

    /** A searched town and source are stored per widget; CWA falls back to Open-Meteo where it has no forecast. */
    @Test
    fun weatherWidgetsKeepTheirOwnLocationAndSource() {
        PocketWeatherCatalog.load(catalogue)
        val a = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
        val b = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
        var layout = layoutOf(a, b)
        layout = PocketDashboard.setWeather(layout, a.id, "tw-2", "CWA")
        layout = PocketDashboard.setWeather(layout, b.id, "tw-3", "CWA")
        assertEquals(OverviewWidgetConfig.Weather(PocketWeatherCatalog.find("tw-2")!!, WeatherSource.CWA), config(layout, a.id))
        assertEquals(WeatherSource.OPEN_METEO, (config(layout, b.id) as OverviewWidgetConfig.Weather).source)
        assertEquals(layout, PocketDashboard.setWeather(layout, a.id, "missing", "CWA"))
        val requests = PocketDashboard.weatherRequests(layout)
        assertEquals(listOf("tw-2" to WeatherSource.CWA, "tw-3" to WeatherSource.OPEN_METEO), requests.map { it.location.id to it.source })
        val widget = PocketDashboard.widgets(layout, 4, clock, true, emptySet(), emptySet()).first { it.id == a.id }
        assertEquals("CWA", widget.weatherSource)
        assertEquals("大安區", widget.selection?.title)
    }

    @Test
    fun demoYouBikeStationsNeverShowNegativeCounts() {
        DemoLive.stations.forEach { station ->
            val row = DemoLive.youBike(station)
            assertTrue(row.bikesText.toInt() >= 0 && row.docksText.toInt() >= 0, "${station.name}: ${row.docksText}")
        }
    }

    /** Any searched station can be chosen, and the live loader asks for each station once. */
    @Test
    fun youBikeAcceptsSearchedStations() {
        val a = OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike())
        val b = OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike())
        val station = YouBikeStation("500999001", "內壢火車站", 24.97, 121.25, "中壢區", "忠孝路")
        var layout = PocketDashboard.selectYouBikeStation(layoutOf(a, b), a.id, station)
        assertEquals(OverviewWidgetConfig.YouBike(station), config(layout, a.id))
        assertEquals(layout, PocketDashboard.selectYouBikeStation(layout, b.id, station.copy(id = "")))
        layout = PocketDashboard.selectYouBikeStation(layout, b.id, station)
        assertEquals(listOf("500999001"), PocketDashboard.youBikeStationsIn(layout).map { it.id })
        assertTrue(PocketDashboard.searchDemoYouBikeStations("台").isEmpty())
        assertTrue(PocketDashboard.searchDemoYouBikeStations("桃園").isNotEmpty())
    }

    @Test
    fun listOptionsMatchAndroidValidation() {
        val announcements = OverviewWidgetInstance.create("announcements", OverviewWidgetConfig.ListItems(2))
        val calendar = OverviewWidgetInstance.create("calendar", OverviewWidgetConfig.ListItems(3))
        var layout = layoutOf(announcements, calendar)
        layout = PocketDashboard.setAnnouncementPriority(layout, announcements.id, listOf("Bookmarked", "Unread", "Pinned"))
        assertEquals(
            listOf(AnnouncementPriority.Bookmarked, AnnouncementPriority.Unread, AnnouncementPriority.Pinned),
            (config(layout, announcements.id) as OverviewWidgetConfig.ListItems).announcementPriority,
        )
        assertEquals(layout, PocketDashboard.setAnnouncementPriority(layout, announcements.id, listOf("Unread", "Unread", "Pinned")))
        layout = PocketDashboard.setMaxEventDuration(layout, calendar.id, 7)
        assertEquals(7, (config(layout, calendar.id) as OverviewWidgetConfig.ListItems).maxEventDurationDays)
        layout = PocketDashboard.setMaxEventDuration(layout, calendar.id, 0)
        assertEquals(null, (config(layout, calendar.id) as OverviewWidgetConfig.ListItems).maxEventDurationDays)
    }

    /** Two widgets on the same town but different sources keep separate live readings. */
    @Test
    fun liveWeatherIsKeptPerSource() {
        val location = WeatherLocation.SCHOOL
        try {
            PocketLive.putWeather(location, WeatherSource.CWA, WeatherSnapshot(
                source = WeatherSource.CWA, locationLabel = location.name, temperatureCelsius = 20,
                apparentTemperatureCelsius = null, condition = "陰", precipitationProbability = null,
                precipitationStart = null, precipitationEndExclusive = null, fetchedAt = instantOfEpochMilli(0),
            ))
            assertEquals("20°", PocketLive.weather(location, WeatherSource.CWA)?.temperatureText)
            assertEquals(null, PocketLive.weather(location, WeatherSource.OPEN_METEO))
        } finally {
            PocketLive.clearAll()
        }
    }

    /** A removed or re-pointed widget leaves no reading behind for a place nobody shows. */
    @Test
    fun unusedLiveReadingsAreDropped() {
        val weather = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather())
        val bike = OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike())
        val layout = layoutOf(weather, bike)
        val request = PocketDashboard.weatherRequests(layout).single()
        val station = PocketDashboard.youBikeStationsIn(layout).single()
        val reading = WeatherSnapshot(
            source = request.source, locationLabel = request.location.name, temperatureCelsius = 20,
            apparentTemperatureCelsius = null, condition = "陰", precipitationProbability = null,
            precipitationStart = null, precipitationEndExclusive = null, fetchedAt = instantOfEpochMilli(0),
        )
        val bikes = YouBikeSnapshot(3, 4, 7, 2, 1, true, instantOfEpochMilli(0), instantOfEpochMilli(0))
        try {
            PocketLive.putWeather(request.location, request.source, reading)
            PocketLive.putWeather(request.location, WeatherSource.CWA, reading)
            PocketLive.putYouBike(station.id, bikes)
            PocketLive.putYouBike("gone", bikes)
            PocketDashboard.dropUnusedLiveData(layout)
            assertEquals("20°", PocketLive.weather(request.location, request.source)?.temperatureText)
            assertEquals(null, PocketLive.weather(request.location, WeatherSource.CWA))
            assertEquals("3", PocketLive.youBike(station)?.bikesText)
            assertEquals(null, PocketLive.youBike(station.copy(id = "gone")))

            PocketDashboard.dropUnusedLiveData(layoutOf())
            assertEquals(null, PocketLive.weather(request.location, request.source))
            assertEquals(null, PocketLive.youBike(station))
        } finally {
            PocketLive.clearAll()
        }
    }
}
