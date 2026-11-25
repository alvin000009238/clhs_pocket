package com.clhs.score.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PocketRootTest {
    @Test
    fun rootOrderAndLabelsMatchAndroidNavigation() {
        assertEquals(listOf("首頁", "課表", "成績", "校園"), PocketRoot.entries.map { it.zhTwLabel })
        assertEquals(4, PocketRootCatalog.rootCount())
        PocketRoot.entries.forEachIndexed { index, root ->
            assertEquals(root.zhTwLabel, PocketRootCatalog.titleAt(index))
        }
    }

    @Test
    fun guestCanOnlyUsePublicRootDestinations() {
        assertFalse(PocketRootCatalog.requiresSessionAt(0))
        assertTrue(PocketRootCatalog.requiresSessionAt(1))
        assertTrue(PocketRootCatalog.requiresSessionAt(2))
        assertFalse(PocketRootCatalog.requiresSessionAt(3))
    }
}
