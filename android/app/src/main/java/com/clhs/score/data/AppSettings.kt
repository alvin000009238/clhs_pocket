package com.clhs.score.data

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class WeatherSource {
    OPEN_METEO,
    CWA,
}

data class WeatherConfiguration(
    val source: WeatherSource,
    val credentialRevision: Long = 0L,
)

data class AppSettings(
    val overview: OverviewPreferences = OverviewPreferences(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val amoledBlack: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val developerEnabled: Boolean = false,
    val demoMode: Boolean = false,
    val biometricEnabled: Boolean = false,
    val weatherSource: WeatherSource = WeatherSource.OPEN_METEO,
    val weatherCredentialRevision: Long = 0L,
    val hasCompletedOnboarding: Boolean = false,
)

/** Device-wide presentation preferences; calendar selections contain only public event IDs. */
data class OverviewPreferences(
    val hiddenSections: Set<String> = emptySet(),
    val calendarLimit: Int = 3,
    val announcementLimit: Int = 2,
    val countdownEventIds: Set<String> = emptySet(),
    val sectionOrder: List<String> = DEFAULT_SECTION_ORDER,
) {
    fun shows(section: String): Boolean = section !in hiddenSections

    fun orderedSections(): List<String> =
        (sectionOrder.filter { it in DEFAULT_SECTION_ORDER } + DEFAULT_SECTION_ORDER).distinct()

    fun moveSection(section: String, offset: Int): OverviewPreferences {
        val order = orderedSections().toMutableList()
        val from = order.indexOf(section)
        if (from < 0) return this
        val to = (from + offset).coerceIn(0, order.lastIndex)
        order.add(to, order.removeAt(from))
        return copy(sectionOrder = order)
    }

    companion object {
        val DEFAULT_SECTION_ORDER = listOf("schedule", "weather", "alerts", "countdown", "calendar", "announcements")
    }
}
