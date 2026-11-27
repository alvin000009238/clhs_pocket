package com.clhs.score.shared.app

import com.clhs.score.data.WeatherLocation
import com.clhs.score.domain.overview.OverviewLayoutJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * Taiwan's townships for the weather widget, searched offline like Android's `WeatherLocationCatalog`.
 * The platform loads the bundled `weather_locations.json` once; until then only the demo locations exist.
 */
object PocketWeatherCatalog {
    private var locations: List<WeatherLocation> = emptyList()

    val isLoaded: Boolean get() = locations.isNotEmpty()

    /** Returns false and keeps the previous catalogue when [json] is not a valid list. */
    fun load(json: String): Boolean {
        val parsed = runCatching {
            OverviewLayoutJson.decodeFromString(ListSerializer(WeatherLocation.serializer()), json)
        }.getOrNull()?.filter { it.countryCode == "TW" } ?: return false
        if (parsed.isEmpty()) return false
        locations = parsed
        return true
    }

    private fun all(): List<WeatherLocation> = (locations + DemoLive.locations).distinctBy { it.id }

    fun find(id: String): WeatherLocation? = all().firstOrNull { it.id == id }

    /** Same matching as Android: every term must appear in county + name, 台 equals 臺, an exact name first, at most 40. */
    fun search(query: String): List<WeatherLocation> {
        val input = query.trim()
        if (input.isBlank() || input.length > MAX_QUERY_LENGTH) return emptyList()
        val terms = normalize(input).split(Regex("[\\s,，]+")).filter(String::isNotBlank)
        return all().asSequence().filter { location ->
            val label = normalize(location.county + location.name)
            terms.all(label::contains)
        }.sortedWith(
            compareByDescending<WeatherLocation> { normalize(it.name) == normalize(input) }.thenBy { it.id },
        ).take(MAX_RESULTS).toList()
    }

    private fun normalize(value: String): String = value.replace('台', '臺').lowercase()

    const val MAX_QUERY_LENGTH = 120
    private const val MAX_RESULTS = 40
}
