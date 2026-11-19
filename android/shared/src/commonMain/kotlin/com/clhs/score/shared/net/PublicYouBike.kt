package com.clhs.score.shared.net

import com.clhs.score.data.SchoolJson
import com.clhs.score.data.YouBikeSnapshot
import com.clhs.score.data.YouBikeStation
import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.parseLocalTime
import com.clhs.score.shared.time.schoolEpochMillis
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public YouBike 2.0 station feed. Never authenticated. */
class PublicYouBikeClient(
    transport: HttpTransport,
    endpoint: String = "https://apis.youbike.com.tw/json/station-yb2.json",
) {
    private val http = PublicHttp(transport)
    private val endpoint = requireNotNull(PortableUrl.parse(endpoint)) { "Invalid endpoint" }

    /** Raw station feed; parse it with [YouBikeFeed]. */
    suspend fun fetchFeedBody(): String = http.get(endpoint, "YouBike", MAX_RESPONSE_BYTES)

    private companion object {
        const val MAX_RESPONSE_BYTES = 8_388_608L
    }
}

object YouBikeFeed {
    private val UPDATED_AT = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

    /** Station rows by id. A duplicated id is ambiguous, so that station maps to null. */
    fun stations(raw: String): Map<String, JsonObject?> {
        val stations = LinkedHashMap<String, JsonObject?>()
        for (row in SchoolJson.parseToJsonElement(raw).jsonArray) {
            val station = row as? JsonObject ?: continue
            val id = (station["station_no"] as? JsonPrimitive)?.contentOrNull ?: continue
            stations[id] = if (id in stations) null else station
        }
        return stations
    }

    /** Throws when the station is missing, duplicated or reports inconsistent counts. */
    fun snapshot(raw: String, fetchedAt: Instant, stationId: String = YouBikeStation.SCHOOL.id): YouBikeSnapshot =
        snapshot(requireNotNull(stations(raw)[stationId]) { "YouBike station is unavailable" }, fetchedAt)

    fun snapshot(station: JsonObject, fetchedAt: Instant): YouBikeSnapshot {
        fun count(key: String): Int = requireNotNull(station[key]?.jsonPrimitive?.intOrNull).also { require(it >= 0) }
        val total = count("parking_spaces")
        val bikes = count("available_spaces").also { require(it <= total) }
        val empty = count("empty_spaces").also { require(it <= total) }
        val operating = count("status").also { require(it in 1..2) } == 1
        val detail = runCatching {
            val parsed = requireNotNull(station["available_spaces_detail"]).jsonObject
            fun JsonObject.detailCount(key: String): Int =
                requireNotNull(get(key)?.jsonPrimitive?.intOrNull).also { require(it in 0..bikes) }
            val ordinary = parsed.detailCount("yb2")
            val electric = parsed.detailCount("eyb")
            require(ordinary.toLong() + electric == bikes.toLong())
            ordinary to electric
        }.getOrNull()
        val updatedText = requireNotNull(station["updated_at"]?.jsonPrimitive?.contentOrNull)
        require(UPDATED_AT.matches(updatedText))
        val updatedAt = instantOfEpochMilli(
            parseLocalDate(updatedText.substring(0, 10)).at(parseLocalTime(updatedText.substring(11))).schoolEpochMillis(),
        )
        return YouBikeSnapshot(bikes, empty, total, detail?.first, detail?.second, operating, updatedAt, fetchedAt)
    }

    fun search(raw: String, query: String): List<YouBikeStation> {
        val input = query.trim()
        require(input.length <= 120)
        if (input.isBlank()) return emptyList()
        return SchoolJson.parseToJsonElement(raw).jsonArray.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val id = obj["station_no"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name_tw"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            YouBikeStation(
                id, name,
                district = obj["district_tw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                address = obj["address_tw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).takeIf { it.matches(input) }
        }.distinctBy { it.id }.take(40)
    }

    private fun YouBikeStation.matches(query: String): Boolean = listOf(name, district, address).any {
        it.replace('台', '臺').contains(query.replace('台', '臺'), ignoreCase = true)
    }
}
