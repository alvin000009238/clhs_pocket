package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class UpdateApkDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var cacheDirectory: java.io.File

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        cacheDirectory = Files.createTempDirectory("update-apk-downloader-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun failedAttemptDoesNotDeletePreviouslyVerifiedApk() = runBlocking {
        val updates = java.io.File(cacheDirectory, "updates").apply { mkdirs() }
        val installedApk = java.io.File(updates, "clhs-score-update.apk").apply { writeBytes("old".toByteArray()) }
        server.enqueue(MockResponse().setBody("bad"))

        val result = runCatching {
            UpdateApkDownloader(cacheDirectory).download(
                ApkAsset(server.url("/app.apk").toString(), "0".repeat(64)),
            ) {}
        }

        assertTrue(result.exceptionOrNull() is ChecksumMismatchException)
        assertArrayEquals("old".toByteArray(), installedApk.readBytes())
        assertTrue(updates.listFiles().orEmpty().contentEquals(arrayOf(installedApk)))
    }

    @Test
    fun cancellationStopsSlowDownloadAndDeletesOnlyItsTemporaryFile() = runBlocking {
        server.enqueue(MockResponse().setBody("apk").setBodyDelay(5, TimeUnit.SECONDS))
        val download = launch(Dispatchers.Default) {
            UpdateApkDownloader(cacheDirectory).download(
                ApkAsset(server.url("/app.apk").toString(), "0".repeat(64)),
            ) {}
        }
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)

        withTimeout(1_000L) { download.cancelAndJoin() }

        assertTrue(download.isCancelled)
        assertFalse(java.io.File(cacheDirectory, "updates/clhs-score-update.apk").exists())
        assertTrue(java.io.File(cacheDirectory, "updates").listFiles().orEmpty().isEmpty())
    }
}
