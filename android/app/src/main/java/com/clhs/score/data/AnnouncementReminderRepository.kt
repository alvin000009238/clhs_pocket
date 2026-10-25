package com.clhs.score.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

val ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES = listOf(30, 60, 180, 360, 720, 1_440)
const val DEFAULT_ANNOUNCEMENT_REMINDER_INTERVAL_MINUTES = 60
const val ANNOUNCEMENT_BADGE_MAX_COUNT = 99

data class AnnouncementReminderState(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_ANNOUNCEMENT_REMINDER_INTERVAL_MINUTES,
    val selectedUnitIds: Set<String> = setOf(ALL_UNITS_ID),
    val baselineInitialized: Boolean = false,
    val knownIds: Set<String> = emptySet(),
    val unreadIds: Set<String> = emptySet(),
    val lastCheckedAtMillis: Long = 0L,
)

internal data class AnnouncementCheckOutcome(
    val state: AnnouncementReminderState,
    val newAnnouncements: List<SchoolAnnouncement>,
)

internal const val ANNOUNCEMENT_REMINDER_MAX_PAGES = 20
internal const val ANNOUNCEMENT_REMINDER_BASELINE_MAX_PAGES = 3

internal suspend fun loadAnnouncementReminderSnapshot(
    selectedUnitIds: Set<String>,
    knownIds: Set<String>,
    loadPage: suspend (pageIndex: Int, unitId: String) -> SchoolAnnouncementPage,
): List<SchoolAnnouncement> {
    val units = if (ALL_UNITS_ID in selectedUnitIds) {
        listOf(ALL_UNITS_ID)
    } else {
        selectedUnitIds.sorted()
    }

    return units.flatMap { unitId ->
        buildList {
            val maxPages = if (knownIds.isEmpty()) {
                ANNOUNCEMENT_REMINDER_BASELINE_MAX_PAGES
            } else {
                ANNOUNCEMENT_REMINDER_MAX_PAGES
            }
            for (pageIndex in 0 until maxPages) {
                val page = loadPage(pageIndex, unitId)
                addAll(page.announcements)
                if (
                    page.announcements.isEmpty() ||
                    page.announcements.any { it.id in knownIds } ||
                    pageIndex + 1 >= page.totalPages
                ) {
                    break
                }
            }
        }
    }.distinctBy(SchoolAnnouncement::id)
}

internal fun applyAnnouncementCheck(
    state: AnnouncementReminderState,
    announcements: List<SchoolAnnouncement>,
    checkedAtMillis: Long,
): AnnouncementCheckOutcome {
    val distinct = announcements.distinctBy(SchoolAnnouncement::id)
    val currentIds = distinct.map(SchoolAnnouncement::id)
    if (!state.baselineInitialized) {
        return AnnouncementCheckOutcome(
            state = state.copy(
                baselineInitialized = true,
                knownIds = boundedIds(currentIds, emptySet(), KNOWN_ID_LIMIT),
                unreadIds = emptySet(),
                lastCheckedAtMillis = checkedAtMillis,
            ),
            newAnnouncements = emptyList(),
        )
    }

    val newAnnouncements = distinct.filterNot { it.id in state.knownIds }
    return AnnouncementCheckOutcome(
        state = state.copy(
            knownIds = boundedIds(currentIds, state.knownIds, KNOWN_ID_LIMIT),
            unreadIds = boundedIds(
                newAnnouncements.map(SchoolAnnouncement::id),
                state.unreadIds,
                UNREAD_ID_LIMIT,
            ),
            lastCheckedAtMillis = checkedAtMillis,
        ),
        newAnnouncements = newAnnouncements,
    )
}

internal fun announcementBadgeText(unreadCount: Int): String =
    if (unreadCount > ANNOUNCEMENT_BADGE_MAX_COUNT) "$ANNOUNCEMENT_BADGE_MAX_COUNT+" else unreadCount.toString()

private fun boundedIds(priority: List<String>, existing: Set<String>, limit: Int): Set<String> =
    buildList {
        addAll(priority)
        addAll(existing)
    }.distinct().take(limit).toSet()

private val Context.announcementReminderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "announcement_reminder",
)

class AnnouncementReminderRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val initializeReading: (suspend () -> Unit)? = null,
) {
    constructor(context: Context, migrateReading: Boolean = true) : this(
        context.applicationContext.announcementReminderDataStore,
        if (migrateReading) ({ AnnouncementReadRepository.get(context).initialize(); Unit }) else null,
    )

    val state: Flow<AnnouncementReminderState> = dataStore.data
        .map(::stateFromPreferences)

    suspend fun loadState(): AnnouncementReminderState = state.first()

    internal suspend fun restorePortable(value: PortableAnnouncementPreferences) {
        value.validate()
        initializeReading?.invoke()
        dataStore.edit { prefs ->
            val current = stateFromPreferences(prefs)
            val next = if (current.selectedUnitIds != value.selectedUnitIds || current.enabled != value.enabled)
                withAnnouncementUnits(current, value.selectedUnitIds) else current
            writeState(prefs, next.copy(enabled = value.enabled, intervalMinutes = value.intervalMinutes))
        }
    }

    internal suspend fun restoreSnapshot(value: AnnouncementReminderState) {
        dataStore.edit { writeState(it, value) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        initializeReading?.invoke()
        dataStore.edit { prefs ->
            prefs[ENABLED] = enabled
            if (!enabled) clearReminderProgress(prefs)
        }
    }

    suspend fun enableWithBaseline(
        expectedUnitIds: Set<String>,
        announcements: List<SchoolAnnouncement>,
        checkedAtMillis: Long,
    ) {
        initializeReading?.invoke()
        dataStore.edit { prefs ->
            val current = stateFromPreferences(prefs)
            check(current.selectedUnitIds == expectedUnitIds) { "Announcement units changed during baseline load" }
            if (current.enabled) return@edit
            val outcome = applyAnnouncementCheck(
                current.copy(enabled = true),
                announcements,
                checkedAtMillis,
            )
            writeState(prefs, outcome.state)
        }
    }

    suspend fun setIntervalMinutes(intervalMinutes: Int) {
        initializeReading?.invoke()
        dataStore.edit { prefs -> writeState(prefs, withAnnouncementInterval(stateFromPreferences(prefs), intervalMinutes)) }
    }

    suspend fun applySelectedUnitIds(
        unitIds: Set<String>,
        announcements: List<SchoolAnnouncement>,
        checkedAtMillis: Long,
    ) {
        initializeReading?.invoke()
        dataStore.edit { prefs ->
            val current = stateFromPreferences(prefs)
            val changed = withAnnouncementUnits(current, unitIds)
            val applied = if (current.enabled) {
                applyAnnouncementCheck(changed, announcements, checkedAtMillis).state
            } else {
                changed
            }
            writeState(prefs, applied)
        }
    }

    suspend fun recordCheck(
        expectedUnitIds: Set<String>,
        announcements: List<SchoolAnnouncement>,
        checkedAtMillis: Long,
    ): List<SchoolAnnouncement> {
        initializeReading?.invoke()
        var newAnnouncements = emptyList<SchoolAnnouncement>()
        dataStore.edit { prefs ->
            val current = stateFromPreferences(prefs)
            if (!current.enabled || current.selectedUnitIds != expectedUnitIds) return@edit
            val outcome = applyAnnouncementCheck(current, announcements, checkedAtMillis)
            writeState(prefs, outcome.state)
            newAnnouncements = outcome.newAnnouncements
        }
        return newAnnouncements
    }

    internal suspend fun prepareReadMigration(today: LocalDate): Pair<LocalDate, Set<String>?> {
        var cutoff = today
        var legacyUnread: Set<String>? = null
        dataStore.edit { prefs ->
            cutoff = prefs[READ_CUTOFF_DAY]?.let(LocalDate::ofEpochDay) ?: today.also {
                prefs[READ_CUTOFF_DAY] = it.toEpochDay()
            }
            if (prefs[READ_MIGRATION_COMPLETE] != true) legacyUnread = prefs[UNREAD_IDS].orEmpty()
        }
        return cutoff to legacyUnread
    }

    internal suspend fun finishReadMigration() {
        dataStore.edit { prefs ->
            prefs[READ_MIGRATION_COMPLETE] = true
            prefs.remove(UNREAD_IDS)
        }
    }

    companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val INTERVAL_MINUTES = intPreferencesKey("interval_minutes")
        val SELECTED_UNIT_IDS = stringSetPreferencesKey("selected_unit_ids")
        // 舊版 unit 參數未生效，重新建立基準，避免把歷史公告當成新公告。
        val BASELINE_INITIALIZED = booleanPreferencesKey("baseline_initialized_v2")
        val KNOWN_IDS = stringSetPreferencesKey("known_ids_v2")
        val UNREAD_IDS = stringSetPreferencesKey("unread_ids_v2")
        val LAST_CHECKED_AT = longPreferencesKey("last_checked_at_v2")
        val READ_CUTOFF_DAY = longPreferencesKey("read_cutoff_day")
        val READ_MIGRATION_COMPLETE = booleanPreferencesKey("read_migration_complete")
    }
}

internal fun withAnnouncementInterval(
    state: AnnouncementReminderState,
    intervalMinutes: Int,
): AnnouncementReminderState {
    require(intervalMinutes in ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES)
    return state.copy(intervalMinutes = intervalMinutes)
}

internal fun withAnnouncementUnits(
    state: AnnouncementReminderState,
    unitIds: Set<String>,
): AnnouncementReminderState = state.copy(
    selectedUnitIds = normalizeUnitIds(unitIds),
    baselineInitialized = false,
    knownIds = emptySet(),
    unreadIds = emptySet(),
    lastCheckedAtMillis = 0L,
)

private fun normalizeUnitIds(unitIds: Set<String>): Set<String> {
    val valid = unitIds.filterTo(linkedSetOf(), ANNOUNCEMENT_UNIT_ID_REGEX::matches)
    require(valid.isNotEmpty()) { "At least one announcement unit is required" }
    return if (ALL_UNITS_ID in valid) setOf(ALL_UNITS_ID) else valid
}

internal fun stateFromPreferences(prefs: Preferences): AnnouncementReminderState = AnnouncementReminderState(
    enabled = prefs[AnnouncementReminderRepository.ENABLED] ?: false,
    intervalMinutes = prefs[AnnouncementReminderRepository.INTERVAL_MINUTES]
        ?.takeIf { it in ANNOUNCEMENT_REMINDER_INTERVALS_MINUTES }
        ?: DEFAULT_ANNOUNCEMENT_REMINDER_INTERVAL_MINUTES,
    selectedUnitIds = prefs[AnnouncementReminderRepository.SELECTED_UNIT_IDS]
        ?.let { runCatching { normalizeUnitIds(it) }.getOrNull() }
        ?: setOf(ALL_UNITS_ID),
    baselineInitialized = prefs[AnnouncementReminderRepository.BASELINE_INITIALIZED] ?: false,
    knownIds = prefs[AnnouncementReminderRepository.KNOWN_IDS].orEmpty(),
    unreadIds = prefs[AnnouncementReminderRepository.UNREAD_IDS].orEmpty(),
    lastCheckedAtMillis = prefs[AnnouncementReminderRepository.LAST_CHECKED_AT] ?: 0L,
)

private fun writeState(prefs: androidx.datastore.preferences.core.MutablePreferences, state: AnnouncementReminderState) {
    prefs[AnnouncementReminderRepository.ENABLED] = state.enabled
    prefs[AnnouncementReminderRepository.INTERVAL_MINUTES] = state.intervalMinutes
    prefs[AnnouncementReminderRepository.SELECTED_UNIT_IDS] = state.selectedUnitIds
    prefs[AnnouncementReminderRepository.BASELINE_INITIALIZED] = state.baselineInitialized
    prefs[AnnouncementReminderRepository.KNOWN_IDS] = state.knownIds
    if (prefs[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE] != true) {
        prefs[AnnouncementReminderRepository.UNREAD_IDS] = state.unreadIds
    }
    prefs[AnnouncementReminderRepository.LAST_CHECKED_AT] = state.lastCheckedAtMillis
}

private fun clearReminderProgress(prefs: androidx.datastore.preferences.core.MutablePreferences) {
    prefs[AnnouncementReminderRepository.BASELINE_INITIALIZED] = false
    prefs[AnnouncementReminderRepository.KNOWN_IDS] = emptySet()
    if (prefs[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE] == true) {
        prefs.remove(AnnouncementReminderRepository.UNREAD_IDS)
    } else {
        prefs[AnnouncementReminderRepository.UNREAD_IDS] = emptySet()
    }
    prefs[AnnouncementReminderRepository.LAST_CHECKED_AT] = 0L
}

private const val KNOWN_ID_LIMIT = 500
private const val UNREAD_ID_LIMIT = 100
