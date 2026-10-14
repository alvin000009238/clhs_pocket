package com.clhs.score.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.TimeUnit

@Serializable
data class YouBikeStation(val id: String, val name: String, val latitude: Double? = null, val longitude: Double? = null, val district: String = "", val address: String = "") {
    companion object { val SCHOOL = YouBikeStation("500304002", "中壢高中", 24.9621, 121.2112) }
}

data class YouBikeSnapshot(
    val availableBikes: Int,
    val emptyDocks: Int,
    val totalDocks: Int,
    val ordinaryBikes: Int?,
    val electricBikes: Int?,
    val isOperating: Boolean,
    val updatedAt: Instant,
    val fetchedAt: Instant,
) {
    fun isStale(now: Instant): Boolean = Duration.between(updatedAt, now).let {
        it.isNegative || it > Duration.ofMinutes(5)
    }
}

data class YouBikeState(
    val snapshot: YouBikeSnapshot? = null,
    val isRefreshing: Boolean = false,
    val failed: Boolean = false,
    val isManuallyRefreshing: Boolean = false,
)

class YouBikeRepository internal constructor(
    private val useFakeData: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val endpoint: HttpUrl = ENDPOINT,
    private val now: () -> Instant = Instant::now,
) {
    private var latest = YouBikeState()
    private var stationStates: Map<String, YouBikeState> = emptyMap()
    private var feed: String? = null
    private var feedFetchedAt: Instant? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeStations(stations: Flow<List<YouBikeStation>>, enabled: Flow<Boolean>, refreshes: Flow<Unit>): Flow<Map<String, YouBikeState>> =
        combine(stations, enabled) { selected, active -> selected.distinctBy { it.id } to active }
            .distinctUntilChanged().flatMapLatest { (selected, active) ->
                if (!active || selected.isEmpty()) flowOf(selected.associate { it.id to (stationStates[it.id] ?: YouBikeState()).copy(isRefreshing = false, failed = false) })
                else refreshes.withIndex().flatMapLatest { request ->
                    flow {
                        // The refresh StateFlow's first emission starts the silent poll.
                        var manual = request.index > 0
                        while (true) {
                            emit(selected.associate { it.id to (stationStates[it.id] ?: YouBikeState()).copy(isRefreshing = true, isManuallyRefreshing = manual) })
                            stationStates = try {
                                val stationsById = if (useFakeData) null else parseYouBikeFeed(fetchFeed())
                                val fetchedAt = now()
                                currentCoroutineContext().ensureActive()
                                selected.associate { station -> station.id to try {
                                    YouBikeState(if (stationsById == null) fakeYouBikeSnapshot(fetchedAt)
                                        else parseYouBikeSnapshot(requireNotNull(stationsById[station.id]), fetchedAt))
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    (stationStates[station.id] ?: YouBikeState()).copy(isRefreshing = false, failed = true)
                                } }
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                selected.associate { it.id to (stationStates[it.id] ?: YouBikeState()).copy(isRefreshing = false, failed = true) }
                            }
                            emit(stationStates)
                            manual = false
                            delay(60_000)
                        }
                    }
                }
            }

    suspend fun searchStations(query: String): List<YouBikeStation> {
        val input = query.trim()
        require(input.length <= 120)
        if (input.isBlank()) return emptyList()
        if (useFakeData) return listOf(YouBikeStation.SCHOOL).filter { it.matches(input) }
        val cached = feed.takeIf { feedFetchedAt?.let { time -> Duration.between(time, now()).let { !it.isNegative && it <= Duration.ofMinutes(5) } } == true }
        val raw = cached ?: fetchFeed()
        return withContext(Dispatchers.Default) {
            SchoolJson.parseToJsonElement(raw).jsonArray.mapNotNull { row ->
                currentCoroutineContext().ensureActive()
                val obj = row as? JsonObject ?: return@mapNotNull null
                val id = obj["station_no"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name_tw"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                YouBikeStation(id, name, district = obj["district_tw"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    address = obj["address_tw"]?.jsonPrimitive?.contentOrNull.orEmpty()).takeIf { it.matches(input) }
            }.distinctBy { it.id }.take(40)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(enabled: Flow<Boolean>, refreshes: Flow<Unit>): Flow<YouBikeState> =
        enabled.distinctUntilChanged().flatMapLatest { active ->
            if (!active) flowOf(latest.copy(isRefreshing = false, failed = false)) else refreshes.flatMapLatest {
                flow {
                    while (true) {
                        emit(latest.copy(isRefreshing = true))
                        latest = try {
                            val snapshot = if (useFakeData) fakeYouBikeSnapshot(now()) else fetch()
                            currentCoroutineContext().ensureActive()
                            YouBikeState(snapshot)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            latest.copy(isRefreshing = false, failed = true)
                        }
                        emit(latest)
                        delay(60_000)
                    }
                }
            }
        }

    private suspend fun fetch(): YouBikeSnapshot = parseYouBikeSnapshot(fetchFeed(), now())

    private suspend fun fetchFeed(): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(endpoint).get().build()).executeCancellable { response ->
            check(response.isSuccessful)
            val source = response.body.source()
            val buffer = Buffer()
            while (buffer.size <= MAX_RESPONSE_BYTES) {
                if (source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - buffer.size)) == -1L) break
            }
            require(buffer.size <= MAX_RESPONSE_BYTES)
            buffer.readUtf8().also { feed = it; feedFetchedAt = now() }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8_388_608L
        val ENDPOINT = "https://apis.youbike.com.tw/json/station-yb2.json".toHttpUrl()
    }
}

private fun YouBikeStation.matches(query: String): Boolean = listOf(name, district, address).any {
    it.replace('台', '臺').contains(query.replace('台', '臺'), ignoreCase = true)
}

private fun parseYouBikeFeed(raw: String): Map<String, JsonObject?> {
    val stations = mutableMapOf<String, JsonObject?>()
    for (row in SchoolJson.parseToJsonElement(raw).jsonArray) {
        val station = row as? JsonObject ?: continue
        val id = (station["station_no"] as? JsonPrimitive)?.contentOrNull ?: continue
        // A duplicate identity is ambiguous, so only that station becomes unavailable.
        stations[id] = if (id in stations) null else station
    }
    return stations
}

internal fun parseYouBikeSnapshot(raw: String, fetchedAt: Instant, stationId: String = YouBikeStation.SCHOOL.id): YouBikeSnapshot =
    parseYouBikeSnapshot(requireNotNull(parseYouBikeFeed(raw)[stationId]), fetchedAt)

private fun parseYouBikeSnapshot(station: JsonObject, fetchedAt: Instant): YouBikeSnapshot {
    fun count(key: String): Int = requireNotNull(station[key]?.jsonPrimitive?.intOrNull).also { require(it >= 0) }
    val total = count("parking_spaces")
    val bikes = count("available_spaces").also { require(it <= total) }
    val empty = count("empty_spaces").also { require(it <= total) }
    val operating = count("status").also { require(it in 1..2) } == 1
    val detail = runCatching {
        val parsed = requireNotNull(station["available_spaces_detail"]).jsonObject
        fun JsonObject.detailCount(key: String): Int = requireNotNull(get(key)?.jsonPrimitive?.intOrNull)
            .also { require(it in 0..bikes) }
        val ordinary = parsed.detailCount("yb2")
        val electric = parsed.detailCount("eyb")
        require(ordinary.toLong() + electric == bikes.toLong())
        ordinary to electric
    }.getOrNull()
    val updatedAt = LocalDateTime.parse(
        requireNotNull(station["updated_at"]?.jsonPrimitive?.contentOrNull),
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT),
    ).atZone(ZoneId.of("Asia/Taipei")).toInstant()
    return YouBikeSnapshot(bikes, empty, total, detail?.first, detail?.second, operating, updatedAt, fetchedAt)
}
