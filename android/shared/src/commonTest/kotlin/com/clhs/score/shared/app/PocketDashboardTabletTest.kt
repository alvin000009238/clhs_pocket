package com.clhs.score.shared.app

import com.clhs.score.domain.overview.OverviewLayoutEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketDashboardTabletTest {
    private val clock = PocketClock(2026, 11, 18, 3, 635)

    /** On phone-width grids iOS renders exactly the placements Android's layout engine produces. */
    @Test
    fun widgetsUseTheSharedPackingOnPhoneGrids() {
        val layout = PocketDashboard.defaultLayout()
        val instances = PocketDashboard.instances(layout).filter { it.enabled }
        for (columns in listOf(2, 4)) {
            val expected = OverviewLayoutEngine.pack(instances, columns).associate { it.id to listOf(it.column, it.row, it.columns, it.rows) }
            val actual = PocketDashboard.widgets(layout, columns, clock, signedIn = true, unreadIds = emptySet(), bookmarkedIds = emptySet())
                .associate { it.id to listOf(it.column, it.row, it.columns, it.rows) }
            assertEquals(expected, actual, "columns=$columns")
        }
    }

    /** Live mode before the calendar has loaded has no upcoming events; adding a countdown must still work. */
    @Test
    fun addingACountdownWithoutUpcomingEventsCreatesACustomCountdown() {
        PocketData.useLive()
        try {
            val base = PocketDashboard.defaultLayout()
            val added = PocketDashboard.add(base, "countdown", clock)
            val before = PocketDashboard.instances(base).map { it.id }.toSet()
            val widget = PocketDashboard.instances(added).single { it.id !in before }
            assertEquals("countdown", widget.type)
            assertTrue(widget.config() is com.clhs.score.domain.overview.OverviewWidgetConfig.CustomCountdown)
            PocketDashboard.widgets(added, 4, clock, signedIn = false, unreadIds = emptySet(), bookmarkedIds = emptySet())
        } finally {
            PocketData.useDemo()
        }
    }

    /** iPad, portrait and landscape, signed in and guest: the grid has no holes and nothing overlaps. */
    @Test
    fun wideGridsHaveNoHoles() {
        val layout = PocketDashboard.defaultLayout()
        for (columns in listOf(6, 8)) for (signedIn in listOf(true, false)) {
            val case = "columns=$columns signedIn=$signedIn"
            val widgets = PocketDashboard.widgets(layout, columns, clock, signedIn = signedIn, unreadIds = emptySet(), bookmarkedIds = emptySet())
            val cells = widgets.flatMap { w -> (w.row until w.row + w.rows).flatMap { y -> (w.column until w.column + w.columns).map { x -> x to y } } }
            assertEquals(cells.size, cells.toSet().size, "overlap, $case")
            val rows = widgets.maxOf { it.row + it.rows }
            val all = (0 until rows).flatMap { y -> (0 until columns).map { x -> x to y } }.toSet()
            assertEquals(all, cells.toSet(), "holes, $case")
            val lists = widgets.filter { it.type in setOf("alerts", "calendar", "announcements") }
            assertTrue(lists.all { it.columns == columns || it.columns == columns / 2 }, "list widths, $case")
            assertTrue(lists.any { it.columns == columns / 2 }, "two lists side by side, $case")
            // Lists that share a row are equally tall, so neither is stretched around the other.
            for (group in lists.groupBy { it.row }.values) assertEquals(1, group.map { it.rows }.toSet().size, "paired heights, $case")
        }
    }

    /** A guest's locked list is a one-line note: it spans the row instead of matching a tall list. */
    @Test
    fun lockedListSpansTheRowAtOneRowHigh() {
        for (columns in listOf(6, 8)) {
            val alerts = PocketDashboard.widgets(PocketDashboard.defaultLayout(), columns, clock, signedIn = false, unreadIds = emptySet(), bookmarkedIds = emptySet())
                .first { it.type == "alerts" }
            assertEquals(listOf(columns, 1), listOf(alerts.columns, alerts.rows), "columns=$columns")
        }
    }

    private fun wide(layout: String, columns: Int, signedIn: Boolean) =
        PocketDashboard.widgets(layout, columns, clock, signedIn = signedIn, unreadIds = emptySet(), bookmarkedIds = emptySet())

    /** A list set to its one-row size shows one item on iPad too, whatever the row around it does. */
    @Test
    fun listItemCountFollowsItsOwnSizeNotThePlacement() {
        val base = PocketDashboard.defaultLayout()
        val id = PocketDashboard.instances(base).first { it.type == "announcements" }.id
        val layout = PocketDashboard.resize(base, id, 4, 1)
        for (columns in listOf(4, 6, 8)) for (signedIn in listOf(true, false)) {
            val widget = wide(layout, columns, signedIn).first { it.id == id }
            assertEquals(1, widget.announcements.size, "columns=$columns signedIn=$signedIn")
            assertEquals(1, widget.rows, "a list is never taller than its own size, columns=$columns signedIn=$signedIn")
        }
    }

    /** Reading the grid top to bottom never meets a widget before one that precedes it in the edit order. */
    @Test
    fun wideGridsKeepTheEditOrderTopToBottom() {
        val base = PocketDashboard.defaultLayout()
        val weather = PocketDashboard.instances(base).first { it.type == "weather" }.id
        val count = PocketDashboard.instances(base).count { it.enabled }
        for (index in 0 until count) {
            val layout = PocketDashboard.move(base, weather, index)
            for (columns in listOf(6, 8)) for (signedIn in listOf(true, false)) {
                val case = "weather at $index, columns=$columns signedIn=$signedIn"
                val widgets = wide(layout, columns, signedIn)
                widgets.zipWithNext { a, b -> assertTrue(b.row >= a.row, "${b.type} is above ${a.type}; $case") }
                val cells = widgets.flatMap { w -> (w.row until w.row + w.rows).flatMap { y -> (w.column until w.column + w.columns).map { x -> x to y } } }
                val all = (0 until widgets.maxOf { it.row + it.rows }).flatMap { y -> (0 until columns).map { x -> x to y } }
                assertEquals(all.toSet(), cells.toSet(), "holes; $case")
                assertEquals(cells.size, cells.toSet().size, "overlap; $case")
            }
        }
    }
}
