package com.clhs.score.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class GradeReportDifferTest {
    @Test
    fun visibleScoreRankAndStandardChangesAreReported() {
        val before = MockGradeSystem.generateReport()
        val changedSubject = before.subjects.first().copy(
            scoreDisplay = "88",
            score = 88.0,
            classRank = 5,
        )
        val changedStandard = before.standards.first().copy(top = 95.0)
        val after = before.copy(
            subjects = listOf(changedSubject) + before.subjects.drop(1),
            standards = listOf(changedStandard) + before.standards.drop(1),
        )

        val changeSet = GradeReportDiffer.diff(
            before = GradeReportDiffer.snapshot(before),
            after = GradeReportDiffer.snapshot(after),
            studentNo = before.studentInfo.studentNo,
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
        )

        assertTrue(changeSet.changes.any { it.field == GradeChangeField.SUBJECT_SCORE })
        assertTrue(changeSet.changes.any { it.field == GradeChangeField.SUBJECT_CLASS_RANK })
        assertTrue(changeSet.changes.any { it.field == GradeChangeField.STANDARD_TOP })
    }

    @Test
    fun rawResultUpdatedAtAndListOrderDoNotTriggerChanges() {
        val before = MockGradeSystem.generateReport()
        val after = before.copy(
            studentInfo = before.studentInfo.copy(updatedAt = "2026-06-12 10:00"),
            subjects = before.subjects.reversed(),
            standards = before.standards.reversed(),
            rawResult = JsonObject(mapOf("ignored" to JsonPrimitive("changed"))),
        )

        val changeSet = GradeReportDiffer.diff(
            before = GradeReportDiffer.snapshot(before),
            after = GradeReportDiffer.snapshot(after),
            studentNo = before.studentInfo.studentNo,
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
        )

        assertTrue(changeSet.changes.isEmpty())
    }

    @Test
    fun summaryAndStandardPresenceChangesAreReported() {
        val report = MockGradeSystem.generateReport()
        val withoutSummaryOrStandards = report.copy(
            examSummary = null,
            standards = emptyList(),
        )

        val changeSet = GradeReportDiffer.diff(
            before = GradeReportDiffer.snapshot(withoutSummaryOrStandards),
            after = GradeReportDiffer.snapshot(report),
            studentNo = report.studentInfo.studentNo,
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
        )

        assertTrue(changeSet.changes.any { it.field == GradeChangeField.SUMMARY_PRESENCE })
        assertTrue(changeSet.changes.any { it.field == GradeChangeField.STANDARD_PRESENCE })
    }

    @Test
    fun notificationBodyDoesNotIncludeOldOrNewValues() {
        val changeSet = GradeChangeSet(
            studentNo = "S1",
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
            checkedAtMillis = 0L,
            changes = listOf(
                GradeChange(
                    targetName = "數學",
                    subjectName = "數學",
                    field = GradeChangeField.SUBJECT_SCORE,
                    oldValue = "60",
                    newValue = "95",
                ),
            ),
        )

        val body = GradeReminderText.notificationBody(changeSet)

        assertEquals("數學有新資訊", body)
        assertFalse(body.contains("分數"))
        assertFalse(body.contains("60"))
        assertFalse(body.contains("95"))
    }

    @Test
    fun notificationBodyUsesFriendlySummaryLabel() {
        val changeSet = GradeChangeSet(
            studentNo = "S1",
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
            checkedAtMillis = 0L,
            changes = listOf(
                GradeChange(
                    targetName = "數學",
                    subjectName = "數學",
                    field = GradeChangeField.SUBJECT_CLASS_RANK,
                    oldValue = "12",
                    newValue = "8",
                ),
                GradeChange(
                    targetName = "總覽",
                    field = GradeChangeField.SUMMARY_AVERAGE,
                    oldValue = "70",
                    newValue = "72",
                ),
            ),
        )

        val body = GradeReminderText.notificationBody(changeSet)

        assertEquals("數學、整體成績有新資訊", body)
        assertFalse(body.contains("總覽：平均更新"))
        assertFalse(body.contains("12"))
        assertFalse(body.contains("72"))
    }

    @Test
    fun notificationBodySummarizesWhenTooManyTargetsChange() {
        val changeSet = GradeChangeSet(
            studentNo = "S1",
            yearValue = "114_1",
            examValue = "E1",
            examName = "第一次段考",
            checkedAtMillis = 0L,
            changes = listOf("國文", "英文", "數學", "自然").map { subject ->
                GradeChange(
                    targetName = subject,
                    subjectName = subject,
                    field = GradeChangeField.SUBJECT_SCORE,
                    oldValue = "60",
                    newValue = "95",
                )
            },
        )

        val body = GradeReminderText.notificationBody(changeSet)

        assertTrue(body.contains("國文、英文、數學等 4 項有新資訊"))
        assertFalse(body.contains("分數"))
        assertFalse(body.contains("60"))
        assertFalse(body.contains("95"))
    }
}
