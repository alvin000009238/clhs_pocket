package com.clhs.score.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PocketScheduleChoiceTest {
    private val store = PocketScheduleStore

    /** Wednesday 2026-11-18. */
    private val clock = PocketClock(2026, 11, 18, 3, 10 * 60)

    @Test
    fun theDemoOffersEveryTermAndTheChosenTermIsTheOneShown() {
        val years = store.years()
        assertTrue(years.size >= 2, "a choice needs more than one term")
        assertNotEquals(years[0].id, years[1].id)

        val older = years[1]
        val (year, term) = older.id.split("_")
        val schedule = store.chosenSchedule(PocketScheduleScope.SEMESTER, clock, older.id, "231", 0, "[]")
        assertEquals("$year 學年度 第 $term 學期", schedule.termText)
        assertEquals("高二 31 班", schedule.className)
        assertNotEquals(
            store.chosenSchedule(PocketScheduleScope.SEMESTER, clock, years.first().id, "231", 0, "[]").termText,
            schedule.termText,
        )
    }

    @Test
    fun aWeekFromTheSchoolListBecomesAnOffsetThatIsNotLimitedToTwoWeeks() {
        val weeks = assertNotNull(store.weeks("115_1"))
        assertEquals(21, weeks.size)
        assertEquals("第1週", weeks.first().title)
        // 115_1 starts on Saturday 2026-08-01, so its first school week starts on Monday 2026-07-27.
        assertEquals("2026-07-27", weeks.first().id)
        assertEquals("7/27 – 8/2", weeks.first().detail)

        val thisWeek = weeks.single { store.weekOffset(clock, it.id) == 0 }
        assertEquals("2026-11-16", thisWeek.id)
        assertEquals(-16, store.weekOffset(clock, weeks.first().id))
        assertEquals(4, store.weekOffset(clock, weeks.last().id))
        assertNull(store.weekOffset(clock, "not a date"))
        // Some terms list weeks from Sunday: Sunday 11/22 starts next week's lessons, Sunday 11/15 this week's.
        assertEquals(1, store.weekOffset(clock, "2026-11-22"))
        assertEquals(0, store.weekOffset(clock, "2026-11-15"))
        assertEquals(1, store.weekOffset(clock, "2026-11-23"))
        // On that Sunday its own school week is the one requested today, not next week.
        val sunday = PocketClock(2026, 11, 22, 7, 10 * 60)
        assertEquals(0, store.weekOffset(sunday, "2026-11-22"))
        assertEquals(-1, store.weekOffset(sunday, "2026-11-15"))
        assertEquals(1, store.weekOffset(sunday, "2026-11-29"))
        // A school week listed from Monday 11/23 has not started on Sunday 11/22.
        assertEquals(0, store.weekOffset(sunday, "2026-11-16"))
        assertEquals(1, store.weekOffset(sunday, "2026-11-23"))

        val later = store.chosenSchedule(PocketScheduleScope.CURRENT_WEEK, clock, "115_1", "230", 4, "[]")
        assertEquals("12/14 – 12/18", later.rangeText)
        assertTrue(later.isAvailable)
    }

    @Test
    fun aMalformedTermHasNoWeeks() {
        assertEquals(emptyList(), store.weeks("garbage"))
    }
}
