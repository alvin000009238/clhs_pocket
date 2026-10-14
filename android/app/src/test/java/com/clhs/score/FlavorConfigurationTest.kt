package com.clhs.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlavorConfigurationTest {
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
}
