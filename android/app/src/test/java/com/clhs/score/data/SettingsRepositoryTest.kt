package com.clhs.score.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
    fun overviewSectionOrderMovesNormalizesAndPersists() = runTest {
        val defaults = OverviewPreferences()
        assertEquals(defaults, defaults.moveSection("schedule", -1))
        assertEquals(defaults, defaults.moveSection("unknown", 1))
        val moved = defaults.moveSection("announcements", -5)
        assertEquals("announcements", moved.orderedSections().first())
        assertEquals(defaults, moved.moveSection("announcements", 5))
        val malformed = defaults.copy(sectionOrder = listOf("weather", "unknown", "weather"))
        assertEquals(listOf("weather", "schedule", "alerts", "countdown", "calendar", "announcements"), malformed.orderedSections())
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
