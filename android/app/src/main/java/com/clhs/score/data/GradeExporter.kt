package com.clhs.score.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExamSelection(
    val yearValue: String,
    val examValue: String,
    val displayName: String,
)

object GradeExporter {

    private const val BOM = "\uFEFF"

    private val HEADER = listOf(
        "學期", "考試名稱", "科目", "分數", "班平均",
        "班排名", "校/類排名",
        "頂標", "前標", "均標", "後標", "底標", "標準差",
    )

    fun buildCsvContent(reports: List<Pair<String, GradeReport>>): String {
        val sb = StringBuilder()
        sb.append(BOM)
        sb.appendLine(HEADER.joinToString(",") { csvEscape(it) })

        for ((examDisplayName, report) in reports) {
            val yearTermDisplay = report.examSummary?.let { summary ->
                val yearText = summary.year?.toString() ?: ""
                val termText = summary.termText
                if (yearText.isNotEmpty()) "${yearText}${termText}" else termText
            } ?: ""

            for ((index, subject) in report.subjects.withIndex()) {
                val standard = report.standardFor(subject, index)
                val row = listOf(
                    yearTermDisplay,
                    examDisplayName,
                    cleanSubjectName(subject.subjectName),
                    subject.scoreDisplay,
                    subject.classAverageDisplay,
                    formatExportRank(subject.classRank, subject.classRankCount),
                    formatExportRank(subject.yearRank, subject.yearRankCount),
                    standard?.top?.formatScore() ?: "",
                    standard?.front?.formatScore() ?: "",
                    standard?.average?.formatScore() ?: "",
                    standard?.back?.formatScore() ?: "",
                    standard?.bottom?.formatScore() ?: "",
                    standard?.standardDeviation?.formatScore() ?: "",
                )
                sb.appendLine(row.joinToString(",") { csvEscape(it) })
            }
        }
        return sb.toString()
    }

    fun saveCsvToDownloads(
        context: Context,
        csv: String,
        studentNo: String,
        shouldPublish: () -> Boolean = { true },
    ): Result<String> = runCatching {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        val fileName = "${studentNo}_成績_${timestamp}.csv"
        val bytes = csv.toByteArray(Charsets.UTF_8)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: throw IllegalStateException("無法建立檔案")
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("無法寫入檔案")
            if (!shouldPublish()) throw kotlinx.coroutines.CancellationException("Session changed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
        fileName
    }

    private fun Double.formatScore(): String {
        return if (this == kotlin.math.floor(this) && !this.isInfinite()) {
            this.toInt().toString()
        } else {
            "%.1f".format(this)
        }
    }

    private fun formatExportRank(rank: Int?, count: Int?): String {
        if (rank == null) return ""
        return if (count != null && count > 0) "$rank/$count" else "$rank"
    }

    internal fun csvEscape(value: String): String {
        val firstCellCharacter = value.dropWhile { it == ' ' }.firstOrNull()
        val safeValue = if (firstCellCharacter != null && firstCellCharacter in "=+-@\t\r") {
            "'$value"
        } else {
            value
        }
        return if (safeValue.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${safeValue.replace("\"", "\"\"")}\""
        } else {
            safeValue
        }
    }
}
