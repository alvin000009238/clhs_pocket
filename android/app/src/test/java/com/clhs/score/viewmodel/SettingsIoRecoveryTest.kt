package com.clhs.score.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.lifecycle.ViewModelStore
import com.clhs.score.data.AnnouncementReminderRepository
import com.clhs.score.data.AppSettings
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.UpdateApkDownloader
import com.clhs.score.data.UpdateChecker
import com.clhs.score.notifications.NotificationTopicManager
import com.clhs.score.notifications.TopicSubscriptionClient
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsIoRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val viewModels = ViewModelStore()

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test fun announcementFailurePreservesScheduleAndRetriesUntilCancelled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val failAfterEmission = CompletableDeferred<Unit>()
        var reads = 0
        var available = true
        val store = TestStore {
            reads++
            if (!available) throw IOException()
            emit(mutablePreferencesOf(AnnouncementReminderRepository.ENABLED to true))
            if (reads == 1) {
                failAfterEmission.await()
                throw IOException()
            }
            awaitCancellation()
        }
        val scheduled = mutableListOf<Int>()
        var cancellations = 0
        val vm = AnnouncementReminderViewModel(
            AnnouncementReminderRepository(store),
            NetworkSchoolAnnouncementsRepository(temporaryFolder.root),
            schedule = { scheduled += it },
            cancel = { cancellations++ },
        )
        viewModels.put("announcements", vm)
        runCurrent()
        assertEquals(listOf(60), scheduled)
        available = false
        failAfterEmission.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.reminder.enabled)
        assertTrue(vm.uiState.value.stateLoadFailed)
        assertFalse(vm.uiState.value.isStateAvailable)
        assertEquals(0, cancellations)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, reads)
        available = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(60), scheduled)
        assertTrue(vm.uiState.value.isStateAvailable)
        assertFalse(vm.uiState.value.stateLoadFailed)
        assertEquals(0, cancellations)
        viewModels.clear()
        val beforeCancel = reads
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(beforeCancel, reads)
    }

    @Test fun settingsFailureKeepsBiometricStateAndRequiresSuccessfulRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var available = false
        val failAfterEmission = CompletableDeferred<Unit>()
        val store = TestStore {
            if (!available) throw IOException()
            emit(mutablePreferencesOf(booleanPreferencesKey("biometric_enabled") to true))
            failAfterEmission.await()
            throw IOException()
        }
        val vm = SettingsViewModel(
            SettingsRepository(store), UpdateChecker(), UpdateApkDownloader(temporaryFolder.root),
            NotificationTopicManager(object : TopicSubscriptionClient {
                override fun subscribe(topic: String) = Unit
                override fun unsubscribe(topic: String) = Unit
            }),
            initialSettings = AppSettings(biometricEnabled = true),
        )
        viewModels.put("settings", vm)
        runCurrent()
        assertTrue(vm.settingsLoadFailed.value)
        assertFalse(vm.isReady.value)
        assertTrue(vm.settings.value.biometricEnabled)
        available = true
        vm.retrySettings()
        runCurrent()
        assertTrue(vm.isReady.value)
        assertFalse(vm.settingsLoadFailed.value)
        assertTrue(vm.settings.value.biometricEnabled)
        failAfterEmission.complete(Unit)
        runCurrent()
        assertFalse(vm.isReady.value)
        assertTrue(vm.settingsLoadFailed.value)
        assertTrue(vm.settings.value.biometricEnabled)
    }

    @Test fun startupReadAndMigrationFailuresNeverReturnDefaultSettings() = runTest {
        var available = false
        var writable = false
        var preferences: Preferences = mutablePreferencesOf(booleanPreferencesKey("biometric_enabled") to true)
        val store = object : DataStore<Preferences> {
            override val data = flow {
                if (!available) throw IOException()
                emit(preferences)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (!writable) throw IOException()
                preferences = transform(preferences)
                return preferences
            }
        }
        val repository = SettingsRepository(store)
        assertTrue(runCatching { repository.loadForStartup() }.exceptionOrNull() is IOException)
        available = true
        assertTrue(runCatching { repository.loadForStartup() }.exceptionOrNull() is IOException)
        writable = true
        assertFalse(repository.loadForStartup().hasCompletedOnboarding)
    }

    private class TestStore(block: suspend kotlinx.coroutines.flow.FlowCollector<Preferences>.() -> Unit) : DataStore<Preferences> {
        override val data = flow(block)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
    }
}
