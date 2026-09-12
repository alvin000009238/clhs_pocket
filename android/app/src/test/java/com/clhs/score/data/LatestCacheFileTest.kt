package com.clhs.score.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

class LatestCacheFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun separateInstancesOfTheSameFileRejectOlderRequests() {
        val older = LatestCacheFile(temporaryFolder.root, "feed.cache")
        val newer = LatestCacheFile(temporaryFolder.root, "./feed.cache")
        val otherFile = LatestCacheFile(temporaryFolder.root, "other.cache")
        val oldGeneration = older.beginWrite()
        val newGeneration = newer.beginWrite()
        val otherGeneration = otherFile.beginWrite()

        newer.commit("new".toByteArray(), 2_000L, newGeneration, EmptyCoroutineContext)
        older.commit("old".toByteArray(), 1_000L, oldGeneration, EmptyCoroutineContext)
        otherFile.commit("other".toByteArray(), 3_000L, otherGeneration, EmptyCoroutineContext)

        assertEquals("new", older.file.readText())
        assertEquals(2_000L, older.file.lastModified())
        assertEquals("other", otherFile.file.readText())
    }

    @Test
    fun staleCommitCannotOverwriteLatestAndEachAttemptOwnsItsTempFile() {
        val firstReady = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val temporaryPaths = Collections.synchronizedList(mutableListOf<String>())
        val cache = LatestCacheFile(temporaryFolder.root, "feed.cache") { temporaryFile ->
            temporaryPaths += temporaryFile.absolutePath
            if (temporaryFile.readText() == "old") {
                firstReady.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        val firstGeneration = cache.beginWrite()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit {
                cache.commit("old".toByteArray(), 1L, firstGeneration, EmptyCoroutineContext)
            }
            assertTrue(firstReady.await(2, TimeUnit.SECONDS))

            val secondGeneration = cache.beginWrite()
            cache.commit("new".toByteArray(), 2L, secondGeneration, EmptyCoroutineContext)
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)

            assertEquals("new", cache.file.readText())
            assertEquals(2, temporaryPaths.size)
            assertNotEquals(temporaryPaths[0], temporaryPaths[1])
            assertTrue(temporaryFolder.root.listFiles().orEmpty().contentEquals(arrayOf(cache.file)))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
