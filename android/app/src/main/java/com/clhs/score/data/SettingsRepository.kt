package com.clhs.score.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            overview = OverviewPreferences(
                hiddenSections = prefs[KEY_OVERVIEW_HIDDEN].orEmpty(),
                calendarLimit = (prefs[KEY_OVERVIEW_CALENDAR_LIMIT] ?: 3).coerceIn(1, 20),
                announcementLimit = (prefs[KEY_OVERVIEW_ANNOUNCEMENT_LIMIT] ?: 2).coerceIn(1, 20),
                countdownEventIds = prefs[KEY_OVERVIEW_COUNTDOWNS]
                    ?: setOfNotNull(prefs[KEY_OVERVIEW_COUNTDOWN]),
                sectionOrder = prefs[KEY_OVERVIEW_ORDER]?.split(',') ?: OverviewPreferences.DEFAULT_SECTION_ORDER,
            ),
            themeMode = prefs[KEY_THEME_MODE]?.let { name ->
                runCatching { ThemeMode.valueOf(name) }.getOrNull()
            } ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            amoledBlack = prefs[KEY_AMOLED_BLACK] ?: false,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: false,
            developerEnabled = prefs[KEY_DEVELOPER_ENABLED] ?: false,
            demoMode = prefs[KEY_DEMO_MODE] ?: false,
            biometricEnabled = prefs[KEY_BIOMETRIC_ENABLED] ?: false,
            weatherSource = prefs[KEY_WEATHER_SOURCE]?.let { name ->
                runCatching { WeatherSource.valueOf(name) }.getOrNull()
            } ?: WeatherSource.OPEN_METEO,
            weatherCredentialRevision = prefs[KEY_WEATHER_CREDENTIAL_REVISION] ?: 0L,
            hasCompletedOnboarding = prefs[KEY_HAS_COMPLETED_ONBOARDING] ?: false,
        )
    }

    val weatherConfiguration: Flow<WeatherConfiguration> = settings.map {
        WeatherConfiguration(it.weatherSource, it.weatherCredentialRevision)
    }

    suspend fun setOverviewPreferences(value: OverviewPreferences) {
        dataStore.edit { prefs ->
            prefs[KEY_OVERVIEW_HIDDEN] = value.hiddenSections
            prefs[KEY_OVERVIEW_CALENDAR_LIMIT] = value.calendarLimit.coerceIn(1, 20)
            prefs[KEY_OVERVIEW_ANNOUNCEMENT_LIMIT] = value.announcementLimit.coerceIn(1, 20)
            prefs[KEY_OVERVIEW_COUNTDOWNS] = value.countdownEventIds
            prefs[KEY_OVERVIEW_ORDER] = value.orderedSections().joinToString(",")
            prefs.remove(KEY_OVERVIEW_COUNTDOWN)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAmoledBlack(enabled: Boolean) {
        dataStore.edit { it[KEY_AMOLED_BLACK] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDeveloperEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DEVELOPER_ENABLED] = enabled }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        dataStore.edit { it[KEY_DEMO_MODE] = enabled }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun setHasCompletedOnboarding(completed: Boolean) {
        dataStore.edit { it[KEY_HAS_COMPLETED_ONBOARDING] = completed }
    }

    suspend fun loadForStartup(): AppSettings {
        val preferences = dataStore.data.first()
        if (preferences[KEY_HAS_COMPLETED_ONBOARDING] == null) {
            dataStore.edit { current ->
                if (current[KEY_HAS_COMPLETED_ONBOARDING] == null) {
                    current.clear()
                    current[KEY_HAS_COMPLETED_ONBOARDING] = false
                }
            }
        }
        return settings.first()
    }

    suspend fun setWeatherSource(source: WeatherSource) {
        dataStore.edit { it[KEY_WEATHER_SOURCE] = source.name }
    }

    suspend fun setWeatherSourceAfterCwaCredentialChange(source: WeatherSource) {
        dataStore.edit { prefs ->
            prefs[KEY_WEATHER_SOURCE] = source.name
            prefs[KEY_WEATHER_CREDENTIAL_REVISION] =
                (prefs[KEY_WEATHER_CREDENTIAL_REVISION] ?: 0L) + 1L
        }
    }

    suspend fun getLastUpdateCheckTime(): Long =
        dataStore.data.first()[KEY_LAST_UPDATE_CHECK_TIME] ?: 0L

    suspend fun setLastUpdateCheckTime(timeMillis: Long) {
        dataStore.edit { it[KEY_LAST_UPDATE_CHECK_TIME] = timeMillis }
    }

    suspend fun getLastKnownUpdate(): UpdateResult.NewVersion? {
        val prefs = dataStore.data.first()
        val versionName = prefs[KEY_LAST_KNOWN_UPDATE_VERSION] ?: return null
        val htmlUrl = prefs[KEY_LAST_KNOWN_UPDATE_HTML_URL].orEmpty()
        val apkDownloadUrl = prefs[KEY_LAST_KNOWN_UPDATE_APK_URL]
        val apkSha256 = prefs[KEY_LAST_KNOWN_UPDATE_APK_SHA256]
        val apkAsset = if (apkDownloadUrl != null && apkSha256 != null) {
            ApkAsset(apkDownloadUrl, apkSha256)
        } else {
            null
        }
        if (htmlUrl.isBlank() && apkAsset == null) return null
        return UpdateResult.NewVersion(
            versionName = versionName,
            htmlUrl = htmlUrl,
            apkAsset = apkAsset,
            releaseNotes = prefs[KEY_LAST_KNOWN_UPDATE_NOTES].orEmpty(),
        )
    }

    suspend fun setLastKnownUpdate(update: UpdateResult.NewVersion) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_KNOWN_UPDATE_VERSION] = update.versionName
            prefs[KEY_LAST_KNOWN_UPDATE_HTML_URL] = update.htmlUrl
            prefs[KEY_LAST_KNOWN_UPDATE_NOTES] = update.releaseNotes
            if (update.apkAsset != null) {
                prefs[KEY_LAST_KNOWN_UPDATE_APK_URL] = update.apkAsset.downloadUrl
                prefs[KEY_LAST_KNOWN_UPDATE_APK_SHA256] = update.apkAsset.sha256
            } else {
                prefs.remove(KEY_LAST_KNOWN_UPDATE_APK_URL)
                prefs.remove(KEY_LAST_KNOWN_UPDATE_APK_SHA256)
            }
        }
    }

    suspend fun clearLastKnownUpdate() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_KNOWN_UPDATE_VERSION)
            prefs.remove(KEY_LAST_KNOWN_UPDATE_HTML_URL)
            prefs.remove(KEY_LAST_KNOWN_UPDATE_APK_URL)
            prefs.remove(KEY_LAST_KNOWN_UPDATE_APK_SHA256)
            prefs.remove(KEY_LAST_KNOWN_UPDATE_NOTES)
        }
    }

    private companion object {
        val KEY_OVERVIEW_HIDDEN = stringSetPreferencesKey("overview_hidden_sections")
        val KEY_OVERVIEW_ORDER = stringPreferencesKey("overview_section_order")
        val KEY_OVERVIEW_CALENDAR_LIMIT = intPreferencesKey("overview_calendar_limit")
        val KEY_OVERVIEW_ANNOUNCEMENT_LIMIT = intPreferencesKey("overview_announcement_limit")
        val KEY_OVERVIEW_COUNTDOWNS = stringSetPreferencesKey("overview_countdown_events")
        val KEY_OVERVIEW_COUNTDOWN = stringPreferencesKey("overview_countdown_event")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_DEVELOPER_ENABLED = booleanPreferencesKey("developer_enabled")
        val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
        val KEY_BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val KEY_HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding_v2")
        val KEY_WEATHER_SOURCE = stringPreferencesKey("weather_source")
        val KEY_WEATHER_CREDENTIAL_REVISION = longPreferencesKey("weather_credential_revision")
        val KEY_LAST_UPDATE_CHECK_TIME = longPreferencesKey("lastUpdateCheckTime")
        val KEY_LAST_KNOWN_UPDATE_VERSION = stringPreferencesKey("lastKnownUpdateVersion")
        val KEY_LAST_KNOWN_UPDATE_HTML_URL = stringPreferencesKey("lastKnownUpdateHtmlUrl")
        val KEY_LAST_KNOWN_UPDATE_APK_URL = stringPreferencesKey("lastKnownUpdateApkUrl")
        val KEY_LAST_KNOWN_UPDATE_APK_SHA256 = stringPreferencesKey("lastKnownUpdateApkSha256")
        val KEY_LAST_KNOWN_UPDATE_NOTES = stringPreferencesKey("lastKnownUpdateReleaseNotes")
    }
}
