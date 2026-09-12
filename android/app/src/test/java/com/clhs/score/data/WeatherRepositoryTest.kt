package com.clhs.score.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WeatherRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cwaValidationReadsResponseBodyOffTheCallingThread() = runTest {
        val callingThread = Thread.currentThread()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(CWA_RESPONSE))
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val guardedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        assertFalse("Network body read on the calling thread", Thread.currentThread() === callingThread)
                        return super.read(sink, byteCount)
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType(): MediaType? = body.contentType()
                    override fun contentLength(): Long = body.contentLength()
                    override fun source(): BufferedSource = guardedSource
                }).build()
            }.build()
            val repository = WeatherRepository(
                cacheDirectory = temporaryFolder.newFolder(),
                client = client,
                cwaEndpoint = server.url("/"),
            )

            assertEquals(CwaKeyValidationResult.VALID, repository.validateCwaKey(SECRET))
        }
    }

    @Test
    fun olderWeatherResponseCannotReplaceNewerRequestCache() = runTest {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val requestCount = AtomicInteger()
        val directory = temporaryFolder.newFolder()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (requestCount.incrementAndGet() == 1) {
                        firstStarted.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS))
                        return MockResponse().setBody(OPEN_METEO_RESPONSE)
                    }
                    return MockResponse().setBody(OPEN_METEO_RESPONSE.replace("26.6", "31.4"))
                }
            }
            val repository = WeatherRepository(
                cacheDirectory = directory,
                now = { Instant.parse("2026-08-30T04:00:00Z") },
                openMeteoEndpoint = server.url("/"),
            )
            val older = async(Dispatchers.IO) { repository.observe(WeatherSource.OPEN_METEO).toList() }
            try {
                assertTrue(withContext(Dispatchers.IO) { firstStarted.await(10, TimeUnit.SECONDS) })
                val newer = repository.observe(WeatherSource.OPEN_METEO).toList().last()
                assertEquals(31, newer.snapshot?.temperatureCelsius)
                releaseFirst.countDown()
                older.await()

                val cached = repository.observe(WeatherSource.OPEN_METEO).toList().last()
                assertEquals(31, cached.snapshot?.temperatureCelsius)
                assertEquals(2, server.requestCount)
            } finally {
                releaseFirst.countDown()
                older.cancel()
            }
        }
    }

    @Test
    fun openMeteoUsesFixedCoordinatesAndParsesNextHourlyProbability() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(OPEN_METEO_RESPONSE))
            val repository = repository(server)

            val states = repository.observe(WeatherSource.OPEN_METEO).toList()
            val request = server.takeRequest()
            val weather = states.last().snapshot!!

            assertTrue(request.path.orEmpty().contains("latitude=24.9621"))
            assertTrue(request.path.orEmpty().contains("longitude=121.2112"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(27, weather.temperatureCelsius)
            assertEquals(65, weather.precipitationProbability)
            assertEquals("中壢區", weather.locationLabel)
        }
    }

    @Test
    fun cwaSendsKeyOnlyInAuthorizationHeaderAndParsesZhongliDistrict() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(CWA_RESPONSE))
            val repository = repository(server, cwaKey = SECRET)

            val weather = repository.observe(WeatherSource.CWA).toList().last().snapshot!!
            val request = server.takeRequest()

            assertEquals(SECRET, request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains(SECRET))
            assertTrue(request.path.orEmpty().contains("LocationName="))
            assertEquals("中壢區", weather.locationLabel)
            assertEquals(28, weather.temperatureCelsius)
            assertEquals(40, weather.precipitationProbability)
            assertEquals(LocalDateTime.of(2026, 8, 30, 12, 0), weather.precipitationStart)
            assertEquals(LocalDateTime.of(2026, 8, 30, 18, 0), weather.precipitationEndExclusive)
        }
    }

    @Test
    fun cwaApiRejectionIsNotReportedAsInvalidCredentials() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":"false","message":"request rejected"}"""))
            val repository = repository(server, cwaKey = SECRET)

            assertEquals(CwaKeyValidationResult.API_REJECTED, repository.validateCwaKey(SECRET))
        }
    }

    @Test
    fun cwaUnauthorizedResponseIsReportedAsInvalidAuthorization() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("server echoed $SECRET"))
            val repository = repository(server, cwaKey = SECRET)

            val result = repository.validateCwaKey(SECRET)

            assertEquals(CwaKeyValidationResult.INVALID_AUTHORIZATION, result)
        }
    }

    @Test
    fun missingCwaKeyIsLocalizedAndDoesNotMakeNetworkRequest() = runTest {
        MockWebServer().use { server ->
            val state = repository(server).observe(WeatherSource.CWA).toList().last()

            assertTrue(state.needsCwaApiKey)
            assertNull(state.snapshot)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun corruptedCwaCredentialIsLocalizedAndDoesNotCancelWeatherFlow() = runTest {
        MockWebServer().use { server ->
            val repository = WeatherRepository(
                cacheDirectory = temporaryFolder.newFolder(),
                client = OkHttpClient(),
                cwaKeyProvider = { error("corrupted") },
                cwaEndpoint = server.url("/"),
            )

            val state = repository.observe(WeatherSource.CWA).toList().last()

            assertNull(state.snapshot)
            assertFalse(state.isRefreshing)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cwaKeyValidationRejectsBlankShortAndControlCharacters() {
        assertThrows(IllegalArgumentException::class.java) { validateCwaApiKey(" ") }
        assertEquals("x", validateCwaApiKey(" x "))
        assertThrows(IllegalArgumentException::class.java) {
            validateCwaApiKey("CWA-valid-length\n-has-newline")
        }
        assertThrows(IllegalArgumentException::class.java) { validateCwaApiKey("a".repeat(257)) }
        assertEquals(SECRET, validateCwaApiKey("  $SECRET  "))
    }

    @Test
    fun cwaChoosesTheNearestCurrentPointForecast() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(CWA_NEAREST_POINT_RESPONSE))

            val weather = repository(server, cwaKey = SECRET)
                .observe(WeatherSource.CWA)
                .toList()
                .last()
                .snapshot!!

            assertEquals(28, weather.temperatureCelsius)
        }
    }

    @Test
    fun cwaDoesNotUseAnOldPointForecastWhenAllPointsAreFromAnEarlierDay() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(CWA_ALL_PAST_RESPONSE))

            val state = repository(server, cwaKey = SECRET)
                .observe(WeatherSource.CWA)
                .toList()
                .last()

            assertNull(state.snapshot)
        }
    }

    @Test
    fun cwaKeyValidationDoesNotRejectAuthorizedKeyWhenForecastParsingIsUnavailable() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":"true","records":{}}"""))

            val result = repository(server).validateCwaKey(SECRET)

            assertEquals(CwaKeyValidationResult.VALID, result)
        }
    }

    @Test
    fun cwaKeyStorePersistsOnlyCiphertextAndCanClearIt() = runTest {
        val file = temporaryFolder.newFile("weather_credentials.pb")
        file.delete()
        val store = CwaApiKeyStore(file, XorTestCipher)

        store.save(SECRET)

        assertTrue(store.hasKey())
        assertFalse(file.readBytes().decodeToString().contains(SECRET))
        assertEquals(SECRET, store.load())
        store.clear()
        assertFalse(file.exists())
    }

    private fun repository(server: MockWebServer, cwaKey: String? = null) = WeatherRepository(
        cacheDirectory = temporaryFolder.newFolder(),
        client = OkHttpClient(),
        now = { Instant.parse("2026-08-30T04:30:00Z") },
        cwaKeyProvider = { cwaKey },
        openMeteoEndpoint = server.url("/"),
        cwaEndpoint = server.url("/"),
    )

    private companion object {
        const val SECRET = "CWA-TEST-KEY-123456789"
        val OPEN_METEO_RESPONSE = """
            {
              "current": {
                "time": "2026-08-30T12:30",
                "temperature_2m": 26.6,
                "apparent_temperature": 29.2,
                "weather_code": 61
              },
              "hourly": {
                "time": ["2026-08-30T12:00", "2026-08-30T13:00"],
                "precipitation_probability": [20, 65]
              }
            }
        """.trimIndent()
        val CWA_RESPONSE = """
            {
              "success": "true",
              "records": {
                "Locations": [{
                  "Location": [{
                    "LocationName": "中壢區",
                    "WeatherElement": [
                      {"ElementName":"溫度","Time":[{"DataTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"Temperature":"28"}]}]},
                      {"ElementName":"體感溫度","Time":[{"DataTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"ApparentTemperature":"31"}]}]},
                      {"ElementName":"天氣現象","Time":[{"StartTime":"2026-08-30T12:00:00+08:00","EndTime":"2026-08-30T15:00:00+08:00","ElementValue":[{"Weather":"多雲短暫雨"}]}]},
                      {"ElementName":"3小時降雨機率","Time":[{
                        "StartTime":"2026-08-30T12:00:00+08:00",
                        "EndTime":"2026-08-30T18:00:00+08:00",
                        "ElementValue":[{"ProbabilityOfPrecipitation":"40"}]
                      }]}
                    ]
                  }]
                }]
              }
            }
        """.trimIndent()
        val CWA_NEAREST_POINT_RESPONSE = """
            {
              "success": "true",
              "records": {
                "Locations": [{
                  "Location": [{
                    "LocationName": "中壢區",
                    "WeatherElement": [
                      {"ElementName":"溫度","Time":[
                        {"DataTime":"2026-08-30T09:00:00+08:00","ElementValue":[{"Temperature":"25"}]},
                        {"DataTime":"2026-08-30T12:00:00+08:00","ElementValue":[{"Temperature":"28"}]}
                      ]},
                      {"ElementName":"天氣現象","Time":[
                        {"DataTime":"2026-08-30T12:00:00+08:00","ElementValue":[{"Weather":"晴"}]}
                      ]}
                    ]
                  }]
                }]
              }
            }
        """.trimIndent()
        val CWA_ALL_PAST_RESPONSE = """
            {
              "success": "true",
              "records": {
                "Locations": [{
                  "Location": [{
                    "LocationName": "中壢區",
                    "WeatherElement": [
                      {"ElementName":"溫度","Time":[
                        {"DataTime":"2026-08-29T12:00:00+08:00","ElementValue":[{"Temperature":"28"}]}
                      ]}
                    ]
                  }]
                }]
              }
            }
        """.trimIndent()
    }

    private object XorTestCipher : SessionCipher {
        override suspend fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = EncryptedPayload(
            version = 1,
            keyVersion = 1,
            iv = ByteArray(12),
            ciphertext = plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray(),
        )

        override suspend fun decrypt(payload: EncryptedPayload, associatedData: ByteArray): ByteArray =
            payload.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
