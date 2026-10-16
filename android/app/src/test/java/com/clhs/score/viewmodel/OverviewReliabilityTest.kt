package com.clhs.score.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.clhs.score.data.*
import com.clhs.score.domain.overview.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewReliabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val viewModels = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()
    private val instant = Instant.parse("2026-08-24T00:30:00Z")
    private val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(1))
    private var session: AuthenticatedSession? = AuthenticatedSession("A", "test", emptyMap(), authorizationId = "A-authority")
    private val reminders = MutableStateFlow(GradeReminderState())
    private var report = report("A")
    private var readSchedule: suspend () -> CachedScheduleReport? = { CachedScheduleReport(report, instant.toEpochMilli()) }
    private var fetchScheduleReport: suspend () -> ScheduleReport = { error("fresh schedule must not fetch") }
    private val scheduleReads = AtomicInteger()
    private val publicRequests = AtomicInteger()
    private var dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    private var clock: Clock = Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() {
        viewModels.clear()
        runBlocking { viewModelJobs.joinAll() }
        Dispatchers.resetMain()
    }

    @Test fun repeatedWeatherIoFailureDoesNotStopOtherSourcesAndBacksOff() = runBlocking<Unit> {
        val scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        val attempts = AtomicInteger()
        val scheduleResult = CompletableDeferred<CachedScheduleReport?>()
        readSchedule = { scheduleResult.await() }
        val vm = viewModel(flow { attempts.incrementAndGet(); throw IOException("settings unavailable") })
        val public = withContext(Dispatchers.Default) {
            awaitState(vm) { it.hasPartialFailure && it.items.any { item -> item.kind == OverviewItemKind.Announcement } && it.calendarEvents.isNotEmpty() }
        }
        assertTrue(public.weather is OverviewWeatherState.Unavailable)
        assertEquals(1, attempts.get())
        scheduler.advanceTimeBy(999)
        scheduler.runCurrent()
        assertEquals("No retry before the first backoff", 1, attempts.get())
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(2, attempts.get())
        scheduler.advanceTimeBy(1_999)
        scheduler.runCurrent()
        assertEquals("The second backoff must increase", 2, attempts.get())
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertEquals(3, attempts.get())
        scheduleResult.complete(CachedScheduleReport(report, instant.toEpochMilli()))
        val loaded = withContext(Dispatchers.Default) { awaitState(vm) { it.hero?.courseName == "A" } }
        assertTrue(loaded.hasPartialFailure)
        assertEquals(2, publicRequests.get())
        viewModels.clear()
        scheduler.advanceTimeBy(30_000)
        scheduler.runCurrent()
        assertEquals("Cleared owners must stop retries", 3, attempts.get())
    }

    @Test fun weatherFailurePreservesSnapshotAndRecoveryClearsFailure() = runBlocking<Unit> {
        val fail = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val vm = viewModel(flow {
            emit(WeatherConfiguration(WeatherSource.OPEN_METEO))
            if (attempts.incrementAndGet() == 1) { fail.await(); throw IOException() }
            awaitCancellation()
        })
        val before = awaitState(vm) { it.weather is OverviewWeatherState.Available }
        fail.complete(Unit)
        val failed = awaitState(vm) { it.hasPartialFailure }
        assertEquals(before.weather, failed.weather)
        val recovered = awaitState(vm) { !it.hasPartialFailure && attempts.get() >= 2 }
        assertTrue(recovered.weather is OverviewWeatherState.Available)
    }

    @Test fun cancellationIsNotConvertedToWeatherFailureOrRetried() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val vm = viewModel(flow {
            attempts.incrementAndGet()
            try {
                emit(WeatherConfiguration(WeatherSource.OPEN_METEO))
                started.complete(Unit)
                awaitCancellation()
            } finally { cancelled.complete(Unit) }
        })
        withTimeout(10_000) { started.await() }
        awaitState(vm) { it.hero != null }
        viewModels.clear()
        withTimeout(10_000) { cancelled.await() }
        assertFalse(vm.uiState.value.hasPartialFailure)
        assertEquals(1, attempts.get())
    }

    @Test fun nonRecoverableWeatherConfigurationFailureIsIsolatedWithoutRetry() = runBlocking<Unit> {
        val scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        val attempts = AtomicInteger()
        val vm = viewModel(flow { attempts.incrementAndGet(); throw IllegalStateException("invalid configuration") })
        withContext(Dispatchers.Default) { awaitState(vm) { it.hasPartialFailure && it.hero != null && it.calendarEvents.isNotEmpty() } }
        scheduler.advanceTimeBy(60_000)
        scheduler.runCurrent()
        assertEquals(1, attempts.get())
        viewModels.clear()
    }

    @Test fun returningToOverviewKeepsContentWithoutRefreshIndicatorWhileCacheReadIsPending() = runBlocking<Unit> {
        val vm = viewModel()
        val before = awaitState(vm) { it.hero != null && it.items.any { item -> item.kind == OverviewItemKind.Announcement } }
        val pending = CompletableDeferred<CachedScheduleReport?>()
        val started = CompletableDeferred<Unit>()
        readSchedule = { started.complete(Unit); pending.await() }
        vm.setClockEnabled(true)
        withTimeout(10_000) { started.await() }
        assertEquals(before.hero, vm.uiState.value.hero)
        assertFalse(vm.uiState.value.isInitialLoading)
        assertNull("Reading an already loaded schedule cache is not a network refresh",
            withTimeoutOrNull(1_000) { vm.uiState.first { it.isScheduleRefreshing } })
        assertTrue(vm.uiState.value.items.any { it.kind == OverviewItemKind.Announcement })
        pending.complete(CachedScheduleReport(report("updated"), instant.toEpochMilli()))
        awaitState(vm) { it.hero?.courseName == "updated" }

        val fetchStarted = CompletableDeferred<Unit>()
        fetchScheduleReport = { fetchStarted.complete(Unit); awaitCancellation() }
        readSchedule = { CachedScheduleReport(report("updated").copy(scope = ScheduleScope.SEMESTER),
            instant.minusSeconds(7 * 3600).toEpochMilli()) }
        vm.refresh()
        withTimeout(10_000) { fetchStarted.await() }
        val refreshing = awaitState(vm) { it.isScheduleRefreshing }
        assertEquals("updated", refreshing.hero?.courseName)
        assertFalse(refreshing.isInitialLoading)
    }

    @Test fun sameAccountRefreshWithEmptyCacheKeepsExistingContent() = runBlocking<Unit> {
        val vm = viewModel()
        val before = awaitState(vm) { it.hero != null && it.items.any { item -> item.kind == OverviewItemKind.Announcement } }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        readSchedule = { started.complete(Unit); release.await(); null }
        try {
            vm.refresh()
            withTimeout(10_000) { started.await() }
            release.complete(Unit)
            delay(100)
            assertTrue(scheduleReads.get() >= 2)
            assertEquals(before.hero, vm.uiState.value.hero)
            assertTrue(vm.uiState.value.items.any { it.kind == OverviewItemKind.Announcement })
            assertFalse(vm.uiState.value.isInitialLoading)
        } finally {
            release.complete(Unit)
            viewModels.clear()
            yield()
        }
    }

    @Test fun logoutImmediatelyIsolatesPrivateContentAndGuestNeverReadsSchedule() = runBlocking<Unit> {
        reminders.value = reminder("A")
        val vm = viewModel()
        val before = awaitState(vm) { it.hero != null && it.items.any { item -> item.kind == OverviewItemKind.GradeUpdate } }
        session = null
        auth.value = AuthState.Guest
        // The render boundary is safe even before the auth collector is scheduled.
        assertNull(before.forGeneration(null).hero)
        assertTrue(before.forGeneration(null).items.none { it.kind.isPrivate() })
        assertNull(vm.uiState.value.hero)
        assertTrue(vm.uiState.value.items.none { it.kind.isPrivate() })
        val reads = scheduleReads.get()
        vm.refresh()
        val guest = awaitState(vm) { it.authGeneration == null && it.calendarEvents.isNotEmpty() && !it.isRefreshing }
        assertTrue(guest.items.none { it.kind.isPrivate() })
        assertEquals(reads, scheduleReads.get())
    }

    @Test fun lateAccountARequestCannotOverwriteBOrRestorePrivateDataForGuest() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        readSchedule = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            returned.complete(Unit)
            CachedScheduleReport(report("late A"), instant.toEpochMilli())
        }
        val vm = viewModel()
        try {
            withTimeout(10_000) { started.await() }
            readSchedule = { CachedScheduleReport(report("B"), instant.toEpochMilli()) }
            session = AuthenticatedSession("B", "test", emptyMap(), authorizationId = "B-authority")
            auth.value = AuthState.Authenticated(2)
            val b = awaitState(vm) { it.hero?.courseName == "B" }
            assertEquals(2L, b.authGeneration)
            assertNull(b.forGeneration(3).hero)
            reminders.value = reminder("A")
            release.complete(Unit)
            withTimeout(10_000) { returned.await() }
            delay(100)
            assertEquals("B", vm.uiState.value.hero?.courseName)
            assertTrue(vm.uiState.value.items.none { it.kind == OverviewItemKind.GradeUpdate })
            reminders.value = reminder("B").copy(latestChangeSet = reminder("A").latestChangeSet)
            delay(50)
            assertTrue(vm.uiState.value.items.none { it.kind == OverviewItemKind.GradeUpdate })
            reminders.value = reminder("B")
            awaitState(vm) { it.items.any { item -> item.kind == OverviewItemKind.GradeUpdate } }
            session = null
            auth.value = AuthState.Guest
            assertNull(vm.uiState.value.hero)
        } finally { release.complete(Unit) }
    }

    @Test fun schoolClockIgnoresDeviceAndInjectedClockZonesForCoursesCountdownsAndCacheAge() = runBlocking<Unit> {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            val vm = viewModel()
            val state = awaitState(vm) { it.hero != null && it.calendarEvents.isNotEmpty() }
            assertEquals(LocalDateTime.parse("2026-08-24T08:30:00"), state.now)
            assertEquals(OverviewContext.InClass, state.context)
            assertEquals(0.4f, state.hero!!.courseProgress!!, 0.001f)
            val countdown = state.withPreferences(OverviewPreferences(countdownEventIds = setOf(state.calendarEvents.single().id)))
            assertEquals("1 小時", countdown.countdowns.single().label)
            val semester = report.copy(scope = ScheduleScope.SEMESTER)
            assertFalse(scheduleNeedsRefresh(semester, instant.minusSeconds(6 * 3600).toEpochMilli(), instant))
            assertTrue(scheduleNeedsRefresh(semester, instant.minusSeconds(6 * 3600 + 1).toEpochMilli(), instant))
            assertTrue(scheduleNeedsRefresh(semester, instant.plusSeconds(1).toEpochMilli(), instant))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun overviewClockTicksOnlyWhileEnabledAndUpdatesImmediatelyOnReturn() = runBlocking<Unit> {
        val scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        val currentMillis = AtomicLong(instant.toEpochMilli())
        clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("America/Los_Angeles")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(currentMillis.get())
        }
        val vm = viewModel()
        val initial = withContext(Dispatchers.Default) {
            awaitState(vm) { it.hero != null && it.calendarEvents.isNotEmpty() && !it.isRefreshing }
        }
        currentMillis.addAndGet(1_000)
        scheduler.advanceTimeBy(1_100)
        scheduler.runCurrent()
        assertEquals(initial.now, vm.uiState.value.now)

        vm.setClockEnabled(true)
        scheduler.runCurrent()
        val resumed = withContext(Dispatchers.Default) {
            awaitState(vm) { it.now != initial.now }
        }
        assertEquals(initial.now.plusSeconds(1), resumed.now)
        vm.setClockEnabled(false)
        currentMillis.addAndGet(1_000)
        scheduler.advanceTimeBy(1_100)
        scheduler.runCurrent()
        assertEquals(initial.now.plusSeconds(1), vm.uiState.value.now)
        viewModels.clear()
    }

    @Test fun latePrivateResultAfterLogoutCannotRepopulateGuestState() = runBlocking<Unit> {
        val vm = viewModel()
        awaitState(vm) { it.hero != null }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        readSchedule = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            returned.complete(Unit)
            CachedScheduleReport(report("late private"), instant.toEpochMilli())
        }
        try {
            vm.refresh()
            withTimeout(10_000) { started.await() }
            session = null
            auth.value = AuthState.Guest
            assertNull(vm.uiState.value.hero)
            awaitState(vm) { it.authGeneration == null && it.calendarEvents.isNotEmpty() }
            release.complete(Unit)
            withTimeout(10_000) { returned.await() }
            delay(100)
            assertNull(vm.uiState.value.hero)
            assertTrue(vm.uiState.value.items.none { it.kind.isPrivate() })
        } finally { release.complete(Unit) }
    }

    @Test fun guestYouBikeHonorsVisibilityHiddenPreferenceAndTargetedRefresh() = runBlocking<Unit> {
        val scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        auth.value = AuthState.Guest
        val fetches = AtomicInteger()
        val preferences = MutableStateFlow<Preferences>(emptyPreferences())
        val vm = viewModel(
            youBikeRepository = YouBikeRepository(useFakeData = true, now = { instant.plusSeconds(fetches.incrementAndGet().toLong()) }),
            preferences = preferences,
        )
        // Initial loading ends on the first content, before all public requests finish.
        withContext(Dispatchers.Default) {
            awaitState(vm) { !it.calendarLoading && !it.announcementsLoading && !it.isRefreshing && it.calendarEvents.isNotEmpty() }
        }
        assertEquals(0, fetches.get())
        vm.setClockEnabled(true)
        scheduler.runCurrent()
        val first = withContext(Dispatchers.Default) {
            awaitState(vm) { it.youBike.snapshot != null && !it.youBike.isRefreshing && !it.isRefreshing }
        }
        assertEquals(0, scheduleReads.get())
        val otherRequests = publicRequests.get()
        val fetchesBeforeRefresh = fetches.get()
        vm.refreshYouBike()
        repeat(5) { vm.refreshYouBike() }
        scheduler.runCurrent()
        awaitState(vm) { it.youBike.snapshot?.fetchedAt != first.youBike.snapshot?.fetchedAt && !it.youBike.isRefreshing }
        assertEquals("Repeated targeted refreshes must coalesce", 1, fetches.get() - fetchesBeforeRefresh)
        assertEquals("YouBike refresh must not restart other public sources", otherRequests, publicRequests.get())
        assertEquals(0, scheduleReads.get())
        val refreshedFetches = fetches.get()
        scheduler.runCurrent()
        assertEquals(refreshedFetches, fetches.get())

        preferences.value = mutablePreferencesOf(stringSetPreferencesKey("overview_hidden_sections") to setOf("youbike"))
        scheduler.runCurrent()
        awaitState(vm) { !it.preferences.shows("youbike") }
        val hiddenFetches = fetches.get()
        vm.refreshYouBike()
        scheduler.runCurrent()
        assertEquals("Hidden YouBike must not fetch", hiddenFetches, fetches.get())
        preferences.value = emptyPreferences()
        scheduler.runCurrent()
        awaitState(vm) { it.preferences.shows("youbike") && it.youBike.snapshot?.fetchedAt == instant.plusSeconds(hiddenFetches + 1L) }
        vm.setClockEnabled(false)
        scheduler.runCurrent()
        val inactiveFetches = fetches.get()
        vm.refreshYouBike()
        scheduler.runCurrent()
        assertEquals("Inactive Overview must not fetch YouBike", inactiveFetches, fetches.get())
    }

    @Test fun unavailableYouBikeDoesNotBlockOtherPublicSources() = runBlocking<Unit> {
        auth.value = AuthState.Guest
        val failedClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("".toResponseBody()).build()
        }.build()
        val vm = viewModel(youBikeRepository = YouBikeRepository(client = failedClient))
        vm.setClockEnabled(true)
        val state = awaitState(vm) {
            it.youBike.failed && !it.isInitialLoading && it.calendarEvents.isNotEmpty() &&
                it.items.any { item -> item.kind == OverviewItemKind.Announcement }
        }
        assertTrue(state.hasPartialFailure)
        assertNull(state.youBike.snapshot)
        assertTrue(state.items.any { it.kind == OverviewItemKind.Announcement })
        assertEquals(0, scheduleReads.get())
    }

    @Test fun returningToOverviewRechecksExpiredSourcesOnlyOnVisibilityTransitions() = runBlocking<Unit> {
        val millis = AtomicLong(instant.toEpochMilli())
        clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
        }
        val vm = viewModel()
        awaitState(vm) { it.hero != null && !it.calendarLoading && !it.announcementsLoading && !it.isRefreshing }
        vm.setClockEnabled(true)
        awaitState(vm) { !it.isRefreshing }
        vm.setClockEnabled(false)
        val requests = publicRequests.get()
        val reads = scheduleReads.get()
        millis.addAndGet(7 * 60 * 60 * 1_000L)
        vm.setClockEnabled(true)
        withTimeout(5_000) {
            while (publicRequests.get() < requests + 2 || scheduleReads.get() <= reads) delay(10)
        }
        awaitState(vm) { !it.isRefreshing }
        val completedReads = scheduleReads.get()
        vm.setClockEnabled(true)
        delay(100)
        assertEquals(completedReads, scheduleReads.get())
        assertEquals(requests + 2, publicRequests.get())
    }

    private fun viewModel(
        configuration: Flow<WeatherConfiguration> = flowOf(WeatherConfiguration(WeatherSource.OPEN_METEO)),
        youBikeRepository: YouBikeRepository? = null,
        preferences: Flow<Preferences> = flowOf(emptyPreferences()),
    ): OverviewViewModel {
        val directory = temporaryFolder.newFolder()
        val publicClient = OkHttpClient.Builder().addInterceptor { chain ->
            publicRequests.incrementAndGet()
            val body = if (chain.request().url.encodedPath.endsWith("ics")) ICS else ANNOUNCEMENTS
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val weatherClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(WEATHER.toResponseBody()).build()
        }.build()
        val schedule = object : ScheduleRepository {
            override suspend fun getScheduleYears(): List<ScheduleYearTermOption> = error("unused")
            override suspend fun getScheduleClasses(year: String, term: String): List<ScheduleClassOption> = error("unused")
            override suspend fun getLatestSchedule(): ScheduleReport? = getLatestScheduleSnapshot()?.report
            override suspend fun getLatestScheduleSnapshot(): CachedScheduleReport? { scheduleReads.incrementAndGet(); return readSchedule() }
            override suspend fun fetchSchedule(yearValue: String, year: String, term: String, classNo: String, scope: ScheduleScope, targetDate: LocalDate, expectedSession: AuthenticatedSession?): ScheduleReport = fetchScheduleReport()
        }
        val settings = SettingsRepository(object : DataStore<Preferences> {
            override val data = preferences
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
        })
        return OverviewViewModel(
            OverviewCoordinator(
                schedule, NetworkSchoolCalendarRepository(directory, publicClient, nowProvider = { clock.instant() }),
                NetworkSchoolAnnouncementsRepository(directory, publicClient, nowProvider = { clock.instant() }),
                reminders, WeatherRepository(directory, client = weatherClient, now = { instant }), configuration, clock,
                youBikeRepository = youBikeRepository,
            ), settings, auth, { session }, dispatcher,
        ).also {
            viewModels.put("overview", it)
            viewModelJobs += it.viewModelScope.coroutineContext.job
        }
    }

    private suspend fun awaitState(vm: OverviewViewModel, predicate: (OverviewState) -> Boolean): OverviewState =
        withTimeout(10_000) { vm.uiState.first(predicate) }

    private fun report(name: String) = ScheduleReport("114_2", "230", ScheduleScope.CURRENT_WEEK,
        weekStartDate = "2026-08-24", weekEndDate = "2026-08-30", items = listOf(ScheduleItem(1, 1, name)))

    private fun reminder(student: String) = GradeReminderState(studentNo = student,
        latestChangeSet = GradeChangeSet(student, "114_2", "E1", "段考", 1,
            listOf(GradeChange("總分", field = GradeChangeField.SUMMARY_TOTAL, oldValue = "1", newValue = "2"))))

    private companion object {
        val ICS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:event
            DTSTART:20260824T013000Z
            DTEND:20260824T023000Z
            SUMMARY:校園活動
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        const val ANNOUNCEMENTS = """[{"pageNum":0,"totalPages":1},{"newsId":"public","title":"公開公告","top":"1","time":"2026-08-24"}]"""
        const val WEATHER = """{"current":{"time":"2026-08-24T08:30","temperature_2m":28,"apparent_temperature":30,"weather_code":0},"hourly":{"time":["2026-08-24T09:00"],"precipitation_probability":[20]}}"""
    }
}
