package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.defaultOverviewWidgets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OverviewLayoutRepositoryTest {
    @Test fun legacyOrderMarkerChangesOnlyAfterSuccessfulSaveAndSurvivesReopen() = runTest {
        val widgets = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(widgets, OverviewLayoutMetadata())
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val repository = OverviewLayoutRepository(dao, settings)
        assertTrue(repository.observeLayout().first().legacyOrder)
        assertEquals(1, dao.marker!!.formatVersion)
        dao.saveFailure = IOException("test write failure")
        assertTrue(runCatching { repository.save(widgets.reversed()) }.isFailure)
        assertEquals(1, dao.marker!!.formatVersion)
        assertEquals(widgets, repository.observe().first())
        dao.saveFailure = null
        repository.save(widgets.reversed())
        val reopened = OverviewLayoutRepository(dao, settings).observeLayout().first()
        assertFalse(reopened.legacyOrder)
        assertEquals(widgets.reversed(), reopened.widgets)
    }
    @Test fun listPreferencesSurviveSavingAndReopening() = runTest {
        val dao = OverviewTestLayoutDao()
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val repository = OverviewLayoutRepository(dao, settings)
        val widgets = repository.observe().first().map { widget ->
            when (widget.type) {
                "announcements" -> widget.withConfig(OverviewWidgetConfig.ListItems(5,
                    com.clhs.score.domain.overview.AnnouncementPriority.entries.reversed()))
                "calendar" -> widget.withConfig(OverviewWidgetConfig.ListItems(4, maxEventDurationDays = 7))
                else -> widget
            }
        }
        repository.save(widgets)
        assertEquals(widgets, OverviewLayoutRepository(dao, settings).observe().first())
    }

    @Test fun standaloneRowSurvivesSavingAndReopeningTheRepository() = runTest {
        val dao = OverviewTestLayoutDao()
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val repository = OverviewLayoutRepository(dao, settings)
        val widgets = repository.observe().first() + OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather()).copy(ownRow = true)
        repository.save(widgets)
        assertEquals(widgets, OverviewLayoutRepository(dao, settings).observe().first())
    }

    @Test
    fun importsLegacyOrderHiddenItemsCountsSourceAndSingleCountdownWithoutClearingKeys() = runTest {
        val legacy = mutablePreferencesOf(
            booleanPreferencesKey("has_completed_onboarding_v2") to true,
            stringPreferencesKey("overview_section_order") to "announcements,weather,countdown,schedule,calendar,alerts",
            stringSetPreferencesKey("overview_hidden_sections") to setOf("weather", "countdown"),
            intPreferencesKey("overview_calendar_limit") to 7,
            intPreferencesKey("overview_announcement_limit") to 5,
            stringPreferencesKey("overview_countdown_event") to "missing-legacy-event",
            stringPreferencesKey("weather_source") to WeatherSource.CWA.name,
        )
        val settings = OverviewTestSettingsStore(legacy)
        val dao = OverviewTestLayoutDao()
        val repository = OverviewLayoutRepository(dao, SettingsRepository(settings))

        val widgets = repository.observe().first()

        assertEquals(listOf("announcements", "weather", "countdown", "schedule", "calendar", "alerts"), widgets.map { it.type })
        assertFalse(widgets.single { it.type == "weather" }.enabled)
        assertFalse(widgets.single { it.type == "countdown" }.enabled)
        assertEquals(WeatherSource.CWA, (widgets.single { it.type == "weather" }.config() as OverviewWidgetConfig.Weather).source)
        assertEquals(OverviewWidgetSize(4, 1), widgets.single { it.type == "weather" }.size)
        assertEquals(7, (widgets.single { it.type == "calendar" }.config() as OverviewWidgetConfig.ListItems).limit)
        assertEquals(5, (widgets.single { it.type == "announcements" }.config() as OverviewWidgetConfig.ListItems).limit)
        assertEquals("missing-legacy-event", (widgets.single { it.type == "countdown" }.config() as OverviewWidgetConfig.SchoolEvent).eventId)
        assertTrue(dao.metadata()!!.importedLegacy)
        assertEquals(1, dao.initializations)
        assertEquals("missing-legacy-event", settings.data.first()[stringPreferencesKey("overview_countdown_event")])
        assertEquals(widgets, repository.observe().first())
        assertEquals(1, dao.initializations)
    }

    @Test
    fun newInstallationDefaultsAreCreatedOnceAndAnIntentionallyEmptyLayoutStaysEmpty() = runTest {
        val settings = SettingsRepository(OverviewTestSettingsStore())
        val dao = OverviewTestLayoutDao()
        val repository = OverviewLayoutRepository(dao, settings)
        val defaults = repository.observe().first()
        assertEquals(listOf("schedule", "alerts", "calendar", "announcements"), defaults.map { it.type })
        assertFalse(dao.metadata()!!.importedLegacy)
        assertTrue(defaults.all { it.enabled && it.config() != null })
        repository.save(emptyList())
        settings.setOverviewPreferences(OverviewPreferences(countdownEventIds = setOf("new-legacy-choice")))

        assertTrue(repository.observe().first().isEmpty())
        assertTrue(OverviewLayoutRepository(dao, settings).observe().first().isEmpty())
        assertEquals(1, dao.initializations)
    }

    @Test
    fun initializedEmptyDatabaseNeverImportsLegacySettings() = runTest {
        val settings = SettingsRepository(OverviewTestSettingsStore(mutablePreferencesOf(
            stringSetPreferencesKey("overview_countdown_events") to setOf("event-a", "event-b"),
        )))
        val dao = OverviewTestLayoutDao(metadata = OverviewLayoutMetadata(importedLegacy = true))

        assertTrue(OverviewLayoutRepository(dao, settings).observe().first().isEmpty())
        assertEquals(0, dao.initializations)
    }

    @Test
    fun savingPreservesUnknownTypeAndFutureConfigurationVerbatimIncludingDisabledEntries() = runTest {
        val unknown = OverviewWidgetInstance("future-id", "future-type", OverviewWidgetSize(7, 6),
            enabled = false, configVersion = 99, configuration = "{\"future\":\"opaque\",\"values\":[1,2]}")
        val futureWeather = OverviewWidgetInstance("future-weather", "weather", OverviewWidgetSize(2, 2),
            configVersion = 2, configuration = "{\"type\":\"weather\",\"newProvider\":true}")
        val defaults = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(defaults, OverviewLayoutMetadata())
        val repository = OverviewLayoutRepository(dao, SettingsRepository(OverviewTestSettingsStore()))

        repository.save(listOf(futureWeather, unknown) + defaults)

        val result = repository.observe().first()
        assertEquals(futureWeather, result[0])
        assertEquals(unknown, result[1])
        assertNull(result[0].config())
        assertNull(result[1].config())
        assertEquals((result.indices).toList(), dao.rows.value.map { it.rank })
    }

    @Test
    fun invalidDuplicateSingletonIdsAndKnownSizesAreRejectedBeforeStorage() = runTest {
        val widgets = defaultOverviewWidgets()
        val dao = OverviewTestLayoutDao(widgets, OverviewLayoutMetadata())
        val repository = OverviewLayoutRepository(dao, SettingsRepository(OverviewTestSettingsStore()))
        val schedule = widgets.single { it.type == "schedule" }

        assertTrue(runCatching { repository.save(widgets + schedule.copy(id = "duplicate-singleton")) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.save(widgets + widgets.first()) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repository.save(widgets.map { if (it.type == "schedule") it.copy(size = OverviewWidgetSize(2, 1)) else it }) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, dao.saveAttempts)
        assertEquals(widgets, repository.observe().first())
    }

    @Test
    fun failedReadNeverCreatesDefaultsAndFutureDatabaseVersionIsRejectedWithoutWrites() = runTest {
        val dao = OverviewTestLayoutDao()
        val settings = SettingsRepository(OverviewTestSettingsStore())
        dao.readFailure = IOException("test read failure")

        assertTrue(runCatching { OverviewLayoutRepository(dao, settings).observe().first() }.exceptionOrNull() is IOException)
        assertEquals(0, dao.initializations)
        assertTrue(dao.rows.value.isEmpty())
        dao.readFailure = null
        dao.marker = OverviewLayoutMetadata(formatVersion = 3)
        assertTrue(runCatching { OverviewLayoutRepository(dao, settings).observe().first() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, dao.initializations)
        assertEquals(0, dao.saveAttempts)
    }
}

/** JVM state fixture; Room transactions/schema are not exercised by this DAO. */
internal class OverviewTestLayoutDao(
    widgets: List<OverviewWidgetInstance> = emptyList(),
    metadata: OverviewLayoutMetadata? = null,
) : OverviewLayoutDao() {
    val rows = MutableStateFlow(widgets.mapIndexed { rank, it -> it.entity(rank) })
    var marker = metadata
    var initializations = 0
    var saveAttempts = 0
    var saveFailure: Exception? = null
    var readFailure: Exception? = null

    override suspend fun metadata(): OverviewLayoutMetadata? {
        readFailure?.let { throw it }
        return marker
    }
    override fun observe(): Flow<List<OverviewWidgetEntity>> = rows
    override fun observeVersioned(): Flow<List<OverviewVersionedWidget>> = rows.map { values ->
        values.map { OverviewVersionedWidget(it, requireNotNull(marker).formatVersion) }
    }
    override suspend fun insertMetadata(metadata: OverviewLayoutMetadata) { check(marker == null); marker = metadata }
    override suspend fun insertWidgets(widgets: List<OverviewWidgetEntity>) { rows.value += widgets }
    override suspend fun deleteWidgets() { rows.value = emptyList() }
    override suspend fun markLegacy() { marker = requireNotNull(marker).copy(formatVersion = 1) }
    override suspend fun markOrdered() { marker = requireNotNull(marker).copy(formatVersion = 2) }
    override suspend fun initialize(widgets: List<OverviewWidgetEntity>, importedLegacy: Boolean) {
        if (metadata() != null) return
        initializations++
        rows.value = widgets
        marker = OverviewLayoutMetadata(formatVersion = if (importedLegacy) 1 else 2, importedLegacy = importedLegacy)
    }
    override suspend fun replace(widgets: List<OverviewWidgetEntity>) {
        check(metadata()?.formatVersion in 1..2)
        saveAttempts++
        saveFailure?.let { throw it }
        markOrdered()
        rows.value = widgets
    }
}

internal class OverviewTestSettingsStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private fun OverviewWidgetInstance.entity(rank: Int) =
    OverviewWidgetEntity(id, type, rank, size.columns, size.rows, enabled, configVersion, configuration, ownRow)
