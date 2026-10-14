package com.clhs.score.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class AnnouncementReadRepositoryTest {
    private val cutoff = LocalDate.of(2026, 10, 1)

    @Test fun migrationRetriesWithoutMovingCutoffOrOverwritingRoom() = runTest {
        val store = MemoryPreferences()
        val metadata = AnnouncementReminderRepository(store)
        val dao = MemoryReads()
        dao.rows.value = mapOf("legacy" to true)
        dao.fail = true
        val first = AnnouncementReadRepository(dao, metadata) { cutoff }
        assertTrue(runCatching { first.initialize() }.exceptionOrNull() is IOException)
        assertEquals(cutoff.toEpochDay(), store.data.value[AnnouncementReminderRepository.READ_CUTOFF_DAY])
        assertNull(store.data.value[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE])
        assertEquals(setOf("legacy", "unread"), store.data.value[AnnouncementReminderRepository.UNREAD_IDS])
        dao.fail = false
        store.failMigrationCommit = true
        assertTrue(runCatching { first.initialize() }.exceptionOrNull() is IOException)
        assertNull(store.data.value[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE])
        assertEquals(setOf("legacy", "unread"), store.data.value[AnnouncementReminderRepository.UNREAD_IDS])
        store.failMigrationCommit = false
        val restarted = AnnouncementReadRepository(dao, metadata) { cutoff.plusDays(1) }
        assertEquals(cutoff, restarted.initialize())
        assertEquals(mapOf("legacy" to true, "unread" to false), dao.rows.value)
        assertTrue(store.data.value[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE] == true)
        assertNull(store.data.value[AnnouncementReminderRepository.UNREAD_IDS])
        assertEquals(setOf("baseline"), metadata.loadState().knownIds)
    }

    @Test fun mixedDiscoveryPreservesReadStateAcrossRestart() = runTest {
        val metadata = AnnouncementReminderRepository(MemoryPreferences())
        val dao = MemoryReads()
        val reading = AnnouncementReadRepository(dao, metadata) { cutoff }
        reading.discover(listOf(notice("old", "2026/09/30"), notice("today", "2026/10/01"), notice("unknown", "")))
        assertFalse("old" in dao.rows.value)
        assertEquals(setOf("legacy", "unread", "today", "unknown"), reading.unreadIds.first())
        reading.markRead("today")
        val restarted = AnnouncementReadRepository(dao, metadata) { cutoff.plusDays(3) }
        restarted.discover(listOf(notice("today", ""), notice("future", "2026-10-02 09:00:00")))
        assertEquals(true, dao.rows.value["today"])
        assertEquals(false, dao.rows.value["future"])
        assertFalse(announcementStartsUnread("2026-09-30T20:00:00", cutoff))
        assertTrue(announcementStartsUnread("invalid", cutoff))
    }

    @Test fun allReadSnapshotLeavesLaterDiscoveryUnreadAndBaselineUnchanged() = runTest {
        val metadata = AnnouncementReminderRepository(MemoryPreferences())
        val reading = AnnouncementReadRepository(MemoryReads(), metadata) { cutoff }
        reading.initialize()
        val baseline = metadata.loadState()
        val snapshot = reading.unreadIds.first()
        reading.discover(listOf(notice("later", "2026/10/01")))
        reading.markAllRead(snapshot)
        assertEquals(setOf("later"), reading.unreadIds.first())
        reading.markRead("later")
        assertEquals(baseline, metadata.loadState())
        metadata.setEnabled(false)
        metadata.applySelectedUnitIds(setOf("44"), emptyList(), 123L)
        reading.discover(listOf(notice("later", "2026/10/01")))
        assertTrue(reading.unreadIds.first().isEmpty())
    }

    @Test fun failedWritesKeepUnreadAndFailedDiscoveryCanRecover() = runTest {
        val metadata = AnnouncementReminderRepository(MemoryPreferences())
        val dao = MemoryReads()
        val reading = AnnouncementReadRepository(dao, metadata) { cutoff }
        reading.initialize()
        val unread = reading.unreadIds.first()
        dao.fail = true
        assertTrue(runCatching { reading.markAllRead(unread) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { reading.markRead("unread") }.exceptionOrNull() is IOException)
        assertEquals(unread, reading.unreadIds.first())
        reading.discoverForDisplay(listOf(notice("later", "2026/10/01")))
        assertFalse(reading.discoveryAvailable.value)
        assertNotNull(reading.errorMessage.value)
        dao.fail = false
        reading.discoverForDisplay(listOf(notice("later", "2026/10/01")))
        assertTrue(reading.discoveryAvailable.value)
        assertTrue("later" in reading.unreadIds.first())
    }

    @Test fun reminderMutationCannotClearLegacyUnreadBeforeMigrationSucceeds() = runTest {
        val store = MemoryPreferences()
        val dao = MemoryReads().also { it.fail = true }
        val reading = AnnouncementReadRepository(dao, AnnouncementReminderRepository(store)) { cutoff }
        val reminder = AnnouncementReminderRepository(store) { reading.initialize() }
        assertTrue(runCatching { reminder.setEnabled(false) }.exceptionOrNull() is IOException)
        assertEquals(setOf("legacy", "unread"), store.data.value[AnnouncementReminderRepository.UNREAD_IDS])
        assertTrue(store.data.value[AnnouncementReminderRepository.ENABLED] == true)
        dao.fail = false
        reminder.setEnabled(false)
        assertEquals(setOf("legacy", "unread"), reading.unreadIds.first())
    }

    private fun notice(id: String, date: String) = SchoolAnnouncement(id, "", date, "公告", "", "", false, "content")

    private class MemoryPreferences : DataStore<Preferences> {
        var failMigrationCommit = false
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(
            AnnouncementReminderRepository.ENABLED to true,
            AnnouncementReminderRepository.BASELINE_INITIALIZED to true,
            AnnouncementReminderRepository.KNOWN_IDS to setOf("baseline"),
            AnnouncementReminderRepository.UNREAD_IDS to setOf("legacy", "unread"),
        ))
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(data.value)
            if (failMigrationCommit && next[AnnouncementReminderRepository.READ_MIGRATION_COMPLETE] == true) {
                throw IOException("migration commit unavailable")
            }
            data.value = next
            return next
        }
    }

    private class MemoryReads : AnnouncementReadDao {
        val rows = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        var fail = false
        private fun checkStorage() { if (fail) throw IOException("storage unavailable") }
        override fun observeUnreadKeys() = rows.map { data -> data.filterValues { !it }.keys.toList() }
        override suspend fun insertDiscovered(states: List<AnnouncementReadState>) {
            checkStorage()
            rows.value = states.associate { it.announcementKey to it.isRead } + rows.value
        }
        override suspend fun markRead(state: AnnouncementReadState) {
            checkStorage()
            rows.value = rows.value + (state.announcementKey to state.isRead)
        }
        override suspend fun markKeysRead(keys: List<String>) {
            checkStorage()
            rows.value = rows.value.mapValues { (key, read) -> read || key in keys }
        }
    }
}
