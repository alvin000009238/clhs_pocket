package com.clhs.score.ui

import com.clhs.score.data.MockGradeSystem
import com.clhs.score.data.SubjectAnalysis
import org.junit.Assert.assertEquals
import org.junit.Test

class GradeSubjectBrowsingTest {
    private val baseSubject = MockGradeSystem.generateReport().subjects.first()

    private fun subject(
        name: String,
        score: Double?,
        flunk: Boolean = false,
        scoreDisplay: String = score?.toString() ?: "--",
    ) = SubjectAnalysis(
        subject = baseSubject.copy(
            subjectName = name,
            scoreDisplay = scoreDisplay,
            score = score,
            flunk = flunk,
        ),
        standard = null,
        level = null,
        standardDistance = null,
        distributionSummary = null,
        comparison = null,
    )

    @Test
    fun scoreSortKeepsMissingScoresLastAndTiesInSchoolOrder() {
        val subjects = listOf(subject("未公布", null), subject("甲", 80.0), subject("乙", 40.0), subject("丙", 80.0))

        assertEquals(listOf("乙", "甲", "丙", "未公布"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreAscending, false).map { it.subject.subjectName })
        assertEquals(listOf("甲", "丙", "乙", "未公布"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreDescending, false).map { it.subject.subjectName })
        assertEquals(listOf("未公布", "甲", "乙", "丙"), subjects.map { it.subject.subjectName })
    }

    @Test
    fun unpublishedDisplaysStayLastEvenWithNumericRawScores() {
        val subjects = listOf(
            subject("待公布", 0.0, scoreDisplay = "未公布"),
            subject("缺分", 100.0, scoreDisplay = "--"),
            subject("低分", 40.0),
            subject("高分", 80.0),
        )

        assertEquals(listOf("低分", "高分", "待公布", "缺分"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreAscending, false).map { it.subject.subjectName })
        assertEquals(listOf("高分", "低分", "待公布", "缺分"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreDescending, false).map { it.subject.subjectName })
    }

    @Test
    fun blankDisplaysUseTheSameNumericFallbackAsSubjectCards() {
        val subjects = listOf(
            subject("未公布", 100.0, scoreDisplay = "未公布"),
            subject("空白低分", 40.0, scoreDisplay = ""),
            subject("空格高分", 90.0, scoreDisplay = "  "),
            subject("已公布", 80.0),
            subject("空白零分", null, scoreDisplay = ""),
        )

        assertEquals(listOf("空白零分", "空白低分", "已公布", "空格高分", "未公布"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreAscending, false).map { it.subject.subjectName })
        assertEquals(listOf("空格高分", "已公布", "空白低分", "空白零分", "未公布"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.ScoreDescending, false).map { it.subject.subjectName })
    }

    @Test
    fun filterUsesOfficialFlunkFlagWithoutInventingPassingThresholds() {
        val subjects = listOf(subject("及格", 55.0), subject("待加強", 65.0, flunk = true), subject("未公布", null))

        assertEquals(listOf("待加強"),
            gradeSubjectsForDisplay(subjects, GradeSubjectSort.SchoolOrder, true).map { it.subject.subjectName })
        assertEquals(subjects, gradeSubjectsForDisplay(subjects, GradeSubjectSort.SchoolOrder, false))
    }

    @Test
    fun allPassingAndEmptyReportsHaveNoFilteredSubjects() {
        assertEquals(emptyList<SubjectAnalysis>(),
            gradeSubjectsForDisplay(listOf(subject("及格", 80.0)), GradeSubjectSort.ScoreAscending, true))
        assertEquals(emptyList<SubjectAnalysis>(),
            gradeSubjectsForDisplay(emptyList(), GradeSubjectSort.ScoreDescending, false))
    }
}
