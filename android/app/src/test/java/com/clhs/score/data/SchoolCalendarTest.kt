package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class SchoolCalendarTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parserHandlesAllDayTimedFoldedAndUnsupportedEvents() {
        val feed = SchoolCalendarIcsParser.parse(SAMPLE_ICS)

        assertEquals(2, feed.events.size)
        assertEquals(1, feed.skippedRecurringEvents)

        val allDay = feed.events[0]
        assertEquals("段考,第一天延長", allDay.title)
        assertEquals(LocalDateTime.of(2026, 8, 10, 0, 0), allDay.start)
        assertEquals(LocalDateTime.of(2026, 8, 12, 0, 0), allDay.endExclusive)
        assertEquals(true, allDay.isAllDay)

        val timed = feed.events[1]
        assertEquals(LocalDateTime.of(2026, 8, 12, 9, 0), timed.start)
        assertEquals(LocalDateTime.of(2026, 8, 12, 10, 30), timed.endExclusive)
        assertEquals("圖書館;二樓", timed.location)
    }

    @Test
    fun allDayDatesDoNotDependOnDeviceTimeZone() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            listOf("Asia/Tokyo", "UTC", "America/Los_Angeles").forEach { zoneId ->
                TimeZone.setDefault(TimeZone.getTimeZone(zoneId))

                val allDay = SchoolCalendarIcsParser.parse(SAMPLE_ICS).events.first()

                assertEquals(zoneId, LocalDateTime.of(2026, 8, 10, 0, 0), allDay.start)
                assertEquals(zoneId, LocalDateTime.of(2026, 8, 12, 0, 0), allDay.endExclusive)
            }
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun repositoryCachesFeedAndNeverStoresServerCookies() = runTest {
        server.enqueue(calendarResponse().setHeader("Set-Cookie", "session=must-not-return"))
        server.enqueue(calendarResponse())
        val cacheDirectory = Files.createTempDirectory("school-calendar-test").toFile()
        val fetchedAt = Instant.parse("2026-08-09T00:00:00Z")
        val repository = NetworkSchoolCalendarRepository(
            cacheDirectory = cacheDirectory,
            feedUrl = server.url("/calendar.ics").toString(),
            nowProvider = { fetchedAt },
        )

        try {
            val network = repository.load(forceRefresh = true)
            val cached = repository.loadCached()
            repository.load(forceRefresh = true)

            assertEquals(network.feed, cached?.feed)
            assertEquals(fetchedAt, cached?.fetchedAt)
            assertNull(server.awaitRequest().getHeader("Cookie"))
            assertNull(server.awaitRequest().getHeader("Cookie"))
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun repeatedAndConcurrentCacheLoadsReuseDecodedFeed() = runTest {
        val fetchedAt = Instant.parse("2026-08-09T00:00:00Z")
        val cacheFile = File(temporaryFolder.root, "school-calendar.ics")
        cacheFile.writeText(SAMPLE_ICS)
        assertTrue(cacheFile.setLastModified(fetchedAt.toEpochMilli()))
        val repository = NetworkSchoolCalendarRepository(
            cacheDirectory = temporaryFolder.root,
            feedUrl = server.url("/calendar.ics").toString(),
            nowProvider = { fetchedAt },
        )

        val snapshots = List(8) {
            async(Dispatchers.Default) { checkNotNull(repository.loadCached()) }
        }.awaitAll()
        val feed = snapshots.first().feed

        snapshots.forEach { assertSame(feed, it.feed) }
        assertSame(feed, repository.load(forceRefresh = false).feed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun decodedCachePreservesFreshnessBoundaryAndForcedRefresh() = runTest {
        val fetchedAt = Instant.parse("2026-08-09T00:00:00Z")
        var now = fetchedAt
        val repository = NetworkSchoolCalendarRepository(
            cacheDirectory = temporaryFolder.root,
            feedUrl = server.url("/calendar.ics").toString(),
            nowProvider = { now },
        )
        server.enqueue(calendarResponse())
        val original = repository.load(forceRefresh = false)

        now = fetchedAt.plusSeconds(6 * 60 * 60)
        assertEquals(original, repository.load(forceRefresh = false))
        assertEquals(1, server.requestCount)

        now = now.plusMillis(1)
        server.enqueue(calendarResponse().setBody(SAMPLE_ICS.replace("UID:all-day", "UID:new-day")))
        val refreshed = repository.load(forceRefresh = false)
        assertEquals("new-day", refreshed.feed.events.first().id)
        assertEquals(now, refreshed.fetchedAt)
        assertEquals(2, server.requestCount)

        now = now.plusSeconds(1)
        server.enqueue(calendarResponse())
        val forced = repository.load(forceRefresh = true)
        assertEquals(original.feed, forced.feed)
        assertEquals(now, forced.fetchedAt)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun sameSizeAndTimestampReplacementAndDeletionInvalidateDecodedCache() = runTest {
        val fetchedAt = Instant.parse("2026-08-09T00:00:00Z")
        val cacheFile = File(temporaryFolder.root, "school-calendar.ics")
        cacheFile.writeText(SAMPLE_ICS)
        assertTrue(cacheFile.setLastModified(fetchedAt.toEpochMilli()))
        val originalLength = cacheFile.length()
        val repository = NetworkSchoolCalendarRepository(
            cacheDirectory = temporaryFolder.root,
            feedUrl = server.url("/calendar.ics").toString(),
            nowProvider = { fetchedAt },
        )
        val original = checkNotNull(repository.loadCached())

        server.enqueue(calendarResponse().setBody(SAMPLE_ICS.replace("UID:all-day", "UID:new-day")))
        NetworkSchoolCalendarRepository(
            cacheDirectory = temporaryFolder.root,
            feedUrl = server.url("/calendar.ics").toString(),
            nowProvider = { fetchedAt },
        ).load(forceRefresh = true)

        assertEquals(originalLength, cacheFile.length())
        assertEquals(fetchedAt.toEpochMilli(), cacheFile.lastModified())
        assertEquals("new-day", repository.load(forceRefresh = false).feed.events.first().id)
        assertTrue(cacheFile.delete())
        assertNull(repository.loadCached())

        cacheFile.writeText(SAMPLE_ICS)
        assertTrue(cacheFile.setLastModified(fetchedAt.toEpochMilli()))
        assertEquals(original, repository.loadCached())
    }

    @Test
    fun cancellingCalendarLoadStopsTheBlockingHttpCall() = runBlocking {
        repeat(20) {
            server.enqueue(calendarResponse().setBodyDelay(5, TimeUnit.SECONDS))
            val cacheDirectory = Files.createTempDirectory("school-calendar-cancel-test").toFile()
            val repository = NetworkSchoolCalendarRepository(
                cacheDirectory = cacheDirectory,
                feedUrl = server.url("/calendar.ics").toString(),
            )

            try {
                val load = launch(Dispatchers.Default) { repository.load(forceRefresh = true) }
                assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                load.cancel()

                withTimeout(1.seconds) { load.join() }
                assertTrue(load.isCancelled)
            } finally {
                cacheDirectory.deleteRecursively()
            }
        }
    }

    private fun calendarResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/calendar; charset=utf-8")
        .setBody(SAMPLE_ICS)

    private companion object {
        val SAMPLE_ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            X-WR-TIMEZONE:Asia/Taipei
            BEGIN:VEVENT
            UID:all-day
            DTSTART;VALUE=DATE:20260810
            DTEND;VALUE=DATE:20260812
            SUMMARY:段考\,第一天
             延長
            END:VEVENT
            BEGIN:VEVENT
            UID:timed
            DTSTART:20260812T010000Z
            DTEND:20260812T023000Z
            SUMMARY:新生訓練
            LOCATION:圖書館\;二樓
            END:VEVENT
            BEGIN:VEVENT
            UID:cancelled
            DTSTART;VALUE=DATE:20260813
            STATUS:CANCELLED
            SUMMARY:取消活動
            END:VEVENT
            BEGIN:VEVENT
            UID:recurring
            DTSTART;VALUE=DATE:20260814
            RRULE:FREQ=WEEKLY
            SUMMARY:重複活動
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
    }
}
