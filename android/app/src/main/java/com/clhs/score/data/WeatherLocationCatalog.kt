package com.clhs.score.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

/** Taiwan township search uses only the bundled official administrative catalogue. */
class WeatherLocationCatalog internal constructor(context: Context) {
    private val assets = context.applicationContext.assets
    private val json = Json { ignoreUnknownKeys = true }
    private val taiwanLocations: List<WeatherLocation> by lazy {
        assets.open("weather_locations.json").bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<List<WeatherLocation>>(it.readText())
        }
    }

    suspend fun search(query: String): List<WeatherLocation> = withContext(Dispatchers.IO) {
        val input = query.trim()
        require(input.length <= MAX_QUERY_LENGTH) { "地點名稱過長" }
        if (input.isBlank()) return@withContext emptyList()
        currentCoroutineContext().ensureActive()
        val results = matchLocalWeatherLocations(taiwanLocations, input)
        currentCoroutineContext().ensureActive()
        results
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 120
    }
}

/** Matches only the supplied offline catalogue, without granting new CWA identities. */
internal fun matchLocalWeatherLocations(locations: List<WeatherLocation>, query: String): List<WeatherLocation> {
    val input = query.trim()
    if (input.isBlank()) return emptyList()
    val terms = normalize(input).split(Regex("[\\s,，]+"))
    return locations.asSequence().filter { location ->
        val label = normalize(location.county + location.name)
        location.countryCode == "TW" && terms.all(label::contains)
    }.sortedWith(compareByDescending<WeatherLocation> {
        normalize(it.name) == normalize(input)
    }.thenBy { it.id }).take(40).toList()
}

private fun normalize(value: String): String = value.replace('台', '臺').lowercase(Locale.ROOT)
