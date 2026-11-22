package com.clhs.score.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnouncementReminderRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun enablingAfterUnitChangeRejectsOldBaselineAndCanRetryWithCurrentUnits() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.root.resolve("reminder.preferences_pb") },
        )
        val repository = AnnouncementReminderRepository(store)
        val requestedUnits = repository.loadState().selectedUnitIds
        val staleAnnouncements = snapshot(announcement("old-unit"))

        // The unit-page request finishes before the earlier enable request.
        repository.applySelectedUnitIds(setOf("44"), snapshot(announcement("current-unit"), unitId = "44"), 10L)
        repository.setIntervalMinutes(180)
        val before = repository.loadState()
        val failure = runCatching {
            repository.enableWithBaseline(requestedUnits, staleAnnouncements, 20L)
        }.exceptionOrNull()

        assertTrue("A baseline for $requestedUnits must not enable ${before.selectedUnitIds}", failure is IllegalStateException)
        assertEquals(before, repository.loadState())

        repository.enableWithBaseline(before.selectedUnitIds, snapshot(announcement("current-unit"), unitId = "44"), 30L)
        val enabled = repository.loadState()
        assertTrue(enabled.enabled)
        assertEquals(setOf("44"), enabled.selectedUnitIds)
        assertEquals(180, enabled.intervalMinutes)
        assertEquals(setOf("current-unit"), enabled.knownIdsByUnit["44"])
        assertTrue(enabled.baselineInitialized)
        assertEquals(30L, enabled.lastCheckedAtMillis)
        assertTrue(repository.recordCheck(setOf("44"), snapshot(announcement("current-unit"), unitId = "44"), 40L).isEmpty())
    }

    @Test
    fun authorityReadPropagatesIoFailureAndCanRecover() = runTest {
        var fail = true
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow {
                if (fail) throw IOException("unavailable")
                emit(mutablePreferencesOf(AnnouncementReminderRepository.ENABLED to true))
            }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = error("unused")
        }
        val repository = AnnouncementReminderRepository(store)
        assertTrue(runCatching { repository.loadState() }.exceptionOrNull() is IOException)
        fail = false
        assertTrue(repository.loadState().enabled)
    }
    @Test
    fun legacyUnfilteredProgressIsRebuiltWithoutNotifyingHistoricalAnnouncements() {
        val state = stateFromPreferences(
            mutablePreferencesOf(
                AnnouncementReminderRepository.ENABLED to true,
                AnnouncementReminderRepository.INTERVAL_MINUTES to 180,
                AnnouncementReminderRepository.SELECTED_UNIT_IDS to setOf("44"),
                booleanPreferencesKey("baseline_initialized") to true,
                stringSetPreferencesKey("known_ids") to setOf("other-unit"),
                stringSetPreferencesKey("unread_ids") to setOf("other-unit"),
                longPreferencesKey("last_checked_at") to 99L,
            ),
        )

        assertTrue(state.enabled)
        assertEquals(180, state.intervalMinutes)
        assertEquals(setOf("44"), state.selectedUnitIds)
        assertFalse(state.baselineInitialized)
        assertTrue(state.knownIds.isEmpty())
        assertTrue(state.unreadIds.isEmpty())
        assertEquals(0L, state.lastCheckedAtMillis)
        val baseline = applyAnnouncementCheck(state, snapshot(announcement("historical"), unitId = "44"), 100L)
        assertTrue(baseline.newAnnouncements.isEmpty())
        assertEquals(setOf("historical"), baseline.state.knownIdsByUnit["44"])
        val next = applyAnnouncementCheck(
            baseline.state, snapshot(announcement("new"), announcement("historical"), unitId = "44"), 200L,
        )
        assertEquals(listOf("new"), next.newAnnouncements.map(SchoolAnnouncement::id))
    }

    @Test
    fun firstCheckOnlyCreatesBaseline() {
        val outcome = applyAnnouncementCheck(
            AnnouncementReminderState(enabled = true),
            snapshot(announcement("2"), announcement("1")),
            checkedAtMillis = 10L,
        )

        assertTrue(outcome.state.baselineInitialized)
        assertEquals(setOf("2", "1"), outcome.state.knownIdsByUnit[ALL_UNITS_ID])
        assertTrue(outcome.state.unreadIds.isEmpty())
        assertTrue(outcome.newAnnouncements.isEmpty())
    }

    @Test
    fun newAnnouncementsAreDeduplicatedMergedAndAccumulateUnread() {
        val state = AnnouncementReminderState(
            enabled = true,
            baselineInitialized = true,
            knownIds = setOf("1"),
            unreadIds = setOf("old-unread"),
        )
        val outcome = applyAnnouncementCheck(
            state,
            snapshot(announcement("3"), announcement("3"), announcement("2"), announcement("1")),
            checkedAtMillis = 20L,
        )

        assertEquals(listOf("3", "2"), outcome.newAnnouncements.map(SchoolAnnouncement::id))
        assertEquals(setOf("3", "2", "old-unread"), outcome.state.unreadIds)
        assertEquals(setOf("3", "2", "1"), outcome.state.knownIdsByUnit[ALL_UNITS_ID])
    }

    @Test
    fun unitChangeClearsProgressWhileIntervalChangePreservesIt() {
        val state = AnnouncementReminderState(
            enabled = true,
            baselineInitialized = true,
            knownIds = setOf("1"),
            unreadIds = setOf("1"),
            lastCheckedAtMillis = 99L,
        )

        val frequencyChanged = withAnnouncementInterval(state, 180)
        assertTrue(frequencyChanged.baselineInitialized)
        assertEquals(setOf("1"), frequencyChanged.unreadIds)

        val unitsChanged = withAnnouncementUnits(frequencyChanged, setOf("69", "70"))
        assertFalse(unitsChanged.baselineInitialized)
        assertTrue(unitsChanged.knownIds.isEmpty())
        assertTrue(unitsChanged.unreadIds.isEmpty())
        assertEquals(setOf("69", "70"), unitsChanged.selectedUnitIds)
    }

    @Test
    fun historiesAreBoundedAndBadgeCapsAtNinetyNinePlus() {
        val state = AnnouncementReminderState(
            enabled = true,
            baselineInitialized = true,
            knownIds = (1..500).map(Int::toString).toSet(),
            unreadIds = (1..100).map { "old-$it" }.toSet(),
        )
        val outcome = applyAnnouncementCheck(
            state,
            AnnouncementReminderSnapshot(mapOf(ALL_UNITS_ID to (501..1_100).map { announcement(it.toString()) })),
            checkedAtMillis = 30L,
        )

        assertEquals((501..1_000).map(Int::toString), outcome.state.knownIdsByUnit.getValue(ALL_UNITS_ID).toList())
        assertEquals((501..600).map(Int::toString), outcome.state.unreadIds.toList())
        assertEquals("99+", announcementBadgeText(outcome.state.unreadIds.size))
        assertEquals("9", announcementBadgeText(9))
    }

    @Test
    fun pollingContinuesPastFirstPageUntilKnownAnnouncement() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            state = AnnouncementReminderState(knownIds = setOf("known")),
            loadPage = { pageIndex, _ ->
                calls += pageIndex
                when (pageIndex) {
                    0 -> page(announcement("new-1"), pageIndex = pageIndex, totalPages = 3)
                    1 -> page(announcement("new-2"), pageIndex = pageIndex, totalPages = 3)
                    else -> page(announcement("known"), pageIndex = pageIndex, totalPages = 3)
                }
            },
        )

        assertEquals(listOf(0, 1, 2), calls)
        assertEquals(listOf("new-1", "new-2", "known"), result.announcements.map(SchoolAnnouncement::id))
    }

    @Test
    fun knownPinnedAnnouncementDoesNotStopPollingBeforeNewerAnnouncements() = runTest {
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            state = AnnouncementReminderState(knownIds = setOf("pinned", "known")),
            loadPage = { pageIndex, _ ->
                when (pageIndex) {
                    0 -> page(announcement("pinned", isPinned = true), pageIndex = 0, totalPages = 3)
                    1 -> page(announcement("new"), announcement("known"), pageIndex = 1, totalPages = 3)
                    else -> error("Polling should stop at the known ordinary announcement")
                }
            },
        )
        assertEquals(listOf("pinned", "new", "known"), result.announcements.map(SchoolAnnouncement::id))
    }

    @Test
    fun pollingStopsAtSafetyPageLimit() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            state = AnnouncementReminderState(knownIds = setOf("known")),
            loadPage = { pageIndex, _ ->
                calls += pageIndex
                page(announcement("id-$pageIndex"), pageIndex = pageIndex, totalPages = 100)
            },
        )

        assertEquals((0 until ANNOUNCEMENT_REMINDER_MAX_PAGES).toList(), calls)
        assertEquals(ANNOUNCEMENT_REMINDER_MAX_PAGES, result.announcements.size)
    }

    @Test
    fun firstPollingLoadsOnlyThreePagesForBaseline() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            loadPage = { pageIndex, _ ->
                calls += pageIndex
                page(announcement("id-$pageIndex"), pageIndex = pageIndex, totalPages = 100)
            },
        )

        assertEquals(listOf(0, 1, 2), calls)
        assertEquals(listOf("id-0", "id-1", "id-2"), result.announcements.map(SchoolAnnouncement::id))
    }

    @Test
    fun baselineIncludesPinnedAnnouncementsThatCanMoveAcrossPageBoundary() = runTest {
        val baseline = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            loadPage = { pageIndex, _ ->
                when (pageIndex) {
                    0 -> page(
                        announcement("pinned-current", isPinned = true),
                        announcement("first-visible"),
                        pageIndex = pageIndex,
                        totalPages = 2,
                    )
                    else -> page(
                        announcement("pinned-older", isPinned = true),
                        pageIndex = pageIndex,
                        totalPages = 2,
                    )
                }
            },
        )
        val baselineState = applyAnnouncementCheck(
            AnnouncementReminderState(enabled = true),
            baseline,
            checkedAtMillis = 10L,
        ).state

        val movedPinnedAnnouncement = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            state = baselineState,
            loadPage = { pageIndex, _ ->
                page(
                    announcement("pinned-older", isPinned = true),
                    announcement("first-visible"),
                    pageIndex = pageIndex,
                    totalPages = 2,
                )
            },
        )

        val outcome = applyAnnouncementCheck(
            baselineState,
            movedPinnedAnnouncement,
            checkedAtMillis = 20L,
        )

        assertTrue(outcome.newAnnouncements.isEmpty())
    }

    @Test
    fun unitFailurePropagatesSoCallerCannotCommitPartialBaseline() = runTest {
        val error = runCatching {
            loadAnnouncementReminderSnapshot(
                selectedUnitIds = setOf("unit-a", "unit-b"),
                loadPage = { pageIndex, unitId ->
                    if (unitId == "unit-b") throw IOException("network")
                    page(announcement("$unitId-$pageIndex"), pageIndex = pageIndex, totalPages = 1)
                },
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun freshBaselineKeepsMoreThanFiveHundredAnnouncementsAcrossUnits() {
        val units = (1..10).map(Int::toString).toSet()
        val snapshot = AnnouncementReminderSnapshot(units.associateWith { unit ->
            (1..60).map { announcement("$unit-$it") }
        })
        val state = AnnouncementReminderState(enabled = true, selectedUnitIds = units)

        val initial = applyAnnouncementCheck(state, snapshot, 1L)
        assertTrue(initial.newAnnouncements.isEmpty())
        assertEquals(units, initial.state.knownIdsByUnit.keys)
        assertEquals(600, initial.state.knownIdsByUnit.values.sumOf { it.size })
        assertTrue(applyAnnouncementCheck(initial.state, snapshot, 2L).newAnnouncements.isEmpty())
    }

    @Test
    fun legacyMultiUnitBaselineRecoversMissingUnitsAndDeduplicatesFutureAlerts() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { temporaryFolder.root.resolve("multi-unit.preferences_pb") },
        )
        val repository = AnnouncementReminderRepository(store)
        val units = (1..10).map { it.toString().padStart(2, '0') }.toSet()
        val older = units.associateWith { unit ->
            (1..60).map { announcement("$unit-$it") }
        }
        val legacy = older.values.flatten().take(500).map(SchoolAnnouncement::id).toSet()
        repository.restoreSnapshot(AnnouncementReminderState(
            enabled = true, selectedUnitIds = units, baselineInitialized = true, knownIds = legacy,
        ))

        val migrationRequests = mutableListOf<Pair<String, Int>>()
        val migration = loadAnnouncementReminderSnapshot(units, repository.loadState()) { index, unit ->
            migrationRequests += unit to index
            page(*older.getValue(unit).drop(index * 20).take(20).toTypedArray(), pageIndex = index, totalPages = 20)
        }
        val rebuilt = repository.recordCheck(units, migration, 10L)
        assertTrue(rebuilt.isEmpty())
        assertEquals(listOf("10" to 0, "10" to 1, "10" to 2), migrationRequests.filter { it.first == "10" })
        val reloaded = AnnouncementReminderRepository(store).loadState()
        assertEquals(60, reloaded.knownIdsByUnit.getValue("10").size)
        assertEquals(legacy, reloaded.knownIds)

        val requests = mutableListOf<Pair<String, Int>>()
        val next = loadAnnouncementReminderSnapshot(units, reloaded) { index, unit ->
            requests += unit to index
            val new = if (unit in setOf("09", "10")) listOf(announcement("shared-new")) else emptyList()
            page(*(new + older.getValue(unit)).take(20).toTypedArray(), pageIndex = index, totalPages = 20)
        }
        assertEquals(units.map { it to 0 }, requests)
        val new = repository.recordCheck(units, next, 20L)
        assertEquals(listOf("shared-new"), new.map(SchoolAnnouncement::id))

        repository.setEnabled(false)
        assertTrue(repository.loadState().knownIdsByUnit.isEmpty())
    }

    private fun snapshot(vararg announcements: SchoolAnnouncement, unitId: String = ALL_UNITS_ID) =
        AnnouncementReminderSnapshot(mapOf(unitId to announcements.toList()))

    private fun announcement(id: String, isPinned: Boolean = false) = SchoolAnnouncement(
        id = id,
        title = "公告 $id",
        date = "2026/09/01",
        category = "公告",
        unit = "測試單位",
        issuer = "測試人員",
        isPinned = isPinned,
        contentType = "content",
    )

    private fun page(
        vararg announcements: SchoolAnnouncement,
        pageIndex: Int,
        totalPages: Int,
    ) = SchoolAnnouncementPage(
        announcements = announcements.toList(),
        pageIndex = pageIndex,
        totalPages = totalPages,
        fetchedAt = Instant.parse("2026-09-01T00:00:00Z"),
    )
}
