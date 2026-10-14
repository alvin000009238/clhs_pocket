package com.clhs.score

import com.clhs.score.ui.buildThirdPartyLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {
    @Test
    fun everyPublishedComponentHasOneLicenseEntry() {
        val licenses = buildThirdPartyLicenses("Outfit license", "Huninn license")

        assertEquals(38, licenses.size)
        assertEquals("Huninn license", licenses.single { it.componentName == "Huninn" }.licenseText)
        assertEquals(licenses.size, licenses.map { it.componentName }.distinct().size)
        assertTrue(licenses.all { it.licenseName.isNotBlank() && it.licenseText.isNotBlank() })
        assertTrue(licenses.none { it.componentName == "Firebase Analytics" })
    }
}
