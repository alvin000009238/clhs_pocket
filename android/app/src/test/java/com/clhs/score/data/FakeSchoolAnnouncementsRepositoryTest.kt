package com.clhs.score.data

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSchoolAnnouncementsRepositoryTest {
    @Test fun legacyBookmarkKeepsItsSummaryAndOfficialDestination() = runTest {
        val bookmark = AnnouncementBookmark("98765", "已收藏的舊公告", "2026/08/01", "公告", "圖書館", "讀者服務組")
        val source = FakeSchoolAnnouncementsRepository(null, loadBookmark = { id -> bookmark.takeIf { it.id == id } })
        val detail = source.loadDetail(bookmark.id)
        assertEquals(bookmark.title, detail.title)
        assertEquals(bookmark.date, detail.date)
        assertEquals(bookmark.unit, detail.unit)
        assertEquals(schoolAnnouncementOfficialUrl(bookmark.id), detail.officialUrl)
        assertTrue(detail.htmlContent.contains("查看原文"))
    }

    @Test fun savedLibraryUnitWorksAndUnsupportedUnitFailsTheCheck() = runTest {
        val source = FakeSchoolAnnouncementsRepository(null)
        assertTrue(source.loadFilterOptions().units.any { it.id == "399" })
        val page = source.loadPage(0, unitId = "399")
        assertEquals(listOf("45072"), page.announcements.map { it.id })
        assertTrue(runCatching { source.loadPage(0, unitId = "999999") }.exceptionOrNull() is IOException)
    }

    @Test fun runtimeSamplesHaveNoPreviewAttachments() = runTest {
        val source = FakeSchoolAnnouncementsRepository(null)
        val pinned = source.loadPage(0).announcements.first { it.isPinned }
        val detail = source.loadDetail(pinned.id)
        assertFalse(detail.htmlContent.isBlank())
        assertTrue(detail.attachments.isEmpty())
    }
}
