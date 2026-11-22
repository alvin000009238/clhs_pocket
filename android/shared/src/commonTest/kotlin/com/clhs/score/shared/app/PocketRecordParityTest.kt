package com.clhs.score.shared.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PocketRecordParityTest {
    private val store = PocketCampusStore

    @Test
    fun meritsFilterByTheSchoolsGroupNewestFirstAndKeepTheirApprovalDetails() {
        assertEquals(listOf("全部", "獎勵", "懲處"), store.meritFilters())
        val all = store.merits(0, "全部")
        assertEquals(listOf("115/10/22", "115/10/14", "115/10/06"), all.rows.map { it.dateText })
        assertEquals(listOf("嘉獎", "小功"), store.merits(0, "獎勵").rows.map { it.category })
        val penalty = store.merits(0, "懲處").rows.single()
        assertEquals("警告", penalty.category)
        assertEquals("懲", penalty.group)
        assertEquals("115/10/16", penalty.checkDate)
        assertEquals("115/10/23", penalty.cleanDate)
        assertEquals("115 學年度第 1 學期", penalty.yearTerm)
        // The summary counts the whole semester whatever the filter.
        assertEquals(all.summary, store.merits(0, "懲處").summary)
    }

    @Test
    fun anEmptyFilterIsNotAnEmptySemester() {
        assertEquals("這個學期沒有獎懲紀錄", store.merits(1, "全部").emptyText)
        assertTrue(store.merits(1, "獎勵").rows.isEmpty())
    }

    @Test
    fun attendanceHighlightsPutSickLeavePersonalLeaveAndTruancyFirst() {
        assertEquals(listOf("病假", "事假", "曠課"), store.attendance(0).highlights.map { it.category })
    }

    @Test
    fun historyHasSixHalvesAndMarksMissingOnes() {
        val history = assertNotNull(store.meritHistory())
        assertEquals(listOf("一上", "一下", "二上", "二下", "三上", "三下"), history.terms)
        val row = history.rows.single()
        assertEquals(listOf("2", "1", "2", "0", "—", "—"), row.cells)
        assertEquals("嘉獎 三上 未提供", row.descriptions[4])
        assertEquals("嘉獎 一上 2 次", row.descriptions[0])
        assertEquals(3, assertNotNull(store.attendanceHistory()).rows.size)
    }
}
