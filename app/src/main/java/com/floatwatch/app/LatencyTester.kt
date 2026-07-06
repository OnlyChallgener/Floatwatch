package com.floatwatch.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LatencyTester {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .writeTimeout(2500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun measure(source: TimeSource): LatencyResult = withContext(Dispatchers.IO) {
        val url = source.url ?: return@withContext LatencyResult(source.id, 0L, null)
        val headResult = requestOnce(source, url, head = true)
        if (headResult.error == "HTTP_405" || headResult.error == "HTTP_403") {
            requestOnce(source, url, head = false)
        } else {
            headResult
        }
    }

    private suspend fun requestOnce(source: TimeSource, url: String, head: Boolean): LatencyResult {
        return suspendCoroutine { cont ->
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Floatwatch/1.0 Android")
                .header("Cache-Control", "no-cache")

            val request = if (head) requestBuilder.head().build() else requestBuilder.get().build()
            val start = System.nanoTime()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resume(LatencyResult(source.id, -1L, null, e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    val latency = ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(0L)
                    val dateEpoch = parseHttpDate(response.header("Date"))
                    val codeError = when (response.code) {
                        403 -> "HTTP_403"
                        405 -> "HTTP_405"
                        in 200..399 -> null
                        else -> "HTTP_${response.code}"
                    }
                    response.close()
                    cont.resume(LatencyResult(source.id, latency, dateEpoch, codeError))
                }
            })
        }
    }
}
