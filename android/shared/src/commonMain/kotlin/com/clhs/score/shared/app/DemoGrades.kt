package com.clhs.score.shared.app

internal object DemoGrades {
    const val DEFAULT_EXAM = "114_1_E4"

    val terms = listOf(
        PocketGradeTerm(
            "114 學年度 第 1 學期", "114_1",
            listOf(
                PocketExamOption("第一次段考", "114_1_E1"),
                PocketExamOption("第二次段考", "114_1_E2"),
                PocketExamOption("期末考", "114_1_E4"),
            ),
        ),
        PocketGradeTerm(
            "113 學年度 第 2 學期", "113_2",
            listOf(
                PocketExamOption("第一次段考", "113_2_E1"),
                PocketExamOption("第二次段考", "113_2_E2"),
                PocketExamOption("期末考", "113_2_E3"),
            ),
        ),
    )

    private const val CLASS_SIZE = 38
    private const val CATEGORY_SIZE = 312
    private const val CORE_CREDITS = 4
    private const val OTHER_CREDITS = 2

    private data class Subject(val name: String, val isCore: Boolean)

    private val subjects = listOf(
        Subject("國文", true), Subject("英文", true), Subject("數學", true), Subject("物理", false),
        Subject("化學", false), Subject("歷史", false), Subject("地理", false),
    )

    /** Chronological. Scores and class averages are held in tenths. */
    private data class Exam(
        val value: String,
        val scores: List<Int>,
        val classAverages: List<Int>,
        val classRank: Int,
        val categoryRank: Int,
        val updatedAt: String,
    )

    private val exams = listOf(
        Exam("113_2_E1", listOf(740, 810, 680, 720, 660, 850, 790), listOf(752, 741, 663, 684, 671, 783, 762), 17, 148, "2026/04/02 16:20"),
        Exam("113_2_E2", listOf(770, 790, 720, 700, 710, 830, 810), listOf(748, 735, 672, 690, 683, 776, 770), 15, 131, "2026/05/21 16:05"),
        Exam("113_2_E3", listOf(790, 840, 700, 750, 690, 870, 800), listOf(761, 752, 668, 701, 677, 790, 765), 13, 118, "2026/07/02 15:40"),
        Exam("114_1_E1", listOf(760, 820, 750, 730, 720, 860, 830), listOf(755, 746, 681, 695, 690, 781, 772), 12, 104, "2026/10/15 16:10"),
        Exam("114_1_E2", listOf(810, 850, 730, 780, 700, 880, 820), listOf(762, 758, 676, 712, 694, 792, 768), 10, 92, "2026/11/26 16:30"),
        Exam("114_1_E4", listOf(830, 880, 780, 760, 580, 900, 850), listOf(766, 761, 684, 705, 687, 797, 774), 8, 76, "2027/01/20 15:55"),
    )

    private fun examName(value: String): String =
        terms.flatMap { it.exams }.first { it.value == value }.text

    private fun term(value: String): PocketGradeTerm = terms.first { term -> term.exams.any { it.value == value } }

    /** Credit-weighted average in hundredths, rounded half up. */
    private fun weightedAverage(exam: Exam): Int {
        var total = 0
        var credits = 0
        subjects.forEachIndexed { index, subject ->
            val credit = if (subject.isCore) CORE_CREDITS else OTHER_CREDITS
            total += exam.scores[index] * credit
            credits += credit
        }
        return (total * 10 + credits / 2) / credits
    }

    private fun topPercent(rank: Int, count: Int): Int = ((rank * 100 + count / 2) / count).coerceIn(1, 100)

    fun report(examValue: String): PocketGradeReport {
        val index = exams.indexOfFirst { it.value == examValue }.takeIf { it >= 0 }
            ?: exams.indexOfFirst { it.value == DEFAULT_EXAM }
        val exam = exams[index]
        val average = weightedAverage(exam)
        val previous = exams.getOrNull(index - 1)
        val delta = previous?.let { average - weightedAverage(it) }

        val rows = subjects.mapIndexed { i, subject ->
            val score = exam.scores[i]
            val classAverage = exam.classAverages[i]
            val diff = score - classAverage
            PocketSubjectGrade(
                name = subject.name,
                scoreText = (score / 10).toString(),
                fraction = score / 1000.0,
                averageFraction = classAverage / 1000.0,
                classAverageText = tenthsText(classAverage),
                diffText = (if (diff > 0) "+" else "") + tenthsText(diff),
                diffSign = if (diff > 0) 1 else if (diff < 0) -1 else 0,
                classRankText = "${subjectRank(diff, i)}/$CLASS_SIZE",
                yearRankText = "${subjectRank(diff, i) * 8 - i}/$CATEGORY_SIZE",
                isFlunk = score < 600,
            )
        }
        val diffs = subjects.indices.map { it to exam.scores[it] - exam.classAverages[it] }
        fun insight(entry: Pair<Int, Int>) = PocketSubjectInsight(
            name = subjects[entry.first].name,
            detail = "${exam.scores[entry.first] / 10} 分 · 班平均 ${tenthsText(exam.classAverages[entry.first])}",
            percentText = (if (entry.second > 0) "+" else "") + tenthsText(entry.second),
        )
        val termExams = term(exam.value).exams.map { it.value }
        return PocketGradeReport(
            examValue = exam.value,
            examTitle = examName(exam.value),
            termTitle = term(exam.value).text,
            averageText = hundredthsText(average),
            totalText = (exam.scores.sum() / 10).toString(),
            deltaText = delta?.let { (if (it > 0) "+" else "") + hundredthsText(it) } ?: "",
            deltaSign = when {
                delta == null || delta == 0 -> 0
                delta > 0 -> 1
                else -> -1
            },
            classRankText = "${exam.classRank}",
            categoryRankText = "${exam.categoryRank}",
            classTopText = "$CLASS_SIZE 人 · 前 ${topPercent(exam.classRank, CLASS_SIZE)}%",
            categoryTopText = "$CATEGORY_SIZE 人 · 前 ${topPercent(exam.categoryRank, CATEGORY_SIZE)}%",
            strengths = diffs.filter { it.second >= 10 }.sortedByDescending { it.second }.take(2).map(::insight),
            weaknesses = diffs.filter { it.second <= -10 }.sortedBy { it.second }.take(2).map(::insight),
            subjects = rows,
            trend = exams.filter { it.value in termExams }.map {
                val value = weightedAverage(it)
                PocketTrendPoint(examName(it.value), value / 100.0, hundredthsText(value))
            },
            updatedAtText = exam.updatedAt,
        )
    }

    private fun subjectRank(diffTenths: Int, index: Int): Int {
        val base = when {
            diffTenths >= 120 -> 3
            diffTenths >= 60 -> 7
            diffTenths >= 0 -> 14
            diffTenths >= -60 -> 23
            else -> 31
        }
        return (base + index).coerceIn(1, CLASS_SIZE)
    }
}
