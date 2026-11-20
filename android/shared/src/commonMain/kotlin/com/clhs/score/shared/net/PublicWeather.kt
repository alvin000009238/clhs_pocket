package com.clhs.score.shared.net

import com.clhs.score.data.SchoolJson
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.domain.overview.WeatherForecastPeriod
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.shared.time.Instant
import com.clhs.score.shared.time.LOCAL_DATE_TIME_ORDER
import com.clhs.score.shared.time.LocalDateTime
import com.clhs.score.shared.time.NANOS_PER_HOUR
import com.clhs.score.shared.time.SCHOOL_ZONE_ID
import com.clhs.score.shared.time.at
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.durationUntil
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.parseLocalTime
import com.clhs.score.shared.time.schoolDateTimeAt
import com.clhs.score.shared.time.schoolEpochMillis
import com.clhs.score.shared.time.shiftNanos
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

enum class CwaKeyValidation { VALID, INVALID_FORMAT, INVALID_AUTHORIZATION, API_REJECTED, UNAVAILABLE }

private const val MAX_CWA_API_KEY_BYTES = 256

/** Trimmed key, or throws when it is empty, too long or contains control characters. */
fun validateCwaApiKey(rawKey: String): String {
    val key = rawKey.trim()
    require(rawKey.none { it.code < 0x20 || it.code in 0x7F..0x9F }) { "請輸入有效的中央氣象署授權碼" }
    require(key.isNotEmpty() && key.encodeToByteArray().size <= MAX_CWA_API_KEY_BYTES) { "請輸入有效的中央氣象署授權碼" }
    return key
}

/**
 * Public weather sources. Open-Meteo is anonymous; the Central Weather Administration key is the
 * user's own and travels only in the `Authorization` header of the request to `opendata.cwa.gov.tw`.
 */
class PublicWeatherClient(
    transport: HttpTransport,
    openMeteoEndpoint: String = "https://api.open-meteo.com/",
    cwaEndpoint: String = "https://opendata.cwa.gov.tw/",
) {
    private val http = PublicHttp(transport)
    private val openMeteo = requireNotNull(PortableUrl.parse(openMeteoEndpoint)) { "Invalid endpoint" }
    private val cwa = requireNotNull(PortableUrl.parse(cwaEndpoint)) { "Invalid endpoint" }

    /** Raw response body; parse it with [WeatherParser.parse]. */
    suspend fun fetchBody(source: WeatherSource, location: WeatherLocation, cwaKey: String?): String {
        WeatherParser.requireSupported(source, location)
        return when (source) {
            WeatherSource.OPEN_METEO -> http.get(
                openMeteo.withPath(
                    "v1/forecast",
                    listOf(
                        "latitude" to location.latitude.toString(),
                        "longitude" to location.longitude.toString(),
                        "current" to "temperature_2m,apparent_temperature,weather_code",
                        "hourly" to "temperature_2m,weather_code,precipitation_probability",
                        "daily" to "temperature_2m_max,temperature_2m_min",
                        "forecast_days" to "2",
                        "timezone" to location.timezoneId,
                    ),
                ),
                "Weather", MAX_RESPONSE_BYTES,
            )
            WeatherSource.CWA -> http.get(
                cwa.withPath(
                    "api/v1/rest/datastore/${requireNotNull(location.cwaDataset)}",
                    listOf(
                        "LocationName" to requireNotNull(location.cwaLocationName),
                        "ElementName" to "溫度,體感溫度,3小時降雨機率,天氣現象",
                    ),
                ),
                "Weather", MAX_RESPONSE_BYTES,
                headers = listOf(HttpHeader("Authorization", requireNotNull(cwaKey) { "CWA key is required" })),
            )
        }
    }

    suspend fun validateCwaKey(rawKey: String): CwaKeyValidation {
        val key = runCatching { validateCwaApiKey(rawKey) }.getOrElse { return CwaKeyValidation.INVALID_FORMAT }
        return try {
            val root = SchoolJson.parseToJsonElement(fetchBody(WeatherSource.CWA, WeatherLocation.SCHOOL, key))
            when (root.successFlag()) {
                true -> CwaKeyValidation.VALID
                false -> CwaKeyValidation.API_REJECTED
                null -> CwaKeyValidation.UNAVAILABLE
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: PublicDataException) {
            if (error.statusCode == 401 || error.statusCode == 403) CwaKeyValidation.INVALID_AUTHORIZATION
            else CwaKeyValidation.UNAVAILABLE
        } catch (_: Exception) {
            CwaKeyValidation.UNAVAILABLE
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 512L * 1024
    }
}

object WeatherParser {
    fun requireSupported(source: WeatherSource, location: WeatherLocation) {
        require(
            location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
                location.longitude.isFinite() && location.longitude in -180.0..180.0,
        )
        // Only the fixed school offset is available without a time-zone database.
        require(location.timezoneId == SCHOOL_ZONE_ID) { "天氣地點只支援臺灣" }
        require(source != WeatherSource.CWA || location.supportsCwa)
        location.cwaDataset?.let { require(Regex("F-D0047-[0-9]{3}").matches(it)) { "氣象資料集格式不符" } }
    }

    fun parse(source: WeatherSource, raw: String, fetchedAt: Instant, location: WeatherLocation): WeatherSnapshot {
        requireSupported(source, location)
        return when (source) {
            WeatherSource.OPEN_METEO -> parseOpenMeteo(raw, fetchedAt, location)
            WeatherSource.CWA -> parseCwa(raw, fetchedAt, location)
        }
    }

    private fun parseOpenMeteo(raw: String, fetchedAt: Instant, location: WeatherLocation): WeatherSnapshot {
        val root = SchoolJson.parseToJsonElement(raw).jsonObject
        val current = root.requiredObject("current")
        val currentTime = parseLocalDateTime(current["time"]?.jsonPrimitive?.contentOrNull ?: error("Missing time"))
        val temperature = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.roundTemperature()
            ?: error("Missing temperature_2m")
        val apparent = current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull?.roundTemperature()
        val weatherCode = current["weather_code"]?.jsonPrimitive?.intOrNull?.also { require(it in 0..99) }
            ?: error("Missing weather_code")
        val hourly = root.requiredObject("hourly")
        val times = hourly.requiredArray("time")
        val probabilities = hourly.requiredArray("precipitation_probability")
        require(times.size == probabilities.size && times.size <= 384)
        fun timeAt(index: Int): LocalDateTime? = runCatching { parseLocalDateTime(times[index].jsonPrimitive.content) }.getOrNull()
        val nextIndex = times.indices.firstOrNull { index -> timeAt(index)?.let { it >= currentTime } == true }
        val precipitation = nextIndex?.let { probabilities[it].jsonPrimitive.intOrNull }?.also { require(it in 0..100) }
        val precipitationStart = nextIndex?.let { parseLocalDateTime(times[it].jsonPrimitive.content) }
        val daily = root["daily"] as? JsonObject
        val today = schoolDateTimeAt(fetchedAt.epochMillis()).datePart().toString()
        val dayIndex = (daily?.get("time") as? JsonArray)
            ?.indexOfFirst { (it as? JsonPrimitive)?.contentOrNull == today }?.takeIf { it >= 0 }
        fun dailyTemperature(name: String): Int? = dayIndex?.let { index ->
            ((daily[name] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.doubleOrNull?.validTemperature()
        }
        return WeatherSnapshot(
            source = WeatherSource.OPEN_METEO,
            locationLabel = location.name,
            temperatureCelsius = temperature,
            apparentTemperatureCelsius = apparent,
            condition = weatherCodeLabel(weatherCode),
            precipitationProbability = precipitation,
            precipitationStart = precipitationStart,
            precipitationEndExclusive = precipitationStart?.shiftNanos(NANOS_PER_HOUR),
            fetchedAt = fetchedAt,
            timezoneId = location.timezoneId,
            maximumTemperatureCelsius = dailyTemperature("temperature_2m_max"),
            minimumTemperatureCelsius = dailyTemperature("temperature_2m_min"),
            forecast = times.indices.mapNotNull { index ->
                val start = timeAt(index)?.schoolEpochMillis() ?: return@mapNotNull null
                WeatherForecastPeriod(
                    instantOfEpochMilli(start), instantOfEpochMilli(start + 3_600_000L),
                    (hourly["temperature_2m"] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.doubleOrNull?.validTemperature(),
                    (hourly["weather_code"] as? JsonArray)?.getOrNull(index)?.jsonPrimitive?.intOrNull?.let(::weatherCodeLabel),
                    probabilities[index].jsonPrimitive.intOrNull?.takeIf { it in 0..100 },
                )
            },
        )
    }

    private val temperatureNames = listOf("溫度", "T")
    private val conditionNames = listOf("天氣現象", "Wx")
    private val probabilityNames = listOf("3小時降雨機率", "PoP6h", "PoP12h", "PoP")

    private fun parseCwa(raw: String, fetchedAt: Instant, requested: WeatherLocation): WeatherSnapshot {
        val root = SchoolJson.parseToJsonElement(raw)
        require(root.successFlag() == true)
        val location = root.findObject { it.primitiveText("LocationName") == requested.cwaLocationName }
            ?: root.findObject { it.primitiveText("locationName") == requested.cwaLocationName }
            ?: error("中央氣象署資料缺少選定地點")
        val elements = (location["WeatherElement"] ?: location["weatherElement"])?.jsonArray
            ?: error("中央氣象署資料格式不符")
        val reference = schoolDateTimeAt(fetchedAt.epochMillis())
        val temperature = elements.valueForPoint(temperatureNames, reference)?.let { requireNotNull(it.toDoubleOrNull()).roundTemperature() }
            ?: error("中央氣象署資料缺少溫度")
        val apparent = elements.valueForPoint(listOf("體感溫度", "AT"), reference)?.toDoubleOrNull()?.validTemperature()
        val condition = elements.valueForPoint(conditionNames, reference)?.take(24)?.ifBlank { null } ?: "天氣狀況未提供"
        val probability = elements.valueForPeriod(probabilityNames, reference)
            ?.toDoubleOrNull()?.toInt()?.also { require(it in 0..100) }
        val period = elements.elementNamed(probabilityNames)?.timeArray()?.periodTimeAt(reference)?.let { time ->
            val start = time.cwaTime("StartTime", "startTime") ?: return@let null
            val end = time.cwaTime("EndTime", "endTime") ?: return@let null
            start to end
        }
        val temperaturePoints = elements.elementNamed(temperatureNames)?.timeArray().orEmpty()
        // The three-day product has temperature points, not official daily extrema.
        val dayEnd = reference.shiftNanos(24 * NANOS_PER_HOUR)
        val temperatures = temperaturePoints.mapNotNull { value ->
            val time = value as? JsonObject ?: return@mapNotNull null
            val point = time.cwaTime("DataTime", "dataTime") ?: return@mapNotNull null
            if (point < reference || point >= dayEnd) return@mapNotNull null
            (time.findPrimitive("Temperature") ?: time.findPrimitive("value"))?.doubleOrNull?.validTemperature()
        }
        return WeatherSnapshot(
            source = WeatherSource.CWA,
            locationLabel = requested.name,
            temperatureCelsius = temperature,
            apparentTemperatureCelsius = apparent,
            condition = condition,
            precipitationProbability = probability,
            precipitationStart = period?.first,
            precipitationEndExclusive = period?.second,
            fetchedAt = fetchedAt,
            timezoneId = requested.timezoneId,
            maximumTemperatureCelsius = temperatures.maxOrNull(),
            minimumTemperatureCelsius = temperatures.minOrNull(),
            forecast = temperaturePoints
                .mapNotNull { (it as? JsonObject)?.cwaTime("DataTime", "dataTime", "StartTime", "startTime") }
                .distinct().sortedWith(LOCAL_DATE_TIME_ORDER).zipWithNext().map { (start, end) ->
                    WeatherForecastPeriod(
                        instantOfEpochMilli(start.schoolEpochMillis()), instantOfEpochMilli(end.schoolEpochMillis()),
                        elements.valueForPoint(temperatureNames, start)?.toDoubleOrNull()?.validTemperature(),
                        elements.valueForPoint(conditionNames, start)?.take(24),
                        elements.valueForPeriod(probabilityNames, start)?.toIntOrNull()?.takeIf { it in 0..100 },
                    )
                },
        )
    }

    private fun JsonArray.valueForPoint(names: List<String>, reference: LocalDateTime): String? =
        valueFor(names) { it.dataPointTimeAt(reference) ?: it.periodTimeAt(reference) }

    private fun JsonArray.valueForPeriod(names: List<String>, reference: LocalDateTime): String? =
        valueFor(names) { it.periodTimeAt(reference) ?: it.dataPointTimeAt(reference) }

    private fun JsonArray.valueFor(names: List<String>, timeSelector: (JsonArray) -> JsonObject?): String? =
        elementNamed(names)?.timeArray()?.let(timeSelector)?.let { time ->
            listOf("Temperature", "ApparentTemperature", "Weather", "ProbabilityOfPrecipitation", "value")
                .firstNotNullOfOrNull { time.findPrimitive(it)?.contentOrNull }
        }

    private fun JsonArray.elementNamed(names: List<String>): JsonObject? = firstNotNullOfOrNull { value ->
        val element = value as? JsonObject ?: return@firstNotNullOfOrNull null
        element.takeIf { (it.primitiveText("ElementName") ?: it.primitiveText("elementName")) in names }
    }

    private fun JsonObject.timeArray(): JsonArray? = (this["Time"] ?: this["time"]) as? JsonArray

    private fun JsonArray.dataPointTimeAt(reference: LocalDateTime): JsonObject? {
        require(size <= 128)
        val points = mapNotNull { value ->
            val time = value as? JsonObject ?: return@mapNotNull null
            time.cwaTime("DataTime", "dataTime")?.let { it to time }
        }.ifEmpty { return null }
        val candidates = points.filter { it.first >= reference }.ifEmpty {
            points.filter { it.first.datePart() == reference.datePart() }
        }
        return candidates.minByOrNull { reference.durationUntil(it.first).absoluteValue }?.second
    }

    private fun JsonArray.periodTimeAt(reference: LocalDateTime): JsonObject? {
        require(size <= 128)
        val periods = mapNotNull { value ->
            val time = value as? JsonObject ?: return@mapNotNull null
            val start = time.cwaTime("StartTime", "startTime") ?: return@mapNotNull null
            val end = time.cwaTime("EndTime", "endTime") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            Triple(start, end, time)
        }
        return periods.firstOrNull { reference >= it.first && reference < it.second }?.third
            ?: periods.filter { it.first >= reference }.minWithOrNull { a, b -> LOCAL_DATE_TIME_ORDER.compare(a.first, b.first) }?.third
    }

    private val OFFSET_SUFFIX = Regex("(Z|[+-]\\d{2}:\\d{2})$")

    /** The wall-clock part of an ISO date-time, with or without a UTC offset (CWA always sends +08:00). */
    private fun JsonObject.cwaTime(vararg names: String): LocalDateTime? = names.firstNotNullOfOrNull { name ->
        primitiveText(name)?.let { value -> runCatching { parseLocalDateTime(value.replace(OFFSET_SUFFIX, "")) }.getOrNull() }
    }

    private fun parseLocalDateTime(text: String): LocalDateTime {
        require(text.length >= 16 && text[10] == 'T') { "Invalid date-time" }
        return parseLocalDate(text.substring(0, 10)).at(parseLocalTime(text.substring(11)))
    }

    private fun JsonObject.primitiveText(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.requiredObject(name: String): JsonObject = this[name]?.jsonObject ?: error("Missing $name")
    private fun JsonObject.requiredArray(name: String): JsonArray = this[name]?.jsonArray ?: error("Missing $name")
    private fun Double.roundTemperature(): Int = roundToInt().also { require(it in -100..70) }
    private fun Double.validTemperature(): Int? = takeIf { it.isFinite() && it in -100.0..70.0 }?.roundToInt()

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "晴朗"
        1, 2 -> "晴時多雲"
        3 -> "陰天"
        45, 48 -> "有霧"
        in 51..57 -> "毛毛雨"
        in 61..67, in 80..82 -> "有雨"
        in 71..77, in 85..86 -> "降雪"
        in 95..99 -> "雷雨"
        else -> "天氣狀況未提供"
    }
}

internal fun JsonElement.successFlag(): Boolean? =
    findPrimitive("success")?.let { it.booleanOrNull ?: it.contentOrNull?.equals("true", ignoreCase = true) }

internal fun JsonElement.findPrimitive(name: String): JsonPrimitive? = when (this) {
    is JsonObject -> this[name]?.let { it as? JsonPrimitive } ?: values.firstNotNullOfOrNull { it.findPrimitive(name) }
    is JsonArray -> firstNotNullOfOrNull { it.findPrimitive(name) }
    else -> null
}

internal fun JsonElement.findObject(predicate: (JsonObject) -> Boolean): JsonObject? = when (this) {
    is JsonObject -> if (predicate(this)) this else values.firstNotNullOfOrNull { it.findObject(predicate) }
    is JsonArray -> firstNotNullOfOrNull { it.findObject(predicate) }
    else -> null
}
