package com.clhs.score.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal val SchoolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonObject.string(key: String, default: String = ""): String {
    val element = this[key]
    if (element == null || element is JsonNull) return default
    return element.asPrimitiveOrNull()?.contentOrNull ?: default
}

internal fun JsonObject.boolean(key: String, default: Boolean = false): Boolean {
    val primitive = this[key].asPrimitiveOrNull() ?: return default
    return primitive.booleanOrNull ?: primitive.contentOrNull?.toBooleanStrictOrNull() ?: default
}

internal fun JsonObject.int(key: String): Int? {
    val primitive = this[key].asPrimitiveOrNull() ?: return null
    return primitive.intOrNull ?: primitive.doubleOrNull?.toInt() ?: primitive.contentOrNull?.toDoubleOrNull()?.toInt()
}

internal fun JsonObject.double(key: String): Double? {
    val primitive = this[key].asPrimitiveOrNull() ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

internal fun parseGradeReport(raw: String): GradeReport {
    val root = SchoolJson.parseToJsonElement(raw).jsonObject
    val result = root["Result"].asObjectOrNull() ?: JsonObject(emptyMap())
    val examItem = result["ExamItem"].asObjectOrNull()
    val subjectItems = result["SubjectExamInfoList"].asArrayOrNull().orEmpty()
    val firstSubject = subjectItems.firstNotNullOfOrNull { it.asObjectOrNull() }
    val subjectYearTerm = firstSubject?.get("YearTermItem").asObjectOrNull()
    val subjects = subjectItems.mapNotNull { item ->
        val subject = item.asObjectOrNull() ?: return@mapNotNull null
        SubjectScore(
            subjectName = subject.string("SubjectName"),
            scoreDisplay = subject.string("ScoreDisplay"),
            score = subject.double("Score"),
            classAverageDisplay = subject.string("ClassAVGScoreDisplay"),
            classAverage = subject.double("ClassAVGScore"),
            classRank = subject.int("ClassRank"),
            classRankCount = subject.int("ClassRankCount"),
            yearRank = subject.int("YearRank"),
            yearRankCount = subject.int("YearRankCount"),
            yearTermDisplay = subject.string("YearTermDisplay"),
            flunk = subject.boolean("Flunk"),
            absent = subject.boolean("Is缺考"),
            cheating = subject.boolean("Is作弊"),
        )
    }
    val standards = result["成績五標List"].asArrayOrNull()?.mapNotNull { item ->
        val standard = item.asObjectOrNull() ?: return@mapNotNull null
        GradeStandard(
            subjectName = standard.string("SubjectName"),
            top = standard.double("頂標"),
            front = standard.double("前標"),
            average = standard.double("均標"),
            back = standard.double("後標"),
            bottom = standard.double("底標"),
            standardDeviation = standard.double("標準差"),
            above90Count = standard.int("大於90Count") ?: 0,
            above80Count = standard.int("大於80Count") ?: 0,
            above70Count = standard.int("大於70Count") ?: 0,
            above60Count = standard.int("大於60Count") ?: 0,
            above50Count = standard.int("大於50Count") ?: 0,
            above40Count = standard.int("大於40Count") ?: 0,
            above30Count = standard.int("大於30Count") ?: 0,
            above20Count = standard.int("大於20Count") ?: 0,
            above10Count = standard.int("大於10Count") ?: 0,
            above0Count = standard.int("大於0Count") ?: 0,
        )
    }.orEmpty()

    return GradeReport(
        message = root.string("Message"),
        studentInfo = StudentInfo(
            studentNo = result.string("StudentNo"),
            studentName = result.string("StudentName"),
            className = result.string("StudentClassName"),
            seatNo = result.string("StudentSeatNo"),
            updatedAt = result.string("GetDataTimeDisplay"),
            showClassRank = result.boolean("Show班級排名"),
            showClassRankCount = result.boolean("Show班級排名人數"),
            showCategoryRank = result.boolean("Show類組排名"),
            showCategoryRankCount = result.boolean("Show類組排名人數"),
        ),
        examSummary = (examItem ?: firstSubject)?.let {
            ExamSummary(
                year = firstSubject?.int("Year") ?: examItem?.int("Year"),
                termText = subjectYearTerm?.string("TermText").orEmpty()
                    .ifBlank { firstSubject?.string("Term").orEmpty() }
                    .ifBlank { examItem?.string("Term").orEmpty() },
                examName = firstSubject?.string("ExamName").orEmpty()
                    .ifBlank { examItem?.string("ExamName").orEmpty() },
                totalScoreDisplay = examItem?.string("TotalScoreDisplay").orEmpty(),
                averageScoreDisplay = examItem?.string("AVGScoreDisplay").orEmpty(),
                classRank = examItem?.double("ClassRank"),
                classCount = examItem?.int("ClassCount"),
                categoryRank = examItem?.double("類組排名"),
                categoryRankCount = examItem?.int("類組排名Count"),
                flunkCount = examItem?.int("FlunkCount"),
            )
        },
        subjects = subjects,
        standards = standards,
        rawResult = result,
    )
}

internal fun parseOptions(raw: String): List<Pair<String, String>> {
    val element = SchoolJson.parseToJsonElement(raw)
    val array = element.asArrayOrNull() ?: element.jsonArray
    return array.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        val text = obj.string("DisplayText").ifBlank { obj.string("text") }
        val value = obj.string("Value").ifBlank { obj.string("value") }
        if (value.isBlank()) null else text to value
    }
}
