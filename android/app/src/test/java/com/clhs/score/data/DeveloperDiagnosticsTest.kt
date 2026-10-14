package com.clhs.score.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperDiagnosticsTest {
    @Test
    fun defaultSelectedCategoriesAreEmpty() {
        assertTrue(defaultSelectedLocalDataCategories().isEmpty())
    }

    @Test
    fun storageEntryUsesStableCategoryMetadata() {
        val entry = StorageEntry(LocalDataCategory.Settings, bytes = 128L)

        assertEquals("settings", entry.key)
        assertEquals("設定資料", entry.label)
        assertFalse(entry.isClearable)
        assertEquals(128L, entry.bytes)
    }

    @Test
    fun diagnosticTextRedactsSensitiveValues() {
        val text = """
            token=secret Cookie: sessionid=abc student_no=123456
            https://example.com/callback?token=secret
        """.trimIndent()

        val sanitized = text.sanitizeDiagnosticText()

        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("123456"))
        assertFalse(sanitized.contains("https://example.com"))
        assertTrue(sanitized.contains("token=[redacted]"))
        assertTrue(sanitized.contains("Cookie: [redacted]"))
        assertTrue(sanitized.contains("student_no=[redacted]"))
        assertTrue(sanitized.contains("[url]"))
    }

    @Test
    fun blankDiagnosticTextFallsBackToNone() {
        assertEquals("無", null.toDiagnosticLine())
        assertEquals("無", " \n ".toDiagnosticLine())
    }
}
