package com.clhs.score.shared.app

import com.clhs.score.data.GradeReport
import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.standardFor
import com.clhs.score.shared.math.formatLocalizedFixed
import kotlin.math.floor

/**
 * Android's 匯出成績 CSV (`GradeExporter.buildCsvContent`): one row per subject of every chosen
 * exam, with a BOM so spreadsheet apps read the Chinese text, and formula-looking cells defused.
 */
object PocketGradeExport {
    private const val BOM = "\uFEFF"
    private val header get() = listOf(
        "學期", "考試名稱", "科目", "分數", "班平均", "班排名", "校/類排名",
        "頂標", "前標", "均標", "後標", "底標", "標準差",
    )

    /** Exam keys of [examValues] whose reports are loaded; the others cannot be exported yet. */
    fun missing(examValues: List<String>): List<String> = examValues.filter { PocketGrades.report(it) == null }

    fun csv(examValues: List<String>): String {
        val reports = examValues.mapNotNull { key ->
            val exam = PocketData.current.locateExam(key)?.second ?: return@mapNotNull null
            PocketGrades.report(key)?.let { exam.text to it }
        }
        return buildCsv(reports)
    }

    internal fun buildCsv(reports: List<Pair<String, GradeReport>>): String = buildString {
        append(BOM)
        appendLine(header.joinToString(",") { escape(it) })
        for ((examName, report) in reports) {
            val term = report.examSummary?.let { summary ->
                val year = summary.year?.toString().orEmpty()
                if (year.isNotEmpty()) "$year${summary.termText}" else summary.termText
            }.orEmpty()
            report.subjects.forEachIndexed { index, subject ->
                val standard = report.standardFor(subject, index)
                val row = listOf(
                    term, examName, cleanSubjectName(subject.subjectName), subject.scoreDisplay, subject.classAverageDisplay,
                    rank(subject.classRank, subject.classRankCount), rank(subject.yearRank, subject.yearRankCount),
                    standard?.top.score(), standard?.front.score(), standard?.average.score(),
                    standard?.back.score(), standard?.bottom.score(), standard?.standardDeviation.score(),
                )
                appendLine(row.joinToString(",") { escape(it) })
            }
        }
    }

    private fun Double?.score(): String = when {
        this == null -> ""
        this == floor(this) && !isInfinite() -> toInt().toString()
        else -> formatLocalizedFixed(this, 1)
    }

    private fun rank(rank: Int?, count: Int?): String = when {
        rank == null -> ""
        count != null && count > 0 -> "$rank/$count"
        else -> "$rank"
    }

    internal fun escape(value: String): String {
        val first = value.dropWhile { it == ' ' }.firstOrNull()
        val safe = if (first != null && first in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
