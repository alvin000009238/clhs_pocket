package com.clhs.score.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class RecordSemester(val year: Int, val term: Int, val label: String)
data class RecordCategoryCount(val category: String, val count: Int)
data class RecordHistoryStatistic(val category: String, val semesterCounts: List<Int?>)
data class AttendanceDetail(val date: String, val category: String, val count: Int, val sections: String)
data class AttendanceRecord(val summary: List<RecordCategoryCount>, val details: List<AttendanceDetail>)
data class MeritRecord(
    val date: String,
    val checkDate: String,
    val cleanDate: String,
    val category: String,
    val count: Int,
    val reason: String,
    val yearTerm: String,
    val group: String = "",
)

internal object StudentRecordsParser {
    private val historyFields = listOf("First", "Second", "Third", "Fourth", "Fifth")
        .flatMap { grade -> listOf("${grade}GradeFirstTerm", "${grade}GradeSecondTerm") }

    fun semesters(json: String): List<RecordSemester> = array(parse(json)).map { row ->
        val obj = obj(row)
        val year = integer(obj["Year"])
        val term = integer(obj["Term"])
        if (year <= 0 || term !in 1..2) invalid()
        RecordSemester(year, term, text(obj, "YearTermInfoFullName"))
    }.also { semesters ->
        if (semesters.distinctBy { it.year to it.term }.size != semesters.size) invalid()
    }.sortedWith(compareByDescending<RecordSemester> { it.year }.thenByDescending { it.term })

    fun history(json: String, categoryField: String): List<RecordHistoryStatistic> =
        array(result(json)).map { row ->
            val obj = obj(row)
            if (historyFields.none { it in obj }) invalid()
            RecordHistoryStatistic(text(obj, categoryField), historyFields.map { field ->
                val value = obj[field]
                if (value == null || value == JsonNull) null else integer(value)
            }) to order(obj)
        }.sortedBy { it.second }.map { it.first }

    fun attendanceSummary(json: String): List<RecordCategoryCount> = array(obj(result(json))["TermStatisticsList"]).map { row ->
        val obj = obj(row)
        RecordCategoryCount(text(obj, "AbsentType"), integer(obj["AbsentNumber"])) to order(obj)
    }.sortedBy { it.second }.map { it.first }

    fun attendanceDetails(json: String): List<AttendanceDetail> {
        val result = obj(result(json))
        return array(result["absentDetailList"]).map { row ->
            val obj = obj(row)
            val display = optionalText(obj, "SectionListDisplay")
            val sections = if (display.isNotBlank()) {
                val remarks = (obj["AbsentSectionList"] as? JsonArray).orEmpty().mapNotNull { section ->
                    val entry = obj(section)
                    optionalText(entry, "Remark").takeIf { it.isNotBlank() }?.let { "${text(entry, "SectionName")} $it" }
                }
                (listOf(display) + remarks).joinToString("；")
            } else {
                array(obj["AbsentSectionList"]).joinToString("、") { section ->
                    val entry = obj(section)
                    listOf(text(entry, "SectionName"), optionalText(entry, "AbsentText"), optionalText(entry, "Remark"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            AttendanceDetail(
                date = text(obj, "AbsentDateDisplayText"),
                category = text(obj, "AbsentText"),
                count = integer(obj["AbsentCount"]),
                sections = sections,
            )
        }
    }

    fun merits(json: String): List<MeritRecord> = array(result(json)).map { row ->
        val obj = obj(row)
        MeritRecord(
            date = text(obj, "MeritPenaltyDateDisplayString"),
            checkDate = optionalText(obj, "MeritPenaltyCheckDateDisplayString"),
            cleanDate = optionalText(obj, "MertipenaltyCleanDateDisplayString"),
            category = text(obj, "MertiPenaltyText"),
            count = integer(obj["MertiPenaltyCount"]),
            reason = text(obj, "MertipenaltyReason"),
            yearTerm = text(obj, "MertipenaltyYearTerm"),
            group = text(obj, "MertiPenaltyType"),
        )
    }

    private fun result(json: String): JsonElement {
        val root = obj(parse(json))
        if (text(root, "Status") != "Success") throw SchoolException("學校系統未能提供在校紀錄")
        return root["Result"]?.takeUnless { it == JsonNull } ?: invalid()
    }

    private fun parse(json: String): JsonElement = try {
        SchoolJson.parseToJsonElement(json)
    } catch (_: IllegalArgumentException) {
        invalid()
    }

    private fun array(value: JsonElement?): JsonArray = value as? JsonArray ?: invalid()
    private fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: invalid()
    private fun text(obj: JsonObject, field: String): String = optionalText(obj, field).takeIf { it.isNotBlank() } ?: invalid()
    private fun optionalText(obj: JsonObject, field: String): String = when (val value = obj[field]) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> invalid()
    }
    private fun integer(value: JsonElement?): Int = (value as? JsonPrimitive)?.content?.toIntOrNull()
        ?.takeIf { it >= 0 } ?: invalid()
    private fun order(obj: JsonObject): Int = obj["Order"]?.let { value ->
        (value as? JsonPrimitive)?.content?.toIntOrNull() ?: invalid()
    } ?: 0
    private fun invalid(): Nothing = throw SchoolException("學校系統在校紀錄格式錯誤")
}
