package com.clhs.score.shared.app

import com.clhs.score.data.cleanSubjectName
import com.clhs.score.data.getSubjectBaseName
import com.clhs.score.data.parseYearTerm
import com.clhs.score.data.shortenSubjectName
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One exam on the x axis, oldest first. [label] is unique across terms. */
data class PocketTrendExam(val index: Int, val label: String, val termLabel: String, val examName: String)

data class PocketTrendValue(val examIndex: Int, val score: Double, val scoreText: String)

/** The scores of one subject key; exams without a score have no value. */
data class PocketTrendSeries(
    val key: String,
    val label: String,
    val baseName: String,
    val colorIndex: Int,
    val values: List<PocketTrendValue>,
)

/**
 * A line between two points of the same subject group. Dashed when it bridges a gap or joins two
 * keys of the same subject (for example 數學A one term and 數學B the next), as Android draws it.
 */
data class PocketTrendSegment(
    val baseName: String,
    val colorIndex: Int,
    val fromIndex: Int,
    val fromScore: Double,
    val toIndex: Int,
    val toScore: Double,
    val isDashed: Boolean,
)

data class PocketTrendLegend(val baseName: String, val label: String, val colorIndex: Int)

data class PocketSubjectTrend(
    val exams: List<PocketTrendExam>,
    val availableSubjects: List<String>,
    val series: List<PocketTrendSeries>,
    val segments: List<PocketTrendSegment>,
    val legend: List<PocketTrendLegend>,
    val minScore: Double,
    val maxScore: Double,
    /** Empty when the chart can be drawn; otherwise what to show instead and the button to offer. */
    val emptyTitle: String,
    val emptyMessage: String,
    val emptyAction: String,
    /** VoiceOver summary of every point. */
    val description: String,
    val yearsText: String,
    val subjectsText: String,
    /** 「部分資料無法載入」 or 「載入折線圖資料失敗」 when reports of the chosen terms are missing. */
    val loadMessage: String,
)

/**
 * The multi-semester subject comparison of Android's `SubjectTrendScreen`: chosen terms and
 * subjects, one line per subject group, built from the reports the source has.
 */
object PocketSubjectTrends {
    private val source get() = PocketData.current

    private fun order(value: String): Int = parseYearTerm(value, "0", "0").let { (year, term) ->
        (year.toIntOrNull() ?: 0) * 10 + (term.toIntOrNull() ?: 0)
    }

    private fun key(subjectName: String) = cleanSubjectName(subjectName).substringBefore("-")

    /** Terms that have exams, oldest first as Android lists them. */
    fun terms(): List<PocketChoice> = source.structure.filter { it.exams.isNotEmpty() }
        .sortedBy { order(it.value) }
        .map { PocketChoice(it.value, it.text, "${it.exams.size} 次考試") }

    /** Exam keys of [yearValues], for loading their reports. */
    fun examKeys(yearValues: List<String>): List<String> = source.structure
        .filter { it.value in yearValues }
        .sortedBy { order(it.value) }
        .flatMap { term -> term.exams.map { PocketExamKey.of(term.value, it.value) } }

    fun trend(yearValues: List<String>, subjectKeys: List<String>): PocketSubjectTrend {
        val terms = source.structure.filter { it.value in yearValues }.sortedBy { order(it.value) }
        val requested = terms.sumOf { it.exams.size }
        val reports = terms.flatMap { term ->
            val (year, half) = parseYearTerm(term.value, "", "")
            val termLabel = year + if (half == "2") "下" else "上"
            term.exams.mapNotNull { exam -> source.report(term.value, exam.value)?.let { Triple(termLabel, exam.text, it) } }
        }
        val exams = reports.mapIndexed { index, (termLabel, examName, _) ->
            PocketTrendExam(index, "$termLabel $examName", termLabel, examName)
        }
        val available = reports.flatMap { (_, _, report) -> report.subjects.map { key(it.subjectName) } }.toSet().sorted()

        val colorOfBase = mutableMapOf<String, Int>()
        val series = subjectKeys.map { subjectKey ->
            val baseName = getSubjectBaseName(subjectKey)
            val colorIndex = colorOfBase.getOrPut(baseName) { colorOfBase.size }
            PocketTrendSeries(
                key = subjectKey,
                label = shortenSubjectName(subjectKey),
                baseName = baseName,
                colorIndex = colorIndex,
                values = reports.mapIndexedNotNull { index, (_, _, report) ->
                    report.subjects.firstOrNull { key(it.subjectName) == subjectKey }
                        ?.takeIf { it.scoreValue > 0 }
                        ?.let { PocketTrendValue(index, it.scoreValue, it.scoreDisplay) }
                },
            )
        }
        val scores = series.flatMap { it.values.map(PocketTrendValue::score) }
        val (low, high) = if (scores.isEmpty()) 0.0 to 100.0 else max(0.0, scores.min() - 10) to min(100.0, scores.max() + 10)
        val pointCount = series.maxOfOrNull { it.values.size } ?: 0

        val (emptyTitle, emptyMessage, emptyAction) = when {
            yearValues.isEmpty() -> Triple("選擇要比較的學期", "可一次選擇多個學期，再比較同一科目的變化。", "選擇學期")
            subjectKeys.isEmpty() -> Triple("選擇要比較的科目", "選好後，就能比較各次考試的成績變化。", "選擇科目")
            pointCount < 2 -> Triple("選取的科目只有 $pointCount 次可比較成績", "至少需要 2 次成績才能繪製趨勢，請調整學期或科目。", "選擇科目")
            else -> Triple("", "", "")
        }
        val allTerms = terms().size
        return PocketSubjectTrend(
            exams = exams,
            availableSubjects = available,
            series = series,
            segments = segments(series),
            legend = series.groupBy { it.baseName }.map { (baseName, members) ->
                PocketTrendLegend(baseName, members.map { it.label }.distinct().joinToString("/"), members.first().colorIndex)
            },
            minScore = low,
            maxScore = high,
            emptyTitle = emptyTitle,
            emptyMessage = emptyMessage,
            emptyAction = emptyAction,
            description = buildString {
                append("${exams.size} 次考試的成績趨勢。")
                append(
                    exams.flatMap { exam ->
                        series.mapNotNull { line ->
                            line.values.firstOrNull { it.examIndex == exam.index }
                                ?.let { "${exam.label}，${line.label} ${it.score.roundToInt()} 分" }
                        }
                    }.joinToString("；"),
                )
            },
            yearsText = when (yearValues.size) {
                0 -> "尚未選擇學期"
                allTerms -> "已全選（共 $allTerms 個學期）"
                else -> "已選 ${yearValues.size} 個學期"
            },
            subjectsText = if (subjectKeys.isEmpty()) "尚未選擇科目" else "已選 ${subjectKeys.size} 科",
            loadMessage = when {
                requested == 0 || reports.size == requested -> ""
                reports.isEmpty() -> "載入折線圖資料失敗"
                else -> "部分資料無法載入"
            },
        )
    }

    /** Android's `SubjectTrendLineChart` lines: solid within a key, dashed across gaps and keys. */
    private fun segments(series: List<PocketTrendSeries>): List<PocketTrendSegment> =
        series.groupBy { it.baseName }.flatMap { (baseName, members) ->
            val colorIndex = members.first().colorIndex
            val solid = members.flatMap { line ->
                line.values.zipWithNext().filter { (a, b) -> b.examIndex - a.examIndex == 1 }.map { (a, b) ->
                    PocketTrendSegment(baseName, colorIndex, a.examIndex, a.score, b.examIndex, b.score, isDashed = false)
                }
            }
            val groups = members.flatMap { line -> line.values.map { line.key to it } }
                .groupBy { it.second.examIndex }.entries.sortedBy { it.key }.map { it.value }
            val dashed = groups.zipWithNext().flatMap { (current, next) ->
                val currentKeys = current.map { it.first }.toSet()
                val nextKeys = next.map { it.first }.toSet()
                current.flatMap { (key1, p1) ->
                    next.mapNotNull { (key2, p2) ->
                        val otherSubject = key1 != key2 && (key1 !in nextKeys || key2 !in currentKeys)
                        val sameSubjectGap = key1 == key2 && p2.examIndex - p1.examIndex > 1
                        if (otherSubject || sameSubjectGap) {
                            PocketTrendSegment(baseName, colorIndex, p1.examIndex, p1.score, p2.examIndex, p2.score, isDashed = true)
                        } else {
                            null
                        }
                    }
                }
            }
            solid + dashed
        }
}
