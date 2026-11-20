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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * `shouldCommit` guard that is evaluated under the same lock as the write and as [clearSchoolData],
 * so a response that finished after the authorization changed is never persisted or resurrected.
 *
 * Error contract (docs/architecture/maintenance-contracts.md): fetched school data is a cache whose
 * read and write failures degrade to a miss. Simulator credits and subject overrides are the user's
 * own settings: unreadable content or I/O failures throw [StorageException] instead of looking like
 * "nothing saved", and so do deletions.
 */
class PrivateCache(private val files: FileStore, private val now: () -> Long) {
    @Serializable
    private data class Envelope(val studentNo: String, val payload: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Cached school data: missing, unreadable or another student's entry is a miss. */
    private fun <T> loadCached(path: String, studentNo: String, serializer: KSerializer<T>): Pair<T, Long>? {
        val stored = files.read(path).takeIf { it.succeeded } ?: return null
        val text = stored.text ?: return null
        return runCatching {
            val envelope = json.decodeFromString(Envelope.serializer(), text)
            if (envelope.studentNo != studentNo) null
            else json.decodeFromString(serializer, envelope.payload) to stored.modifiedAtEpochMillis
        }.getOrNull()
    }

    /** User settings: null only when nothing is stored for [studentNo]; anything unreadable throws. */
    private fun <T> loadStrict(path: String, studentNo: String, serializer: KSerializer<T>): T? {
        val text = files.read(path).orThrow("Settings read").text ?: return null
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (_: IllegalArgumentException) {
            throw StorageException("Stored settings are unreadable")
        }
        if (envelope.studentNo != studentNo) return null
        return try {
            json.decodeFromString(serializer, envelope.payload)
        } catch (_: IllegalArgumentException) {
            throw StorageException("Stored settings are unreadable")
        }
    }

    /** Returns false when [shouldCommit] refused the write. */
    private fun <T> write(path: String, studentNo: String, serializer: KSerializer<T>, value: T, shouldCommit: () -> Boolean): Boolean {
        val text = json.encodeToString(Envelope.serializer(), Envelope(studentNo, json.encodeToString(serializer, value)))
        if (!shouldCommit()) return false
        files.write(path, text, now()).orThrow("Cache write")
        return true
    }

    private suspend fun <T> saveCached(path: String, studentNo: String, serializer: KSerializer<T>, value: T, shouldCommit: () -> Boolean) {
        lock.withLock { runCatching { write(path, studentNo, serializer, value, shouldCommit) } }
    }

    suspend fun loadStructure(studentNo: String): List<YearTermOption>? =
        lock.withLock { loadCached(STRUCTURE, studentNo, ListSerializer(YearTermOption.serializer()))?.first }

    suspend fun saveStructure(studentNo: String, structure: List<YearTermOption>, shouldCommit: () -> Boolean) =
        saveCached(STRUCTURE, studentNo, ListSerializer(YearTermOption.serializer()), structure, shouldCommit)

    suspend fun loadStudentInfo(studentNo: String): StudentInfo? =
        lock.withLock { loadCached(STUDENT, studentNo, StudentInfo.serializer())?.first }

    suspend fun saveStudentInfo(studentNo: String, info: StudentInfo, shouldCommit: () -> Boolean) =
        saveCached(STUDENT, studentNo, StudentInfo.serializer(), info, shouldCommit)

    suspend fun loadGradeReport(studentNo: String, yearValue: String, examValue: String): GradeReport? = lock.withLock {
        loadCached(gradePath(yearValue, examValue), studentNo, CachedGradeReport.serializer())?.first?.toCurrentGradeReport()
    }

    suspend fun saveGradeReport(studentNo: String, yearValue: String, examValue: String, report: GradeReport, shouldCommit: () -> Boolean) =
        saveCached(gradePath(yearValue, examValue), studentNo, CachedGradeReport.serializer(), report.toCachedGradeReport(), shouldCommit)

    /** Null when nothing is saved for this exam; throws [StorageException] when the saved value cannot be read. */
    suspend fun loadSimulatorCredits(studentNo: String, yearValue: String, examValue: String): String? =
        lock.withLock { loadStrict(simulatorPath(yearValue, examValue), studentNo, String.serializer()) }

    /** Throws [StorageException] when the change could not be stored. */
    suspend fun saveSimulatorCredits(studentNo: String, yearValue: String, examValue: String, settingsJson: String?, shouldCommit: () -> Boolean) {
        val path = simulatorPath(yearValue, examValue)
        lock.withLock {
            if (settingsJson == null) {
                if (shouldCommit()) files.delete(path).orThrow("Cache delete")
            } else {
                write(path, studentNo, String.serializer(), settingsJson, shouldCommit)
            }
        }
    }

    suspend fun loadSchedule(studentNo: String, cacheKey: String): CachedSchedule? = lock.withLock {
        loadCached(schedulePath(cacheKey), studentNo, ScheduleReport.serializer())?.let { CachedSchedule(it.first, it.second) }
    }

    suspend fun loadLatestSchedule(studentNo: String): CachedSchedule? = lock.withLock {
        loadCached(LATEST_SCHEDULE, studentNo, ScheduleReport.serializer())?.let { CachedSchedule(it.first, it.second) }
    }

    suspend fun saveSchedule(studentNo: String, cacheKey: String, report: ScheduleReport, latest: Boolean, shouldCommit: () -> Boolean) {
        saveCached(schedulePath(cacheKey), studentNo, ScheduleReport.serializer(), report, shouldCommit)
        if (latest) saveCached(LATEST_SCHEDULE, studentNo, ScheduleReport.serializer(), report, shouldCommit)
    }

    /**
     * Subject overrides are the user's own edits; they stay through logout and go with delete-all.
     * Each account has its own file, as Android keys them by student number, so signing in as
     * someone else never replaces them. The single file older builds wrote is still read for its owner.
     * Throws [StorageException] when stored overrides cannot be read, so they are never replaced by
     * an empty list by mistake.
     */
    suspend fun loadSubjectOverrides(studentNo: String): List<ScheduleSubjectOverride> = lock.withLock {
        val serializer = ListSerializer(ScheduleSubjectOverride.serializer())
        (loadStrict(overridesPath(studentNo), studentNo, serializer) ?: loadStrict(LEGACY_OVERRIDES, studentNo, serializer)).orEmpty()
    }

    /** Returns false when [shouldCommit] refused the write; throws [StorageException] when it failed. */
    suspend fun saveSubjectOverrides(studentNo: String, overrides: List<ScheduleSubjectOverride>, shouldCommit: () -> Boolean): Boolean =
        lock.withLock {
            write(
                overridesPath(studentNo), studentNo, ListSerializer(ScheduleSubjectOverride.serializer()),
                overrides.normalizedSubjectOverrides(), shouldCommit,
            )
        }

    /** Removes all cached school data. Throws [StorageException] when anything could not be deleted. */
    suspend fun clearSchoolData() {
        lock.withLock { files.deleteTree(FileStore.PRIVATE).orThrow("Private cache delete") }
    }

    /** Deletes both override locations, attempting each; throws [StorageException] when either remains. */
    suspend fun clearSubjectOverrides() {
        lock.withLock {
            val directory = files.deleteTree(OVERRIDES_DIRECTORY).succeeded
            val legacy = files.delete(LEGACY_OVERRIDES).succeeded
            if (!directory || !legacy) throw StorageException("Subject override delete failed")
        }
    }

    private companion object {
        const val STRUCTURE = "${FileStore.PRIVATE}/structure.json"
        const val STUDENT = "${FileStore.PRIVATE}/student.json"
        const val LATEST_SCHEDULE = "${FileStore.PRIVATE}/schedule-latest.json"
        const val LEGACY_OVERRIDES = "${FileStore.SETTINGS}/subject-overrides.json"
        const val OVERRIDES_DIRECTORY = "${FileStore.SETTINGS}/subject-overrides"
        fun overridesPath(studentNo: String) = "$OVERRIDES_DIRECTORY/${fileKey(studentNo)}.json"
        fun gradePath(year: String, exam: String) = "${FileStore.PRIVATE}/grades/${fileKey("$year|$exam")}.json"
        fun simulatorPath(year: String, exam: String) = "${FileStore.PRIVATE}/simulator/${fileKey("$year|$exam")}.json"
        fun schedulePath(key: String) = "${FileStore.PRIVATE}/schedule/${fileKey(key)}.json"
    }
}
