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
}
