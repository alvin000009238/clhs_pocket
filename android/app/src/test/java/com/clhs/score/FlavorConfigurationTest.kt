package com.clhs.score

import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.schoolAnnouncementsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FlavorConfigurationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun flavorSelectsDataAndApplicationIdentity() {
        assertTrue(BuildConfig.FLAVOR in setOf("demo", "production"))
        val isDemo = BuildConfig.FLAVOR == "demo"
        assertEquals(isDemo, BuildConfig.USE_FAKE_DATA)
        assertEquals(
            "com.clhs.score" + (if (isDemo) ".demo" else "") +
                (if (BuildConfig.DEBUG) ".debug" else ""),
            BuildConfig.APPLICATION_ID,
        )
    }

    @Test
    fun announcementListAndDetailsUseTheConfiguredFlavor() = runTest {
        val cache = temporaryFolder.newFolder()
        val repository = schoolAnnouncementsRepository(cache, BuildConfig.USE_FAKE_DATA)
        if (!BuildConfig.USE_FAKE_DATA) {
            assertTrue(repository is NetworkSchoolAnnouncementsRepository)
            return@runTest
        }

        val firstPage = requireNotNull(repository.loadCached())
        val pinned = firstPage.announcements.first { it.isPinned }
        assertEquals(firstPage, repository.loadPage(0))
        assertEquals(listOf(pinned), repository.loadPage(0, keyword = pinned.title).announcements)
        val filters = repository.loadFilterOptions()
        val unit = filters.units.single { it.name == pinned.unit }
        val category = filters.categories.single { it.name == pinned.category }
        assertEquals(listOf(pinned), repository.loadPage(0, unitId = unit.id, categoryId = category.id).announcements)
        assertTrue(repository.loadPage(1).announcements.isEmpty())
        for (announcement in firstPage.announcements) {
            val detail = repository.loadDetail(announcement.id, announcement.category)
            assertEquals(announcement.id, detail.id)
            assertEquals(announcement.title, detail.title)
        }
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }
}
