package com.clhs.score.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketCampusStoreTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)

    private fun list(
        category: String = "全部", query: String = "",
        filter: PocketAnnouncementFilter = PocketAnnouncementFilter.ALL,
        unread: Set<String> = PocketCampusStore.defaultUnreadIds(), bookmarks: Set<String> = emptySet(),
    ) = PocketCampusStore.announcements(clock, category, query, filter, unread, bookmarks)

    @Test
    fun announcementsFilterByCategorySearchAndState() {
        val all = list()
        val category = PocketCampusStore.categories(clock)[1]
        assertTrue(list(category = category).all { it.category == category })
        assertTrue(list(category = category).size < all.size)
        assertTrue(list(query = "運動會").all { "運動會" in it.title })
        assertEquals(emptyList(), list(query = "不存在的關鍵字"))
        assertTrue(list(filter = PocketAnnouncementFilter.UNREAD).all { it.isUnread })
        val saved = all.last().id
        assertEquals(listOf(saved), list(filter = PocketAnnouncementFilter.BOOKMARKED, bookmarks = setOf(saved)).map { it.id })
        assertEquals(all.sortedByDescending { it.isPinned }.map { it.isPinned }, all.map { it.isPinned })
    }

    @Test
    fun monthGridIsWholeWeeksStartingMondayAndMarksToday() {
        val month = PocketCampusStore.month(clock, 2026, 11)
        assertEquals(0, month.days.size % 7)
        assertEquals("26", month.days.first().dayText) // Monday 26 October 2026
        assertEquals(30, month.days.count { it.inMonth })
        assertEquals("18", month.days.single { it.isToday }.dayText)
        assertTrue(PocketCampusStore.eventsOn(clock, "2026-11-18").isNotEmpty())
        assertEquals(31, PocketCampusStore.month(clock, 2026, 12).days.count { it.inMonth })
    }

    @Test
    fun recordsSummariseTheSelectedSemester() {
        val attendance = PocketCampusStore.attendance(0)
        assertEquals(3, attendance.rows.size)
        assertTrue(PocketCampusStore.attendance(1).rows.isEmpty())
        val merits = PocketCampusStore.merits(0)
        assertTrue(merits.rows.single { it.category == "警告" }.note.isNotEmpty())
    }
}

class PocketScheduleStoreTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)

    private fun week(offset: Int = 0, overrides: String = "[]") =
        PocketScheduleStore.schedule(PocketScheduleScope.CURRENT_WEEK, clock, "", offset, overrides)

    @Test
    fun overrideRenamesEveryLessonOfTheSubjectAndCanBeCleared() {
        val subject = week().cells.first { it.change == PocketCellChange.NONE }.subject
        val saved = PocketScheduleStore.setOverride("[]", subject, "自訂科目", "", "B101")
        val cells = week(overrides = saved).cells.filter { it.originalSubject == subject }
        assertTrue(cells.isNotEmpty() && cells.all { it.subject == "自訂科目" && it.classroom == "B101" && it.isOverridden })
        assertEquals(1, PocketScheduleStore.overrides(saved).size)
        assertEquals(emptyList(), PocketScheduleStore.overrides(PocketScheduleStore.clearOverride(saved, subject)))
        assertEquals(emptyList(), PocketScheduleStore.overrides(PocketScheduleStore.setOverride("[]", subject, subject, " ", "")))
    }

    @Test
    fun otherWeeksShiftDatesAndNeverShowACurrentLesson() {
        assertTrue(week().cells.any { it.status == PocketCellStatus.CURRENT })
        val next = week(offset = 1)
        assertEquals("11/23", next.days.first().dateText)
        assertTrue(next.cells.none { it.status != PocketCellStatus.NORMAL } && next.days.none { it.isToday })
    }
}

class PocketDashboardTabletTest {
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60 + 35)

    private fun widgets(columns: Int) =
        PocketDashboard.widgets(PocketDashboard.defaultLayout(), columns, clock, true, emptySet(), emptySet())

    @Test
    fun wideGridsFillEachRowWithoutOverlapAndPhonesAreUntouched() {
        assertTrue(widgets(4).all { it.column + it.columns <= 4 })
        val wide = widgets(6)
        val cells = wide.flatMap { w -> (w.row until w.row + w.rows).flatMap { y -> (w.column until w.column + w.columns).map { x -> x to y } } }
        assertEquals(cells.size, cells.toSet().size)
        assertTrue(wide.all { it.column + it.columns <= 6 })
        assertTrue(wide.filter { it.type in setOf("alerts", "calendar", "announcements") }.all { it.columns == 6 })
    }

    @Test
    fun editingAddsRemovesReordersResizesAndConfiguresWidgets() {
        val start = PocketDashboard.defaultLayout()
        fun types(layout: String) = widgetsOf(layout).map { it.type }
        val added = PocketDashboard.add(start, "youbike", clock)
        assertEquals(types(start) + "youbike", types(added))
        assertEquals(start, PocketDashboard.add(start, "schedule", clock)) // single-instance widget
        val bike = widgetsOf(added).last()
        assertEquals("youbike", widgetsOf(PocketDashboard.move(added, bike.id, 0)).first().type)
        assertEquals(types(start), types(PocketDashboard.remove(added, bike.id)))

        val weather = widgetsOf(start).first { it.type == "weather" }
        val resized = widgetsOf(PocketDashboard.resize(start, weather.id, 4, 1)).first { it.id == weather.id }
        assertEquals(4 to 1, resized.columns to resized.rows)
        assertEquals(start, PocketDashboard.resize(start, weather.id, 3, 3)) // not an offered size
        val other = PocketDashboard.weatherLocations().first { it.id != weather.selectionId }
        assertEquals(other.id, widgetsOf(PocketDashboard.setWeatherLocation(start, weather.id, other.id)).first { it.id == weather.id }.selectionId)

        val countdown = widgetsOf(start).first { it.type == "countdown" }
        assertEquals(start, PocketDashboard.setCustomCountdown(start, countdown.id, "畢旅", "not-a-date", ""))
        val custom = widgetsOf(PocketDashboard.setCustomCountdown(start, countdown.id, "畢旅", "2026-12-01", "")).first { it.id == countdown.id }
        assertEquals("畢旅", custom.countdown?.title)
        assertEquals("2026/12/1", custom.countdown?.dateText)

        val list = widgetsOf(start).first { it.type == "calendar" }
        assertEquals(1, widgetsOf(PocketDashboard.setListLimit(start, list.id, 1)).first { it.id == list.id }.events.size)
    }

    @Test
    fun guestsNeverReceivePrivateWidgetContent() {
        val guest = PocketDashboard.widgets(PocketDashboard.defaultLayout(), 4, clock, false, emptySet(), emptySet())
        val private = guest.filter { it.type == "schedule" || it.type == "alerts" }
        assertTrue(private.isNotEmpty() && private.all { it.needsSignIn && it.hero == null && it.alerts.isEmpty() })
    }

    private fun widgetsOf(layout: String) = PocketDashboard.widgets(layout, 4, clock, true, emptySet(), emptySet())
}
