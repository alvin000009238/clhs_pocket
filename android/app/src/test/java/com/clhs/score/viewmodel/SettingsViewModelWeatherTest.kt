package com.clhs.score.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.clhs.score.data.AppSettings
import com.clhs.score.data.CwaApiKeyStore
import com.clhs.score.data.EncryptedPayload
import com.clhs.score.data.SessionCipher
import com.clhs.score.data.SettingsRepository
import com.clhs.score.data.UpdateApkDownloader
import com.clhs.score.data.UpdateChecker
import com.clhs.score.data.WeatherSource
import com.clhs.score.notifications.NotificationTopicManager
import com.clhs.score.notifications.TopicSubscriptionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelWeatherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cwaCannotBePersistedBeforeAnApiKeyExists() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settingsFile = temporaryFolder.newFile("app-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { settingsFile },
        )
        val repository = SettingsRepository(dataStore)
        val keyFile = temporaryFolder.newFile("weather_credentials.pb").also { it.delete() }
        val viewModel = SettingsViewModel(
            repository = repository,
            updateChecker = UpdateChecker(),
            updateApkDownloader = UpdateApkDownloader(temporaryFolder.root),
            notificationTopicManager = NotificationTopicManager(NoOpTopicSubscriptionClient),
            initialSettings = AppSettings(),
            cwaApiKeyStore = CwaApiKeyStore(keyFile, NoOpCipher),
        )

        viewModel.setWeatherSource(WeatherSource.CWA)
        advanceUntilIdle()

        val cwaSettings = withTimeoutOrNull(2.seconds) {
            repository.settings.first { it.weatherSource == WeatherSource.CWA }
        }
        assertEquals(null, cwaSettings)
    }

    private object NoOpTopicSubscriptionClient : TopicSubscriptionClient {
        override fun subscribe(topic: String) = Unit

        override fun unsubscribe(topic: String) = Unit
    }

    private object NoOpCipher : SessionCipher {
        override suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedPayload =
            error("unused")

        override suspend fun decrypt(payload: EncryptedPayload, associatedData: ByteArray): ByteArray =
            error("unused")
    }
}
