package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import com.clhs.score.data.cleanSubjectName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketSubjectTrendsTest {
    private val trends = PocketSubjectTrends
    private val allTerms get() = trends.terms().map { it.id }

    @Test
    fun everyTermIsComparedOldestFirst() {
        assertEquals(listOf("113_2", "114_1"), allTerms)
        val trend = trends.trend(allTerms, emptyList())
        assertEquals(6, trend.exams.size)
        assertEquals("113下 第一次段考", trend.exams.first().label)
        assertEquals("114上 期末考", trend.exams.last().label)
        assertEquals("已全選（共 2 個學期）", trend.yearsText)
        assertEquals("", trend.loadMessage)
    }

    @Test
    fun aSubjectBecomesOneSolidLineThroughEveryExamWithAScore() {
        val subject = trends.trend(allTerms, emptyList()).availableSubjects.first()
        val trend = trends.trend(allTerms, listOf(subject))
        assertEquals("", trend.emptyTitle)
        val expected = listOf(
            "113_2" to "113_2_E1", "113_2" to "113_2_E2", "113_2" to "113_2_E3",
            "114_1" to "114_1_E1", "114_1" to "114_1_E2", "114_1" to "114_1_E4",
        ).map { (year, exam) ->
            FakeData.reportFor(year, exam).subjects.first { cleanSubjectName(it.subjectName).substringBefore("-") == subject }.scoreValue
        }
        val line = trend.series.single()
        assertEquals(expected.filter { it > 0 }, line.values.map { it.score })
        assertEquals(line.values.size - 1, trend.segments.count { !it.isDashed })
        assertTrue(trend.segments.none { it.isDashed }, "an unbroken line has no dashed bridge")
        assertTrue(trend.minScore <= line.values.minOf { it.score } && trend.maxScore >= line.values.maxOf { it.score })
        assertTrue(trend.description.startsWith("6 次考試的成績趨勢。113下 第一次段考，"), trend.description)
    }

    @Test
    fun oneTermHasOnlyItsOwnExamsAndTheChoiceIsDescribed() {
        val trend = trends.trend(listOf("114_1"), emptyList())
        assertEquals(listOf("114上 第一次段考", "114上 第二次段考", "114上 期末考"), trend.exams.map { it.label })
        assertEquals("已選 1 個學期", trend.yearsText)
        assertEquals("選擇要比較的科目", trend.emptyTitle)
        assertEquals("選擇科目", trend.emptyAction)
    }

    @Test
    fun withoutTermsOrWithTooFewScoresTheChartAsksForAnotherChoice() {
        val none = trends.trend(emptyList(), listOf("國語文"))
        assertEquals("選擇要比較的學期", none.emptyTitle)
        assertEquals("選擇學期", none.emptyAction)
        assertEquals("尚未選擇學期", none.yearsText)

        val unknown = trends.trend(allTerms, listOf("不存在的科目"))
        assertEquals("選取的科目只有 0 次可比較成績", unknown.emptyTitle)
    }

    @Test
    fun subjectsOfOneGroupShareAColourAndALegendEntry() {
        val trend = trends.trend(allTerms, listOf("數學A", "數學B", "國語文"))
        assertEquals(listOf(0, 0, 1), trend.series.map { it.colorIndex })
        assertEquals(listOf("數學A/數學B", "國語文"), trend.legend.map { it.label })
    }
}
