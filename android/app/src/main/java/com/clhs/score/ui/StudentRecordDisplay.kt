package com.clhs.score.ui

import com.clhs.score.data.AttendanceDetail
import com.clhs.score.data.MeritRecord
import com.clhs.score.data.RecordCategoryCount
import java.time.DateTimeException
import java.time.LocalDate

internal data class AttendanceDateGroup(val date: String, val records: List<AttendanceDetail>)

internal enum class MeritFilter(val label: String) {
    ALL("全部"), REWARDS("獎勵"), PENALTIES("懲處");

    fun includes(record: MeritRecord): Boolean = when (this) {
        ALL -> true
        REWARDS -> record.group.trim() in listOf("獎", "獎勵")
        PENALTIES -> record.group.trim() in listOf("懲", "懲處", "罰", "懲罰")
    }
}

internal fun attendanceHighlights(summary: List<RecordCategoryCount>): List<RecordCategoryCount> {
    val categories = summary.filterNot { it.category.trim() in listOf("全部", "合計", "總計", "總數", "總節數", "總節次", "總缺曠", "缺曠總計") }
    val preferred = listOf("病假", "事假", "曠課")
    return categories.sortedWith(
        compareBy<RecordCategoryCount> { preferred.indexOf(it.category.trim()).takeIf { index -> index >= 0 } ?: preferred.size }
            .thenByDescending { if (it.category.trim() in preferred) 0 else it.count },
    ).take(3)
}

internal fun attendanceDateGroups(details: List<AttendanceDetail>): List<AttendanceDateGroup> =
    details.groupBy { it.date }.entries
        .sortedWith(compareByDescending { recordDate(it.key) })
        .map { AttendanceDateGroup(it.key, it.value) }

internal fun recordDate(source: String): LocalDate? {
    val match = Regex("^(?:民國)?(\\d{2,4})[年/.-](\\d{1,2})[月/.-](\\d{1,2})(?:日)?(?:\\s*[（(].*[）)])?$")
        .matchEntire(source.trim()) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toInt().let { if (it < 1000) it + 1911 else it }
    return try {
        LocalDate.of(year, monthText.toInt(), dayText.toInt())
    } catch (_: DateTimeException) {
        null
    }
}
