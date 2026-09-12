package com.clhs.score.data

import biweekly.Biweekly
import biweekly.component.VEvent
import biweekly.util.ICalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

const val SCHOOL_CALENDAR_ICS_URL =
    "https://calendar.google.com/calendar/ical/document%40clhs.tyc.edu.tw/public/basic.ics"
const val SCHOOL_CALENDAR_WEB_URL =
    "https://calendar.google.com/calendar/u/0/r?cid=document@clhs.tyc.edu.tw"
const val SCHOOL_CALENDAR_SEARCH_MAX_LENGTH = 100

data class SchoolCalendarEvent(
    val id: String,
    val title: String,
    val start: LocalDateTime,
    val endExclusive: LocalDateTime,
    val isAllDay: Boolean,
    val location: String? = null,
)

data class SchoolCalendarFeed(
    val events: List<SchoolCalendarEvent>,
    val skippedRecurringEvents: Int = 0,
)

data class SchoolCalendarSnapshot(
    val feed: SchoolCalendarFeed,
    val fetchedAt: Instant,
)

class NetworkSchoolCalendarRepository(
    cacheDirectory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build(),
    private val feedUrl: String = SCHOOL_CALENDAR_ICS_URL,
    private val nowProvider: () -> Instant = Instant::now,
) {
    private val cache = LatestCacheFile(cacheDirectory, CACHE_FILE_NAME)
    private val cacheFile = cache.file

    suspend fun loadCached(): SchoolCalendarSnapshot? = withContext(Dispatchers.IO) {
        readCache()
    }

    suspend fun load(forceRefresh: Boolean): SchoolCalendarSnapshot {
        val now = nowProvider()
        val cached = runInterruptibleHttp { readCache() }
        if (!forceRefresh && cached != null &&
            Duration.between(cached.fetchedAt, now).let { !it.isNegative && it <= CACHE_MAX_AGE }
        ) {
            return cached
        }
        val cacheGeneration = cache.beginWrite()
        val requestContext = currentCoroutineContext()

        val request = Request.Builder().url(feedUrl).get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).executeCancellable { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) {
                    throw IOException("Calendar request failed with HTTP ${response.code}")
                }
                val body = response.body
                if (body.contentLength() > MAX_FEED_BYTES) {
                    throw IOException("Calendar response is too large")
                }
                val bytes = readLimited(body.source())
                val feed = SchoolCalendarIcsParser.parse(bytes.toString(Charsets.UTF_8))
                currentCoroutineContext().ensureActive()
                cache.commit(bytes, now.toEpochMilli(), cacheGeneration, requestContext)
                SchoolCalendarSnapshot(feed = feed, fetchedAt = now)
            }
        }
    }

    private fun readCache(): SchoolCalendarSnapshot? {
        if (!cacheFile.isFile || cacheFile.length() !in 1..MAX_FEED_BYTES) return null
        return runCatching {
            SchoolCalendarSnapshot(
                feed = SchoolCalendarIcsParser.parse(cacheFile.readText(Charsets.UTF_8)),
                fetchedAt = Instant.ofEpochMilli(cacheFile.lastModified()),
            )
        }.getOrNull()
    }

    private fun readLimited(source: okio.BufferedSource): ByteArray {
        val buffer = Buffer()
        var total = 0L
        while (total <= MAX_FEED_BYTES) {
            val read = source.read(buffer, minOf(8_192L, MAX_FEED_BYTES + 1L - total))
            if (read == -1L) break
            total += read
        }
        if (total > MAX_FEED_BYTES) throw IOException("Calendar response is too large")
        return buffer.readByteArray()
    }

    private companion object {
        const val CACHE_FILE_NAME = "school-calendar.ics"
        const val MAX_FEED_BYTES = 5L * 1024 * 1024
        val CACHE_MAX_AGE: Duration = Duration.ofHours(6)
    }
}

internal object SchoolCalendarIcsParser {
    private val schoolZone = ZoneId.of("Asia/Taipei")

    fun parse(source: String): SchoolCalendarFeed {
        val calendar = runCatching { Biweekly.parse(source.removePrefix("\uFEFF")).first() }
            .getOrElse { error -> throw IOException("Invalid calendar data", error) }
            ?: throw IOException("Invalid calendar data")
        val (recurringEvents, singleEvents) = calendar.events.partition { it.isRecurring() }

        return SchoolCalendarFeed(
            events = singleEvents.mapNotNull { it.toSchoolCalendarEvent() }
                .distinctBy(SchoolCalendarEvent::id)
                .sortedBy(SchoolCalendarEvent::start),
            skippedRecurringEvents = recurringEvents.size,
        )
    }

    private fun VEvent.isRecurring(): Boolean =
        recurrenceRule != null ||
            exceptionRules.isNotEmpty() ||
            recurrenceDates.isNotEmpty() ||
            exceptionDates.isNotEmpty()

    private fun VEvent.toSchoolCalendarEvent(): SchoolCalendarEvent? {
        if (status?.isCancelled == true) return null
        val startValue = dateStart?.value ?: return null
        val isAllDay = !startValue.hasTime()
        val start = startValue.toLocalDateTime()
        val parsedEnd = dateEnd?.value?.toLocalDateTime()
        val durationEnd = duration?.value?.toMillis()?.let { millis ->
            start.plus(Duration.ofMillis(millis))
        }
        val defaultEnd = if (isAllDay) start.plusDays(1) else start
        val endExclusive = (parsedEnd ?: durationEnd)?.takeIf { !it.isBefore(start) } ?: defaultEnd
        val title = summary?.value?.takeIf(String::isNotBlank) ?: "未命名活動"
        return SchoolCalendarEvent(
            id = uid?.value?.takeIf(String::isNotBlank) ?: "$start-$title",
            title = title,
            start = start,
            endExclusive = endExclusive,
            isAllDay = isAllDay,
            location = location?.value?.takeIf(String::isNotBlank),
        )
    }

    private fun ICalDate.toLocalDateTime(): LocalDateTime = if (hasTime()) {
        toInstant().atZone(schoolZone).toLocalDateTime()
    } else {
        Calendar.getInstance(TimeZone.getDefault()).run {
            time = this@toLocalDateTime
            LocalDate.of(get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH)).atStartOfDay()
        }
    }
}
