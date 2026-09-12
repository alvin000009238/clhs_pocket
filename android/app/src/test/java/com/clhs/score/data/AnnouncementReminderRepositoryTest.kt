package com.clhs.score.data

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
import org.junit.Test

class AnnouncementReminderRepositoryTest {
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
        val baseline = applyAnnouncementCheck(state, listOf(announcement("historical")), 100L)
        assertTrue(baseline.newAnnouncements.isEmpty())
        assertEquals(setOf("historical"), baseline.state.knownIds)
        val next = applyAnnouncementCheck(
            baseline.state, listOf(announcement("new"), announcement("historical")), 200L,
        )
        assertEquals(listOf("new"), next.newAnnouncements.map(SchoolAnnouncement::id))
    }

    @Test
    fun firstCheckOnlyCreatesBaseline() {
        val outcome = applyAnnouncementCheck(
            AnnouncementReminderState(enabled = true),
            listOf(announcement("2"), announcement("1")),
            checkedAtMillis = 10L,
        )

        assertTrue(outcome.state.baselineInitialized)
        assertEquals(setOf("2", "1"), outcome.state.knownIds)
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
            listOf(announcement("3"), announcement("3"), announcement("2"), announcement("1")),
            checkedAtMillis = 20L,
        )

        assertEquals(listOf("3", "2"), outcome.newAnnouncements.map(SchoolAnnouncement::id))
        assertEquals(setOf("3", "2", "old-unread"), outcome.state.unreadIds)
        assertEquals(setOf("3", "2", "1"), outcome.state.knownIds)
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
            (501..1_100).map { announcement(it.toString()) },
            checkedAtMillis = 30L,
        )

        assertEquals(500, outcome.state.knownIds.size)
        assertEquals(100, outcome.state.unreadIds.size)
        assertEquals("99+", announcementBadgeText(outcome.state.unreadIds.size))
        assertEquals("9", announcementBadgeText(9))
    }

    @Test
    fun pollingContinuesPastFirstPageUntilKnownAnnouncement() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            knownIds = setOf("known"),
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
        assertEquals(listOf("new-1", "new-2", "known"), result.map(SchoolAnnouncement::id))
    }

    @Test
    fun pollingStopsAtSafetyPageLimit() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            knownIds = setOf("known"),
            loadPage = { pageIndex, _ ->
                calls += pageIndex
                page(announcement("id-$pageIndex"), pageIndex = pageIndex, totalPages = 100)
            },
        )

        assertEquals((0 until ANNOUNCEMENT_REMINDER_MAX_PAGES).toList(), calls)
        assertEquals(ANNOUNCEMENT_REMINDER_MAX_PAGES, result.size)
    }

    @Test
    fun firstPollingLoadsOnlyThreePagesForBaseline() = runTest {
        val calls = mutableListOf<Int>()
        val result = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            knownIds = emptySet(),
            loadPage = { pageIndex, _ ->
                calls += pageIndex
                page(announcement("id-$pageIndex"), pageIndex = pageIndex, totalPages = 100)
            },
        )

        assertEquals(listOf(0, 1, 2), calls)
        assertEquals(listOf("id-0", "id-1", "id-2"), result.map(SchoolAnnouncement::id))
    }

    @Test
    fun baselineIncludesPinnedAnnouncementsThatCanMoveAcrossPageBoundary() = runTest {
        val baseline = loadAnnouncementReminderSnapshot(
            selectedUnitIds = setOf(ALL_UNITS_ID),
            knownIds = emptySet(),
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
            knownIds = baselineState.knownIds,
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
                knownIds = emptySet(),
                loadPage = { pageIndex, unitId ->
                    if (unitId == "unit-b") throw IOException("network")
                    page(announcement("$unitId-$pageIndex"), pageIndex = pageIndex, totalPages = 1)
                },
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
    }

    @Test
    fun markingReadAlsoMakesIdKnownAtomically() {
        val state = markAnnouncementRead(
            AnnouncementReminderState(
                enabled = true,
                baselineInitialized = true,
                knownIds = setOf("old"),
                unreadIds = setOf("announcement", "old"),
            ),
            "announcement",
        )

        assertTrue("announcement" in state.knownIds)
        assertEquals(setOf("old"), state.unreadIds)
    }

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
