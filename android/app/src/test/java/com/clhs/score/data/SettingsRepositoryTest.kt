package com.clhs.score.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @Test
    fun profileAvatarChoiceSurvivesDataStoreRecreationAndWraps() = runTest {
        val file = temporaryFolder.newFile("profile-avatar.preferences_pb")
        val storeJob = Job(backgroundScope.coroutineContext[Job])
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + storeJob),
            produceFile = { file },
        )
        try {
            val repository = SettingsRepository(dataStore)
            repository.setHasCompletedOnboarding(true)
            assertFalse(repository.settings.first().hasSeenYouBikeWidget)
            repository.markYouBikeWidgetSeen()
            repository.markYouBikeWidgetSeen()
            assertEquals(0, repository.settings.first().profileAvatarShapeIndex)
            for (expected in listOf(1, 2, 3, 0, 1)) {
                repository.cycleProfileAvatarShape()
                assertEquals(expected, repository.settings.first().profileAvatarShapeIndex)
            }
        } finally {
            storeJob.cancelAndJoin()
        }
        val restoredStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val restoredRepository = SettingsRepository(restoredStore)
        assertEquals(1, restoredRepository.loadForStartup().profileAvatarShapeIndex)
        assertTrue(restoredRepository.settings.first().hasSeenYouBikeWidget)
        restoredRepository.cycleProfileAvatarShape()
        assertEquals(2, restoredRepository.settings.first().profileAvatarShapeIndex)
    }

    @Test
    fun backgroundPauseDefaultsOffAndPersistsTogether() = runTest {
        val file = temporaryFolder.newFile("pause.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val repository = SettingsRepository(dataStore)
        assertEquals(BackgroundPause(), repository.settings.first().backgroundPause)
        val pause = BackgroundPause(true, 22 * 60, 6 * 60 + 30)
        repository.setBackgroundPause(pause)
        assertEquals(pause, SettingsRepository(dataStore).settings.first().backgroundPause)
        repository.setBackgroundPause(pause.copy(enabled = false))
        assertEquals(pause.copy(enabled = false), repository.settings.first().backgroundPause)
    }

    @Test
    fun exactAlarmOptInDefaultsOffAndPersistsExplicitChoice() = runTest {
        val file = temporaryFolder.newFile("exact-alarm-preference.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val repository = SettingsRepository(dataStore)

        assertFalse(repository.settings.first().preferExactCourseAlarms)
        repository.setPreferExactCourseAlarms(true)
        assertTrue(SettingsRepository(dataStore).settings.first().preferExactCourseAlarms)
        repository.setPreferExactCourseAlarms(false)
        assertFalse(repository.settings.first().preferExactCourseAlarms)
    }

    @Test
    fun overviewSectionOrderMovesNormalizesAndPersists() = runTest {
        val defaults = OverviewPreferences()
        assertEquals(defaults, defaults.moveSection("schedule", -1))
        assertEquals(defaults, defaults.moveSection("unknown", 1))
        val moved = defaults.moveSection("announcements", -defaults.orderedSections().lastIndex)
        assertEquals("announcements", moved.orderedSections().first())
        assertEquals(defaults, moved.moveSection("announcements", defaults.orderedSections().indexOf("announcements")))
        val malformed = defaults.copy(sectionOrder = listOf("weather", "unknown", "weather"))
        assertEquals(listOf("weather", "schedule", "alerts", "countdown", "calendar", "announcements", "youbike"), malformed.orderedSections())
        val legacyOrder = listOf("announcements", "calendar", "schedule", "weather", "alerts", "countdown")
        assertEquals(legacyOrder + "youbike", defaults.copy(sectionOrder = legacyOrder).orderedSections())
        val file = temporaryFolder.newFile("overview-order.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val repository = SettingsRepository(dataStore)
        assertEquals(defaults.sectionOrder, repository.settings.first().overview.sectionOrder)
        repository.setOverviewPreferences(moved)
        assertEquals(moved, SettingsRepository(dataStore).settings.first().overview)
        repository.setOverviewPreferences(defaults)
        assertEquals(defaults, repository.settings.first().overview)
        repository.setOverviewPreferences(malformed)
        assertEquals(malformed.orderedSections(), repository.settings.first().overview.sectionOrder)
        val hiddenYouBike = defaults.copy(hiddenSections = setOf("youbike"))
        repository.setOverviewPreferences(hiddenYouBike)
        assertEquals(hiddenYouBike, repository.settings.first().overview)
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun singleCountdownSelectionMigratesAndDoesNotReturnAfterClearing() = runTest {
        val file = temporaryFolder.newFile("countdown-migration.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        dataStore.edit { it[stringPreferencesKey("overview_countdown_event")] = "legacy-event" }
        val repository = SettingsRepository(dataStore)
        assertEquals(setOf("legacy-event"), repository.settings.first().overview.countdownEventIds)
        repository.setOverviewPreferences(OverviewPreferences(countdownEventIds = setOf("legacy-event", "new-event")))
        assertNull(dataStore.data.first()[stringPreferencesKey("overview_countdown_event")])
        repository.setOverviewPreferences(OverviewPreferences())
        assertTrue(SettingsRepository(dataStore).settings.first().overview.countdownEventIds.isEmpty())
    }

    @Test
    fun overviewPreferencesPersistClampCountsAndReset() = runTest {
        val file = temporaryFolder.newFile("overview.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val repository = SettingsRepository(dataStore)
        repository.loadForStartup()
        val preferences = OverviewPreferences(setOf("weather", "calendar"), 8, 6, setOf("public-event", "another-event"))
        repository.setOverviewPreferences(preferences)
        assertEquals(preferences, SettingsRepository(dataStore).settings.first().overview)
        repository.setOverviewPreferences(preferences.copy(calendarLimit = -1, announcementLimit = 999))
        val clamped = repository.settings.first().overview
        assertEquals(1, clamped.calendarLimit)
        assertEquals(20, clamped.announcementLimit)
        repository.setOverviewPreferences(OverviewPreferences())
        assertEquals(OverviewPreferences(), repository.settings.first().overview)
    }

    @Test
    fun emptySettingsStartOnboardingAndPersistTheMarker() = runTest {
        val file = temporaryFolder.newFile("new-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )

        val settings = SettingsRepository(dataStore).loadForStartup()

        assertFalse(settings.hasCompletedOnboarding)
        assertEquals(
            false,
            dataStore.data.first()[booleanPreferencesKey("has_completed_onboarding_v2")],
        )
    }

    @Test
    fun upgradeResetsAllSettingsOnceAndRequiresWelcome() = runTest {
        val file = temporaryFolder.newFile("legacy-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        dataStore.edit {
            it[stringPreferencesKey("theme_mode")] = ThemeMode.DARK.name
            it[booleanPreferencesKey("has_completed_onboarding")] = true
            it[booleanPreferencesKey("biometric_enabled")] = true
        }

        val settings = SettingsRepository(dataStore).loadForStartup()

        assertFalse(settings.hasCompletedOnboarding)
        assertFalse(settings.biometricEnabled)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(1, dataStore.data.first().asMap().size)
        assertEquals(
            false,
            dataStore.data.first()[booleanPreferencesKey("has_completed_onboarding_v2")],
        )
        val repository = SettingsRepository(dataStore)
        repository.setHasCompletedOnboarding(true)
        repository.setBiometricEnabled(true)
        repository.setThemeMode(ThemeMode.DARK)
        val restored = SettingsRepository(dataStore).loadForStartup()
        assertTrue(restored.hasCompletedOnboarding)
        assertTrue(restored.biometricEnabled)
        assertEquals(ThemeMode.DARK, restored.themeMode)
    }

    @Test
    fun explicitOnboardingMarkerWinsOverLegacySettings() = runTest {
        val file = temporaryFolder.newFile("explicit-marker.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        dataStore.edit {
            it[booleanPreferencesKey("has_completed_onboarding_v2")] = false
            it[stringPreferencesKey("theme_mode")] = ThemeMode.DARK.name
        }

        val settings = SettingsRepository(dataStore).loadForStartup()

        assertFalse(settings.hasCompletedOnboarding)
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun knownUpdateSurvivesRepositoryRecreationAndCanBeCleared() = runTest {
        val file = temporaryFolder.newFile("app-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        val update = UpdateResult.NewVersion(
            versionName = "2.0.0",
            htmlUrl = "https://github.com/alvin000009238/clhs_pocket/releases/tag/v2.0.0",
            apkAsset = ApkAsset(
                downloadUrl = "https://github.com/alvin000009238/clhs_pocket/releases/download/v2.0.0/app.apk",
                sha256 = "a".repeat(64),
            ),
            releaseNotes = "修正錯誤",
        )

        SettingsRepository(dataStore).setLastKnownUpdate(update)

        assertEquals(update, SettingsRepository(dataStore).getLastKnownUpdate())

        SettingsRepository(dataStore).clearLastKnownUpdate()
        assertNull(SettingsRepository(dataStore).getLastKnownUpdate())
        assertEquals(emptyMap<Any, Any>(), dataStore.data.first().asMap())
    }

    @Test
    fun savingCwaCredentialIncrementsWeatherConfigurationRevision() = runTest {
        val file = temporaryFolder.newFile("weather-settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        val repository = SettingsRepository(dataStore)

        repository.setWeatherSource(WeatherSource.CWA)
        val before = repository.settings.first()
        repository.setWeatherSourceAfterCwaCredentialChange(WeatherSource.CWA)
        val after = repository.settings.first()

        assertEquals(WeatherSource.CWA, after.weatherSource)
        assertEquals(before.weatherCredentialRevision + 1, after.weatherCredentialRevision)
    }
}
