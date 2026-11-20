@file:OptIn(ExperimentalAtomicApi::class)

package com.clhs.score.shared.repo

import com.clhs.score.data.ALL_UNITS_ID
import com.clhs.score.data.AnnouncementFilterOptions
import com.clhs.score.data.SchoolAnnouncement
import com.clhs.score.data.SchoolAnnouncementDetail
import com.clhs.score.data.SchoolAnnouncementPage
import com.clhs.score.data.SchoolCalendarSnapshot
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeSnapshot
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.WeatherSnapshot
import com.clhs.score.shared.net.CwaKeyValidation
import com.clhs.score.shared.net.PublicAnnouncementParser
import com.clhs.score.shared.net.PublicAnnouncementsClient
import com.clhs.score.shared.net.PublicCalendarClient
import com.clhs.score.shared.net.PublicWeatherClient
import com.clhs.score.shared.net.PublicYouBikeClient
import com.clhs.score.shared.net.SchoolCalendarIcs
import com.clhs.score.shared.net.WeatherParser
import com.clhs.score.shared.net.YouBikeFeed
import com.clhs.score.shared.net.validateCwaApiKey
import com.clhs.score.shared.time.LocalDate
import com.clhs.score.shared.time.compareTo
import com.clhs.score.shared.time.datePart
import com.clhs.score.shared.time.epochMillis
import com.clhs.score.shared.time.instantOfEpochMilli
import com.clhs.score.shared.time.parseLocalDate
import com.clhs.score.shared.time.schoolDateTimeAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** Only the newest started request may replace a cache file (Android `LatestCacheFile`). */
internal class LatestWrite {
    private val generation = AtomicLong(0L)
    private val lock = Mutex()
    fun begin(): Long = generation.incrementAndFetch()

    /** Runs [write] only while [started] is the newest request; the check and the write are atomic. */
    suspend fun commitIfLatest(started: Long, write: () -> Unit) {
        lock.withLock { if (generation.load() == started) write() }
    }
}

private val storeJson = Json { ignoreUnknownKeys = true }

/** Public school announcements with a first-page cache and local read / bookmark state. */
class AnnouncementsRepository(
    private val client: PublicAnnouncementsClient,
    private val files: FileStore,
    private val now: () -> Long,
) {
    @Serializable
    private data class ReadState(val cutoff: String, val read: Map<String, Boolean> = emptyMap())

    @Serializable
    private data class Bookmark(
        val id: String, val title: String, val date: String, val category: String, val unit: String,
        val issuer: String, val contentType: String, val externalUrl: String? = null,
    )

    private val listWrite = LatestWrite()
    private val optionsWrite = LatestWrite()

    /** Read state and bookmarks are read-modify-write; one lock keeps concurrent changes. */
    private val stateLock = Mutex()

    fun loadCached(): SchoolAnnouncementPage? {
        val stored = files.readOrNull(LIST_CACHE)?.takeIf { it.text.length <= PublicAnnouncementsClient.MAX_LIST_BYTES } ?: return null
        return runCatching {
            PublicAnnouncementParser.parsePage(stored.text, instantOfEpochMilli(stored.modifiedAtEpochMillis))
        }.getOrNull()
    }

    suspend fun loadPage(
        pageIndex: Int,
        keyword: String = "",
        unitId: String = ALL_UNITS_ID,
        categoryId: String = ALL_UNITS_ID,
    ): SchoolAnnouncementPage {
        val isDefaultFirstPage = pageIndex == 0 && keyword.isBlank() && unitId == ALL_UNITS_ID && categoryId == ALL_UNITS_ID
        val write = if (isDefaultFirstPage) listWrite.begin() else null
        val body = client.fetchPageBody(pageIndex, keyword, unitId, categoryId)
        val fetchedAt = now()
        val page = PublicAnnouncementParser.parsePage(body, instantOfEpochMilli(fetchedAt))
        if (write != null) listWrite.commitIfLatest(write) { files.write(LIST_CACHE, body, fetchedAt).orThrow("Announcement cache write") }
        discoverForDisplay(page.announcements)
        return page
    }

    fun loadCachedFilterOptions(): AnnouncementFilterOptions {
        val stored = runCatching { files.readOrNull(OPTIONS_CACHE) }.getOrNull() ?: return AnnouncementFilterOptions()
        return runCatching { PublicAnnouncementParser.parseFilterOptions(stored.text) }.getOrElse { AnnouncementFilterOptions() }
    }

    suspend fun loadFilterOptions(): AnnouncementFilterOptions {
        val write = optionsWrite.begin()
        val body = client.fetchFilterOptionsBody()
        val options = PublicAnnouncementParser.parseFilterOptions(body)
        optionsWrite.commitIfLatest(write) { files.write(OPTIONS_CACHE, body, now()).orThrow("Announcement cache write") }
        return options
    }

    suspend fun loadDetail(id: String, categoryHint: String = ""): SchoolAnnouncementDetail = client.fetchDetail(id, categoryHint)

    // Read state. An announcement published before the first launch starts as read.

    private fun readState(): ReadState {
        files.readOrNull(READ_STATE)?.let { stored ->
            runCatching { storeJson.decodeFromString(ReadState.serializer(), stored.text) }.getOrNull()?.let { return it }
        }
        return ReadState(schoolDateTimeAt(now()).datePart().toString()).also(::writeReadState)
    }

    private fun writeReadState(state: ReadState) {
        files.write(READ_STATE, storeJson.encodeToString(ReadState.serializer(), state), now()).orThrow("Read state write")
    }

    suspend fun unreadIds(): Set<String> = stateLock.withLock { readState().read.filterValues { !it }.keys }

    suspend fun discover(announcements: List<SchoolAnnouncement>) = stateLock.withLock {
        val state = readState()
        val cutoff = parseLocalDate(state.cutoff)
        val discovered = announcements.distinctBy(SchoolAnnouncement::id)
            .filter { it.id.isNotBlank() && it.id !in state.read && startsUnread(it.date, cutoff) }
        if (discovered.isNotEmpty()) writeReadState(state.copy(read = state.read + discovered.associate { it.id to false }))
    }

    /** Public content stays available when reading-state storage is temporarily unavailable. */
    private suspend fun discoverForDisplay(announcements: List<SchoolAnnouncement>) {
        try {
            discover(announcements)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    suspend fun markRead(id: String) {
        require(id.isNotBlank())
        stateLock.withLock {
            val state = readState()
            writeReadState(state.copy(read = state.read + (id to true)))
        }
    }

    suspend fun markAllRead(ids: Set<String>) = stateLock.withLock {
        val state = readState()
        writeReadState(state.copy(read = state.read + ids.filter { state.read[it] == false }.associateWith { true }))
    }

    suspend fun bookmarks(): List<SchoolAnnouncement> = stateLock.withLock { storedBookmarks() }

    private fun storedBookmarks(): List<SchoolAnnouncement> {
        val stored = files.readOrNull(BOOKMARKS) ?: return emptyList()
        return runCatching { storeJson.decodeFromString<List<Bookmark>>(stored.text) }.getOrDefault(emptyList()).map {
            SchoolAnnouncement(it.id, it.title, it.date, it.category, it.unit, it.issuer, isPinned = false, it.contentType, it.externalUrl)
        }
    }

    /** Returns true when the announcement is bookmarked after the call. */
    suspend fun toggleBookmark(announcement: SchoolAnnouncement): Boolean = stateLock.withLock {
        val current = storedBookmarks()
        val exists = current.any { it.id == announcement.id }
        val next = if (exists) current.filterNot { it.id == announcement.id } else listOf(announcement) + current
        val encoded = storeJson.encodeToString(
            next.take(MAX_BOOKMARKS).map { Bookmark(it.id, it.title, it.date, it.category, it.unit, it.issuer, it.contentType, it.externalUrl) },
        )
        files.write(BOOKMARKS, encoded, now()).orThrow("Bookmark write")
        !exists
    }

    private fun startsUnread(date: String, cutoff: LocalDate): Boolean {
        val published = runCatching {
            parseLocalDate(date.trim().substringBefore(' ').substringBefore('T').replace('/', '-'))
        }.getOrNull()
        return published == null || published >= cutoff
    }

    internal companion object {
        private const val LIST_CACHE = "${FileStore.PUBLIC}/school-announcements.json"
        private const val OPTIONS_CACHE = "${FileStore.PUBLIC}/school-announcement-units.html"
        const val READ_STATE = "${FileStore.SETTINGS}/announcement-read.json"
        const val BOOKMARKS = "${FileStore.SETTINGS}/announcement-bookmarks.json"
        private const val MAX_BOOKMARKS = 500
    }
}

/** Public school calendar with a six-hour cache. */
class CalendarRepository(
    private val client: PublicCalendarClient,
    private val files: FileStore,
    private val now: () -> Long,
) {
    private val write = LatestWrite()

    private class Parsed(val text: String, val modifiedAtEpochMillis: Long, val snapshot: SchoolCalendarSnapshot)

    /** The last parsed cache file, so a cache hit neither rereads nor reparses an unchanged feed twice. */
    private val parsed = AtomicReference<Parsed?>(null)

    fun loadCached(): SchoolCalendarSnapshot? = files.readOrNull(CACHE)?.let(::parseCached)

    private fun parseCached(stored: StoredText): SchoolCalendarSnapshot? {
        if (stored.text.length > PublicCalendarClient.MAX_FEED_BYTES) return null
        val fetchedAt = instantOfEpochMilli(stored.modifiedAtEpochMillis)
        parsed.load()?.takeIf { it.modifiedAtEpochMillis == stored.modifiedAtEpochMillis && it.text == stored.text }?.let { return it.snapshot }
        return runCatching { SchoolCalendarSnapshot(SchoolCalendarIcs.parse(stored.text), fetchedAt) }.getOrNull()
            ?.also { parsed.store(Parsed(stored.text, stored.modifiedAtEpochMillis, it)) }
    }

    suspend fun load(forceRefresh: Boolean): SchoolCalendarSnapshot {
        val startedAt = now()
        if (!forceRefresh) {
            val stored = files.readOrNull(CACHE)
            if (stored != null && startedAt - stored.modifiedAtEpochMillis in 0..CACHE_MAX_AGE_MILLIS) parseCached(stored)?.let { return it }
        }
        val started = write.begin()
        val body = client.fetchFeedBody()
        val feed = SchoolCalendarIcs.parse(body)
        write.commitIfLatest(started) { files.write(CACHE, body, startedAt).orThrow("Calendar cache write") }
        return SchoolCalendarSnapshot(feed, instantOfEpochMilli(startedAt))
    }

    private companion object {
        const val CACHE = "${FileStore.PUBLIC}/school-calendar.ics"
        const val CACHE_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}

/** Result of one weather load: what to show and why it may be incomplete. */
class WeatherLoad(
    val snapshot: WeatherSnapshot?,
    val needsCwaApiKey: Boolean = false,
    val failed: Boolean = false,
    /** True when [snapshot] is fresh enough that no request was made. */
    val fromFreshCache: Boolean = false,
)

/**
 * Weather with separate caches per source and location (fresh for 30 minutes, usable for 6 hours).
 * The Central Weather Administration key lives only in [SecureStore].
 */
class WeatherRepository(
    private val client: PublicWeatherClient,
    private val files: FileStore,
    private val secure: SecureStore,
    private val now: () -> Long,
) {
    private val writes = HashMap<String, LatestWrite>()
    private val writesLock = Mutex()

    /** A cached snapshot that is still usable, for rendering before [load] returns. */
    fun cached(source: WeatherSource, location: WeatherLocation = WeatherLocation.SCHOOL): WeatherSnapshot? {
        WeatherParser.requireSupported(source, location)
        val stored = runCatching { files.readOrNull(cachePath(source, location)) }.getOrNull() ?: return null
        if (now() - stored.modifiedAtEpochMillis !in 0..MAX_STALE_AGE_MILLIS) return null
        return runCatching { WeatherParser.parse(source, stored.text, instantOfEpochMilli(stored.modifiedAtEpochMillis), location) }.getOrNull()
    }

    suspend fun load(
        source: WeatherSource,
        location: WeatherLocation = WeatherLocation.SCHOOL,
        forceRefresh: Boolean = false,
    ): WeatherLoad {
        val usable = cached(source, location)
        if (usable != null && !forceRefresh && now() - usable.fetchedAtMillis() <= FRESH_AGE_MILLIS) {
            return WeatherLoad(usable, fromFreshCache = true)
        }
        val key = if (source == WeatherSource.CWA) {
            try {
                secure.readOrNull(CWA_KEY)?.takeIf(String::isNotBlank)
            } catch (_: StorageException) {
                return WeatherLoad(usable, failed = true)
            } ?: return WeatherLoad(usable, needsCwaApiKey = true)
        } else null
        val path = cachePath(source, location)
        val write = writesLock.withLock { writes.getOrPut(path) { LatestWrite() } }
        val started = write.begin()
        return try {
            val raw = client.fetchBody(source, location, key)
            val fetchedAt = now()
            val snapshot = WeatherParser.parse(source, raw, instantOfEpochMilli(fetchedAt), location)
            write.commitIfLatest(started) { files.write(path, raw, fetchedAt).orThrow("Weather cache write") }
            WeatherLoad(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WeatherLoad(usable, failed = true)
        }
    }

    fun hasCwaKey(): Boolean = secure.readOrNull(CWA_KEY)?.isNotBlank() == true

    /** Validates against the live API before storing; only a [CwaKeyValidation.VALID] key is kept. */
    suspend fun saveCwaKey(rawKey: String): CwaKeyValidation {
        val result = client.validateCwaKey(rawKey)
        if (result == CwaKeyValidation.VALID) secure.write(CWA_KEY, validateCwaApiKey(rawKey)).orThrow("Credential write")
        return result
    }

    fun clearCwaKey() {
        secure.delete(CWA_KEY).orThrow("Credential delete")
    }

    private fun WeatherSnapshot.fetchedAtMillis(): Long = fetchedAt.epochMillis()

    private fun cachePath(source: WeatherSource, location: WeatherLocation): String {
        if (location == WeatherLocation.SCHOOL) {
            return when (source) {
                WeatherSource.OPEN_METEO -> "${FileStore.PUBLIC}/weather-open-meteo-clhs.json"
                WeatherSource.CWA -> "${FileStore.PUBLIC}/weather-cwa-zhongli-district.json"
            }
        }
        val identity = "${source.ordinal}|${location.latitude}|${location.longitude}|${location.cwaDataset}|${location.cwaLocationName}"
        return "${FileStore.PUBLIC}/weather-${hashedFileKey(identity)}.json"
    }

    internal companion object {
        const val CWA_KEY = "weather.cwa-api-key.v1"
        const val FRESH_AGE_MILLIS = 30 * 60 * 1000L
        const val MAX_STALE_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}

/** One station's availability, or the reason it is missing. */
class YouBikeStationLoad(val stationId: String, val snapshot: YouBikeSnapshot?, val failed: Boolean)

/** YouBike availability. The feed is kept in memory only (one minute for status, five for search). */
class YouBikeRepository(private val client: PublicYouBikeClient, private val now: () -> Long) {
    private class Feed(val raw: String, val fetchedAt: Long)

    private val feed = AtomicReference<Feed?>(null)

    private suspend fun feed(maxAgeMillis: Long): Feed {
        feed.load()?.takeIf { now() - it.fetchedAt in 0..maxAgeMillis }?.let { return it }
        val raw = client.fetchFeedBody()
        return Feed(raw, now()).also(feed::store)
    }

    /** Throws when the feed itself is unavailable; a single bad station only fails that station. */
    suspend fun load(stations: List<YouBikeStation>, forceRefresh: Boolean = false): List<YouBikeStationLoad> {
        val current = feed(if (forceRefresh) -1L else STATUS_MAX_AGE_MILLIS)
        val rows = YouBikeFeed.stations(current.raw)
        return stations.distinctBy { it.id }.map { station ->
            val snapshot = runCatching {
                YouBikeFeed.snapshot(requireNotNull(rows[station.id]), instantOfEpochMilli(current.fetchedAt))
            }.getOrNull()
            YouBikeStationLoad(station.id, snapshot, failed = snapshot == null)
        }
    }

    suspend fun searchStations(query: String): List<YouBikeStation> {
        require(query.trim().length <= 120)
        if (query.isBlank()) return emptyList()
        return YouBikeFeed.search(feed(SEARCH_MAX_AGE_MILLIS).raw, query)
    }

    private companion object {
        const val STATUS_MAX_AGE_MILLIS = 60_000L
        const val SEARCH_MAX_AGE_MILLIS = 5 * 60_000L
    }
}
