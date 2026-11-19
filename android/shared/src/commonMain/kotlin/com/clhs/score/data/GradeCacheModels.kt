package com.clhs.score.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val GRADE_REPORT_CACHE_VERSION = 2

@Serializable
data class CachedGradeReport(
    val cacheVersion: Int = GRADE_REPORT_CACHE_VERSION,
    val message: String,
    val studentInfo: StudentInfo,
    val examSummary: ExamSummary?,
    val subjects: List<SubjectScore>,
    val standards: List<GradeStandard>,
)

fun GradeReport.toCachedGradeReport(): CachedGradeReport = CachedGradeReport(
    message = message,
    studentInfo = studentInfo,
    examSummary = examSummary,
    subjects = subjects,
    standards = standards,
)

fun CachedGradeReport.toGradeReport(): GradeReport = GradeReport(
    message = message,
    studentInfo = studentInfo,
    examSummary = examSummary,
    subjects = subjects,
    standards = standards,
    rawResult = JsonObject(emptyMap()),
)

fun CachedGradeReport.toCurrentGradeReport(): GradeReport? =
    takeIf { cacheVersion == GRADE_REPORT_CACHE_VERSION }?.toGradeReport()
