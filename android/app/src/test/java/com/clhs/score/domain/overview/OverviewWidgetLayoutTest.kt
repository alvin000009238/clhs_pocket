package com.clhs.score.domain.overview

import com.clhs.score.data.AppSettings
import com.clhs.score.data.OverviewPreferences
import com.clhs.score.data.SchoolCalendarEvent
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.YouBikeStation
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalDate

class OverviewWidgetLayoutTest {
    @Test fun laterSmallWidgetCannotBackfillAboveEarlierFullWidthWidgets() {
        val widgets = listOf(
            weather("youbike", OverviewWidgetSize(2, 1)),
            weather("alerts", OverviewWidgetSize(4, 1)),
            weather("calendar", OverviewWidgetSize(4, 1)),
            weather("countdown", OverviewWidgetSize(2, 1)),
        )
        for (columns in listOf(2, 4, 6, 8)) {
            val placements = OverviewLayoutEngine.pack(widgets, columns)
            assertEquals(widgets.map { it.id }, placements.sortedWith(
                compareBy<OverviewPlacement> { it.row }.thenBy { it.column },
            ).map { it.id })
            assertNoOverlap(placements, columns)
        }
    }

    @Test fun legacyBackfilledCountdownStaysAfterYouBikeWhenYouBikeExpandsAndShrinks() {
        val bike = OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike(YouBikeStation.SCHOOL))
            .copy(id = "bike", size = OverviewWidgetSize(2, 1))
        val countdown = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("Exam", "2026-10-14"))
            .copy(id = "countdown", size = OverviewWidgetSize(2, 1))
        val legacy = listOf(weather("weather", OverviewWidgetSize(4, 1)), bike,
            weather("alerts", OverviewWidgetSize(4, 1)), weather("calendar", OverviewWidgetSize(4, 1)),
            weather("announcements", OverviewWidgetSize(4, 1)), countdown)
        val before = OverviewLayoutEngine.pack(legacy, 4, legacyOrder = true)
        val order = before.sortedWith(compareBy<OverviewPlacement> { it.row }.thenBy { it.column }).map { it.id }
        val normalized = OverviewLayoutEngine.inVisualOrder(legacy, order)
        assertEquals(before.sortedBy { it.id }, OverviewLayoutEngine.pack(normalized, 4).sortedBy { it.id })
        for (size in listOf(OverviewWidgetSize(4, 1), OverviewWidgetSize(2, 2), OverviewWidgetSize(2, 1))) {
            val resized = normalized.map { if (it.id == bike.id) it.copy(size = size) else it }
            val placements = OverviewLayoutEngine.pack(resized, 4)
            val bikePlacement = placements.first { it.id == bike.id }
            val countdownPlacement = placements.first { it.id == countdown.id }
            assertEquals(order, placements.sortedWith(compareBy<OverviewPlacement> { it.row }.thenBy { it.column }).map { it.id })
            if (size.columns == 4) assertEquals(bikePlacement.row + bikePlacement.rows, countdownPlacement.row)
            assertTrue(countdownPlacement.row < placements.first { it.id == "alerts" }.row)
            assertNoOverlap(placements, 4)
        }
    }

    @Test fun visualOrderConversionKeepsHiddenRowsAndOpaqueSettings() {
        val hidden = OverviewWidgetInstance("future", "unknown", OverviewWidgetSize(7, 9), enabled = false,
            configVersion = 99, configuration = "opaque")
        val widgets = listOf(weather("a"), hidden, weather("b"))
        val result = OverviewLayoutEngine.inVisualOrder(widgets, listOf("b", "a", "a", "missing"))
        assertEquals(listOf(widgets[2], hidden, widgets[0]), result)
    }
    @Test fun standaloneSmallWidgetReservesItsRowWithoutGrowingItsWidth() {
        val first = weather("first", OverviewWidgetSize(2, 1))
        val alone = weather("alone", OverviewWidgetSize(2, 1)).copy(ownRow = true)
        val last = weather("last", OverviewWidgetSize(2, 1))
        for (columns in listOf(2, 4, 6, 8)) {
            val placements = OverviewLayoutEngine.pack(listOf(first, alone, last), columns)
            val standalone = placements[1]
            assertEquals(2, standalone.columns)
            assertTrue(placements[2].row > standalone.row)
            assertTrue(placements.filter { it.id != "alone" }.none { it.row == standalone.row })
            assertNoOverlap(placements, columns)
        }
        assertEquals(alone, OverviewLayoutJson.decodeFromString<OverviewWidgetInstance>(OverviewLayoutJson.encodeToString(alone)))
    }

    @Test fun readingOrderDoesNotFillEarlierHoles() {
        val left = weather("left", OverviewWidgetSize(2, 2))
        val wide = weather("wide", OverviewWidgetSize(4, 1))
        val right = weather("right", OverviewWidgetSize(2, 1))
        val placements = OverviewLayoutEngine.pack(listOf(left, wide, right), 4)
        assertEquals(OverviewPlacement("left", 0, 0, 2, 2), placements[0])
        assertEquals(OverviewPlacement("wide", 0, 2, 4, 1), placements[1])
        assertEquals(OverviewPlacement("right", 0, 3, 2, 1), placements[2])
        assertNoOverlap(placements, 4)
    }

    @Test fun fiftyMixedInstancesPackDeterministicallyAtAllResponsiveColumnCounts() {
        val sizes = requireNotNull(OverviewWidgetDefinitions["weather"]).sizes
        val widgets = List(50) { weather("weather-$it", sizes[it % sizes.size]) }
        for (columns in listOf(2, 4, 6, 8)) {
            val placements = OverviewLayoutEngine.pack(widgets, columns)
            assertEquals(50, placements.size)
            assertEquals(widgets.map { it.id }, placements.map { it.id })
            assertEquals(placements, OverviewLayoutEngine.pack(widgets, columns))
            assertNoOverlap(placements, columns)
        }
    }

    @Test fun columnsFollowWidthAndFontScaleBoundaries() {
        assertEquals(2, OverviewLayoutEngine.columns(319f, 1f))
        assertEquals(4, OverviewLayoutEngine.columns(320f, 1f))
        assertEquals(4, OverviewLayoutEngine.columns(599f, 1f))
        assertEquals(6, OverviewLayoutEngine.columns(600f, 1f))
        assertEquals(6, OverviewLayoutEngine.columns(839f, 1f))
        assertEquals(8, OverviewLayoutEngine.columns(840f, 1f))
        assertEquals(4, OverviewLayoutEngine.columns(840f, 1.5f))
        assertEquals(2, OverviewLayoutEngine.columns(400f, 2f))
        assertEquals(4, OverviewLayoutEngine.columns(379f, 1.3f))
        assertEquals(2, OverviewLayoutEngine.columns(328f, 1.3f))
        assertEquals(4, OverviewLayoutEngine.columns(400f, 0.8f))
    }

    @Test fun moveAndNarrowPresentationPreserveIdentityConfigurationAndSavedSize() {
        val large = weather("large", OverviewWidgetSize(4, 2))
        val small = weather("small", OverviewWidgetSize(2, 1))
        val widgets = listOf(large, small)
        val moved = OverviewLayoutEngine.move(widgets, "large", 20)
        assertEquals(listOf(small, large), moved)
        assertEquals(widgets, OverviewLayoutEngine.move(widgets, "unknown", 0))
        assertEquals(widgets, OverviewLayoutEngine.move(moved, "large", -10))
        val narrow = OverviewLayoutEngine.pack(moved, 2)
        assertEquals(2, narrow.first { it.id == "large" }.columns)
        assertEquals(OverviewWidgetSize(4, 2), moved.first { it.id == "large" }.size)
        assertEquals(large.configuration, moved.first { it.id == "large" }.configuration)
        assertNoOverlap(narrow, 2)
    }

    @Test fun disabledAndEmptyLayoutsRemainEmptyAndUnknownSizesAreBounded() {
        assertTrue(OverviewLayoutEngine.pack(emptyList(), 4).isEmpty())
        assertTrue(OverviewLayoutEngine.pack(listOf(weather("disabled").copy(enabled = false)), 4).isEmpty())
        val unknown = OverviewWidgetInstance("future", "future-type", OverviewWidgetSize(Int.MAX_VALUE, -1),
            configVersion = 42, configuration = "opaque future setting")
        val placement = OverviewLayoutEngine.pack(listOf(unknown), 2).single()
        assertEquals(OverviewPlacement("future", 0, 0, 2, 1), placement)
        assertNull(unknown.config())
        val restored = OverviewLayoutJson.decodeFromString<OverviewWidgetInstance>(OverviewLayoutJson.encodeToString(unknown))
        assertEquals(unknown, restored)
    }

    @Test fun defaultsKeepOptionalWidgetsOptInAndIndividualListLimits() {
        val widgets = defaultOverviewWidgets()
        assertEquals(listOf("schedule", "alerts", "calendar", "announcements"), widgets.map { it.type })
        assertEquals(4, widgets.map { it.id }.toSet().size)
        assertTrue(widgets.all { it.enabled && it.config() != null })
        assertEquals(3, (widgets.first { it.type == "calendar" }.config() as OverviewWidgetConfig.ListItems).limit)
        assertEquals(2, (widgets.first { it.type == "announcements" }.config() as OverviewWidgetConfig.ListItems).limit)
        val positions = OverviewLayoutEngine.pack(widgets, 4).associateBy { it.id }
        assertTrue(positions.values.all { it.column == 0 })
    }

    @Test fun retiredDateTimeRowsRoundTripButAreNotOfferedAsWidgets() {
        val widget = OverviewWidgetInstance.create("date_time")
        assertFalse(OverviewWidgetDefinitions.all.any { it.type == "date_time" })
        assertEquals(OverviewWidgetConfig.None, widget.config())
        assertEquals(OverviewWidgetSize(4, 1), widget.size)
        assertFalse(requireNotNull(OverviewWidgetDefinitions[widget.type]).multiple)
        assertEquals(widget, OverviewLayoutJson.decodeFromString<OverviewWidgetInstance>(OverviewLayoutJson.encodeToString(widget)))
        assertNull(widget.withConfig(OverviewWidgetConfig.ListItems()).config())
    }

    @Test fun legacySchoolStationUsesItsOfficialName() {
        val widget = OverviewWidgetInstance.create("youbike",
            OverviewWidgetConfig.YouBike(YouBikeStation.SCHOOL.copy(name = "中壢高中 YouBike")))
        assertEquals(YouBikeStation.SCHOOL, (widget.config() as OverviewWidgetConfig.YouBike).station)
    }

    @Test fun legacyImportRetainsOrderHiddenInstancesSourceLimitsAndMissingCountdowns() {
        val earlier = event("earlier", "2026-10-05T00:00:00")
        val later = event("later", "2026-10-10T00:00:00")
        val settings = AppSettings(weatherSource = WeatherSource.CWA, overview = OverviewPreferences(
            hiddenSections = setOf("weather", "countdown"), calendarLimit = 12, announcementLimit = 7,
            countdownEventIds = setOf("later", "missing", "earlier"),
            sectionOrder = listOf("announcements", "countdown", "weather", "schedule", "calendar", "alerts"),
        ))
        val widgets = importOverviewWidgets(settings, listOf(later, earlier))
        assertEquals(listOf("announcements", "countdown", "countdown", "countdown", "weather", "schedule", "calendar", "alerts"),
            widgets.map { it.type })
        assertTrue(widgets.filter { it.type in setOf("weather", "countdown") }.all { !it.enabled })
        val countdowns = widgets.filter { it.type == "countdown" }.map { it.config() as OverviewWidgetConfig.SchoolEvent }
        assertEquals(setOf("earlier", "later", "missing"), countdowns.map { it.eventId }.toSet())
        assertEquals(listOf("earlier", "later", "missing"), countdowns.map { it.eventId })
        assertEquals("", countdowns.first { it.eventId == "missing" }.title)
        assertEquals(WeatherSource.CWA, (widgets.first { it.type == "weather" }.config() as OverviewWidgetConfig.Weather).source)
        assertEquals(OverviewWidgetSize(4, 1), widgets.first { it.type == "weather" }.size)
        assertEquals(12, (widgets.first { it.type == "calendar" }.config() as OverviewWidgetConfig.ListItems).limit)
        assertEquals(7, (widgets.first { it.type == "announcements" }.config() as OverviewWidgetConfig.ListItems).limit)
    }

    @Test fun importWithoutCachedEventsRetainsEveryIdInDeterministicOrderAndClampsLimits() {
        val widgets = importOverviewWidgets(AppSettings(overview = OverviewPreferences(
            countdownEventIds = linkedSetOf("z-event", "a-event"), calendarLimit = 0, announcementLimit = 999,
        )))
        val countdowns = widgets.filter { it.type == "countdown" }
        assertFalse(widgets.any { it.type == "youbike" })
        assertEquals(listOf("a-event", "z-event"), countdowns.map { (it.config() as OverviewWidgetConfig.SchoolEvent).eventId })
        assertTrue(countdowns.all { it.size == OverviewWidgetSize(2, 1) })
        assertEquals(1, (widgets.first { it.type == "calendar" }.config() as OverviewWidgetConfig.ListItems).limit)
        assertEquals(20, (widgets.first { it.type == "announcements" }.config() as OverviewWidgetConfig.ListItems).limit)
    }

    @Test fun invalidTypedConfigurationsStayOpaqueWithoutChangingTheirStoredJson() {
        val cases = listOf(
            "weather" to OverviewWidgetConfig.Weather(WeatherLocation.SCHOOL.copy(latitude = 91.0)),
            "weather" to OverviewWidgetConfig.Weather(WeatherLocation.SCHOOL.copy(longitude = -181.0)),
            "weather" to OverviewWidgetConfig.Weather(WeatherLocation.SCHOOL.copy(timezoneId = "invalid/timezone")),
            "weather" to OverviewWidgetConfig.Weather(WeatherLocation.SCHOOL.copy(countryCode = "US", cwaDataset = null), WeatherSource.CWA),
            "youbike" to OverviewWidgetConfig.YouBike(YouBikeStation.SCHOOL.copy(id = " ")),
            "youbike" to OverviewWidgetConfig.YouBike(YouBikeStation.SCHOOL.copy(latitude = -91.0)),
            "countdown" to OverviewWidgetConfig.SchoolEvent(""),
            "countdown" to OverviewWidgetConfig.CustomCountdown(" ", "2026-10-05"),
            "countdown" to OverviewWidgetConfig.CustomCountdown("事件", "2026-02-30"),
            "countdown" to OverviewWidgetConfig.CustomCountdown("事件", "2026-10-05", "25:00"),
            "calendar" to OverviewWidgetConfig.ListItems(0),
            "announcements" to OverviewWidgetConfig.ListItems(21),
            "weather" to OverviewWidgetConfig.YouBike(),
        )
        for ((type, config) in cases) {
            val widget = OverviewWidgetInstance.create(type, config)
            val raw = widget.configuration
            assertNull("$type accepted $raw", widget.config())
            assertEquals(raw, widget.configuration)
            assertEquals(widget, OverviewLayoutJson.decodeFromString<OverviewWidgetInstance>(OverviewLayoutJson.encodeToString(widget)))
        }
        val future = weather("future").copy(configVersion = 2)
        assertNull(future.config())
        assertEquals(OverviewWidgetConfig.Weather(), weather("valid").config())
    }

    @Test fun customDateCountdownRetainsExpiredDateAndUsesCalendarDayBoundaries() {
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("考試", "2026-10-05"))
        assertEquals("1 天", widget.countdownItem(state("2026-10-04T23:59:59"))?.label)
        assertEquals("今天", widget.countdownItem(state("2026-10-05T00:00:00"))?.label)
        assertEquals("今天", widget.countdownItem(state("2026-10-05T23:59:59"))?.label)
        val expired = requireNotNull(widget.countdownItem(state("2026-10-06T00:00:00")))
        assertEquals("已到期", expired.label)
        assertEquals(widget.id, expired.id)
        assertEquals("考試", expired.title)
        assertEquals("2026-10-05", expired.supportingText)
        assertEquals(LocalDate.parse("2026-10-05"), expired.displayDate)
    }

    @Test fun timedCountdownHasExplicitExpiryAndNeverShowsZeroMinutesBeforeDeadline() {
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.CustomCountdown("活動", "2026-10-05", "12:00"))
        assertEquals("1 天", widget.countdownItem(state("2026-10-04T11:00:00"))?.label)
        assertEquals("1 小時", widget.countdownItem(state("2026-10-05T10:59:00"))?.label)
        assertEquals("1 分鐘", widget.countdownItem(state("2026-10-05T11:59:59"))?.label)
        assertEquals("已到期", widget.countdownItem(state("2026-10-05T12:00:00"))?.label)
        assertEquals("已到期", widget.countdownItem(state("2026-10-06T00:00:00"))?.label)
        assertNull(widget.withConfig(OverviewWidgetConfig.CustomCountdown("無效", "2026-02-30")).countdownItem(state("2026-10-04T00:00:00")))
    }

    @Test fun multiDaySchoolCountdownShowsActiveStatusThroughLastDay() {
        val event = SchoolCalendarEvent("exam", "期中考", LocalDateTime.parse("2026-10-13T00:00:00"),
            LocalDateTime.parse("2026-10-15T00:00:00"), isAllDay = true)
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.SchoolEvent(event.id, event.title))
        fun label(now: String) = widget.countdownItem(state(now).copy(calendarEvents = listOf(event)))?.label
        assertEquals("1 天", label("2026-10-12T23:59:59"))
        assertEquals("進行中", label("2026-10-13T00:00:00"))
        assertEquals("進行中", label("2026-10-13T12:00:00"))
        assertEquals("進行中", label("2026-10-14T23:59:59"))
        assertEquals("已結束", label("2026-10-15T00:00:00"))
    }

    @Test fun schoolCountdownKeepsStartEndAndMissingEventSemantics() {
        val event = event("school", "2026-10-05T00:00:00")
        val widget = OverviewWidgetInstance.create("countdown", OverviewWidgetConfig.SchoolEvent(event.id, event.title))
        assertEquals("1 天", widget.countdownItem(state("2026-10-04T00:00:00").copy(calendarEvents = listOf(event)))?.label)
        val ongoing = requireNotNull(widget.countdownItem(state("2026-10-05T12:00:00").copy(calendarEvents = listOf(event))))
        assertEquals("進行中", ongoing.label)
        assertTrue(ongoing.supportingText.endsWith("結束"))
        assertEquals(LocalDate.parse("2026-10-05"), ongoing.displayDate)
        assertEquals(event.start.toLocalDate(), widget.countdownItem(state("2026-10-04T00:00:00")
            .copy(calendarEvents = listOf(event)))?.displayDate)
        assertEquals("已結束", widget.countdownItem(state("2026-10-06T00:00:00").copy(calendarEvents = listOf(event)))?.label)
        assertNull(widget.countdownItem(state("2026-10-04T00:00:00")))
        assertEquals(event.id, (widget.config() as OverviewWidgetConfig.SchoolEvent).eventId)
    }

    private fun weather(id: String, size: OverviewWidgetSize = OverviewWidgetSize(2, 2)) =
        OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(id = id, size = size)

    private fun event(id: String, start: String) = LocalDateTime.parse(start).let {
        SchoolCalendarEvent(id, "事件 $id", it, it.plusDays(1), isAllDay = true)
    }

    private fun state(now: String) = OverviewState(now = LocalDateTime.parse(now))

    private fun assertNoOverlap(placements: List<OverviewPlacement>, columns: Int) {
        val occupied = mutableSetOf<Pair<Int, Int>>()
        for (placement in placements) {
            assertTrue(placement.column >= 0 && placement.column + placement.columns <= columns)
            assertTrue(placement.row >= 0 && placement.rows > 0)
            for (row in placement.row until placement.row + placement.rows) {
                for (column in placement.column until placement.column + placement.columns) {
                    assertTrue("Cell $column,$row overlaps at ${placement.id}", occupied.add(column to row))
                }
            }
        }
    }
}
