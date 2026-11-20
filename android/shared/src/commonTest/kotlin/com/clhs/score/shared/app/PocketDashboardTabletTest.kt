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

    /** iPad: no row leaves empty columns, lists sit side by side or fill the row, nothing overlaps. */
    @Test
    fun wideGridsFillEveryRow() {
        val layout = PocketDashboard.defaultLayout()
        for (columns in listOf(6, 8)) {
            val widgets = PocketDashboard.widgets(layout, columns, clock, signedIn = true, unreadIds = emptySet(), bookmarkedIds = emptySet())
            val cells = widgets.flatMap { w -> (w.row until w.row + w.rows).flatMap { y -> (w.column until w.column + w.columns).map { x -> x to y } } }
            assertEquals(cells.size, cells.toSet().size, "overlap at columns=$columns")
            for (w in widgets) assertTrue(w.column >= 0 && w.column + w.columns <= columns, "${w.type} outside the grid")
            // The first grid row of every widget row is covered edge to edge.
            for (row in widgets.map { it.row }.toSet()) {
                assertEquals(columns, widgets.filter { it.row == row }.sumOf { it.columns }, "row $row at columns=$columns")
            }
            val lists = widgets.filter { it.type in setOf("alerts", "calendar", "announcements") }
            assertTrue(lists.all { it.columns == columns || it.columns == columns / 2 }, "list widths at columns=$columns")
            assertTrue(lists.any { it.columns == columns / 2 }, "two lists side by side at columns=$columns")
        }
    }
}
