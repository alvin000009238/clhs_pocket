package com.clhs.score.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class InterruptibleHttpTest {
    @Test
    fun cancellationAfterResponseClosesItBeforeCallerProcessesIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val call = DeferredResponseCall()
        var processed = false
        val load = launch(dispatcher) {
            call.executeCancellable().use {
                processed = true
            }
        }
        runCurrent()

        val responseClosed = call.respond()
        load.cancel()
        runCurrent()

        assertFalse(processed)
        assertTrue(responseClosed.get())
        assertTrue(call.isCanceled())
    }
}

private class DeferredResponseCall(
    private val request: Request = Request.Builder().url("https://example.com/").build(),
    private val delegate: Call = OkHttpClient().newCall(request),
) : Call by delegate {
    private val canceled = AtomicBoolean(false)
    private lateinit var callback: Callback

    override fun enqueue(responseCallback: Callback) {
        callback = responseCallback
    }
    override fun cancel() {
        canceled.set(true)
        delegate.cancel()
    }
    override fun isCanceled(): Boolean = canceled.get()

    fun respond(): AtomicBoolean {
        val closed = AtomicBoolean(false)
        val source = object : ForwardingSource(Buffer()) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()
        callback.onResponse(
            this,
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    object : ResponseBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength(): Long = 0
                        override fun source(): BufferedSource = source
                    },
                )
                .build(),
        )
        return closed
    }
}
