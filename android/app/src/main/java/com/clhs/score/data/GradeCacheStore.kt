package com.clhs.score.data

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

internal val gradeCacheCorruptionHandler =
    ReplaceFileCorruptionHandler { emptyPreferences() }

internal fun ScheduleReport.toWidgetReportOrNull(): ScheduleReport? =
    if (scope == ScheduleScope.CURRENT_WEEK) copy(changes = null) else null

internal fun ScheduleReport.toWidgetReportOrNull(
    currentWidgetReport: ScheduleReport?,
    today: LocalDate = LocalDate.now(),
): ScheduleReport? =
    toWidgetReportOrNull() ?: when {
        currentWidgetReport == null -> copy(changes = null)
        currentWidgetReport.scope == ScheduleScope.CURRENT_WEEK &&
            currentWidgetReport.isValidOn(today) &&
            !currentWidgetReport.shouldRefreshAt(LocalDateTime.now()) -> null
        else -> copy(changes = null)
    }

data class CachedScheduleReport(
    val report: ScheduleReport,
    val fetchedAtMillis: Long?,
)

private val Context.gradeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "grade_cache",
    corruptionHandler = gradeCacheCorruptionHandler,
)

class GradeCacheStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val recordDiagnostic: (String) -> Unit,
    private val privateSnapshotAllowed: Flow<Boolean>,
) {
    internal constructor(dataStore: DataStore<Preferences>, recordDiagnostic: (String) -> Unit) :
        this(dataStore, recordDiagnostic, flowOf(true))

    constructor(context: Context) : this(
        dataStore = context.applicationContext.gradeDataStore,
        recordDiagnostic = { message ->
            DeveloperDiagnostics.recordEvent(
                context = context.applicationContext,
                area = "GradeCache",
                message = message,
            )
        },
        privateSnapshotAllowed = SessionStore(context.applicationContext).privateSnapshotAccess,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private suspend fun canReadPrivateSnapshot(): Boolean = try {
        privateSnapshotAllowed.firstOrNull() == true
    } catch (_: SessionStorageException) {
        recordDiagnostic("PRIVATE_SNAPSHOT_AUTHORITY_UNKNOWN")
        false
    } catch (_: IOException) {
        recordDiagnostic("PRIVATE_SNAPSHOT_AUTHORITY_UNKNOWN")
        false
    }

    suspend fun saveStructure(studentNo: String, structure: List<YearTermOption>) {
        saveStructure(studentNo, structure) { true }
    }

    internal suspend fun saveStructure(
        studentNo: String,
        structure: List<YearTermOption>,
        shouldCommit: () -> Boolean,
    ) {
        val key = stringPreferencesKey("structure_$studentNo")
        val serialized = json.encodeToString(structure)
        saveCacheValue("structure") {
            dataStore.edit { prefs ->
                if (shouldCommit()) prefs[key] = serialized
            }
        }
    }

    suspend fun loadStructure(studentNo: String): List<YearTermOption>? {
        val key = stringPreferencesKey("structure_$studentNo")
        val serialized = loadCacheValue("structure") {
            dataStore.data.map { prefs -> prefs[key] }.firstOrNull()
        } ?: return null
        return runCatching {
            json.decodeFromString<List<YearTermOption>>(serialized)
        }.onFailure { error ->
            recordDiagnostic("structure cache decode failed: ${error::class.simpleName ?: "unknown"}")
            removeCacheValue(key, "structure")
        }.getOrNull()
    }

    suspend fun saveStudentInfo(studentNo: String, studentInfo: StudentInfo) {
        saveStudentInfo(studentNo, studentInfo) { true }
    }

    internal suspend fun saveStudentInfo(
        studentNo: String,
        studentInfo: StudentInfo,
        shouldCommit: () -> Boolean,
    ) {
        val key = stringPreferencesKey("student_info_$studentNo")
        val serialized = json.encodeToString(studentInfo)
        saveCacheValue("student info") {
            dataStore.edit { prefs ->
                if (shouldCommit()) prefs[key] = serialized
            }
        }
    }

    suspend fun loadStudentInfo(studentNo: String): StudentInfo? {
        val key = stringPreferencesKey("student_info_$studentNo")
        val serialized = loadCacheValue("student info") {
            dataStore.data.map { prefs -> prefs[key] }.firstOrNull()
        } ?: return null
        return decodeCacheValue(key, serialized, "student info")
    }

    internal suspend fun saveGradeReport(
        studentNo: String,
        yearValue: String,
        examValue: String,
        report: GradeReport,
        shouldCommit: () -> Boolean,
    ) {
        val key = stringPreferencesKey("grade_${studentNo}_${yearValue}_${examValue}")
        val serialized = json.encodeToString(report.toCachedGradeReport())
        saveCacheValue("grade report") {
            dataStore.edit { prefs ->
                if (shouldCommit()) prefs[key] = serialized
            }
        }
    }

    suspend fun loadGradeReport(studentNo: String, yearValue: String, examValue: String): GradeReport? {
        val key = stringPreferencesKey("grade_${studentNo}_${yearValue}_${examValue}")
        val serialized = loadCacheValue("grade report") {
            dataStore.data.map { prefs -> prefs[key] }.firstOrNull()
        } ?: return null
        decodeCachedGradeReport(serialized)?.let { return it }
        recordDiagnostic("grade report cache expired or decode failed")
        removeCacheValue(key, "grade report")
        return null
    }

    private suspend inline fun <reified T> decodeCacheValue(
        key: Preferences.Key<String>,
        serialized: String,
        label: String,
    ): T? =
        runCatching {
            json.decodeFromString<T>(serialized)
        }.onFailure { error ->
            recordDiagnostic("$label cache decode failed: ${error::class.simpleName ?: "unknown"}")
            removeCacheValue(key, label)
        }.getOrNull()

    suspend fun saveScheduleReport(studentNo: String, yearValue: String, report: ScheduleReport, shouldCommit: () -> Boolean = { true }) {
        val key = stringPreferencesKey("schedule_${studentNo}_${yearValue}")
        val fetchedAtKey = longPreferencesKey("schedule_${studentNo}_${yearValue}_fetched_at")
        val latestKey = stringPreferencesKey("schedule_latest_$studentNo")
        val latestFetchedAtKey = longPreferencesKey("schedule_${studentNo}_latest_fetched_at")
        val serialized = json.encodeToString(report)
        val fetchedAt = System.currentTimeMillis()
        saveCacheValue("schedule") {
            dataStore.edit { prefs ->
                if (!shouldCommit()) return@edit
                prefs[key] = serialized
                prefs[fetchedAtKey] = fetchedAt
                prefs[latestKey] = serialized
                prefs[latestFetchedAtKey] = fetchedAt
                val currentWidgetReport = prefs[PREF_WIDGET_SCHEDULE_REPORT]
                    ?.let { runCatching { json.decodeFromString<ScheduleReport>(it) }.getOrNull() }
                val widgetReport = report.toWidgetReportOrNull(currentWidgetReport)
                if (widgetReport != null) {
                    prefs[PREF_WIDGET_SCHEDULE_REPORT] = json.encodeToString(widgetReport)
                    prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] = studentNo
                }
            }
        }
    }

    suspend fun saveWidgetScheduleReport(studentNo: String, report: ScheduleReport, shouldCommit: () -> Boolean = { true }) {
        saveCacheValue("widget schedule") {
            dataStore.edit { prefs ->
                if (!shouldCommit()) return@edit
                val currentWidgetReport = prefs[PREF_WIDGET_SCHEDULE_REPORT]
                    ?.let { runCatching { json.decodeFromString<ScheduleReport>(it) }.getOrNull() }
                val widgetReport = report.toWidgetReportOrNull(currentWidgetReport) ?: return@edit
                prefs[PREF_WIDGET_SCHEDULE_REPORT] = json.encodeToString(widgetReport)
                prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] = studentNo
            }
        }
    }

    suspend fun loadLatestScheduleSnapshot(studentNo: String): CachedScheduleReport? {
        val key = stringPreferencesKey("schedule_latest_$studentNo")
        val fetchedAtKey = longPreferencesKey("schedule_${studentNo}_latest_fetched_at")
        val cached = loadCacheValue("latest schedule") {
            dataStore.data.map { prefs -> prefs[key] to prefs[fetchedAtKey] }.firstOrNull()
        } ?: return null
        val report = decodeCacheValue<ScheduleReport>(key, cached.first ?: return null, "latest schedule")
            ?: return null
        return CachedScheduleReport(report, cached.second)
    }

    suspend fun loadLatestScheduleReport(studentNo: String): ScheduleReport? =
        loadLatestScheduleSnapshot(studentNo)?.report

    suspend fun loadWidgetScheduleReport(): ScheduleReport? {
        if (!canReadPrivateSnapshot()) return null
        val cached = loadCacheValue("widget schedule") {
            dataStore.data
                .map { prefs -> prefs[PREF_WIDGET_SCHEDULE_REPORT] to prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] }
                .firstOrNull()
        } ?: return null
        val report = decodeCacheValue<ScheduleReport>(
            PREF_WIDGET_SCHEDULE_REPORT,
            cached.first ?: return null,
            "widget schedule",
        ) ?: return null
        if (report.scope != ScheduleScope.CURRENT_WEEK || !report.shouldRefreshAt(LocalDateTime.now())) {
            return report
        }

        val studentNo = cached.second ?: return report
        val semesterKey = if (report.classNo.isNotBlank()) {
            "${report.yearTermValue}_${report.classNo}"
        } else {
            report.yearTermValue
        }
        return loadScheduleReport(studentNo, semesterKey)
            ?.takeIf { it.scope == ScheduleScope.SEMESTER }
            ?.copy(
                changes = null,
                subjectOverrides = loadScheduleSubjectOverrides(studentNo),
            )
            ?: report
    }

    suspend fun loadScheduleReport(studentNo: String, yearValue: String): ScheduleReport? {
        val key = stringPreferencesKey("schedule_${studentNo}_${yearValue}")
        val serialized = loadCacheValue("schedule") {
            dataStore.data.map { prefs -> prefs[key] }.firstOrNull()
        } ?: return null
        return decodeCacheValue(key, serialized, "schedule")
    }

    suspend fun loadScheduleSnapshot(studentNo: String, yearValue: String): CachedScheduleReport? {
        val key = stringPreferencesKey("schedule_${studentNo}_${yearValue}")
        val fetchedAtKey = longPreferencesKey("schedule_${studentNo}_${yearValue}_fetched_at")
        val cached = loadCacheValue("schedule") {
            dataStore.data.map { prefs -> prefs[key] to prefs[fetchedAtKey] }.firstOrNull()
        } ?: return null
        val report = decodeCacheValue<ScheduleReport>(key, cached.first ?: return null, "schedule")
            ?: return null
        return CachedScheduleReport(report, cached.second)
    }

    fun scheduleSubjectOverridesFlow(studentNo: String) = dataStore.data.map { prefs ->
        val serialized = prefs[scheduleSubjectOverridesKey(studentNo)] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<ScheduleSubjectOverride>>(serialized) }
            .onFailure { error ->
                recordDiagnostic(
                    "schedule subject overrides decode failed: ${error::class.simpleName ?: "unknown"}",
                )
            }
            .getOrDefault(emptyList())
    }

    suspend fun loadScheduleSubjectOverrides(studentNo: String): List<ScheduleSubjectOverride> =
        loadCacheValue("schedule subject overrides") {
            scheduleSubjectOverridesFlow(studentNo).firstOrNull()
        }.orEmpty()

    suspend fun saveScheduleSubjectOverrides(
        studentNo: String,
        overrides: List<ScheduleSubjectOverride>,
    ) {
        val normalized = overrides.normalizedSubjectOverrides()
        val key = scheduleSubjectOverridesKey(studentNo)
        dataStore.edit { prefs ->
            if (normalized.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = json.encodeToString(normalized)
            }
            if (prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] == studentNo) {
                prefs[PREF_WIDGET_SCHEDULE_REPORT]
                    ?.let { serialized -> runCatching { json.decodeFromString<ScheduleReport>(serialized) }.getOrNull() }
                    ?.copy(subjectOverrides = normalized)
                    ?.let { report -> prefs[PREF_WIDGET_SCHEDULE_REPORT] = json.encodeToString(report) }
            }
        }
    }

    suspend fun clearWidgetScheduleReport(studentNo: String? = null) {
        dataStore.edit { prefs ->
            val owner = prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO]
            if (studentNo == null || owner == studentNo) {
                prefs.remove(PREF_WIDGET_SCHEDULE_REPORT)
                prefs.remove(PREF_WIDGET_SCHEDULE_STUDENT_NO)
            }
        }
    }

    suspend fun clearStudent(studentNo: String, preserveSubjectOverrides: Boolean = false) {
        val structureKey = "structure_$studentNo"
        val studentInfoKey = "student_info_$studentNo"
        val gradePrefix = "grade_${studentNo}_"
        val schedulePrefix = "schedule_${studentNo}_"
        val latestScheduleKey = "schedule_latest_$studentNo"
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name == structureKey ||
                        key.name == studentInfoKey ||
                        key.name == latestScheduleKey ||
                        key.name.startsWith(gradePrefix) ||
                        key.name.startsWith(schedulePrefix)
                }
                .filterNot { key -> preserveSubjectOverrides && key == scheduleSubjectOverridesKey(studentNo) }
                .forEach { key -> prefs.remove(key) }
            if (prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] == studentNo) {
                prefs.remove(PREF_WIDGET_SCHEDULE_REPORT)
                prefs.remove(PREF_WIDGET_SCHEDULE_STUDENT_NO)
            }
        }
    }

    suspend fun clearAll(preserveSubjectOverrides: Boolean = false) {
        dataStore.edit { prefs ->
            if (preserveSubjectOverrides) {
                prefs.asMap().keys
                    .filterNot { it.name.startsWith("schedule_") && it.name.endsWith("_subject_overrides") }
                    .forEach { prefs.remove(it) }
            } else {
                prefs.clear()
            }
        }
    }

    fun widgetScheduleReportFlow() = combine(dataStore.data, privateSnapshotAllowed) { prefs, allowed ->
        if (!allowed) return@combine null
        val serialized = prefs[PREF_WIDGET_SCHEDULE_REPORT] ?: return@combine null
        runCatching { json.decodeFromString<ScheduleReport>(serialized) }.getOrNull()
    }
        .catch { error ->
            if (error is CancellationException) throw error
            if (error !is IOException && error !is SessionStorageException) throw error
            recordDiagnostic("PRIVATE_SNAPSHOT_UNAVAILABLE")
            emit(null)
        }

    internal suspend fun loadLegacyWidgetPreferences(): Triple<Boolean, Boolean, Boolean> {
        val prefs = loadCacheValue("legacy widget preferences") {
            dataStore.data.firstOrNull()
        }
        return Triple(
            prefs?.get(PREF_WIDGET_SHOW_TEACHER) ?: true,
            prefs?.get(PREF_WIDGET_SHOW_CLASSROOM) ?: true,
            prefs?.get(PREF_WIDGET_SHOW_TIME) ?: true,
        )
    }

    internal suspend fun clearLegacyWidgetPreferences() {
        val hasLegacyPreferences = dataStore.data
            .map { prefs ->
                prefs[PREF_WIDGET_SHOW_TEACHER] != null ||
                    prefs[PREF_WIDGET_SHOW_CLASSROOM] != null ||
                    prefs[PREF_WIDGET_SHOW_TIME] != null
            }
            .firstOrNull() == true
        if (!hasLegacyPreferences) return
        dataStore.edit { prefs ->
            prefs.remove(PREF_WIDGET_SHOW_TEACHER)
            prefs.remove(PREF_WIDGET_SHOW_CLASSROOM)
            prefs.remove(PREF_WIDGET_SHOW_TIME)
        }
    }

    private fun decodeCachedGradeReport(serialized: String): GradeReport? =
        runCatching {
            json.decodeFromString<CachedGradeReport>(serialized).toCurrentGradeReport()
        }.getOrNull()

    private suspend fun <T> loadCacheValue(label: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            recordDiagnostic("$label cache read failed: IOException")
            null
        }

    private suspend fun saveCacheValue(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            recordDiagnostic("$label cache write failed: IOException")
        }
    }

    private suspend fun removeCacheValue(key: Preferences.Key<*>, label: String) {
        saveCacheValue(label) { dataStore.edit { prefs -> prefs.remove(key) } }
    }

    private companion object {
        const val KEY_WIDGET_SCHEDULE_REPORT = "widget_schedule_report"
        const val KEY_WIDGET_SCHEDULE_STUDENT_NO = "widget_schedule_student_no"
        val PREF_WIDGET_SCHEDULE_REPORT = stringPreferencesKey(KEY_WIDGET_SCHEDULE_REPORT)
        val PREF_WIDGET_SCHEDULE_STUDENT_NO = stringPreferencesKey(KEY_WIDGET_SCHEDULE_STUDENT_NO)
        val PREF_WIDGET_SHOW_TEACHER = booleanPreferencesKey("widget_show_teacher")
        val PREF_WIDGET_SHOW_CLASSROOM = booleanPreferencesKey("widget_show_classroom")
        val PREF_WIDGET_SHOW_TIME = booleanPreferencesKey("widget_show_time")

        fun scheduleSubjectOverridesKey(studentNo: String) =
            stringPreferencesKey("schedule_${studentNo}_subject_overrides")
    }
}
