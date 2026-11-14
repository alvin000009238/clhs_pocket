package com.clhs.score.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime

@Entity(tableName = "schedule_snapshots", foreignKeys = [ForeignKey(
    entity = ScheduleSnapshotRow::class, parentColumns = ["id"], childColumns = ["semesterId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("semesterId"), Index(value = ["studentNo", "cacheKey", "capturedAt"]),
    Index(value = ["studentNo", "yearTerm", "classNo", "weekStart"])])
internal data class ScheduleSnapshotRow(
    @PrimaryKey val id: String,
    val studentNo: String,
    val cacheKey: String,
    val yearTerm: String,
    val classNo: String,
    val weekStart: String?,
    val capturedAt: Long,
    val reportJson: String,
    val semesterId: String?,
    val baselineOnly: Boolean = false,
)

@Entity(tableName = "schedule_pointers", primaryKeys = ["studentNo", "purpose"], foreignKeys = [ForeignKey(
    entity = ScheduleSnapshotRow::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("snapshotId")])
internal data class ScheduleSnapshotPointer(val studentNo: String, val purpose: String, val snapshotId: String)

@Entity(tableName = "schedule_imports")
internal data class ScheduleImportMarker(@PrimaryKey val studentNo: String)

@Dao
internal abstract class ScheduleSnapshotDao {
    @Query("SELECT * FROM schedule_snapshots WHERE studentNo = :student")
    abstract suspend fun rows(student: String): List<ScheduleSnapshotRow>
    @Query("SELECT * FROM schedule_pointers")
    abstract suspend fun pointers(): List<ScheduleSnapshotPointer>
    @Query("SELECT * FROM schedule_snapshots")
    abstract fun observeRows(): Flow<List<ScheduleSnapshotRow>>
    @Query("SELECT * FROM schedule_pointers")
    abstract fun observePointers(): Flow<List<ScheduleSnapshotPointer>>
    @Query("SELECT COUNT(*) FROM schedule_imports WHERE studentNo = :student")
    abstract suspend fun imported(student: String): Int
    @Upsert abstract suspend fun putRows(rows: List<ScheduleSnapshotRow>)
    @Upsert abstract suspend fun putPointers(pointers: List<ScheduleSnapshotPointer>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun markImported(marker: ScheduleImportMarker)
    @Query("DELETE FROM schedule_snapshots WHERE studentNo = :student AND id NOT IN (:keep)")
    abstract suspend fun prune(student: String, keep: List<String>)
    @Query("DELETE FROM schedule_pointers WHERE purpose = 'widget'")
    abstract suspend fun clearWidget()
    @Query("DELETE FROM schedule_pointers WHERE purpose = 'widget' AND studentNo = :student")
    abstract suspend fun clearWidget(student: String)
    @Query("DELETE FROM schedule_snapshots WHERE studentNo = :student")
    abstract suspend fun clearStudent(student: String)
    @Query("DELETE FROM schedule_imports WHERE studentNo = :student")
    abstract suspend fun clearMarker(student: String)
    @Query("DELETE FROM schedule_snapshots") abstract suspend fun clearRows()
    @Query("DELETE FROM schedule_imports") abstract suspend fun clearMarkers()

    @Transaction
    open suspend fun commit(rows: List<ScheduleSnapshotRow>, pointers: List<ScheduleSnapshotPointer>,
        importedStudent: String?, replaceWidget: Boolean, shouldCommit: () -> Boolean) {
        if (!shouldCommit()) throw CancellationException("Session changed")
        if (importedStudent != null && imported(importedStudent) != 0) return
        val safeRows = rows.map { incoming ->
            val previous = this.rows(incoming.studentNo).firstOrNull { it.id == incoming.id }
            if (previous != null && previous.capturedAt > incoming.capturedAt) previous
            else incoming.copy(baselineOnly = incoming.baselineOnly && previous?.baselineOnly != false)
        }
        putRows(safeRows.filter { it.semesterId == null })
        putRows(safeRows.filter { it.semesterId != null })
        if (replaceWidget) clearWidget()
        putPointers(pointers)
        if (importedStudent != null) markImported(ScheduleImportMarker(importedStudent))
        if (!shouldCommit()) throw CancellationException("Session changed")
    }

    @Transaction open suspend fun pruneRetained(student: String) {
        val rows = rows(student)
        val pointers = pointers()
        val unique = rows.associateBy { it.id }.values
        val newest = unique.filterNot { it.baselineOnly }.groupBy { it.cacheKey to it.weekStart }
            .values.map { versions -> versions.maxBy { it.capturedAt } }
        val weeks = newest.filter { it.weekStart != null }.sortedByDescending { it.weekStart }.take(32)
        val keep = (weeks + newest.filter { it.weekStart == null }).map { it.id }.toMutableSet()
        keep += pointers.filter { it.studentNo == student }.map { it.snapshotId }
        var added: Boolean
        do {
            added = false
            unique.filter { it.id in keep }.mapNotNull { it.semesterId }.forEach { if (keep.add(it)) added = true }
        } while (added)
        prune(student, keep.toList())
    }

    @Transaction open suspend fun deleteStudent(student: String) { clearStudent(student); clearMarker(student) }
    @Transaction open suspend fun deleteAll() { clearRows(); clearMarkers() }
}

@Database(entities = [ScheduleSnapshotRow::class, ScheduleSnapshotPointer::class, ScheduleImportMarker::class], version = 1, exportSchema = true)
internal abstract class ScheduleSnapshotDatabase : RoomDatabase() {
    abstract fun snapshots(): ScheduleSnapshotDao
    companion object {
        @Volatile private var instance: ScheduleSnapshotDatabase? = null
        fun get(context: Context): ScheduleSnapshotDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ScheduleSnapshotDatabase::class.java, "schedule_snapshots.db")
                .build().also { instance = it }
        }
    }
}

internal data class LegacyScheduleSnapshot(val cacheKey: String, val report: ScheduleReport, val capturedAt: Long, val purpose: String? = null)

internal class ScheduleSnapshotStore private constructor(
    private val dao: ScheduleSnapshotDao,
    private val database: ScheduleSnapshotDatabase?,
    private val nowProvider: () -> LocalDateTime = { LocalDateTime.now(SCHOOL_COURSE_ZONE) },
) {
    constructor(database: ScheduleSnapshotDatabase) : this(database.snapshots(), database)
    internal constructor(
        dao: ScheduleSnapshotDao,
        nowProvider: () -> LocalDateTime = { LocalDateTime.now(SCHOOL_COURSE_ZONE) },
    ) : this(dao, null, nowProvider)
    private val mutex = Mutex()
    private suspend fun <T> transaction(block: suspend () -> T): T {
        val db = database
        return if (db != null) db.withTransaction { block() } else mutex.withLock { block() }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val changes: Flow<Unit> = combine(dao.observeRows(), dao.observePointers()) { _, _ -> }

    private fun key(report: ScheduleReport): String = report.yearTermValue +
        report.classNo.takeIf(String::isNotBlank)?.let { "_$it" }.orEmpty() +
        if (report.scope == ScheduleScope.CURRENT_WEEK) "_current_week" else ""

    private fun row(student: String, report: ScheduleReport, at: Long, base: String? = null, baselineOnly: Boolean = false): ScheduleSnapshotRow {
        val raw = report.copy(items = report.items.sortedWith(compareBy({ it.dayOfWeek }, { it.period })), changes = null, subjectOverrides = emptyList())
        val encoded = json.encodeToString(raw)
        val id = MessageDigest.getInstance("SHA-256").digest("$student|$encoded|$base".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ScheduleSnapshotRow(id, student, key(raw), raw.yearTermValue, raw.classNo, raw.weekStartDate, at, encoded, base, baselineOnly)
    }

    private fun reversedBase(report: ScheduleReport): ScheduleReport? {
        if (report.scope != ScheduleScope.CURRENT_WEEK || report.changes == null) return null
        val slots = report.items.associateBy { it.dayOfWeek to it.period }.toMutableMap()
        report.changes.forEach { change ->
            val slot = change.dayOfWeek to change.period
            if (change.semesterItem == null) slots.remove(slot) else slots[slot] = change.semesterItem
        }
        return ScheduleReport(report.yearTermValue, report.classNo, ScheduleScope.SEMESTER, items = slots.values.toList())
    }

    private fun encodedRows(student: String, report: ScheduleReport, at: Long, baseline: ScheduleReport?): List<ScheduleSnapshotRow> {
        val baseReport = baseline ?: reversedBase(report)
        val base = baseReport?.takeIf { it.scope == ScheduleScope.SEMESTER && it.yearTermValue == report.yearTermValue && it.classNo == report.classNo }
            ?.let { row(student, it, at, baselineOnly = baseline == null) }
        return listOfNotNull(base, row(student, report, at, base?.id))
    }

    private fun decode(row: ScheduleSnapshotRow, rows: List<ScheduleSnapshotRow>): CachedScheduleReport {
        val raw = json.decodeFromString<ScheduleReport>(row.reportJson)
        val base = row.semesterId?.let { id -> rows.firstOrNull { it.id == id } }?.let { json.decodeFromString<ScheduleReport>(it.reportJson) }
        return CachedScheduleReport(raw.copy(changes = base?.let { compareScheduleItems(it.items, raw.items) }), row.capturedAt)
    }

    suspend fun imported(student: String): Boolean = dao.imported(student) != 0

    suspend fun importLegacy(student: String, legacy: List<LegacyScheduleSnapshot>, shouldCommit: () -> Boolean = { true }) = transaction {
        if (imported(student)) return@transaction
        val existing = dao.rows(student)
        val rows = legacy.flatMap { encodedRows(student, it.report, it.capturedAt, null) }
            .groupBy { it.id }.map { (_, versions) ->
                versions.maxBy { it.capturedAt }.copy(baselineOnly = versions.all { it.baselineOnly })
            }
            .filter { incoming -> existing.none { it.id == incoming.id && it.capturedAt > incoming.capturedAt } }
        val currentPointers = dao.pointers().filter { it.studentNo == student }.map { it.purpose }.toSet()
        val pointers = legacy.filter { it.purpose != null && it.purpose !in currentPointers }.map {
            ScheduleSnapshotPointer(student, requireNotNull(it.purpose), encodedRows(student, it.report, it.capturedAt, null).last().id)
        }
        val browser = legacy.lastOrNull { it.purpose == "browser" }?.report
        val automatic = legacy.filter {
            it.report.scope == ScheduleScope.CURRENT_WEEK && it.report.isValidOn(nowProvider().toLocalDate()) &&
                (browser == null || (it.report.yearTermValue == browser.yearTermValue && it.report.classNo == browser.classNo))
        }.maxByOrNull { it.capturedAt }
        val migratedPointers = if ("overview" !in currentPointers && pointers.none { it.purpose == "overview" } && automatic != null) {
            pointers + ScheduleSnapshotPointer(student, "overview", encodedRows(student, automatic.report, automatic.capturedAt, null).last().id)
        } else pointers
        dao.commit(rows, migratedPointers, student, migratedPointers.any { it.purpose == "widget" }, shouldCommit)
    }

    suspend fun save(student: String, report: ScheduleReport, automatic: Boolean, shouldCommit: () -> Boolean,
        baseline: ScheduleReport? = null, capturedAt: Long = System.currentTimeMillis(), browse: Boolean = true,
        publishToWidget: Boolean = browse || automatic) = transaction {
        val pointers = dao.pointers()
        val oldWidget = pointers.firstOrNull { it.purpose == "widget" }?.let { pointer ->
            dao.rows(pointer.studentNo).firstOrNull { it.id == pointer.snapshotId }?.let { decode(it, dao.rows(pointer.studentNo)).report }
        }
        val encoded = encodedRows(student, report, capturedAt, baseline)
        val version = encoded.last()
        val updates = mutableListOf<ScheduleSnapshotPointer>()
        if (browse) updates += ScheduleSnapshotPointer(student, "browser", version.id)
        val now = nowProvider()
        val oldOverview = pointers.firstOrNull { it.studentNo == student && it.purpose == "overview" }?.let { pointer ->
            dao.rows(student).firstOrNull { it.id == pointer.snapshotId }?.let { decode(it, dao.rows(student)).report }
        }
        val replacesOverview = automatic || (
            browse &&
                report.scope == ScheduleScope.CURRENT_WEEK &&
                report.isValidOn(now.toLocalDate()) &&
                !report.shouldRefreshAt(now) &&
                (oldOverview == null || oldOverview.yearTermValue != report.yearTermValue ||
                    oldOverview.classNo != report.classNo || oldOverview.scope != ScheduleScope.CURRENT_WEEK ||
                    (oldOverview.weekStartDate ?: "") <= (report.weekStartDate ?: ""))
            )
        if (replacesOverview) {
            updates += ScheduleSnapshotPointer(student, "overview", version.id)
        }
        val publishWidget = publishToWidget && report.toWidgetReportOrNull(oldWidget) != null
        if (publishWidget) updates += ScheduleSnapshotPointer(student, "widget", version.id)
        dao.commit(encoded, updates, null, publishWidget, shouldCommit)
        dao.pruneRetained(student)
    }

    suspend fun load(student: String, cacheKey: String, date: LocalDate? = null): CachedScheduleReport? = transaction {
        val rows = dao.rows(student)
        rows.filter { it.cacheKey == cacheKey && !it.baselineOnly }
            .filter { row -> date == null || json.decodeFromString<ScheduleReport>(row.reportJson).isValidOn(date) }
            .maxByOrNull { it.capturedAt }?.let { decode(it, rows) }
    }

    suspend fun select(student: String, report: ScheduleReport, automatic: Boolean, shouldCommit: () -> Boolean, browse: Boolean = true) = transaction {
        val rows = dao.rows(student)
        val selected = rows.filter { it.cacheKey == key(report) && it.weekStart == report.weekStartDate && !it.baselineOnly }
            .maxByOrNull { it.capturedAt } ?: return@transaction
        val pointers = mutableListOf<ScheduleSnapshotPointer>()
        if (browse) pointers += ScheduleSnapshotPointer(student, "browser", selected.id)
        if (automatic) pointers += ScheduleSnapshotPointer(student, "overview", selected.id)
        dao.commit(emptyList(), pointers, null, false, shouldCommit)
    }

    suspend fun selected(student: String, purpose: String): CachedScheduleReport? = transaction {
        val rows = dao.rows(student)
        val pointers = dao.pointers()
        val selected = pointers.firstOrNull { it.studentNo == student && it.purpose == purpose } ?: return@transaction null
        val row = rows.firstOrNull { it.id == selected.snapshotId } ?: return@transaction null
        if (purpose == "overview") {
            val browseId = pointers.firstOrNull { it.studentNo == student && it.purpose == "browser" }?.snapshotId
            val browse = rows.firstOrNull { it.id == browseId }
            if (browse != null && (browse.yearTerm != row.yearTerm || browse.classNo != row.classNo)) return@transaction null
        }
        decode(row, rows)
    }

    suspend fun widget(): Pair<String, ScheduleReport>? = transaction {
        val pointer = dao.pointers().firstOrNull { it.purpose == "widget" } ?: return@transaction null
        val rows = dao.rows(pointer.studentNo)
        val row = rows.firstOrNull { it.id == pointer.snapshotId } ?: return@transaction null
        pointer.studentNo to decode(row, rows).report.copy(changes = null)
    }
    suspend fun clearWidget(student: String?) = transaction { if (student == null) dao.clearWidget() else dao.clearWidget(student) }
    suspend fun clearStudent(student: String) = transaction { dao.deleteStudent(student) }
    suspend fun clearAll() = transaction { dao.deleteAll() }
}
