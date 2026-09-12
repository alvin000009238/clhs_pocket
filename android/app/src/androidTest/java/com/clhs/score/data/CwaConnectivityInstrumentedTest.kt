package com.clhs.score.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class CwaConnectivityInstrumentedTest {
    @Test
    fun authorizedCwaResponseCanBeReadFromMainDispatcher() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "cwa-main-test").toFile()
        try {
            ServerSocket(0).use { server ->
                val serve = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.getOutputStream().write("""{"success":"true","records":{}}""".toByteArray())
                    }
                }
                // A real socket body exercises Android's main-thread network guard without a real API key.
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val source = Socket("127.0.0.1", server.localPort).getInputStream().source().buffer()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(object : ResponseBody() {
                            override fun contentType(): MediaType? = null
                            override fun contentLength(): Long = -1
                            override fun source() = source
                        }).build()
                }.build()
                val repository = WeatherRepository(cacheDirectory = directory, client = client)

                val result = withContext(Dispatchers.Main) {
                    repository.validateCwaKey("CWA-test-key")
                }

                assertEquals(CwaKeyValidationResult.VALID, result)
                serve.await()
            }
        } finally {
            directory.delete()
        }
    }

    @Test
    fun cwaEndpointIsReachableFromAndroid() = runBlocking {
        val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).build()
        val request = Request.Builder()
            .url("https://opendata.cwa.gov.tw/api/v1/rest/datastore/F-D0047-005?LocationName=%E4%B8%AD%E5%A3%A2%E5%8D%80")
            .header("Authorization", "invalid-test-key-0000000000000000")
            .build()

        client.newCall(request).executeCancellable().use { response ->
            assertTrue("Unexpected HTTP ${response.code}", response.code in 400..499)
        }
    }
}
