package com.clhs.score.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

internal suspend fun <T> runInterruptibleHttp(block: () -> T): T = try {
    runInterruptible(Dispatchers.IO, block)
} catch (error: InterruptedIOException) {
    currentCoroutineContext().ensureActive()
    throw error
}

internal suspend fun Call.executeCancellable(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                continuation.resume(response) { _, unconsumedResponse, _ ->
                    unconsumedResponse.close()
                }
            }
        })
    }

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> Call.executeCancellable(block: suspend (Response) -> T): T {
    val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = true,
    ) { cause ->
        if (cause is kotlinx.coroutines.CancellationException) cancel()
    }
    return try {
        executeCancellable().use { response ->
            currentCoroutineContext().ensureActive()
            block(response)
        }
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        cancellationHandle?.dispose()
    }
}
