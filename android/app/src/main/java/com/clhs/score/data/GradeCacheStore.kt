package com.clhs.score.data

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    today: LocalDate = LocalDate.now(SCHOOL_COURSE_ZONE),
): ScheduleReport? =
    when {
        scope == ScheduleScope.CURRENT_WEEK && !isAuthoritativeWeekOn(today) &&
            currentWidgetReport?.isAuthoritativeWeekOn(today) == true -> null
        scope == ScheduleScope.CURRENT_WEEK -> copy(changes = null)
        currentWidgetReport == null -> copy(changes = null)
        currentWidgetReport.isAuthoritativeWeekOn(today) -> null
        else -> copy(changes = null)
    }

private fun ScheduleReport.validWeekRange(): ClosedRange<LocalDate>? {
    if (scope != ScheduleScope.CURRENT_WEEK) return null
    val first = weekStartDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val last = weekEndDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return (first..last).takeIf { java.time.temporal.ChronoUnit.DAYS.between(first, last) in 0..6 }
}

private fun ScheduleReport.isAuthoritativeWeekOn(date: LocalDate): Boolean =
    validWeekRange()?.contains(date) == true

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
    private val snapshots: ScheduleSnapshotStore,
    private val commitSchedule: suspend (suspend () -> Unit) -> Unit = { it() },
    private val migrationAuthority: () -> (() -> Boolean) = { { true } },
) {
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
        snapshots = ScheduleSnapshotStore(ScheduleSnapshotDatabase.get(context)),
        commitSchedule = { block -> com.clhs.score.notifications.CurrentCourseNotifications.get(context).commitSnapshot(block) },
        migrationAuthority = {
            val authority = SessionStore(context.applicationContext)
            val generation = authority.requestGeneration()
            val guard: () -> Boolean = { authority.withSnapshotGeneration(generation) {} }
            guard
        },
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
        val serialized = withContext(Dispatchers.Default) { json.encodeToString(structure) }
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
        val decoded = withContext(Dispatchers.Default) {
            runCatching { json.decodeFromString<List<YearTermOption>>(serialized) }
        }
        return decoded.onFailure { error ->
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
        val serialized = withContext(Dispatchers.Default) { json.encodeToString(studentInfo) }
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
        val serialized = withContext(Dispatchers.Default) { json.encodeToString(report.toCachedGradeReport()) }
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

    suspend fun loadSimulatorCredits(studentNo: String, yearValue: String, examValue: String): SavedSimulatorCredits? {
        val key = simulatorCreditsKey(studentNo, yearValue, examValue)
        val serialized = dataStore.data.first()[key] ?: return null
        return withContext(Dispatchers.Default) {
            json.decodeFromString<SavedSimulatorCredits>(serialized)
        }
    }

    suspend fun saveSimulatorCredits(
        studentNo: String,
        yearValue: String,
        examValue: String,
        settings: SavedSimulatorCredits?,
        shouldCommit: () -> Boolean = { true },
    ) {
        val key = simulatorCreditsKey(studentNo, yearValue, examValue)
        val serialized = withContext(Dispatchers.Default) {
            settings?.copy(subjectNames = settings.subjectNames.toList(), credits = settings.credits.toMap())
                ?.let { json.encodeToString(it) }
        }
        dataStore.edit { prefs ->
            if (!shouldCommit()) throw CancellationException("Session changed")
            if (serialized == null) prefs.remove(key) else prefs[key] = serialized
        }
    }

    private suspend inline fun <reified T> decodeCacheValue(
        key: Preferences.Key<String>,
        serialized: String,
        label: String,
    ): T? {
        val decoded = withContext(Dispatchers.Default) {
            runCatching { json.decodeFromString<T>(serialized) }
        }
        return decoded.onFailure { error ->
            recordDiagnostic("$label cache decode failed: ${error::class.simpleName ?: "unknown"}")
            removeCacheValue(key, label)
        }.getOrNull()
    }

    private val migrationMutex = Mutex()

    private suspend fun migrateSchedules() = migrationMutex.withLock {
        val shouldCommit = migrationAuthority()
        val prefs = dataStore.data.first()
        val entries = mutableMapOf<String, MutableList<LegacyScheduleSnapshot>>()
        val keys = mutableMapOf<String, MutableSet<Preferences.Key<*>>>()
        val widgetStudent = prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO]
        prefs.asMap().forEach { (prefKey, value) ->
            if (value !is String || prefKey.name.endsWith("_subject_overrides")) return@forEach
            val name = prefKey.name
            if (!name.startsWith("schedule_") && name != PREF_WIDGET_SCHEDULE_REPORT.name) return@forEach
            val report = runCatching { json.decodeFromString<ScheduleReport>(value) }.getOrNull() ?: return@forEach
            val cacheKey = report.yearTermValue + report.classNo.takeIf(String::isNotBlank)?.let { "_$it" }.orEmpty() +
                if (report.scope == ScheduleScope.CURRENT_WEEK) "_current_week" else ""
            val purpose = when {
                name.startsWith("schedule_latest_") -> "browser"
                name.endsWith("_overview") -> "overview"
                name == PREF_WIDGET_SCHEDULE_REPORT.name -> "widget"
                else -> null
            }
            val student = when (purpose) {
                "browser" -> name.removePrefix("schedule_latest_")
                "overview" -> name.removePrefix("schedule_").removeSuffix("_overview")
                "widget" -> widgetStudent ?: return@forEach
                else -> name.removePrefix("schedule_").takeIf { it.endsWith("_$cacheKey") }
                    ?.removeSuffix("_$cacheKey") ?: return@forEach
            }
            val timeKey = when (purpose) {
                "browser" -> longPreferencesKey("schedule_${student}_latest_fetched_at")
                "overview" -> longPreferencesKey("schedule_${student}_overview_fetched_at")
                else -> longPreferencesKey("${name}_fetched_at")
            }
            entries.getOrPut(student) { mutableListOf() } += LegacyScheduleSnapshot(cacheKey, report, prefs[timeKey] ?: 0L, purpose)
            keys.getOrPut(student) { mutableSetOf() }.apply { add(prefKey); add(timeKey) }
        }
        entries.forEach { (student, reports) ->
            snapshots.importLegacy(student, reports, shouldCommit)
            dataStore.edit { current ->
                keys[student].orEmpty().forEach { key ->
                    if (current.asMap()[key] == prefs.asMap()[key]) current.remove(key)
                }
                if (widgetStudent == student && current[PREF_WIDGET_SCHEDULE_REPORT] == null) current.remove(PREF_WIDGET_SCHEDULE_STUDENT_NO)
            }
        }
    }

    suspend fun saveScheduleReport(
        studentNo: String,
        yearValue: String,
        report: ScheduleReport,
        updateOverviewSnapshot: Boolean = false,
        semesterBaseline: ScheduleReport? = null,
        updateBrowseSelection: Boolean = true,
        shouldCommit: () -> Boolean = { true },
    ) = commitSchedule {
        require(yearValue.isNotBlank())
        migrateSchedules()
        snapshots.save(studentNo, report, updateOverviewSnapshot, shouldCommit, semesterBaseline, browse = updateBrowseSelection)
    }

    suspend fun saveWidgetScheduleReport(studentNo: String, report: ScheduleReport, shouldCommit: () -> Boolean = { true }) = commitSchedule {
        migrateSchedules()
        snapshots.save(studentNo, report, false, shouldCommit, browse = false, publishToWidget = true)
    }

    suspend fun loadOverviewScheduleSnapshot(studentNo: String): CachedScheduleReport? {
        return loadCacheValue("schedule overview") {
            migrateSchedules()
            snapshots.selected(studentNo, "overview")
        }
    }

    suspend fun loadLatestScheduleSnapshot(studentNo: String): CachedScheduleReport? {
        migrateSchedules()
        return snapshots.selected(studentNo, "browser")
    }

    suspend fun loadLatestScheduleReport(studentNo: String): ScheduleReport? = loadLatestScheduleSnapshot(studentNo)?.report

    internal suspend fun selectSchedule(studentNo: String, report: ScheduleReport, automatic: Boolean, browse: Boolean = true, shouldCommit: () -> Boolean) = commitSchedule {
        migrateSchedules()
        snapshots.select(studentNo, report, automatic, shouldCommit, browse)
    }

    suspend fun loadWidgetScheduleReport(): ScheduleReport? {
        if (!canReadPrivateSnapshot()) return null
        migrateSchedules()
        val (student, report) = snapshots.widget() ?: return null
        val now = LocalDateTime.now(SCHOOL_COURSE_ZONE)
        val selected = if (report.scope == ScheduleScope.CURRENT_WEEK &&
            ((report.validWeekRange()?.endInclusive?.let { now.toLocalDate() > it } == true) || (report.items.isNotEmpty() && report.shouldRefreshAt(now)))) {
            val key = report.yearTermValue + report.classNo.takeIf(String::isNotBlank)?.let { "_$it" }.orEmpty()
            snapshots.load(student, key + "_current_week", report.refreshTargetDateAt(now))?.report
                ?: snapshots.load(student, key)?.report ?: report
        } else report
        return selected.copy(changes = null, subjectOverrides = loadScheduleSubjectOverrides(student))
    }

    suspend fun loadScheduleReport(studentNo: String, yearValue: String): ScheduleReport? = loadScheduleSnapshot(studentNo, yearValue)?.report

    suspend fun loadScheduleSnapshot(studentNo: String, yearValue: String, targetDate: LocalDate? = null): CachedScheduleReport? {
        migrateSchedules()
        return snapshots.load(studentNo, yearValue, targetDate)
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
        shouldCommit: () -> Boolean = { true },
    ) = commitSchedule {
        val normalized = overrides.normalizedSubjectOverrides()
        val key = scheduleSubjectOverridesKey(studentNo)
        dataStore.edit { prefs ->
            if (!shouldCommit()) throw kotlinx.coroutines.CancellationException("Session changed")
            if (normalized.isEmpty()) {
                prefs.remove(key)
            } else {
                prefs[key] = json.encodeToString(normalized)
            }

        }
    }

    suspend fun clearWidgetScheduleReport(studentNo: String? = null) = commitSchedule {
        try { dataStore.edit { prefs ->
            val owner = prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO]
            if (studentNo == null || owner == studentNo) {
                prefs.remove(PREF_WIDGET_SCHEDULE_REPORT)
                prefs.remove(PREF_WIDGET_SCHEDULE_STUDENT_NO)
            }
        } } finally { snapshots.clearWidget(studentNo) }
    }

    suspend fun clearStudent(studentNo: String, preserveSubjectOverrides: Boolean = false) = commitSchedule {
        val structureKey = "structure_$studentNo"
        val studentInfoKey = "student_info_$studentNo"
        val gradePrefix = "grade_${studentNo}_"
        val simulatorCreditsPrefix = simulatorCreditsPrefix(studentNo)
        val schedulePrefix = "schedule_${studentNo}_"
        val latestScheduleKey = "schedule_latest_$studentNo"
        try { dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name == structureKey ||
                        key.name == studentInfoKey ||
                        key.name == latestScheduleKey ||
                        key.name.startsWith(gradePrefix) ||
                        key.name.startsWith(simulatorCreditsPrefix) ||
                        key.name.startsWith(schedulePrefix)
                }
                .filterNot { key -> preserveSubjectOverrides && key == scheduleSubjectOverridesKey(studentNo) }
                .forEach { key -> prefs.remove(key) }
            if (prefs[PREF_WIDGET_SCHEDULE_STUDENT_NO] == studentNo) {
                prefs.remove(PREF_WIDGET_SCHEDULE_REPORT)
                prefs.remove(PREF_WIDGET_SCHEDULE_STUDENT_NO)
            }
        } } finally { snapshots.clearStudent(studentNo) }
    }

    suspend fun clearAll(preserveSubjectOverrides: Boolean = false) = commitSchedule {
        try { dataStore.edit { prefs ->
            if (preserveSubjectOverrides) {
                prefs.asMap().keys
                    .filterNot { it.name.startsWith("schedule_") && it.name.endsWith("_subject_overrides") }
                    .forEach { prefs.remove(it) }
            } else {
                prefs.clear()
            }
        } } finally { snapshots.clearAll() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun scheduleReportFlow(includeSemesterFallback: Boolean = false): Flow<ScheduleReport?> = privateSnapshotAllowed.flatMapLatest { allowed ->
        if (!allowed) flowOf<ScheduleReport?>(null)
        else flow<ScheduleReport?> {
            migrateSchedules()
            emitAll(combine<Unit, Preferences, ScheduleReport?>(snapshots.changes, dataStore.data) { _, _ ->
                val (student, raw) = snapshots.widget() ?: return@combine null
                val report = raw.copy(subjectOverrides = loadScheduleSubjectOverrides(student))
                val today = LocalDate.now(SCHOOL_COURSE_ZONE)
                val range = report.validWeekRange()
                val future = range != null && today < range.start && report.displayItems().any {
                    it.dayOfWeek in 1..7 && it.subjectName.isNotBlank() && it.periodInterval() != null
                }
                if (!includeSemesterFallback || report.scope != ScheduleScope.CURRENT_WEEK || report.isAuthoritativeWeekOn(today) || future) report
                else {
                    val key = report.yearTermValue + report.classNo.takeIf(String::isNotBlank)?.let { "_$it" }.orEmpty()
                    snapshots.load(student, key)?.report?.copy(changes = null, subjectOverrides = report.subjectOverrides) ?: report
                }
            })
        }
    }

    /** Widget UI treats unavailable private snapshots as empty; notification scheduling must retry I/O errors. */
    fun courseNotificationScheduleReportFlow() = scheduleReportFlow(includeSemesterFallback = true)

    fun widgetScheduleReportFlow() = scheduleReportFlow()
        .map { loadWidgetScheduleReport() }
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

    private suspend fun decodeCachedGradeReport(serialized: String): GradeReport? =
        withContext(Dispatchers.Default) {
            runCatching {
                json.decodeFromString<CachedGradeReport>(serialized).toCurrentGradeReport()
            }.getOrNull()
        }

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

        fun simulatorCreditsPrefix(studentNo: String) = "simulator_credits_${studentNo.length}:$studentNo:"

        fun simulatorCreditsKey(studentNo: String, yearValue: String, examValue: String) =
            stringPreferencesKey("${simulatorCreditsPrefix(studentNo)}${yearValue.length}:$yearValue:$examValue")
    }
}
