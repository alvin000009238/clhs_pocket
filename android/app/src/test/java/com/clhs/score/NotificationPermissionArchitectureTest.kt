package com.clhs.score

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class NotificationPermissionArchitectureTest {
    @Test
    fun notificationPermissionFlowUsesRuntimeRequestAndSystemAvailabilityChecks() {
        val generalSettings = readSource("app/src/main/java/com/clhs/score/ui/GeneralSettingsScreen.kt")
        val grades = readSource("app/src/main/java/com/clhs/score/ui/GradesScreen.kt")
        val onboarding = readSource("app/src/main/java/com/clhs/score/ui/OnboardingScreen.kt")
        val app = readSource("app/src/main/java/com/clhs/score/ui/ScoreApp.kt")
        val helper = readSource("app/src/main/java/com/clhs/score/notifications/NotificationPermissionHelper.kt")

        listOf(generalSettings, grades, onboarding).forEach { source ->
            assertTrue(source.contains("ActivityResultContracts.RequestPermission()"))
            assertTrue(source.contains("shouldShowPostNotificationsRationale()"))
        }
        assertTrue(generalSettings.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(grades.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(onboarding.contains("notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"))
        assertTrue(onboarding.contains("openAppNotificationSettings()"))
        assertFalse(app.contains("NotificationPromptDialog("))
        assertTrue(helper.contains("ContextCompat.checkSelfPermission("))
        assertTrue(helper.contains("NotificationManagerCompat.from(this).areNotificationsEnabled()"))
        assertTrue(helper.contains("hasPostNotificationsPermission() && areNotificationsEnabled()"))
        assertTrue(helper.contains("ActivityCompat.shouldShowRequestPermissionRationale("))
    }

    private fun readSource(relativePath: String): String =
        Files.readString(findAndroidRoot().resolve(relativePath))

    private fun findAndroidRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }
            current = current.parent ?: error("Unable to locate Android project root")
        }
    }
}
