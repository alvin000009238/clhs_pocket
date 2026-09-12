package com.clhs.score.ui

import com.clhs.score.data.AnnouncementUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class AnnouncementReminderScreenTest {
    @Test
    fun recommendedUnitsComeFirstWithoutDuplicatingSources() {
        val units = listOf(
            AnnouncementUnit("1", "生輔組官網"),
            AnnouncementUnit("2", "學務處官網"),
            AnnouncementUnit("3", "教學組官網"),
            AnnouncementUnit("4", "實驗研究組官網"),
        )

        assertEquals(
            listOf("實驗研究組官網", "教學組官網", "學務處官網", "生輔組官網"),
            orderAnnouncementUnits(units).map(AnnouncementUnit::name),
        )
    }

    @Test
    fun draftUnitSelectionKeepsAtLeastOneChoice() {
        assertEquals(setOf("12"), toggleAnnouncementUnitSelection(setOf("12"), "12"))
        assertEquals(setOf("13"), toggleAnnouncementUnitSelection(setOf("-1"), "13"))
        assertEquals(setOf("-1"), toggleAnnouncementUnitSelection(setOf("12"), "-1"))
    }

    @Test
    fun unitSummaryKeepsAllSourcesCompact() {
        val units = listOf(
            AnnouncementUnit("-1", "全部"),
            AnnouncementUnit("1", "教學組"),
            AnnouncementUnit("2", "生輔組"),
            AnnouncementUnit("3", "訓育組"),
            AnnouncementUnit("4", "文書組"),
        )

        assertEquals("全部單位 · 4 個來源", announcementUnitSummary(units, setOf("-1")))
        assertEquals("教學組、生輔組 · 2 個來源", announcementUnitSummary(units, setOf("1", "2")))
        assertEquals(
            "教學組、生輔組等 4 個來源",
            announcementUnitSummary(units, setOf("1", "2", "3", "4")),
        )
    }

    @Test
    fun lastCheckedLabelUsesRelativeDayForRecentChecks() {
        val zone = ZoneOffset.UTC
        val checkedAt = LocalDateTime.of(2026, 9, 3, 22, 31).toInstant(zone).toEpochMilli()

        assertEquals(
            "今天 22:31",
            announcementLastCheckedLabel(checkedAt, zone, LocalDate.of(2026, 9, 3)),
        )
    }
}
