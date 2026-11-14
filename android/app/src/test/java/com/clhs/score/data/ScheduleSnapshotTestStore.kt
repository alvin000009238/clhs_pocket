package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.WeakHashMap

private class MemoryScheduleDao : ScheduleSnapshotDao() {
    private val records = linkedMapOf<String, ScheduleSnapshotRow>()
    private val selections = linkedMapOf<Pair<String, String>, ScheduleSnapshotPointer>()
    private val markers = mutableSetOf<String>()
    private val rowFlow = MutableStateFlow<List<ScheduleSnapshotRow>>(emptyList())
    private val pointerFlow = MutableStateFlow<List<ScheduleSnapshotPointer>>(emptyList())
    private var transaction = false
    private fun publish() { if (!transaction) { rowFlow.value = records.values.toList(); pointerFlow.value = selections.values.toList() } }
    override suspend fun rows(student: String) = records.values.filter { it.studentNo == student }
    override suspend fun pointers() = selections.values.toList()
    override fun observeRows(): Flow<List<ScheduleSnapshotRow>> = rowFlow
    override fun observePointers(): Flow<List<ScheduleSnapshotPointer>> = pointerFlow
    override suspend fun imported(student: String) = if (student in markers) 1 else 0
    override suspend fun putRows(rows: List<ScheduleSnapshotRow>) { rows.forEach { records[it.id] = it }; publish() }
    override suspend fun putPointers(pointers: List<ScheduleSnapshotPointer>) { pointers.forEach { selections[it.studentNo to it.purpose] = it }; publish() }
    override suspend fun markImported(marker: ScheduleImportMarker) { markers += marker.studentNo }
    override suspend fun prune(student: String, keep: List<String>) { records.entries.removeAll { it.value.studentNo == student && it.key !in keep }; publish() }
    override suspend fun clearWidget() { selections.entries.removeAll { it.key.second == "widget" }; publish() }
    override suspend fun clearWidget(student: String) { selections.remove(student to "widget"); publish() }
    override suspend fun clearStudent(student: String) { records.entries.removeAll { it.value.studentNo == student }; selections.entries.removeAll { it.key.first == student }; publish() }
    override suspend fun clearMarker(student: String) { markers.remove(student) }
    override suspend fun clearRows() { records.clear(); selections.clear(); publish() }
    override suspend fun clearMarkers() { markers.clear() }
    override suspend fun commit(rows: List<ScheduleSnapshotRow>, pointers: List<ScheduleSnapshotPointer>, importedStudent: String?, replaceWidget: Boolean, shouldCommit: () -> Boolean) {
        val oldRows = records.toMap(); val oldPointers = selections.toMap(); val oldMarkers = markers.toSet()
        transaction = true
        try { super.commit(rows, pointers, importedStudent, replaceWidget, shouldCommit) }
        catch (error: Throwable) { records.clear(); records.putAll(oldRows); selections.clear(); selections.putAll(oldPointers); markers.clear(); markers.addAll(oldMarkers); throw error }
        finally { transaction = false; publish() }
    }
}

private val stores = WeakHashMap<DataStore<Preferences>, ScheduleSnapshotStore>()
internal fun testGradeCacheStore(dataStore: DataStore<Preferences>, recordDiagnostic: (String) -> Unit): GradeCacheStore =
    testGradeCacheStore(dataStore, recordDiagnostic, flowOf(true))

internal fun testScheduleSnapshotStore(
    nowProvider: () -> java.time.LocalDateTime,
): ScheduleSnapshotStore = ScheduleSnapshotStore(MemoryScheduleDao(), nowProvider)

internal fun testGradeCacheStore(dataStore: DataStore<Preferences>, recordDiagnostic: (String) -> Unit,
    privateSnapshotAllowed: Flow<Boolean>, commitSchedule: suspend (suspend () -> Unit) -> Unit = { it() }): GradeCacheStore =
    GradeCacheStore(dataStore, recordDiagnostic, privateSnapshotAllowed,
        synchronized(stores) { stores.getOrPut(dataStore) { ScheduleSnapshotStore(MemoryScheduleDao()) } }, commitSchedule)
