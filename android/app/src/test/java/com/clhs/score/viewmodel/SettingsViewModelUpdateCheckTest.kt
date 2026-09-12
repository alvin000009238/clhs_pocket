package com.clhs.score.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.clhs.score.data.AppSettings
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.UpdateChecker
import com.clhs.score.data.UpdateResult
import com.clhs.score.data.UpdateApkDownloader
import com.clhs.score.notifications.NotificationTopicManager
import com.clhs.score.notifications.TopicSubscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelUpdateCheckTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun automaticCheckIsDueOnlyAfterTwelveHours() {
        val now = 100_000_000L

        assertTrue(isAutomaticUpdateCheckDue(0L, now))
        assertFalse(isAutomaticUpdateCheckDue(now - 12 * 60 * 60 * 1_000L + 1L, now))
        assertTrue(isAutomaticUpdateCheckDue(now - 12 * 60 * 60 * 1_000L, now))
        assertTrue(isAutomaticUpdateCheckDue(now + 1L, now))
    }

    @Test
    fun restoresKnownUpdateAsAvailableAfterViewModelRecreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = temporaryFolder.newFile("app-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        val update = UpdateResult.NewVersion(
            versionName = "999.0.0",
            htmlUrl = "https://github.com/alvin000009238/clhs_pocket/releases/tag/v999.0.0",
            apkAsset = null,
            releaseNotes = "修正錯誤",
        )
        SettingsRepository(dataStore).setLastKnownUpdate(update)
        assertEquals(update, SettingsRepository(dataStore).getLastKnownUpdate())

        val viewModel = SettingsViewModel(
            repository = SettingsRepository(dataStore),
            updateChecker = UpdateChecker(),
            updateApkDownloader = UpdateApkDownloader(temporaryFolder.root),
            notificationTopicManager = NotificationTopicManager(NoOpTopicSubscriptionClient),
            initialSettings = AppSettings(),
        )
        val restoredState = withTimeout(5.seconds) {
            viewModel.uiState.first { it.updateState is UpdateState.UpdateAvailable }
        }
        assertEquals(UpdateState.UpdateAvailable(update), restoredState.updateState)
    }

    private object NoOpTopicSubscriptionClient : TopicSubscriptionClient {
        override fun subscribe(topic: String) = Unit

        override fun unsubscribe(topic: String) = Unit
    }
}
