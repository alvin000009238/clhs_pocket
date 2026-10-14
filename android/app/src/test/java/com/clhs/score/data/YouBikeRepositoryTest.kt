package com.clhs.score.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class YouBikeRepositoryTest {
    private val fetchedAt = Instant.parse("2026-10-02T04:43:03Z")
    private val fixture = requireNotNull(javaClass.getResource("/youbike-clhs.txt")).readText()

    @Test fun officialResponseParsesStationAndDetails() {
        val snapshot = parseYouBikeSnapshot(fixture, fetchedAt)
        assertEquals(4, snapshot.availableBikes)
        assertEquals(44, snapshot.emptyDocks)
        assertEquals(52, snapshot.totalDocks)
        assertEquals(2, snapshot.ordinaryBikes)
        assertEquals(2, snapshot.electricBikes)
        assertTrue(snapshot.isOperating)
        assertEquals(fetchedAt, snapshot.updatedAt)
        assertEquals(fetchedAt, snapshot.fetchedAt)
        assertFalse(snapshot.isStale(fetchedAt.plusSeconds(300)))
        assertTrue(snapshot.isStale(fetchedAt.plusSeconds(301)))
        assertTrue(snapshot.isStale(fetchedAt.minusSeconds(1)))
        assertTrue(parseYouBikeSnapshot(fixture, fetchedAt.plusSeconds(600)).isStale(fetchedAt.plusSeconds(600)))
    }

    @Test fun stationSearchMatchesNameDistrictAndAddressUsingOneCachedFeed() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture))
            val repository = YouBikeRepository(endpoint = server.url("/station"), now = { fetchedAt })
            for (query in listOf("中壢高中", "中壢區", "中央西路")) {
                assertEquals(query, listOf(YouBikeStation.SCHOOL.id), repository.searchStations(query).map { it.id })
            }
            assertTrue(repository.searchStations("不存在的地點").isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun multiStationFeedIsolatesMalformedRowsMissingAndDuplicateStations() = runTest {
        MockWebServer().use { server ->
            val school = SchoolJson.parseToJsonElement(fixture).jsonArray.single()
            val other = SchoolJson.parseToJsonElement(responseStation("station_no" to "other"))
            server.enqueue(MockResponse().setBody("[$school,null,$other,$other]"))
            val repository = YouBikeRepository(endpoint = server.url("/station"), now = { fetchedAt })
            val stations = listOf(YouBikeStation.SCHOOL, YouBikeStation("other", "重複站"), YouBikeStation("missing", "缺少站"))
            val result = repository.observeStations(flowOf(stations), flowOf(true), flowOf(Unit)).take(2).toList().last()
            assertEquals(4, result.getValue(YouBikeStation.SCHOOL.id).snapshot?.availableBikes)
            assertFalse(result.getValue(YouBikeStation.SCHOOL.id).failed)
            assertTrue(result.getValue("other").failed)
            assertTrue(result.getValue("missing").failed)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun detailsCanBePlainJsonMissingMalformedOrInconsistentWithoutLosingTotals() {
        val plain = parseYouBikeSnapshot(response("available_spaces_detail" to """{"yb2":"3","eyb":"1"}"""), fetchedAt)
        assertEquals(3, plain.ordinaryBikes)
        assertEquals(1, plain.electricBikes)
        for (detail in listOf(null, "broken", """{"yb2":"4","eyb":"1"}""", """{"yb2":"-1","eyb":"5"}""")) {
            val snapshot = parseYouBikeSnapshot(response("available_spaces_detail" to detail), fetchedAt)
            assertEquals(4, snapshot.availableBikes)
            assertNull(snapshot.ordinaryBikes)
            assertNull(snapshot.electricBikes)
        }
    }

    @Test fun zeroAndSuspendedStationAreValidButMissingStationAndInvalidCountsAreNot() {
        val zero = parseYouBikeSnapshot(response("available_spaces" to "0", "empty_spaces" to "0", "available_spaces_detail" to """{"yb2":"0","eyb":"0"}"""), fetchedAt)
        assertEquals(0, zero.availableBikes)
        assertEquals(0, zero.emptyDocks)
        assertFalse(parseYouBikeSnapshot(response("status" to "2"), fetchedAt).isOperating)
        for ((field, value) in listOf("station_no" to "other", "available_spaces" to "-1", "empty_spaces" to "53", "parking_spaces" to "invalid", "status" to "3", "updated_at" to "2026-02-30 12:43:03")) {
            assertThrows(Exception::class.java) { parseYouBikeSnapshot(response(field to value), fetchedAt) }
        }
        assertThrows(Exception::class.java) { parseYouBikeSnapshot("[]", fetchedAt) }
        assertThrows(Exception::class.java) { parseYouBikeSnapshot("<html>Request Rejected</html>", fetchedAt) }
        val row = SchoolJson.parseToJsonElement(fixture).jsonArray.single()
        assertThrows(Exception::class.java) { parseYouBikeSnapshot("[$row,$row]", fetchedAt) }
        val multiple = JsonArray(listOf(SchoolJson.parseToJsonElement(responseStation("station_no" to "other")), row))
        assertEquals(4, parseYouBikeSnapshot(multiple.toString(), fetchedAt).availableBikes)
    }

    @Test fun pollingRefreshAndVisibilityUseVirtualTimeAndDemoMakesNoRequests() = runTest {
        val forbiddenClient = OkHttpClient.Builder().addInterceptor { error("Demo must not make network requests") }.build()
        val repository = YouBikeRepository(useFakeData = true, client = forbiddenClient, now = { fetchedAt.plusMillis(testScheduler.currentTime) })
        val visible = MutableStateFlow(false)
        val states = mutableListOf<YouBikeState>()
        val job = backgroundScope.launch { repository.observe(visible, flowOf(Unit)).collect { states += it } }
        runCurrent()
        assertNull(states.last().snapshot)
        visible.value = true
        runCurrent()
        assertEquals(fetchedAt, states.last().snapshot?.fetchedAt)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(fetchedAt, states.last().snapshot?.fetchedAt)
        advanceTimeBy(1); runCurrent()
        assertEquals(fetchedAt.plusSeconds(60), states.last().snapshot?.fetchedAt)
        visible.value = false // Route exit, ON_STOP and the hidden-section preference all use this gate.
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(fetchedAt.plusSeconds(60), states.last().snapshot?.fetchedAt)
        visible.value = true
        runCurrent()
        assertEquals(fetchedAt.plusSeconds(180), states.last().snapshot?.fetchedAt)
        assertFalse(states.last().isRefreshing)
        job.cancel()
    }

    @Test fun manualStationIndicatorIsClearedBeforeAutomaticPollingAndRouteReentry() = runTest {
        val repository = YouBikeRepository(useFakeData = true)
        val version = MutableStateFlow(0)
        val visible = MutableStateFlow(true)
        val states = mutableListOf<YouBikeState>()
        val job = backgroundScope.launch {
            repository.observeStations(flowOf(listOf(YouBikeStation.SCHOOL)), visible, version.map { Unit })
                .collect { states += it.getValue(YouBikeStation.SCHOOL.id) }
        }
        runCurrent()
        assertTrue(states.none { it.isManuallyRefreshing })
        states.clear()
        version.value++
        runCurrent()
        assertTrue(states.first().isRefreshing && states.first().isManuallyRefreshing)
        assertFalse(states.last().isManuallyRefreshing)
        states.clear()
        advanceTimeBy(60_000); runCurrent()
        assertTrue(states.none { it.isManuallyRefreshing })
        visible.value = false; runCurrent()
        states.clear()
        visible.value = true; runCurrent()
        assertTrue(states.none { it.isManuallyRefreshing })
        job.cancel()
    }

    @Test fun manualRefreshRestartsOnlyTheYouBikePoll() = runTest {
        val repository = YouBikeRepository(useFakeData = true, now = { fetchedAt.plusMillis(testScheduler.currentTime) })
        val version = MutableStateFlow(0)
        val states = mutableListOf<YouBikeState>()
        val job = backgroundScope.launch {
            repository.observe(flowOf(true), version.map { Unit }).collect { states += it }
        }
        runCurrent()
        advanceTimeBy(1_000)
        version.value++
        runCurrent()
        assertEquals(fetchedAt.plusSeconds(1), states.last().snapshot?.fetchedAt)
        advanceTimeBy(59_999); runCurrent()
        assertEquals(fetchedAt.plusSeconds(1), states.last().snapshot?.fetchedAt)
        advanceTimeBy(1); runCurrent()
        assertEquals(fetchedAt.plusSeconds(61), states.last().snapshot?.fetchedAt)
        job.cancel()
    }

    @Test fun networkFailureRetainsPreviousSnapshotAndNeverSendsCredentials() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture))
            server.enqueue(MockResponse().setResponseCode(503))
            val repository = YouBikeRepository(endpoint = server.url("/station"), now = { fetchedAt })
            val initial = repository.observe(flowOf(true), flowOf(Unit)).take(2).toList().last()
            assertNotNull(initial.snapshot)
            val failed = repository.observe(flowOf(true), flowOf(Unit)).take(2).toList().last()
            assertTrue(failed.failed)
            assertEquals(initial.snapshot, failed.snapshot)
            assertFalse(failed.isRefreshing)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun oversizedResponseFailsLocallyAndCancellationStopsTheNetworkCall() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(8_388_609)))
            val client = OkHttpClient()
            val repository = YouBikeRepository(client = client, endpoint = server.url("/station"))
            assertTrue(repository.observe(flowOf(true), flowOf(Unit)).take(2).toList().last().failed)
            server.takeRequest(5, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = backgroundScope.launch { repository.observe(flowOf(true), flowOf(Unit)).collect() }
            runCurrent()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
            val inactive = repository.observe(flowOf(false), flowOf(Unit)).take(1).toList().single()
            assertFalse(inactive.isRefreshing)
            assertFalse(inactive.failed)
        }
    }

    private fun response(vararg fields: Pair<String, String?>): String = "[${responseStation(*fields)}]"

    private fun responseStation(vararg fields: Pair<String, String?>): String {
        val row = SchoolJson.parseToJsonElement(fixture).jsonArray.single().jsonObject.toMutableMap()
        for ((key, value) in fields) {
            if (value == null) row.remove(key)
            else row[key] = if (key == "available_spaces_detail" && value.startsWith("{"))
                SchoolJson.parseToJsonElement(value) else JsonPrimitive(value)
        }
        return kotlinx.serialization.json.JsonObject(row).toString()
    }
}
