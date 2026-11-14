package com.clhs.score.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ScheduleSnapshotTransactionTest {
    @Test fun concurrentFuturePublicationCannotOverwriteNewlyPublishedCurrentWidget() = runBlocking {
        val armed = AtomicBoolean(false)
        val readingWidget = CountDownLatch(1)
        val releaseFuture = CountDownLatch(1)
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM schedule_snapshots WHERE studentNo =") && armed.compareAndSet(true, false)) {
                    readingWidget.countDown()
                    check(releaseFuture.await(10, TimeUnit.SECONDS))
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val first = ScheduleSnapshotStore(database)
            val second = ScheduleSnapshotStore(database)
            val monday = LocalDate.now(SCHOOL_COURSE_ZONE).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            first.save("a", week(monday.minusWeeks(1)), false, { true })
            armed.set(true)
            val future = async(Dispatchers.IO) { first.save("a", week(monday.plusWeeks(1), "未來課程"), false, { true }) }
            check(withContext(Dispatchers.IO) { readingWidget.await(10, TimeUnit.SECONDS) })
            val current = async(Dispatchers.IO) { second.save("a", week(monday, "當週課程"), true, { true }) }
            withTimeoutOrNull(2_000) { current.await() }
            releaseFuture.countDown()
            withTimeout(10_000) { future.await(); current.await() }
            assertEquals("當週課程", first.widget()!!.second.items.single().subjectName)
        } finally { releaseFuture.countDown(); database.close() }
    }

    @Test fun readerKeepsCoherentSelectionWhileAnotherStorePublishesAndPrunes() = runBlocking {
        val armed = AtomicBoolean(false)
        val readingPointers = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql == "SELECT * FROM schedule_pointers" && armed.compareAndSet(true, false)) {
                    readingPointers.countDown()
                    check(releaseReader.await(10, TimeUnit.SECONDS))
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        try {
            val readerStore = ScheduleSnapshotStore(database)
            val writerStore = ScheduleSnapshotStore(database)
            val old = week(LocalDate.of(2025, 1, 6), "舊課程")
            readerStore.save("a", old, false, { true }, capturedAt = 1)
            armed.set(true)
            val reader = async(Dispatchers.IO) { readerStore.selected("a", "browser") }
            check(withContext(Dispatchers.IO) { readingPointers.await(10, TimeUnit.SECONDS) })
            val writer = async(Dispatchers.IO) { writerStore.save("a", old.copy(items = semester("新課程").items), false, { true }, capturedAt = 2) }
            withTimeoutOrNull(2_000) { writer.await() }
            releaseReader.countDown()
            assertEquals("舊課程", withTimeout(10_000) { reader.await() }!!.report.items.single().subjectName)
            withTimeout(10_000) { writer.await() }
            assertEquals("新課程", readerStore.selected("a", "browser")!!.report.items.single().subjectName)
        } finally { releaseReader.countDown(); database.close() }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun semester(subject: String = "國文") = ScheduleReport("115_1", "230", ScheduleScope.SEMESTER,
        items = listOf(ScheduleItem(1, 1, subject, "教師", "教室")))
    private fun week(start: LocalDate, subject: String = "數學") = semester(subject).copy(
        scope = ScheduleScope.CURRENT_WEEK, weekStartDate = start.toString(), weekEndDate = start.plusDays(6).toString())

    @Test fun revokedCommitRollsBackBothSnapshotAndSelection() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java).build()
        try {
            val dao = database.snapshots()
            val store = ScheduleSnapshotStore(database)
            var checks = 0
            val failure = runCatching {
                store.save("a", week(LocalDate.of(2026, 11, 9)), true, { ++checks == 1 }, semester())
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(dao.rows("a").isEmpty())
            assertTrue(dao.pointers().isEmpty())
            store.save("a", week(LocalDate.of(2026, 11, 9)), true, { true }, semester())
            assertNotNull(store.selected("a", "overview"))
        } finally { database.close() }
    }

    @Test fun historicalWeekKeepsOriginalBaselineAcrossSemesterUpdateAndDatabaseReopen() = runBlocking {
        val name = "schedule-history-test-${System.nanoTime()}.db"
        var database = Room.databaseBuilder(context, ScheduleSnapshotDatabase::class.java, name).build()
        try {
            var store = ScheduleSnapshotStore(database)
            val monday = LocalDate.of(2026, 11, 9)
            store.save("a", week(monday), true, { true }, semester(), capturedAt = 10)
            store.save("a", semester("英文"), false, { true }, capturedAt = 20)
            database.close()
            database = Room.databaseBuilder(context, ScheduleSnapshotDatabase::class.java, name).build()
            store = ScheduleSnapshotStore(database)
            val saved = store.load("a", "115_1_230_current_week", monday)!!.report
            assertEquals("數學", saved.items.single().subjectName)
            assertEquals("國文", saved.changes!!.single().semesterItem!!.subjectName)
            assertEquals("英文", store.load("a", "115_1_230")!!.report.items.single().subjectName)
            assertNull(store.load("b", "115_1_230_current_week", monday))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun legacyImportIsDurableIdempotentAndDeletesPayloadOnlyAfterCommit() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = context.cacheDir.resolve("schedule-import-${System.nanoTime()}.preferences_pb")
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            val old = week(LocalDate.of(2026, 11, 9)).let { it.copy(changes = compareScheduleItems(semester().items, it.items)) }
            val key = stringPreferencesKey("schedule_latest_a")
            preferences.edit {
                it[key] = Json.encodeToString(old)
                it[stringPreferencesKey("schedule_a_115_1_230")] = Json.encodeToString(semester())
            }
            val store = ScheduleSnapshotStore(database)
            val facade = GradeCacheStore(preferences, {}, flowOf(true), store)
            assertEquals("數學", facade.loadLatestScheduleSnapshot("a")!!.report.items.single().subjectName)
            assertEquals("國文", facade.loadLatestScheduleSnapshot("a")!!.report.changes!!.single().semesterItem!!.subjectName)
            assertNull(preferences.data.first()[key])
            assertEquals(1, database.snapshots().imported("a"))
            assertEquals("國文", facade.loadScheduleReport("a", "115_1_230")!!.items.single().subjectName)
            store.save("a", week(LocalDate.of(2026, 11, 16), "英文"), false, { true }, capturedAt = 50)
            preferences.edit { it[key] = Json.encodeToString(old) }
            assertEquals("英文", facade.loadLatestScheduleSnapshot("a")!!.report.items.single().subjectName)
            assertNull(preferences.data.first()[key])
            facade.clearStudent("a")
            assertTrue(database.snapshots().rows("a").isEmpty())
            assertTrue(database.snapshots().pointers().isEmpty())
            assertEquals(0, database.snapshots().imported("a"))
        } finally { scope.cancel(); database.close(); file.delete() }
    }

    @Test fun failedMigrationRetainsLegacyPayloadForRetry() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = context.cacheDir.resolve("schedule-import-retry-${System.nanoTime()}.preferences_pb")
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            val key = stringPreferencesKey("schedule_latest_a")
            preferences.edit { it[key] = Json.encodeToString(semester()) }
            var checks = 0
            val facade = GradeCacheStore(preferences, {}, flowOf(true), ScheduleSnapshotStore(database),
                migrationAuthority = { { ++checks == 1 } })
            assertTrue(runCatching { facade.loadLatestScheduleSnapshot("a") }.exceptionOrNull() is CancellationException)
            assertNotNull(preferences.data.first()[key])
            assertTrue(database.snapshots().rows("a").isEmpty())
            assertEquals(0, database.snapshots().imported("a"))
            val retry = GradeCacheStore(preferences, {}, flowOf(true), ScheduleSnapshotStore(database))
            assertNotNull(retry.loadLatestScheduleSnapshot("a"))
            assertNull(preferences.data.first()[key])
        } finally { scope.cancel(); database.close(); file.delete() }
    }

    @Test fun overviewRefreshAndCachedBrowsingKeepIndependentSelectionsAndOriginalTimestamp() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java).build()
        try {
            val store = ScheduleSnapshotStore(database)
            val start = LocalDate.of(2026, 11, 9)
            val future = week(start.plusWeeks(2))
            store.save("a", future, false, { true }, capturedAt = 10)
            store.save("a", week(start), true, { true }, capturedAt = 20, browse = false)
            assertEquals(future.weekStartDate, store.selected("a", "browser")!!.report.weekStartDate)
            assertEquals(start.toString(), store.selected("a", "overview")!!.report.weekStartDate)
            store.select("a", future, false, { true })
            assertEquals(10L, store.selected("a", "browser")!!.fetchedAtMillis)
            assertEquals(start.toString(), store.selected("a", "overview")!!.report.weekStartDate)
        } finally { database.close() }
    }

    @Test fun retentionPreservesPinnedBaselineAndOtherAccountWhileBoundingHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ScheduleSnapshotDatabase::class.java).build()
        try {
            val dao = database.snapshots()
            val store = ScheduleSnapshotStore(database)
            val start = LocalDate.of(2025, 1, 6)
            store.save("b", semester("他人"), false, { true })
            store.save("a", week(start), true, { true }, semester("原基準"), capturedAt = 1)
            repeat(40) { index ->
                store.save("a", week(start.plusWeeks(index + 1L)), false, { true }, semester("新基準"), capturedAt = index + 2L)
            }
            assertEquals(33, dao.rows("a").count { it.weekStart != null })
            assertEquals("原基準", store.selected("a", "overview")!!.report.changes!!.single().semesterItem!!.subjectName)
            assertEquals("他人", store.load("b", "115_1_230")!!.report.items.single().subjectName)
            store.clearStudent("a")
            assertTrue(dao.rows("a").isEmpty())
            assertNotNull(store.load("b", "115_1_230"))
        } finally { database.close() }
    }
}
