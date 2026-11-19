package com.clhs.score.shared.app

import com.clhs.score.domain.overview.OverviewLayoutEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class PocketDashboardTabletTest {
    private val clock = PocketClock(2026, 11, 18, 3, 635)

    /** iOS renders exactly the placements Android's layout engine produces, on every grid width. */
    @Test
    fun widgetsUseTheSharedPackingOnEveryGridWidth() {
        val layout = PocketDashboard.defaultLayout()
        val instances = PocketDashboard.instances(layout).filter { it.enabled }
        for (columns in listOf(2, 4, 6, 8)) {
            val expected = OverviewLayoutEngine.pack(instances, columns).associate { it.id to listOf(it.column, it.row, it.columns, it.rows) }
            val actual = PocketDashboard.widgets(layout, columns, clock, signedIn = true, unreadIds = emptySet(), bookmarkedIds = emptySet())
                .associate { it.id to listOf(it.column, it.row, it.columns, it.rows) }
            assertEquals(expected, actual, "columns=$columns")
        }
    }
}
