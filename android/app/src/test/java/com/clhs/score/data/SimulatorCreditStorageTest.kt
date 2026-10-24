package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class SimulatorCreditStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val settings = SavedSimulatorCredits(
        subjectNames = listOf("國語文-甲", "國語文-乙").sorted(),
        credits = mapOf("國語文-甲" to 4, "國語文-乙" to 1),
        source = "manual",
    )

    @Test
    fun settingsSurviveClosingAndReopeningRealDataStore() = runTest {
        val file = temporaryFolder.root.resolve("durable.preferences_pb")
        val firstJob = SupervisorJob()
        try {
            val disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + firstJob)) { file }
            GradeCacheStore(disk) {}.saveSimulatorCredits("student", "114_1", "E1", settings)
        } finally {
            firstJob.cancelAndJoin()
        }
        val secondJob = SupervisorJob()
        try {
            val disk = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + secondJob)) { file }
            assertEquals(settings, GradeCacheStore(disk) {}.loadSimulatorCredits("student", "114_1", "E1"))
            assertEquals(1, disk.data.first().asMap().size)
        } finally {
            secondJob.cancelAndJoin()
        }
    }

    @Test
    fun scopesAreIsolatedAndNewSignatureReplacesLatestExamSettings() = runTest {
        val disk = dataStore(backgroundScope)
        val store = GradeCacheStore(disk) {}
        store.saveSimulatorCredits("student", "114_1", "E1", settings)
        store.saveSimulatorCredits("student", "114_1", "E2", settings)
        store.saveSimulatorCredits("student", "114_2", "E1", settings)
        store.saveSimulatorCredits("student_other", "114_1", "E1", settings)
        val replacement = SavedSimulatorCredits(listOf("數學-甲"), mapOf("數學-甲" to 3), "inferred")
        store.saveSimulatorCredits("student", "114_1", "E1", replacement)

        assertEquals(replacement, store.loadSimulatorCredits("student", "114_1", "E1"))
        assertEquals(settings, store.loadSimulatorCredits("student", "114_1", "E2"))
        assertEquals(settings, store.loadSimulatorCredits("student", "114_2", "E1"))
        assertEquals(settings, store.loadSimulatorCredits("student_other", "114_1", "E1"))
        assertNull(store.loadSimulatorCredits("unknown", "114_1", "E1"))
        assertEquals(4, disk.data.first().asMap().size)

        store.saveSimulatorCredits("student", "114_1", "E1", null)
        assertNull(store.loadSimulatorCredits("student", "114_1", "E1"))
        assertEquals(settings, store.loadSimulatorCredits("student", "114_1", "E2"))
    }

    @Test
    fun scopeComponentsContainingSeparatorsCannotAliasAnotherScope() = runTest {
        val store = GradeCacheStore(dataStore(backgroundScope)) {}
        store.saveSimulatorCredits("student", "year_exam", "one", settings)
        assertNull(store.loadSimulatorCredits("student", "year", "exam_one"))
        assertNull(store.loadSimulatorCredits("student_year", "exam", "one"))
    }

    @Test
    fun sixtyFourSubjectsCanBeStoredAndLoaded() = runTest {
        val names = (1..64).map { "subject$it" }.sorted()
        val settings = SavedSimulatorCredits(names, names.associateWith { 1 }, "manual")
        val store = GradeCacheStore(dataStore(backgroundScope)) {}
        store.saveSimulatorCredits("student", "114_1", "E1", settings)
        assertEquals(settings, store.loadSimulatorCredits("student", "114_1", "E1"))
    }

    @Test
    fun missingSettingReturnsNullButRealDataStoreReadFailurePropagates() = runTest {
        val store = GradeCacheStore(dataStore(backgroundScope)) {}
        assertNull(store.loadSimulatorCredits("student", "114_1", "E1"))

        val unreadableFile = temporaryFolder.newFolder("unreadable.preferences_pb")
        val failedDisk = PreferenceDataStoreFactory.create(scope = backgroundScope) { unreadableFile }
        val failedStore = GradeCacheStore(failedDisk) {}
        assertTrue(runCatching { failedDisk.data.first() }.exceptionOrNull() is IOException)
        assertTrue(runCatching {
            failedStore.loadSimulatorCredits("student", "114_1", "E1")
        }.exceptionOrNull() is IOException)

        val repository = FakeGradeRepository(cacheStore = failedStore)
        repository.activateSession(FakeData.session)
        assertTrue(runCatching {
            repository.loadSimulatorCredits(FakeData.session, "114_1", "E1")
        }.exceptionOrNull() is IOException)
    }

    @Test
    fun privacyClearsRemoveCreditsEvenWhenPreservingScheduleOverrides() = runTest {
        val store = GradeCacheStore(dataStore(backgroundScope)) {}
        store.saveSimulatorCredits("student", "114_1", "E1", settings)
        store.saveSimulatorCredits("student", "114_1", "E2", settings)
        store.saveSimulatorCredits("student_other", "114_1", "E1", settings)
        store.saveScheduleSubjectOverrides("student", listOf(ScheduleSubjectOverride("國語文", "自訂國文")))

        store.clearStudent("student", preserveSubjectOverrides = true)
        assertNull(store.loadSimulatorCredits("student", "114_1", "E1"))
        assertNull(store.loadSimulatorCredits("student", "114_1", "E2"))
        assertEquals(settings, store.loadSimulatorCredits("student_other", "114_1", "E1"))
        assertEquals(1, store.loadScheduleSubjectOverrides("student").size)

        store.clearAll(preserveSubjectOverrides = true)
        assertNull(store.loadSimulatorCredits("student_other", "114_1", "E1"))
        assertEquals(1, store.loadScheduleSubjectOverrides("student").size)
    }

    @Test
    fun staleTransactionThrowsForReplacementAndRemovalWithoutChangingSavedValue() = runTest {
        val store = GradeCacheStore(dataStore(backgroundScope)) {}
        store.saveSimulatorCredits("student", "114_1", "E1", settings)
        for (attempt in listOf(settings.copy(source = "inferred"), null)) {
            val error = runCatching {
                store.saveSimulatorCredits("student", "114_1", "E1", attempt) { false }
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(settings, store.loadSimulatorCredits("student", "114_1", "E1"))
        }
    }

    @Test
    fun invalidSettingsCannotBeConstructed() {
        val invalid = listOf<() -> SavedSimulatorCredits>(
            { settings.copy(subjectNames = emptyList(), credits = emptyMap()) },
            { settings.copy(subjectNames = settings.subjectNames.reversed()) },
            { settings.copy(subjectNames = listOf("國語文<br/>"), credits = mapOf("國語文<br/>" to 1)) },
            { settings.copy(subjectNames = listOf(" "), credits = mapOf(" " to 1)) },
            { settings.copy(subjectNames = listOf("數學", "數學"), credits = mapOf("數學" to 1)) },
            { settings.copy(credits = mapOf("國語文-甲" to 1)) },
            { settings.copy(credits = settings.credits + ("額外科目" to 1)) },
            { settings.copy(credits = settings.credits.mapValues { -1 }) },
            { settings.copy(credits = settings.credits.mapValues { 5 }) },
            { settings.copy(credits = settings.credits.mapValues { 0 }) },
            { settings.copy(credits = settings.credits + ("國語文-乙" to 0)) },
            { settings.copy(source = "unknown") },
            { settings.copy(version = 2) },
            {
                val names = (1..65).map { "subject$it" }.sorted()
                SavedSimulatorCredits(names, names.associateWith { 1 }, "manual")
            },
        )
        invalid.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }

    @Test
    fun malformedOrInvalidStoredSettingsPropagateWithoutDeletingTheSavedValue() = runTest {
        val disk = dataStore(backgroundScope)
        val store = GradeCacheStore(disk) {}
        store.saveSimulatorCredits("student", "114_1", "E1", settings)
        val key = disk.data.first().asMap().keys.single()
        for (serialized in listOf("not json", """{"subjectNames":["數學"],"credits":{"數學":5},"source":"manual"}""", """{"subjectNames":["數學"],"credits":{"數學":0},"source":"manual"}""")) {
            disk.edit { it[stringPreferencesKey(key.name)] = serialized }
            val error = runCatching { store.loadSimulatorCredits("student", "114_1", "E1") }.exceptionOrNull()
            assertTrue(error is SerializationException || error is IllegalArgumentException)
            assertEquals(serialized, disk.data.first()[stringPreferencesKey(key.name)])
        }
    }

    @Test
    fun writeDeleteAndPrivacyCleanupFailuresPropagate() = runTest {
        val failure = IOException("storage unavailable")
        val store = GradeCacheStore(object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw failure
        }) {}

        for (attempt in listOf(settings, null)) {
            assertEquals(failure, runCatching {
                store.saveSimulatorCredits("student", "114_1", "E1", attempt)
            }.exceptionOrNull())
        }
        assertEquals(failure, runCatching { store.clearStudent("student") }.exceptionOrNull())
        assertEquals(failure, runCatching { store.clearAll() }.exceptionOrNull())
    }

    @Test
    fun fakeRepositoryFallbackIsScopedAndRejectsStaleSession() = runTest {
        val repository = FakeGradeRepository()
        val first = FakeData.session
        val second = first.copy(studentNo = "other", authorizationId = "other-authorization")
        repository.activateSession(first)
        repository.saveSimulatorCredits(first, "114_1", "E1", settings)
        assertNull(repository.loadSimulatorCredits(first, "114_1", "E2"))
        repository.activateSession(second)
        assertNull(repository.loadSimulatorCredits(second, "114_1", "E1"))
        assertTrue(runCatching {
            repository.saveSimulatorCredits(first, "114_1", "E1", null)
        }.exceptionOrNull() is CancellationException)
        repository.activateSession(first)
        assertEquals(settings, repository.loadSimulatorCredits(first, "114_1", "E1"))
        repository.saveSimulatorCredits(first, "114_1", "E1", null)
        assertNull(repository.loadSimulatorCredits(first, "114_1", "E1"))
        repository.saveSimulatorCredits(first, "114_1", "E1", settings)
        repository.logout(first)
        assertTrue(runCatching {
            repository.saveSimulatorCredits(first, "114_1", "E1", settings)
        }.exceptionOrNull() is CancellationException)
        repository.activateSession(first)
        assertNull(repository.loadSimulatorCredits(first, "114_1", "E1"))
    }

    @Test
    fun fakeRepositoryUsesSuppliedCacheAndRejectsQueuedWriteAfterSessionChanges() = runTest {
        val disk = dataStore(backgroundScope)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val delayedDisk = object : DataStore<Preferences> by disk {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writeStarted.complete(Unit)
                releaseWrite.await()
                return disk.updateData(transform)
            }
        }
        val repository = FakeGradeRepository(cacheStore = GradeCacheStore(delayedDisk) {})
        val session = FakeData.session
        repository.activateSession(session)
        val queuedWrite = async {
            runCatching { repository.saveSimulatorCredits(session, "114_1", "E1", settings) }
        }
        writeStarted.await()
        repository.activateSession(session.copy(authorizationId = "new-authorization"))
        releaseWrite.complete(Unit)
        assertTrue(queuedWrite.await().exceptionOrNull() is CancellationException)
        assertNull(GradeCacheStore(disk) {}.loadSimulatorCredits(session.studentNo, "114_1", "E1"))

        repository.activateSession(session)
        repository.saveSimulatorCredits(session, "114_1", "E1", settings)
        val recreated = FakeGradeRepository(cacheStore = GradeCacheStore(disk) {})
        recreated.activateSession(session)
        assertEquals(settings, recreated.loadSimulatorCredits(session, "114_1", "E1"))
    }

    @Test
    fun fakeRepositoryRejectsReadCompletingAfterSessionChanges() = runTest {
        val disk = dataStore(backgroundScope)
        val session = FakeData.session
        GradeCacheStore(disk) {}.saveSimulatorCredits(session.studentNo, "114_1", "E1", settings)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val delayedDisk = object : DataStore<Preferences> by disk {
            override val data: Flow<Preferences> = flow {
                readStarted.complete(Unit)
                releaseRead.await()
                emitAll(disk.data)
            }
        }
        val repository = FakeGradeRepository(cacheStore = GradeCacheStore(delayedDisk) {})
        repository.activateSession(session)
        val read = async { runCatching { repository.loadSimulatorCredits(session, "114_1", "E1") } }
        readStarted.await()
        repository.activateSession(session.copy(authorizationId = "new-authorization"))
        releaseRead.complete(Unit)
        assertTrue(read.await().exceptionOrNull() is CancellationException)
    }

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { temporaryFolder.root.resolve("settings.preferences_pb") },
    )
}
