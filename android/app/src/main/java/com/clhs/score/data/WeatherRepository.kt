package com.clhs.score.data

import com.clhs.score.domain.overview.WeatherSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

data class WeatherRepositoryState(
    val snapshot: WeatherSnapshot?,
    val isRefreshing: Boolean,
    val needsCwaApiKey: Boolean = false,
)

internal enum class CwaKeyValidationResult {
    VALID,
    INVALID_FORMAT,
    INVALID_AUTHORIZATION,
    API_REJECTED,
    UNAVAILABLE,
}

class WeatherRepository internal constructor(
    cacheDirectory: File,
    keyStore: CwaApiKeyStore? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val now: () -> Instant = Instant::now,
    private val cwaKeyProvider: suspend () -> String? = { keyStore?.load() },
    private val openMeteoEndpoint: HttpUrl = OPEN_METEO_ENDPOINT,
    private val cwaEndpoint: HttpUrl = CWA_ENDPOINT,
) {
    private val openMeteoCache = LatestCacheFile(cacheDirectory, "weather-open-meteo-clhs.json")
    private val cwaCache = LatestCacheFile(cacheDirectory, "weather-cwa-zhongli-district.json")

    fun observe(source: WeatherSource): Flow<WeatherRepositoryState> = flow {
        val cache = cacheFor(source)
        val cached = readCache(cache, source)
        val age = cached?.let { Duration.between(it.fetchedAt, now()) }
        val usableCache = cached?.takeIf { age != null && !age.isNegative && age <= MAX_STALE_AGE }
        if (usableCache != null) emit(WeatherRepositoryState(usableCache, isRefreshing = age!! > FRESH_AGE))
        if (usableCache != null && age!! <= FRESH_AGE) return@flow

        val key = if (source == WeatherSource.CWA) {
            try {
                cwaKeyProvider()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emit(WeatherRepositoryState(usableCache, isRefreshing = false))
                return@flow
            }
        } else {
            null
        }
        if (source == WeatherSource.CWA && key == null) {
            emit(WeatherRepositoryState(usableCache, isRefreshing = false, needsCwaApiKey = true))
            return@flow
        }
        val generation = cache.beginWrite()
        try {
            val raw = fetch(source, key)
            val fetchedAt = now()
            val snapshot = parse(source, raw, fetchedAt)
            cache.commit(raw.encodeToByteArray(), fetchedAt.toEpochMilli(), generation, currentCoroutineContext())
            emit(WeatherRepositoryState(snapshot, isRefreshing = false))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emit(WeatherRepositoryState(usableCache, isRefreshing = false))
        }
    }

    internal suspend fun validateCwaKey(rawKey: String): CwaKeyValidationResult {
        val key = runCatching { validateCwaApiKey(rawKey) }
            .getOrElse { return CwaKeyValidationResult.INVALID_FORMAT }
        return try {
            val root = SchoolJson.parseToJsonElement(fetch(WeatherSource.CWA, key))
            val success = root.findPrimitive("success")?.booleanOrNull
                ?: root.findPrimitive("success")?.contentOrNull?.equals("true", ignoreCase = true)
            when (success) {
                true -> CwaKeyValidationResult.VALID
                false -> CwaKeyValidationResult.API_REJECTED
                null -> CwaKeyValidationResult.UNAVAILABLE
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WeatherRequestException) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                CwaKeyValidationResult.INVALID_AUTHORIZATION
            } else {
                CwaKeyValidationResult.UNAVAILABLE
            }
        } catch (_: Exception) {
            CwaKeyValidationResult.UNAVAILABLE
        }
    }

    private suspend fun fetch(source: WeatherSource, cwaKey: String?): String = withContext(Dispatchers.IO) {
        val request = when (source) {
            WeatherSource.OPEN_METEO -> Request.Builder().url(openMeteoUrl(openMeteoEndpoint)).get().build()
            WeatherSource.CWA -> Request.Builder()
                .url(cwaUrl(cwaEndpoint))
                .header("Authorization", requireNotNull(cwaKey))
                .get()
                .build()
        }
        client.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw WeatherRequestException(response.code)
            val body = response.body
            val sourceBody = body.source()
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                val read = sourceBody.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - buffer.size))
                if (read == -1L) break
            }
            val bytes = buffer.readByteArray()
            require(bytes.size <= MAX_RESPONSE_BYTES) { "天氣資料超過大小限制" }
            bytes.decodeToString()
        }
    }

    private fun readCache(cache: LatestCacheFile, source: WeatherSource): WeatherSnapshot? {
        if (!cache.file.isFile || cache.file.length() !in 1..MAX_RESPONSE_BYTES.toLong()) return null
        val fetchedAt = Instant.ofEpochMilli(cache.file.lastModified())
        return runCatching { parse(source, cache.file.readText(), fetchedAt) }.getOrNull()
    }

    private fun parse(source: WeatherSource, raw: String, fetchedAt: Instant): WeatherSnapshot = when (source) {
        WeatherSource.OPEN_METEO -> parseOpenMeteo(raw, fetchedAt)
        WeatherSource.CWA -> parseCwa(raw, fetchedAt)
    }

    private fun parseOpenMeteo(raw: String, fetchedAt: Instant): WeatherSnapshot {
        val root = SchoolJson.parseToJsonElement(raw).jsonObject
        val current = root.requiredObject("current")
        val currentTime = LocalDateTime.parse(current.requiredText())
        val temperature = current.requiredTemperature()
        val apparent = current.optionalTemperature()
        val weatherCode = current.requiredInt()
        val hourly = root.requiredObject("hourly")
        val times = hourly.requiredArray("time")
        val probabilities = hourly.requiredArray("precipitation_probability")
        require(times.size == probabilities.size && times.size <= 384)
        val nextIndex = times.indices.firstOrNull {
            runCatching { LocalDateTime.parse(times[it].jsonPrimitive.content) >= currentTime }.getOrDefault(false)
        }
        val precipitation = nextIndex?.let { probabilities[it].jsonPrimitive.intOrNull }?.also {
            require(it in 0..100)
        }
        val precipitationStart = nextIndex?.let { LocalDateTime.parse(times[it].jsonPrimitive.content) }
        return WeatherSnapshot(
            source = WeatherSource.OPEN_METEO,
            locationLabel = "中壢區",
            temperatureCelsius = temperature,
            apparentTemperatureCelsius = apparent,
            condition = weatherCodeLabel(weatherCode),
            precipitationProbability = precipitation,
            precipitationStart = precipitationStart,
            precipitationEndExclusive = precipitationStart?.plusHours(1),
            fetchedAt = fetchedAt,
        )
    }

    private fun parseCwa(raw: String, fetchedAt: Instant): WeatherSnapshot {
        val root = SchoolJson.parseToJsonElement(raw)
        val success = root.findPrimitive("success")?.booleanOrNull
            ?: root.findPrimitive("success")?.contentOrNull?.equals("true", ignoreCase = true)
        require(success == true)
        val location = root.findObject { it["LocationName"]?.jsonPrimitive?.contentOrNull == CWA_LOCATION }
            ?: root.findObject { it["locationName"]?.jsonPrimitive?.contentOrNull == CWA_LOCATION }
            ?: error("中央氣象署資料缺少中壢區")
        val elements = (location["WeatherElement"] ?: location["weatherElement"])?.jsonArray
            ?: error("中央氣象署資料格式不符")
        val reference = LocalDateTime.ofInstant(fetchedAt, TAIPEI_ZONE)
        val temperature = elements.valueForPoint(listOf("溫度", "T"), reference)?.roundTemperature()
            ?: error("中央氣象署資料缺少溫度")
        val apparent = elements.valueForPoint(listOf("體感溫度", "AT"), reference)?.roundTemperature()
        val condition = elements.valueForPoint(listOf("天氣現象", "Wx"), reference)
            ?.take(24)?.ifBlank { null } ?: "天氣狀況未提供"
        val probabilityNames = listOf("3小時降雨機率", "PoP6h", "PoP12h", "PoP")
        val probability = elements.valueForPeriod(probabilityNames, reference)
            ?.toDoubleOrNull()?.toInt()?.also { require(it in 0..100) }
        val period = elements.firstPeriodFor(probabilityNames, reference)
        return WeatherSnapshot(
            source = WeatherSource.CWA,
            locationLabel = "中壢區",
            temperatureCelsius = temperature,
            apparentTemperatureCelsius = apparent,
            condition = condition,
            precipitationProbability = probability,
            precipitationStart = period?.first,
            precipitationEndExclusive = period?.second,
            fetchedAt = fetchedAt,
        )
    }

    private fun cacheFor(source: WeatherSource) = when (source) {
        WeatherSource.OPEN_METEO -> openMeteoCache
        WeatherSource.CWA -> cwaCache
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val CWA_LOCATION = "中壢區"
        val FRESH_AGE: Duration = Duration.ofMinutes(30)
        val MAX_STALE_AGE: Duration = Duration.ofHours(6)

        val OPEN_METEO_ENDPOINT: HttpUrl = HttpUrl.Builder().scheme("https").host("api.open-meteo.com").build()
        val CWA_ENDPOINT: HttpUrl = HttpUrl.Builder().scheme("https").host("opendata.cwa.gov.tw").build()
        val TAIPEI_ZONE: ZoneId = ZoneId.of("Asia/Taipei")

        fun openMeteoUrl(endpoint: HttpUrl): HttpUrl = endpoint.newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", "24.9621")
            .addQueryParameter("longitude", "121.2112")
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code")
            .addQueryParameter("hourly", "precipitation_probability")
            .addQueryParameter("forecast_days", "2")
            .addQueryParameter("timezone", "Asia/Taipei")
            .build()

        fun cwaUrl(endpoint: HttpUrl): HttpUrl = endpoint.newBuilder()
            .addPathSegments("api/v1/rest/datastore/F-D0047-005")
            .addQueryParameter("LocationName", CWA_LOCATION)
            .addQueryParameter("ElementName", "溫度,體感溫度,3小時降雨機率,天氣現象")
            .build()
    }
}

private class WeatherRequestException(val statusCode: Int) : Exception("天氣資料暫時無法取得")

private fun JsonObject.requiredObject(name: String): JsonObject = this[name]?.jsonObject ?: error("Missing $name")
private fun JsonObject.requiredArray(name: String): JsonArray = this[name]?.jsonArray ?: error("Missing $name")
private fun JsonObject.requiredText(): String = this["time"]?.jsonPrimitive?.contentOrNull ?: error("Missing time")
private fun JsonObject.requiredInt(): Int =
    this["weather_code"]?.jsonPrimitive?.intOrNull?.also { require(it in 0..99) } ?: error("Missing weather_code")
private fun JsonObject.requiredTemperature(): Int =
    this["temperature_2m"]?.jsonPrimitive?.doubleOrNull?.roundTemperature() ?: error("Missing temperature_2m")
private fun JsonObject.optionalTemperature(): Int? =
    this["apparent_temperature"]?.jsonPrimitive?.doubleOrNull?.roundTemperature()
private fun Double.roundTemperature(): Int = roundToInt().also { require(it in -100..70) }
private fun String.roundTemperature(): Int = requireNotNull(toDoubleOrNull()).roundTemperature()

private fun JsonElement.findPrimitive(name: String): JsonPrimitive? = when (this) {
    is JsonObject -> this[name]?.let { it as? JsonPrimitive } ?: values.firstNotNullOfOrNull { it.findPrimitive(name) }
    is JsonArray -> firstNotNullOfOrNull { it.findPrimitive(name) }
    else -> null
}

private fun JsonElement.findObject(predicate: (JsonObject) -> Boolean): JsonObject? = when (this) {
    is JsonObject -> if (predicate(this)) this else values.firstNotNullOfOrNull { it.findObject(predicate) }
    is JsonArray -> firstNotNullOfOrNull { it.findObject(predicate) }
    else -> null
}

private fun JsonArray.valueForPoint(elementNames: List<String>, reference: LocalDateTime): String? =
    valueFor(elementNames) { it.pointTimeAt(reference) }

private fun JsonArray.valueForPeriod(elementNames: List<String>, reference: LocalDateTime): String? =
    valueFor(elementNames) { it.periodValueTimeAt(reference) }

private fun JsonArray.valueFor(
    elementNames: List<String>,
    timeSelector: (JsonArray) -> JsonObject?,
): String? = elementNamed(elementNames)?.let { element ->
    element.timeArray()?.let(timeSelector)?.let { time ->
        listOf("Temperature", "ApparentTemperature", "Weather", "ProbabilityOfPrecipitation", "value")
            .firstNotNullOfOrNull { time.findPrimitive(it)?.contentOrNull }
    }
}

private fun JsonArray.firstPeriodFor(names: List<String>, reference: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
    val time = elementNamed(names)?.timeArray()?.periodTimeAt(reference) ?: return null
    val start = time.cwaTime("StartTime", "startTime") ?: return null
    val end = time.cwaTime("EndTime", "endTime") ?: return null
    return start to end
}

private fun JsonArray.elementNamed(names: List<String>): JsonObject? = firstNotNullOfOrNull { value ->
    val objectValue = value as? JsonObject ?: return@firstNotNullOfOrNull null
    val name = (objectValue["ElementName"] ?: objectValue["elementName"])?.jsonPrimitive?.contentOrNull
    objectValue.takeIf { name in names }
}

private fun JsonObject.timeArray(): JsonArray? = (this["Time"] ?: this["time"]) as? JsonArray

private fun JsonArray.pointTimeAt(reference: LocalDateTime): JsonObject? =
    dataPointTimeAt(reference) ?: periodTimeAt(reference)

private fun JsonArray.periodValueTimeAt(reference: LocalDateTime): JsonObject? =
    periodTimeAt(reference) ?: dataPointTimeAt(reference)

private fun JsonArray.dataPointTimeAt(reference: LocalDateTime): JsonObject? {
    require(size <= 128)
    val points = mapNotNull { value ->
        val time = value as? JsonObject ?: return@mapNotNull null
        time.cwaTime("DataTime", "dataTime")?.let { it to time }
    }.ifEmpty { return null }
    val future = points.filter { !it.first.isBefore(reference) }
    val candidates = future.ifEmpty {
        points.filter { it.first.toLocalDate() == reference.toLocalDate() }
    }
    return candidates.minByOrNull { Duration.between(reference, it.first).abs() }?.second
}

private fun JsonArray.periodTimeAt(reference: LocalDateTime): JsonObject? {
    require(size <= 128)
    val periods = mapNotNull { value ->
        val time = value as? JsonObject ?: return@mapNotNull null
        val start = time.cwaTime("StartTime", "startTime") ?: return@mapNotNull null
        val end = time.cwaTime("EndTime", "endTime") ?: return@mapNotNull null
        if (!end.isAfter(start)) return@mapNotNull null
        Triple(start, end, time)
    }
    return periods.firstOrNull { !reference.isBefore(it.first) && reference.isBefore(it.second) }?.third
        ?: periods.filter { !it.first.isBefore(reference) }.minByOrNull { it.first }
            ?.third
}

private fun JsonObject.cwaTime(vararg names: String): LocalDateTime? = names.firstNotNullOfOrNull { name ->
    this[name]?.jsonPrimitive?.contentOrNull?.let { value ->
        runCatching { java.time.OffsetDateTime.parse(value).toLocalDateTime() }
            .getOrElse { runCatching { LocalDateTime.parse(value) }.getOrNull() }
    }
}

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
