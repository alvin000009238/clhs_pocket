package com.clhs.score.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class AnnouncementCampusPinTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun pinRequiresBookmarkAndUnbookmarkingClearsOnlyTheMatchingPin() = runTest {
        val file = folder.root.resolve("announcements.preferences_pb")
        val firstStoreJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(backgroundScope.coroutineContext + firstStoreJob),
            produceFile = { file },
        )
        val first = announcement("1")
        val second = announcement("2")
        try {
            val repository = AnnouncementBookmarkRepository(firstStore)
            assertNull(repository.campusPin.first())
            assertFalse(repository.pinToCampus(first.id))
            repository.toggle(first)
            assertTrue(repository.pinToCampus(first.id))
            assertFalse(repository.pinToCampus(second.id))
            assertEquals(first, repository.campusPin.first()?.toAnnouncement())
            repository.toggle(second)
            assertTrue(repository.pinToCampus(second.id))
            repository.removeCampusPin(first.id)
            assertFalse(repository.toggle(first))
        } finally {
            firstStoreJob.cancelAndJoin()
        }

        val reopened = AnnouncementBookmarkRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }),
        )
        assertEquals(second, reopened.campusPin.first()?.toAnnouncement())
        assertEquals(listOf(second), reopened.bookmarks.first().map(AnnouncementBookmark::toAnnouncement))
        reopened.removeCampusPin(second.id)
        assertNull(reopened.campusPin.first())
        assertEquals(listOf(second), reopened.bookmarks.first().map(AnnouncementBookmark::toAnnouncement))
        assertTrue(reopened.pinToCampus(second.id))
        assertTrue(reopened.toggle(second))
        assertNull(reopened.campusPin.first())
        assertTrue(reopened.bookmarks.first().isEmpty())
        reopened.toggle(second)
        assertNull(reopened.campusPin.first())
    }

    private fun announcement(id: String) = SchoolAnnouncement(
        id = id,
        title = "校園公告 $id",
        date = "2026/11/27",
        category = "公告",
        unit = "教務處",
        issuer = "教務處",
        isPinned = false,
        contentType = "html",
    )
}
