package com.clhs.score.ui

import com.clhs.score.data.AttendanceDetail
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordCategoryCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StudentRecordDisplayTest {
    @Test
    fun highlightsUseOnlyProvidedCategoriesAndNeverSubstituteTotalsForMissingCategories() {
        val summary = listOf(
            RecordCategoryCount("合計", 20),
            RecordCategoryCount("遲到", 5),
            RecordCategoryCount("病假", 0),
            RecordCategoryCount("公假", 8),
        )
        assertEquals(listOf(summary[2], summary[3], summary[1]), attendanceHighlights(summary))
        assertEquals(emptyList<RecordCategoryCount>(), attendanceHighlights(listOf(summary[0])))
        assertEquals(listOf(summary[1]), attendanceHighlights(listOf(RecordCategoryCount("全部", 20), summary[1])))
    }

    @Test
    fun datesSortAcrossRocAndGregorianYearsWithoutDroppingDuplicatesOrUnknownDates() {
        val dates = listOf("114/12/31", "校方日期待確認", "2026-10-21", "115年10月22日", "2026-10-21", "115/02/30")
        val records = dates.map { AttendanceDetail(it, "病假", 1, "第 2 節") }
        val groups = attendanceDateGroups(records)
        assertEquals(listOf("115年10月22日", "2026-10-21", "114/12/31", "校方日期待確認", "115/02/30"), groups.map { it.date })
        assertEquals(2, groups[1].records.size)
        assertEquals(records.size, groups.sumOf { it.records.size })
        assertEquals(LocalDate.of(2026, 10, 21), recordDate("115/10/21"))
        assertEquals(LocalDate.of(2026, 10, 21), recordDate("2026/10/21"))
        assertNull(recordDate("10/21"))
        assertNull(recordDate("115/02/30"))
    }

    @Test
    fun meritFiltersUseOfficialGroupAndKeepUnknownGroupsInAll() {
        fun record(group: String) = MeritRecord("115/10/21", "", "", "校方類別", 1, "示範事由", "115-1", group)
        assertTrue(MeritFilter.REWARDS.includes(record("獎")))
        assertTrue(MeritFilter.REWARDS.includes(record("獎勵")))
        assertTrue(MeritFilter.PENALTIES.includes(record("懲")))
        assertTrue(MeritFilter.PENALTIES.includes(record("懲處")))
        assertFalse(MeritFilter.REWARDS.includes(record("待確認")))
        assertFalse(MeritFilter.PENALTIES.includes(record("")))
        assertTrue(MeritFilter.ALL.includes(record("待確認")))
        assertTrue(MeritFilter.ALL.includes(record("")))
    }
}
