package com.clhs.score.data

import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

internal class LatestCacheFile(
    cacheDirectory: File,
    fileName: String,
    private val beforeCommit: (File) -> Unit = {},
) {
    val file = File(cacheDirectory, fileName)
    private val directory = cacheDirectory
    private val generation = synchronized(generations) {
        generations.getOrPut(file.canonicalPath) { AtomicLong() }
    }

    fun beginWrite(): Long = synchronized(generation) { generation.incrementAndGet() }

    fun commit(
        bytes: ByteArray,
        lastModifiedMillis: Long,
        writeGeneration: Long,
        coroutineContext: CoroutineContext,
    ) {
        directory.mkdirs()
        val temporaryFile = Files.createTempFile(directory.toPath(), "${file.name}-", ".tmp").toFile()
        try {
            temporaryFile.writeBytes(bytes)
            beforeCommit(temporaryFile)
            coroutineContext.ensureActive()
            synchronized(generation) {
                coroutineContext.ensureActive()
                if (writeGeneration != generation.get()) return
                try {
                    Files.move(
                        temporaryFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporaryFile.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                file.setLastModified(lastModifiedMillis)
            }
        } finally {
            temporaryFile.delete()
        }
    }

    private companion object {
        val generations = mutableMapOf<String, AtomicLong>()
    }
}
