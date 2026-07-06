package com.floatwatch.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * HTTP latency tester.
 *
 * Kept as a class with a companion object on purpose:
 * - LatencyTester().test(url) works
 * - LatencyTester.test(url) also works
 * This avoids CI failures when older files still instantiate LatencyTester.
 */
class LatencyTester {
    data class Result(
        val latencyMs: Long,
        val serverOffsetMs: Long?
    )

    suspend fun test(url: String): Result = testInternal(url)

    companion object {
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        suspend fun test(url: String): Result = testInternal(url)

        private suspend fun testInternal(url: String): Result = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "Floatwatch/1.0")
                .build()

            val start = System.currentTimeMillis()
            try {
                client.newCall(request).execute().use { response ->
                    val end = System.currentTimeMillis()
                    val latency = end - start
                    val dateHeader = response.header("Date")
                    val offset = parseHttpDate(dateHeader)?.let { serverTime ->
                        serverTime - end
                    }
                    Result(latency, offset)
                }
            } catch (_: Exception) {
                Result(-1, null)
            }
        }

        private fun parseHttpDate(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                formatter.timeZone = TimeZone.getTimeZone("GMT")
                formatter.parse(value)?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
