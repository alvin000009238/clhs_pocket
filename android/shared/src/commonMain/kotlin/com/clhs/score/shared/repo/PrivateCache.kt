package com.clhs.score.shared.repo

import com.clhs.score.data.CachedGradeReport
import com.clhs.score.data.GradeReport
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.StudentInfo
import com.clhs.score.data.YearTermOption
import com.clhs.score.data.normalizedSubjectOverrides
import com.clhs.score.data.toCachedGradeReport
import com.clhs.score.data.toCurrentGradeReport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A cached schedule with the time it was captured from the school system. */
class CachedSchedule(val report: ScheduleReport, val capturedAtEpochMillis: Long)

/**
 * Account-scoped cache of school data in the protected [FileStore.PRIVATE] area.
 *
 * Every entry records the student it belongs to and is ignored for anyone else. Writes take a
 * `shouldCommit` guard that is evaluated immediately before the file is written, so a response that
 * finished after the authorization changed is never persisted.
 */
class PrivateCache(private val files: FileStore, private val now: () -> Long) {
    @Serializable
    private data class Envelope(val studentNo: String, val payload: String)

    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> load(path: String, studentNo: String, serializer: KSerializer<T>): Pair<T, Long>? {
        val stored = files.readOrNull(path) ?: return null
        return runCatching {
            val envelope = json.decodeFromString(Envelope.serializer(), stored.text)
            if (envelope.studentNo != studentNo) null
            else json.decodeFromString(serializer, envelope.payload) to stored.modifiedAtEpochMillis
        }.getOrNull()
    }

    private fun <T> save(path: String, studentNo: String, serializer: KSerializer<T>, value: T, shouldCommit: () -> Boolean) {
        val text = json.encodeToString(Envelope.serializer(), Envelope(studentNo, json.encodeToString(serializer, value)))
        if (!shouldCommit()) return
        files.write(path, text, now()).orThrow("Cache write")
    }

    fun loadStructure(studentNo: String): List<YearTermOption>? =
        load(STRUCTURE, studentNo, ListSerializer(YearTermOption.serializer()))?.first

    fun saveStructure(studentNo: String, structure: List<YearTermOption>, shouldCommit: () -> Boolean) =
        save(STRUCTURE, studentNo, ListSerializer(YearTermOption.serializer()), structure, shouldCommit)

    fun loadStudentInfo(studentNo: String): StudentInfo? = load(STUDENT, studentNo, StudentInfo.serializer())?.first

    fun saveStudentInfo(studentNo: String, info: StudentInfo, shouldCommit: () -> Boolean) =
        save(STUDENT, studentNo, StudentInfo.serializer(), info, shouldCommit)

    fun loadGradeReport(studentNo: String, yearValue: String, examValue: String): GradeReport? =
        load(gradePath(yearValue, examValue), studentNo, CachedGradeReport.serializer())?.first?.toCurrentGradeReport()

    fun saveGradeReport(studentNo: String, yearValue: String, examValue: String, report: GradeReport, shouldCommit: () -> Boolean) =
        save(gradePath(yearValue, examValue), studentNo, CachedGradeReport.serializer(), report.toCachedGradeReport(), shouldCommit)

    fun loadSimulatorCredits(studentNo: String, yearValue: String, examValue: String): String? =
        load(simulatorPath(yearValue, examValue), studentNo, String.serializer())?.first

    fun saveSimulatorCredits(studentNo: String, yearValue: String, examValue: String, settingsJson: String?, shouldCommit: () -> Boolean) {
        val path = simulatorPath(yearValue, examValue)
        if (settingsJson == null) {
            if (shouldCommit()) files.delete(path).orThrow("Cache delete")
        } else {
            save(path, studentNo, String.serializer(), settingsJson, shouldCommit)
        }
    }

    fun loadSchedule(studentNo: String, cacheKey: String): CachedSchedule? =
        load(schedulePath(cacheKey), studentNo, ScheduleReport.serializer())?.let { CachedSchedule(it.first, it.second) }

    fun loadLatestSchedule(studentNo: String): CachedSchedule? =
        load(LATEST_SCHEDULE, studentNo, ScheduleReport.serializer())?.let { CachedSchedule(it.first, it.second) }

    fun saveSchedule(studentNo: String, cacheKey: String, report: ScheduleReport, latest: Boolean, shouldCommit: () -> Boolean) {
        save(schedulePath(cacheKey), studentNo, ScheduleReport.serializer(), report, shouldCommit)
        if (latest) save(LATEST_SCHEDULE, studentNo, ScheduleReport.serializer(), report, shouldCommit)
    }

    /** Subject overrides are the user's own edits; they stay through logout and go with delete-all. */
    fun loadSubjectOverrides(studentNo: String): List<ScheduleSubjectOverride> =
        load(OVERRIDES, studentNo, ListSerializer(ScheduleSubjectOverride.serializer()))?.first.orEmpty()

    fun saveSubjectOverrides(studentNo: String, overrides: List<ScheduleSubjectOverride>, shouldCommit: () -> Boolean) =
        save(OVERRIDES, studentNo, ListSerializer(ScheduleSubjectOverride.serializer()), overrides.normalizedSubjectOverrides(), shouldCommit)

    /** Removes all cached school data. Throws [StorageException] when anything could not be deleted. */
    fun clearSchoolData() {
        files.deleteTree(FileStore.PRIVATE).orThrow("Private cache delete")
    }

    fun clearSubjectOverrides() {
        files.delete(OVERRIDES).orThrow("Subject override delete")
    }

    private companion object {
        const val STRUCTURE = "${FileStore.PRIVATE}/structure.json"
        const val STUDENT = "${FileStore.PRIVATE}/student.json"
        const val LATEST_SCHEDULE = "${FileStore.PRIVATE}/schedule-latest.json"
        const val OVERRIDES = "${FileStore.SETTINGS}/subject-overrides.json"
        fun gradePath(year: String, exam: String) = "${FileStore.PRIVATE}/grades/${fileKey("$year|$exam")}.json"
        fun simulatorPath(year: String, exam: String) = "${FileStore.PRIVATE}/simulator/${fileKey("$year|$exam")}.json"
        fun schedulePath(key: String) = "${FileStore.PRIVATE}/schedule/${fileKey(key)}.json"
    }
}
