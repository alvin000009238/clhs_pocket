package com.clhs.score.data

import com.clhs.score.shared.math.BigDecimal
import com.clhs.score.shared.math.DECIMAL_ZERO
import com.clhs.score.shared.math.RoundingMode
import com.clhs.score.shared.math.decimalValueOf
import com.clhs.score.shared.math.formatFixed
import com.clhs.score.shared.math.formatLocalizedFixed
import com.clhs.score.shared.math.minus
import com.clhs.score.shared.math.plus
import com.clhs.score.shared.math.times
import com.clhs.score.shared.math.toPlainDecimalOrNull

import kotlin.math.abs
import kotlin.math.round

object GradeReportDiffer {
    fun snapshot(report: GradeReport): GradeReminderSnapshot {
        val summary = report.examSummary?.let { exam ->
            GradeReminderSummarySnapshot(
                examName = exam.examName.trim(),
                totalScore = exam.totalScoreDisplay.canonicalDisplayValue(),
                averageScore = exam.averageScoreDisplay.canonicalDisplayValue(),
                classRank = exam.classRank.canonicalNumberValue(),
                classCount = exam.classCount?.toString(),
                categoryRank = exam.categoryRank.canonicalNumberValue(),
                categoryRankCount = exam.categoryRankCount?.toString(),
                flunkCount = exam.flunkCount?.toString(),
            )
        }

        return GradeReminderSnapshot(
            summary = summary,
            subjects = report.subjects
                .map { subject ->
                    GradeReminderSubjectSnapshot(
                        key = cleanSubjectName(subject.subjectName),
                        name = cleanSubjectName(subject.subjectName),
                        score = subject.scoreDisplay.canonicalDisplayValue()
                            ?: subject.score.canonicalNumberValue(),
                        classAverage = subject.classAverageDisplay.canonicalDisplayValue()
                            ?: subject.classAverage.canonicalNumberValue(),
                        classRank = subject.classRank?.toString(),
                        classRankCount = subject.classRankCount?.toString(),
                        yearRank = subject.yearRank?.toString(),
                        yearRankCount = subject.yearRankCount?.toString(),
                        flunk = subject.flunk.yesNo(),
                        absent = subject.absent.yesNo(),
                        cheating = subject.cheating.yesNo(),
                    )
                }
                .sortedBy { it.key },
            standards = report.standards
                .map { standard ->
                    GradeReminderStandardSnapshot(
                        key = cleanSubjectName(standard.subjectName),
                        name = cleanSubjectName(standard.subjectName),
                        top = standard.top.canonicalNumberValue(),
                        front = standard.front.canonicalNumberValue(),
                        average = standard.average.canonicalNumberValue(),
                        back = standard.back.canonicalNumberValue(),
                        bottom = standard.bottom.canonicalNumberValue(),
                        standardDeviation = standard.standardDeviation.canonicalNumberValue(),
                        above90Count = standard.above90Count.toString(),
                        above80Count = standard.above80Count.toString(),
                        above70Count = standard.above70Count.toString(),
                        above60Count = standard.above60Count.toString(),
                        above50Count = standard.above50Count.toString(),
                        above40Count = standard.above40Count.toString(),
                        above30Count = standard.above30Count.toString(),
                        above20Count = standard.above20Count.toString(),
                        above10Count = standard.above10Count.toString(),
                        above0Count = standard.above0Count.toString(),
                    )
                }
                .sortedBy { it.key },
        )
    }

    fun diff(
        before: GradeReminderSnapshot,
        after: GradeReminderSnapshot,
        studentNo: String,
        yearValue: String,
        examValue: String,
        examName: String,
        checkedAtMillis: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    ): GradeChangeSet {
        val changes = buildList {
            addSummaryChanges(before.summary, after.summary)
            addSubjectChanges(before.subjects, after.subjects)
            addStandardChanges(before.standards, after.standards)
        }
        return GradeChangeSet(
            studentNo = studentNo,
            yearValue = yearValue,
            examValue = examValue,
            examName = examName,
            checkedAtMillis = checkedAtMillis,
            changes = changes,
        )
    }

    private fun MutableList<GradeChange>.addSummaryChanges(
        before: GradeReminderSummarySnapshot?,
        after: GradeReminderSummarySnapshot?,
    ) {
        when {
            before == null && after == null -> return
            before == null && after != null -> {
                add(
                    GradeChange(
                        targetName = SUMMARY_TARGET,
                        field = GradeChangeField.SUMMARY_PRESENCE,
                        oldValue = "無",
                        newValue = "已出現",
                    ),
                )
                return
            }
            before != null && after == null -> {
                add(
                    GradeChange(
                        targetName = SUMMARY_TARGET,
                        field = GradeChangeField.SUMMARY_PRESENCE,
                        oldValue = "已存在",
                        newValue = "已移除",
                    ),
                )
                return
            }
        }

        before ?: return
        after ?: return
        compare(
            targetName = SUMMARY_TARGET,
            field = GradeChangeField.EXAM_NAME,
            oldValue = before.examName,
            newValue = after.examName,
        )
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_TOTAL, before.totalScore, after.totalScore)
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_AVERAGE, before.averageScore, after.averageScore)
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_CLASS_RANK, before.classRank, after.classRank)
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_CLASS_RANK_COUNT, before.classCount, after.classCount)
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_CATEGORY_RANK, before.categoryRank, after.categoryRank)
        compare(
            SUMMARY_TARGET,
            GradeChangeField.SUMMARY_CATEGORY_RANK_COUNT,
            before.categoryRankCount,
            after.categoryRankCount,
        )
        compare(SUMMARY_TARGET, GradeChangeField.SUMMARY_FLUNK_COUNT, before.flunkCount, after.flunkCount)
    }

    private fun MutableList<GradeChange>.addSubjectChanges(
        before: List<GradeReminderSubjectSnapshot>,
        after: List<GradeReminderSubjectSnapshot>,
    ) {
        val beforeMap = before.associateBy { it.key }
        val afterMap = after.associateBy { it.key }
        val keys = (beforeMap.keys + afterMap.keys).sorted()
        keys.forEach { key ->
            val oldSubject = beforeMap[key]
            val newSubject = afterMap[key]
            when {
                oldSubject == null && newSubject != null -> add(
                    GradeChange(
                        targetName = newSubject.name,
                        subjectName = newSubject.name,
                        field = GradeChangeField.SUBJECT_PRESENCE,
                        oldValue = "無",
                        newValue = "已出現",
                    ),
                )
                oldSubject != null && newSubject == null -> add(
                    GradeChange(
                        targetName = oldSubject.name,
                        subjectName = oldSubject.name,
                        field = GradeChangeField.SUBJECT_PRESENCE,
                        oldValue = "已存在",
                        newValue = "已移除",
                    ),
                )
                oldSubject != null && newSubject != null -> {
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_SCORE, oldSubject.score, newSubject.score)
                    compareSubject(
                        newSubject.name,
                        GradeChangeField.SUBJECT_CLASS_AVERAGE,
                        oldSubject.classAverage,
                        newSubject.classAverage,
                    )
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_CLASS_RANK, oldSubject.classRank, newSubject.classRank)
                    compareSubject(
                        newSubject.name,
                        GradeChangeField.SUBJECT_CLASS_RANK_COUNT,
                        oldSubject.classRankCount,
                        newSubject.classRankCount,
                    )
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_YEAR_RANK, oldSubject.yearRank, newSubject.yearRank)
                    compareSubject(
                        newSubject.name,
                        GradeChangeField.SUBJECT_YEAR_RANK_COUNT,
                        oldSubject.yearRankCount,
                        newSubject.yearRankCount,
                    )
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_FLUNK, oldSubject.flunk, newSubject.flunk)
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_ABSENT, oldSubject.absent, newSubject.absent)
                    compareSubject(newSubject.name, GradeChangeField.SUBJECT_CHEATING, oldSubject.cheating, newSubject.cheating)
                }
            }
        }
    }

    private fun MutableList<GradeChange>.addStandardChanges(
        before: List<GradeReminderStandardSnapshot>,
        after: List<GradeReminderStandardSnapshot>,
    ) {
        val beforeMap = before.associateBy { it.key }
        val afterMap = after.associateBy { it.key }
        val keys = (beforeMap.keys + afterMap.keys).sorted()
        keys.forEach { key ->
            val oldStandard = beforeMap[key]
            val newStandard = afterMap[key]
            when {
                oldStandard == null && newStandard != null -> add(
                    GradeChange(
                        targetName = newStandard.name,
                        subjectName = newStandard.name,
                        field = GradeChangeField.STANDARD_PRESENCE,
                        oldValue = "無",
                        newValue = "已出現",
                    ),
                )
                oldStandard != null && newStandard == null -> add(
                    GradeChange(
                        targetName = oldStandard.name,
                        subjectName = oldStandard.name,
                        field = GradeChangeField.STANDARD_PRESENCE,
                        oldValue = "已存在",
                        newValue = "已移除",
                    ),
                )
                oldStandard != null && newStandard != null -> {
                    compareSubject(newStandard.name, GradeChangeField.STANDARD_TOP, oldStandard.top, newStandard.top)
                    compareSubject(newStandard.name, GradeChangeField.STANDARD_FRONT, oldStandard.front, newStandard.front)
                    compareSubject(newStandard.name, GradeChangeField.STANDARD_AVERAGE, oldStandard.average, newStandard.average)
                    compareSubject(newStandard.name, GradeChangeField.STANDARD_BACK, oldStandard.back, newStandard.back)
                    compareSubject(newStandard.name, GradeChangeField.STANDARD_BOTTOM, oldStandard.bottom, newStandard.bottom)
                    compareSubject(
                        newStandard.name,
                        GradeChangeField.STANDARD_DEVIATION,
                        oldStandard.standardDeviation,
                        newStandard.standardDeviation,
                    )
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_90, oldStandard.above90Count, newStandard.above90Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_80, oldStandard.above80Count, newStandard.above80Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_70, oldStandard.above70Count, newStandard.above70Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_60, oldStandard.above60Count, newStandard.above60Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_50, oldStandard.above50Count, newStandard.above50Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_40, oldStandard.above40Count, newStandard.above40Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_30, oldStandard.above30Count, newStandard.above30Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_20, oldStandard.above20Count, newStandard.above20Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_10, oldStandard.above10Count, newStandard.above10Count)
                    compareSubject(newStandard.name, GradeChangeField.DISTRIBUTION_ABOVE_0, oldStandard.above0Count, newStandard.above0Count)
                }
            }
        }
    }

    private fun MutableList<GradeChange>.compareSubject(
        subjectName: String,
        field: GradeChangeField,
        oldValue: String?,
        newValue: String?,
    ) {
        compare(
            targetName = subjectName,
            subjectName = subjectName,
            field = field,
            oldValue = oldValue,
            newValue = newValue,
        )
    }

    private fun MutableList<GradeChange>.compare(
        targetName: String,
        field: GradeChangeField,
        oldValue: String?,
        newValue: String?,
        subjectName: String? = null,
    ) {
        if (oldValue == newValue) return
        add(
            GradeChange(
                targetName = targetName,
                subjectName = subjectName,
                field = field,
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }

    private fun String.canonicalDisplayValue(): String? {
        val trimmed = trim()
        if (trimmed.isBlank()) return null
        return trimmed.toDoubleOrNull()?.canonicalNumberValue() ?: trimmed
    }

    private fun Double?.canonicalNumberValue(): String? = this?.canonicalNumberValue()

    private fun Double.canonicalNumberValue(): String {
        if (abs(this - round(this)) < 0.0001) {
            return formatFixed(this, 0)
        }
        return formatFixed(this, 2)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun Boolean.yesNo(): String = if (this) "是" else "否"

    private const val SUMMARY_TARGET = "總覽"
}
