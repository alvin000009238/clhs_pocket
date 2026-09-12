package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class UpdateApkDownloader(
    private val cacheDirectory: File,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun download(
        asset: ApkAsset,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(cacheDirectory, "updates").apply { mkdirs() }
        val temporaryApk = Files.createTempFile(directory.toPath(), "clhs-score-update-", ".apk").toFile()
        val installedApk = File(directory, FINAL_APK_NAME)
        try {
            val request = Request.Builder().url(asset.downloadUrl).get().build()
            client.newCall(request).executeCancellable { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val totalBytes = response.body.contentLength()
                if (totalBytes > MAX_UPDATE_APK_BYTES) throw UpdateApkTooLargeException()
                onProgress(if (totalBytes > 0L) 0f else null)
                var lastPercent = -1
                response.body.byteStream().use { input ->
                    writeVerifiedApk(input, temporaryApk, asset.sha256) { copiedBytes ->
                        if (totalBytes > 0L) {
                            val percent = ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            try {
                Files.move(
                    temporaryApk.toPath(),
                    installedApk.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryApk.toPath(),
                    installedApk.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            onProgress(1f)
            installedApk
        } catch (error: Throwable) {
            temporaryApk.delete()
            throw error
        }
    }

    private companion object {
        const val FINAL_APK_NAME = "clhs-score-update.apk"
    }
}

internal suspend fun writeVerifiedApk(
    input: InputStream,
    destination: File,
    expectedSha256: String,
    maxBytes: Long = MAX_UPDATE_APK_BYTES,
    onBytesCopied: suspend (Long) -> Unit = {},
): File {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var copiedBytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read.toLong() > maxBytes - copiedBytes) throw UpdateApkTooLargeException()
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                copiedBytes += read
                onBytesCopied(copiedBytes)
            }
        }
        val actualSha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (actualSha256 != expectedSha256) throw ChecksumMismatchException()
        return destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
}

internal class ChecksumMismatchException : Exception()
internal class UpdateApkTooLargeException : Exception()

internal const val MAX_UPDATE_APK_BYTES = 256L * 1024 * 1024
