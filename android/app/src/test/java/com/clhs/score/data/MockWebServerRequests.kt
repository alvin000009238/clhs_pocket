package com.clhs.score.data

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.TimeUnit

internal fun MockWebServer.awaitRequest(): RecordedRequest =
    takeRequest(5, TimeUnit.SECONDS)
        ?: throw AssertionError("Expected an HTTP request within 5 seconds")
