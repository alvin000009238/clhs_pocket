package com.clhs.score.domain.overview

import com.clhs.score.data.AuthenticatedSession
import com.clhs.score.data.GradeReminderState
import com.clhs.score.data.NetworkSchoolAnnouncementsRepository
import com.clhs.score.data.NetworkSchoolCalendarRepository
import com.clhs.score.data.ScheduleClassOption
import com.clhs.score.data.ScheduleReport
import com.clhs.score.data.ScheduleRepository
import com.clhs.score.data.ScheduleScope
import com.clhs.score.data.ScheduleYearTermOption
import com.clhs.score.data.SchoolJson
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherRepository
import com.clhs.score.data.WeatherConfiguration
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeRepository
import com.clhs.score.data.YouBikeStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Resource/lifecycle contracts of the instance-based coordinator path, without UI or live APIs. */
class OverviewWidgetCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val instant = Instant.parse("2026-10-02T04:43:03Z")
    private val publicRequests = AtomicInteger()
    private val scheduleReads = AtomicInteger()
    private val reminderSubscriptions = AtomicInteger()

    @Test fun returningHomeKeepsLoadedWeatherWhilePollingIsPaused() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather(28)))
            val config = OverviewWidgetConfig.Weather()
            val visible = MutableStateFlow(true)
            val observation = record(coordinator(weatherRepository = weatherRepository(server)),
                flowOf(listOf(OverviewWidgetInstance.create("weather", config))), visible)
            try {
                observation.await { it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Available }
                visible.value = false
                delay(100)
                val paused = observation.state.value
                assertEquals(28, (paused.weatherLocations[config.resourceKey()] as OverviewWeatherState.Available).snapshot.temperatureCelsius)
                visible.value = true
                observation.await { it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Available }
                assertEquals(1, server.requestCount)
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun refreshingCompletedSourcesDoesNotReturnToInitialSkeletons() = runBlocking<Unit> {
        val refresh = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var initialLoading = false
        val job = launch(Dispatchers.Default) {
            coordinator().observe(refreshes = refresh).collect { state ->
                if (started.isCompleted && state.isRefreshing) {
                    initialLoading = initialLoading || state.calendarLoading || state.announcementsLoading
                    release.complete(Unit)
                }
                if (!state.calendarLoading && !state.announcementsLoading && !state.isRefreshing) started.complete(Unit)
            }
        }
        try {
            withTimeout(10_000) { started.await() }
            refresh.emit(Unit)
            withTimeout(10_000) { release.await() }
            assertFalse("A background refresh must preserve the loaded/empty presentation", initialLoading)
        } finally { job.cancelAndJoin() }
    }

    @Test fun duplicateWeatherInstancesShareOneResourceAndDoNotFetchDisabledLocations() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather(28)))
            val configuration = OverviewWidgetConfig.Weather()
            val first = OverviewWidgetInstance.create("weather", configuration)
            val second = OverviewWidgetInstance.create("weather", configuration).copy(size = OverviewWidgetSize(4, 2))
            val disabled = OverviewWidgetInstance.create("weather", OverviewWidgetConfig.Weather(london)).copy(enabled = false)
            val observation = record(coordinator(weatherRepository = weatherRepository(server)), flowOf(listOf(first, second, disabled)))
            try {
                val loaded = observation.await { it.weatherLocations[configuration.resourceKey()] is OverviewWeatherState.Available }
                assertEquals(setOf(configuration.resourceKey()), loaded.weatherLocations.keys)
                assertEquals(28, (loaded.weatherLocations.getValue(configuration.resourceKey()) as OverviewWeatherState.Available).snapshot.temperatureCelsius)
                assertEquals(1, server.requestCount)
                val request = requireNotNull(server.takeRequest(10, TimeUnit.SECONDS)).requestUrl!!
                assertEquals(WeatherLocation.SCHOOL.latitude.toString(), request.queryParameter("latitude"))
                assertEquals("Asia/Taipei", request.queryParameter("timezone"))
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun backgroundVisibilityCancelsPendingWeatherAndForegroundRestartsWithoutFailure() = runBlocking<Unit> {
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody(weather(28)))
            val configuration = OverviewWidgetConfig.Weather()
            val visible = MutableStateFlow(false)
            val observation = record(coordinator(weatherRepository = weatherRepository(server, client)),
                flowOf(listOf(OverviewWidgetInstance.create("weather", configuration))), visible)
            try {
                observation.await { !it.isInitialLoading }
                assertEquals("No legacy or widget weather request while backgrounded", 0, server.requestCount)
                visible.value = true
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                visible.value = false
                assertTrue("Route/background transition must cancel the actual HTTP call", await(cancelled))
                visible.value = true
                val resumed = observation.await { it.weatherLocations[configuration.resourceKey()] is OverviewWeatherState.Available }
                assertFalse((resumed.weatherLocations.getValue(configuration.resourceKey()) as OverviewWeatherState.Available).failed)
                assertFalse(resumed.hasPartialFailure)
                assertEquals(2, server.requestCount)
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun changedDraftCancelsOldLocationAndRestoringCommittedLayoutDropsDraftResources() = runBlocking<Unit> {
        val cancelled = CountDownLatch(1)
        val schoolRequests = AtomicInteger()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).build()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val isSchool = request.requestUrl?.queryParameter("latitude") == WeatherLocation.SCHOOL.latitude.toString()
                    if (isSchool && schoolRequests.incrementAndGet() == 1) return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    return MockResponse().setBody(weather(if (isSchool) 28 else 16))
                }
            }
            val committed = OverviewWidgetConfig.Weather()
            val draft = OverviewWidgetConfig.Weather(WeatherLocation("taipei", "臺北", 25.033, 121.5654, "Asia/Taipei", "TW"))
            val instance = OverviewWidgetInstance.create("weather", committed)
            val widgets = MutableStateFlow(listOf(instance))
            val observation = record(coordinator(weatherRepository = weatherRepository(server, client)), widgets)
            try {
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                widgets.value = listOf(instance.withConfig(draft))
                val preview = observation.await { it.weatherLocations[draft.resourceKey()] is OverviewWeatherState.Available }
                assertTrue("Draft replacement must cancel its previous location", await(cancelled))
                assertEquals(setOf(draft.resourceKey()), preview.weatherLocations.keys)
                assertEquals("Asia/Taipei", (preview.weatherLocations.getValue(draft.resourceKey()) as OverviewWeatherState.Available).snapshot.timezoneId)
                widgets.value = listOf(instance)
                val restored = observation.await { it.weatherLocations[committed.resourceKey()] is OverviewWeatherState.Available }
                assertEquals(setOf(committed.resourceKey()), restored.weatherLocations.keys)
                assertEquals(28, (restored.weatherLocations.getValue(committed.resourceKey()) as OverviewWeatherState.Available).snapshot.temperatureCelsius)
                assertEquals(3, server.requestCount)
                assertEquals(2, schoolRequests.get())
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun weatherRetryBypassesFreshCacheWithoutRefreshingOtherPublicSources() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather(28)))
            server.enqueue(MockResponse().setBody(weather(31)).setBodyDelay(400, TimeUnit.MILLISECONDS))
            val configuration = OverviewWidgetConfig.Weather()
            val retry = MutableSharedFlow<Unit>()
            val observation = record(coordinator(weatherRepository = weatherRepository(server)),
                flowOf(listOf(OverviewWidgetInstance.create("weather", configuration))), refreshes = retry)
            try {
                observation.await { !it.isInitialLoading && (it.weatherLocations[configuration.resourceKey()] as? OverviewWeatherState.Available)?.snapshot?.temperatureCelsius == 28 }
                withTimeout(10_000) { retry.subscriptionCount.first { it > 0 } }
                assertTrue(observation.state.value.manuallyRefreshingWeatherKeys.isEmpty())
                val before = publicRequests.get()
                retry.emit(Unit)
                val refreshing = observation.await { configuration.resourceKey() in it.manuallyRefreshingWeatherKeys }
                assertEquals(28, (refreshing.weatherLocations[configuration.resourceKey()] as? OverviewWeatherState.Available)?.snapshot?.temperatureCelsius)
                observation.await { (it.weatherLocations[configuration.resourceKey()] as? OverviewWeatherState.Available)?.snapshot?.temperatureCelsius == 31 }
                assertTrue(observation.state.value.manuallyRefreshingWeatherKeys.isEmpty())
                assertEquals(2, server.requestCount)
                assertEquals("Weather retry must not re-fetch announcements or calendar", before, publicRequests.get())
                assertEquals(0, scheduleReads.get())
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun multipleYouBikeStationsShareOneFeedAndGuestNeverSubscribesToPrivateData() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(twoStationFeed()))
            val stations = listOf(YouBikeStation.SCHOOL, YouBikeStation("second", "第二站"), YouBikeStation("missing", "已撤站"))
            val widgets = stations.map { OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike(it)) } +
                OverviewWidgetInstance.create("youbike", OverviewWidgetConfig.YouBike(YouBikeStation("disabled", "隱藏站"))).copy(enabled = false)
            val observation = record(coordinator(youBikeRepository = YouBikeRepository(endpoint = server.url("/feed"), now = { instant })), flowOf(widgets))
            try {
                val loaded = observation.await { !it.isInitialLoading &&
                    it.youBikeStations[YouBikeStation.SCHOOL.id]?.snapshot != null &&
                    it.youBikeStations["second"]?.snapshot != null && it.youBikeStations["missing"]?.failed == true }
                assertEquals(stations.map { it.id }.toSet(), loaded.youBikeStations.keys)
                assertEquals(4, loaded.youBikeStations.getValue(YouBikeStation.SCHOOL.id).snapshot?.availableBikes)
                assertEquals(6, loaded.youBikeStations.getValue("second").snapshot?.availableBikes)
                assertNull(loaded.youBikeStations.getValue("missing").snapshot)
                assertFalse(loaded.youBikeStations.getValue(YouBikeStation.SCHOOL.id).failed)
                assertFalse(loaded.youBikeStations.getValue("second").failed)
                assertEquals(1, server.requestCount)
                assertNull(loaded.hero)
                // Raw aggregation includes the generic schedule-setup placeholder;
                // the existing auth-generation projection removes protected UI items.
                val guest = loaded.forGeneration(null)
                assertTrue(guest.items.none { it.kind.isPrivate() })
                assertEquals(loaded.youBikeStations, guest.youBikeStations)
                assertTrue(loaded.hasPartialFailure)
                assertEquals(0, scheduleReads.get())
                assertEquals(0, reminderSubscriptions.get())
            } finally { observation.job.cancelAndJoin() }
        }
    }

    @Test fun widgetWeatherConfigurationIoFailureRetainsSnapshotAndRecovers() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather(28)))
            val fail = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val settings = flow {
                emit(WeatherConfiguration(WeatherSource.OPEN_METEO))
                if (attempts.incrementAndGet() == 1) { fail.await(); throw IOException("temporary settings failure") }
                awaitCancellation()
            }
            val config = OverviewWidgetConfig.Weather()
            val observation = record(coordinator(weatherRepository = weatherRepository(server), configuration = settings),
                flowOf(listOf(OverviewWidgetInstance.create("weather", config))))
            try {
                observation.await { it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Available }
                fail.complete(Unit)
                val failed = observation.await { it.hasPartialFailure }
                val retained = failed.weatherLocations[config.resourceKey()] as? OverviewWeatherState.Available
                assertEquals(28, retained?.snapshot?.temperatureCelsius)
                assertTrue(retained?.failed == true)
                observation.await { attempts.get() >= 2 && !it.hasPartialFailure &&
                    it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Available }
                assertEquals(1, server.requestCount)
            } finally { fail.complete(Unit); observation.job.cancelAndJoin() }
        }
    }

    @Test fun widgetWeatherFailureBeforeFirstSnapshotIsUnavailableAndRetries() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather(28)))
            val attempts = AtomicInteger()
            val settings = flow {
                if (attempts.incrementAndGet() == 1) throw IOException("temporary settings failure")
                emit(WeatherConfiguration(WeatherSource.OPEN_METEO))
                awaitCancellation()
            }
            val config = OverviewWidgetConfig.Weather()
            val observation = record(coordinator(weatherRepository = weatherRepository(server), configuration = settings),
                flowOf(listOf(OverviewWidgetInstance.create("weather", config))))
            try {
                observation.await { it.hasPartialFailure && it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Unavailable }
                observation.await { !it.hasPartialFailure && it.weatherLocations[config.resourceKey()] is OverviewWeatherState.Available }
                assertEquals(2, attempts.get())
            } finally { observation.job.cancelAndJoin() }
        }
    }

    private val london = WeatherLocation("london", "倫敦", 51.5074, -0.1278, "Europe/London", "GB")

    private fun weatherRepository(server: MockWebServer, client: OkHttpClient = OkHttpClient()) = WeatherRepository(
        cacheDirectory = temporaryFolder.newFolder(), client = client, now = { instant }, openMeteoEndpoint = server.url("/"),
    )

    private fun coordinator(weatherRepository: WeatherRepository? = null, youBikeRepository: YouBikeRepository? = null,
        configuration: Flow<WeatherConfiguration> = flowOf(WeatherConfiguration(WeatherSource.OPEN_METEO))): OverviewCoordinator {
        val directory = temporaryFolder.newFolder()
        val publicClient = OkHttpClient.Builder().addInterceptor { chain ->
            publicRequests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (chain.request().url.encodedPath.endsWith("ics")) ICS else ANNOUNCEMENTS).toResponseBody()).build()
        }.build()
        val schedule = object : ScheduleRepository {
            override suspend fun getScheduleYears(): List<ScheduleYearTermOption> = error("Guest must not read school data")
            override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> = error("Guest must not read school data")
            override suspend fun getLatestSchedule(): ScheduleReport? {
                scheduleReads.incrementAndGet()
                error("Guest must not subscribe to school data")
            }
            override suspend fun fetchSchedule(yearValue: String, year: String, term: String, classNo: String,
                scope: ScheduleScope, targetDate: LocalDate, expectedSession: AuthenticatedSession?): ScheduleReport = error("Guest must not fetch school data")
        }
        return OverviewCoordinator(
            schedule, NetworkSchoolCalendarRepository(directory, publicClient, nowProvider = { instant }),
            NetworkSchoolAnnouncementsRepository(directory, publicClient, nowProvider = { instant }),
            reminderState = flow { reminderSubscriptions.incrementAndGet(); emit(GradeReminderState(studentNo = "private")); awaitCancellation() },
            weatherRepository = weatherRepository, weatherConfiguration = configuration,
            clock = Clock.fixed(instant, ZoneOffset.UTC), youBikeRepository = youBikeRepository,
        )
    }

    private fun CoroutineScope.record(coordinator: OverviewCoordinator, widgets: Flow<List<OverviewWidgetInstance>>,
        visible: Flow<Boolean> = flowOf(true), refreshes: Flow<Unit> = emptyFlow()): Observation {
        val state = MutableStateFlow(OverviewState())
        val job = launch(Dispatchers.Default) {
            coordinator.observe(session = null, clockEnabled = visible, widgets = widgets, weatherRefreshes = refreshes).collect { state.value = it }
        }
        return Observation(state, job)
    }

    private data class Observation(val state: MutableStateFlow<OverviewState>, val job: Job) {
        suspend fun await(predicate: (OverviewState) -> Boolean): OverviewState = withTimeout(10_000) { state.first(predicate) }
    }

    private suspend fun await(latch: CountDownLatch): Boolean = withContext(Dispatchers.IO) { latch.await(10, TimeUnit.SECONDS) }

    private fun weather(temperature: Int) = """{"current":{"time":"2026-10-02T12:43:03","temperature_2m":$temperature,"apparent_temperature":30,"weather_code":0},"hourly":{"time":["2026-10-02T13:00"],"temperature_2m":[$temperature],"weather_code":[0],"precipitation_probability":[20]}}"""

    private fun twoStationFeed(): String {
        val fixture = requireNotNull(javaClass.getResource("/youbike-clhs.txt")).readText()
        val first = SchoolJson.parseToJsonElement(fixture).jsonArray.single().jsonObject
        val second = JsonObject(first.toMutableMap().apply {
            put("station_no", JsonPrimitive("second")); put("name_tw", JsonPrimitive("第二站"))
            put("available_spaces", JsonPrimitive(6)); put("empty_spaces", JsonPrimitive(42))
            put("available_spaces_detail", JsonObject(mapOf("yb2" to JsonPrimitive(4), "eyb" to JsonPrimitive(2))))
        })
        return JsonArray(listOf(first, second)).toString()
    }

    private companion object {
        val ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event
            DTSTART:20261003T013000Z
            DTEND:20261003T023000Z
            SUMMARY:公開活動
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        const val ANNOUNCEMENTS = """[{"pageNum":0,"totalPages":1},{"newsId":"public","title":"公開公告","top":"1","time":"2026-10-02"}]"""
    }
}
