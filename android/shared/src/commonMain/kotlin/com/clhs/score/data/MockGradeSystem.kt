package com.clhs.score.data

import com.clhs.score.shared.math.BigDecimal
import com.clhs.score.shared.math.DECIMAL_ZERO
import com.clhs.score.shared.math.RoundingMode
import com.clhs.score.shared.math.formatFixed
import com.clhs.score.shared.math.plus
import com.clhs.score.shared.math.times
import kotlinx.serialization.json.JsonObject

enum class StudentScenario {
    NORMAL, EXCELLENT, STRUGGLING, SPECIAL
}

object MockGradeSystem {
    private const val DEMO_STUDENT_NO = "DEMO-000"
    private const val DEMO_STUDENT_NAME = "範例學生"
    private const val DEMO_CLASS_NAME = "示範班級"
    private const val DEMO_SEAT_NO = "00"

    data class SubjectSpec(
        val name: String,
        val classAverage: Double,
        val standard: GradeStandard,
    )

    val subjectSpecs = listOf(
        SubjectSpec(
            name = "國文",
            classAverage = 76.2,
            standard = GradeStandard("國文", 91.0, 84.0, 76.0, 68.0, 55.0, 10.4, 4, 9, 12, 8, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "英文",
            classAverage = 74.8,
            standard = GradeStandard("英文", 90.0, 82.0, 75.0, 66.0, 52.0, 11.6, 3, 8, 13, 8, 4, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "數學",
            classAverage = 70.5,
            standard = GradeStandard("數學", 88.0, 80.0, 71.0, 60.0, 45.0, 14.2, 5, 7, 9, 8, 5, 2, 1, 1, 0, 0),
        ),
        SubjectSpec(
            name = "自然",
            classAverage = 73.4,
            standard = GradeStandard("自然", 89.0, 81.0, 73.0, 64.0, 50.0, 12.3, 4, 7, 12, 8, 5, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "社會",
            classAverage = 75.0,
            standard = GradeStandard("社會", 92.0, 84.0, 75.0, 67.0, 54.0, 10.8, 3, 10, 11, 9, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "地理",
            classAverage = 77.1,
            standard = GradeStandard("地理", 93.0, 85.0, 77.0, 69.0, 56.0, 9.9, 4, 9, 12, 8, 3, 1, 1, 0, 0, 0),
        ),
        SubjectSpec(
            name = "歷史",
            classAverage = 72.6,
            standard = GradeStandard("歷史", 89.0, 82.0, 73.0, 65.0, 52.0, 11.1, 2, 8, 12, 10, 4, 1, 1, 0, 0, 0),
        ),
    )

    fun generateReport(
        scenario: StudentScenario = StudentScenario.NORMAL,
        year: Int = 114,
        termText: String = "第 1 學期",
        examName: String = "期末考",
        customTotalScore: Double? = null,
        customAverageScore: Double? = null,
        customClassRank: Double? = null,
        customCategoryRank: Double? = null,
        customScores: List<Double>? = null,
        customSubjectNames: List<String>? = null,
    ): GradeReport {
        val yearTermDisplay = "$year 學年度 $termText"
        
        val scores = customScores ?: when (scenario) {
            StudentScenario.NORMAL -> listOf(78.0, 75.0, 80.0, 76.0, 70.0, 79.0, 74.0)
            StudentScenario.EXCELLENT -> listOf(95.0, 98.0, 100.0, 92.0, 96.0, 90.0, 94.0)
            StudentScenario.STRUGGLING -> listOf(45.0, 50.0, 30.0, 40.0, 55.0, 60.0, 42.0)
            StudentScenario.SPECIAL -> listOf(80.0, -1.0, 90.0, -2.0, 70.0, 85.0, 75.0) // -1:缺考, -2:作弊
        }

        val classCount = 38
        val yearRankCount = 226

        val subjects = subjectSpecs.zip(scores).mapIndexedNotNull { index, (spec, scoreValue) ->
            if (scoreValue == -999.0) return@mapIndexedNotNull null
            
            val subjectName = customSubjectNames?.getOrNull(index) ?: spec.name
            val classAverage = spec.classAverage
            val isAbsent = scoreValue == -1.0
            val isCheating = scoreValue == -2.0
            val actualScore = if (isAbsent || isCheating) 0.0 else scoreValue
            val scoreDisplay = when {
                isAbsent -> "缺考"
                isCheating -> "作弊"
                else -> formatFixed(actualScore, 2)
            }

            val subjectClassRank = if (isAbsent || isCheating) classCount else subjectRank(actualScore, classAverage, index)
            val subjectYearRank = if (isAbsent || isCheating) yearRankCount else (subjectClassRank * 6 - 2).coerceIn(1, yearRankCount)

            SubjectScore(
                subjectName = subjectName,
                scoreDisplay = scoreDisplay,
                score = if (isAbsent || isCheating) null else actualScore,
                classAverageDisplay = formatFixed(classAverage, 2),
                classAverage = classAverage,
                classRank = subjectClassRank,
                classRankCount = classCount,
                yearRank = subjectYearRank,
                yearRankCount = yearRankCount,
                yearTermDisplay = yearTermDisplay,
                flunk = actualScore < 60.0,
                absent = isAbsent,
                cheating = isCheating,
            )
        }

        val credits = checkNotNull(SimulatorCredits.defaultCredits(subjects))
        val computedTotal = subjects.fold(DECIMAL_ZERO) { total, subject ->
            val score = if (subject.absent || subject.cheating) DECIMAL_ZERO else subject.scoreDisplay.let(::BigDecimal)
            total + score * BigDecimal(credits.getValue(cleanSubjectName(subject.subjectName)))
        }
        val totalCredits = credits.values.sum()
        val computedAverage = if (totalCredits > 0) {
            computedTotal.divide(BigDecimal(totalCredits), 2, RoundingMode.HALF_UP)
        } else {
            DECIMAL_ZERO.setScale(2, RoundingMode.HALF_UP)
        }
        val finalTotalScore = customTotalScore?.let { formatFixed(it, 2) }
            ?: computedTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()
        val finalAverageScore = customAverageScore?.let { formatFixed(it, 2) }
            ?: computedAverage.toPlainString()
        
        val finalClassRank = customClassRank ?: when (scenario) {
            StudentScenario.EXCELLENT -> 1.0
            StudentScenario.STRUGGLING -> 35.0
            else -> 15.0
        }
        val finalCategoryRank = customCategoryRank ?: (finalClassRank * 6 - 2).coerceIn(1.0, yearRankCount.toDouble())

        return GradeReport(
            message = "fake mock data",
            studentInfo = StudentInfo(
                studentNo = DEMO_STUDENT_NO,
                studentName = DEMO_STUDENT_NAME,
                className = DEMO_CLASS_NAME,
                seatNo = DEMO_SEAT_NO,
                updatedAt = "2026-05-20 08:00",
                showClassRank = true,
                showClassRankCount = true,
                showCategoryRank = true,
                showCategoryRankCount = true,
            ),
            examSummary = ExamSummary(
                year = year,
                termText = termText,
                examName = examName,
                totalScoreDisplay = finalTotalScore,
                averageScoreDisplay = finalAverageScore,
                classRank = finalClassRank,
                classCount = classCount,
                categoryRank = finalCategoryRank,
                categoryRankCount = yearRankCount,
                flunkCount = subjects.count { it.flunk },
            ),
            subjects = subjects,
            standards = subjectSpecs.map { it.standard },
            rawResult = JsonObject(emptyMap()),
        )
    }

    private fun subjectRank(score: Double, classAverage: Double, index: Int): Int {
        val base = when {
            score - classAverage >= 12.0 -> 4
            score - classAverage >= 6.0 -> 8
            score >= classAverage -> 15
            score >= classAverage - 6.0 -> 23
            else -> 31
        }
        return (base + index).coerceIn(1, 38)
    }
}
