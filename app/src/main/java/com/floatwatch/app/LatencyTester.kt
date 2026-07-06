package com.floatwatch.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.max

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

        /**
         * Stable HTTP RTT sampler.
         *
         * It still measures the selected platform with HTTP HEAD, but behaves closer to a
         * traditional ping display: short burst sampling, drop obvious spikes, then return a
         * stable low/median value instead of a single random request.
         */
        suspend fun stableTest(url: String, repeats: Int = 5): Result {
            val samples = mutableListOf<Result>()
            repeat(repeats.coerceAtLeast(3)) { index ->
                val r = testInternal(url)
                if (r.latencyMs in 1..2500) samples.add(r)
                if (index != repeats - 1) delay(70L)
            }
            if (samples.isEmpty()) return Result(-1, null)

            val sorted = samples.sortedBy { it.latencyMs }
            val picked = when {
                sorted.size >= 5 -> sorted[1] // ignore one unrealistically low sample and high spikes
                sorted.size >= 3 -> sorted[sorted.size / 2]
                else -> sorted.first()
            }
            return picked
        }

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


/**
 * Process-wide latency smoother used by Activity and FloatingService.
 * It prevents one-off HTTP/TLS/CDN spikes from jumping the visible number from 10ms to 600ms.
 */
object LatencyStabilizer {
    private val lastValues = mutableMapOf<String, Long>()

    @Synchronized
    fun reset(key: String) {
        lastValues.remove(key)
    }

    @Synchronized
    fun update(key: String, rawMs: Long): Long {
        if (rawMs < 0) return rawMs
        val previous = lastValues[key]
        if (previous == null || previous < 0) {
            lastValues[key] = rawMs
            return rawMs
        }

        // Drop one-off large spikes that are usually DNS/TLS/CDN reconnects, not real RTT.
        if (rawMs > max(260L, previous * 4) && rawMs - previous > 180L) {
            return previous
        }

        val alpha = when {
            rawMs > previous + 120L -> 0.18
            rawMs > previous -> 0.30
            else -> 0.55
        }
        val smoothed = (previous * (1.0 - alpha) + rawMs * alpha).toLong().coerceAtLeast(1L)
        lastValues[key] = smoothed
        return smoothed
    }
}
