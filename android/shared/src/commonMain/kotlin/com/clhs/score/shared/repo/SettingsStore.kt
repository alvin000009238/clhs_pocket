package com.clhs.score.shared.repo

import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.ANNOUNCEMENT_UNIT_ID_REGEX
import com.clhs.score.data.BackgroundPause
import com.clhs.score.data.ScheduleDisplayMode
import com.clhs.score.data.ThemeMode
import com.clhs.score.data.WeatherSource
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.defaultOverviewWidgets
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ANNOUNCEMENT_CHECK_INTERVALS_MINUTES = listOf(30, 60, 180, 360, 720, 1_440)

/**
 * Device-wide preferences persisted on iOS. Fields that only drive Android behaviour
 * ([dynamicColor], [amoledBlack], [preferExactCourseAlarms], [developerEnabled]) are stored so a
 * settings backup made on Android survives an import and re-export on iOS unchanged.
 */
@Serializable
data class PocketSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val amoledBlack: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val preferExactCourseAlarms: Boolean = false,
    val developerEnabled: Boolean = false,
    /** App lock with Face ID / Touch ID / device passcode. */
    val appLockEnabled: Boolean = false,
    val weatherSource: WeatherSource = WeatherSource.OPEN_METEO,
    val startOnSchedule: Boolean = false,
    val scheduleDisplayMode: ScheduleDisplayMode = ScheduleDisplayMode.WEEK,
    val profileAvatarShapeIndex: Int = 0,
    val hasCompletedOnboarding: Boolean = false,
    val backgroundPauseEnabled: Boolean = false,
    val backgroundPauseStartMinute: Int = 23 * 60,
    val backgroundPauseEndMinute: Int = 7 * 60,
    val overview: List<OverviewWidgetInstance> = defaultOverviewWidgets(),
    val overviewLegacyOrder: Boolean = false,
    val announcementReminderEnabled: Boolean = false,
    val announcementIntervalMinutes: Int = 60,
    val announcementUnitIds: Set<String> = setOf(ALL_UNITS_ID),
    val remindBeforeClass: Boolean = false,
) {
    val backgroundPause: BackgroundPause
        get() = BackgroundPause(backgroundPauseEnabled, backgroundPauseStartMinute, backgroundPauseEndMinute)

    /** Throws [IllegalArgumentException] for values no screen can produce. */
    fun validate() {
        require(profileAvatarShapeIndex in 0..3) { "頭像形狀超出範圍" }
        backgroundPause
        require(announcementIntervalMinutes in ANNOUNCEMENT_CHECK_INTERVALS_MINUTES) { "公告檢查間隔不支援" }
        require(normalizeAnnouncementUnitIds(announcementUnitIds) == announcementUnitIds) { "公告單位格式不符" }
    }
}

/** Same rule as Android: only valid ids, and "all" replaces any other selection. */
internal fun normalizeAnnouncementUnitIds(unitIds: Set<String>): Set<String> {
    val valid = unitIds.filterTo(LinkedHashSet()) { ANNOUNCEMENT_UNIT_ID_REGEX.matches(it) }
    return when {
        valid.isEmpty() || ALL_UNITS_ID in valid -> setOf(ALL_UNITS_ID)
        else -> valid
    }
}

/** Preferences in one protected file. Unknown or damaged content falls back to defaults on read. */
class SettingsStore(private val files: FileStore, private val now: () -> Long) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): PocketSettings {
        val stored = files.readOrNull(PATH) ?: return PocketSettings()
        return runCatching { json.decodeFromString(PocketSettings.serializer(), stored.text).also(PocketSettings::validate) }
            .getOrDefault(PocketSettings())
    }

    /** Throws [StorageException] when the file could not be written; nothing is kept in memory only. */
    fun save(settings: PocketSettings) {
        settings.validate()
        files.write(PATH, json.encodeToString(PocketSettings.serializer(), settings), now()).orThrow("Settings write")
    }

    fun update(transform: (PocketSettings) -> PocketSettings): PocketSettings = transform(load()).also(::save)

    fun clear() {
        files.delete(PATH).orThrow("Settings delete")
    }

    private companion object {
        const val PATH = "${FileStore.SETTINGS}/app-settings.json"
    }
}
