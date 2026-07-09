package com.floatwatch.app

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max
import kotlin.math.roundToLong

class LatencyTester {
    data class Result(
        val latencyMs: Long,
        val serverOffsetMs: Long?
    )

    suspend fun test(url: String): Result = stableTest(url, repeats = 3)

    companion object {
        private const val DEFAULT_PORT = 443
        private const val CONNECT_TIMEOUT_MS = 1000

        suspend fun test(url: String): Result = stableTest(url, repeats = 3)

        suspend fun stableTest(url: String, repeats: Int = 5): Result {
            val host = hostFromUrl(url) ?: return Result(-1L, null)
            return stableHosts(listOf(HostTarget(host, DEFAULT_PORT)), repeats)
        }

        suspend fun stableTest(platform: Platform, repeats: Int = 5): Result {
            if (platform.url == null) return Result(0L, null)
            val targets = platform.latencyHosts.ifEmpty {
                listOfNotNull(hostFromUrl(platform.url)?.let { HostTarget(it, DEFAULT_PORT) })
            }
            if (targets.isEmpty()) return Result(-1L, null)
            return stableHosts(targets, repeats)
        }

        private suspend fun stableHosts(targets: List<HostTarget>, repeats: Int): Result {
            val perHostSamples = repeats.coerceIn(3, 5)
            val hostValues = mutableListOf<Long>()
            for (target in targets) {
                val samples = mutableListOf<Long>()
                repeat(perHostSamples) { index ->
                    val sample = tcpConnect(target)
                    if (sample in 1..CONNECT_TIMEOUT_MS.toLong()) samples.add(sample)
                    if (index != perHostSamples - 1) delay(45L)
                }
                pickStableLow(samples)?.let { hostValues.add(it) }
            }
            val picked = pickStableLow(hostValues) ?: return Result(-1L, null)
            return Result(picked, null)
        }

        private suspend fun tcpConnect(target: HostTarget): Long = withContext(Dispatchers.IO) {
            try {
                val address = InetSocketAddress(target.host, target.port)
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    val start = SystemClock.elapsedRealtime()
                    socket.connect(address, CONNECT_TIMEOUT_MS)
                    SystemClock.elapsedRealtime() - start
                }
            } catch (_: Exception) {
                -1L
            }
        }

        private fun pickStableLow(samples: List<Long>): Long? {
            if (samples.isEmpty()) return null
            val sorted = samples.filter { it > 0L }.sorted()
            if (sorted.isEmpty()) return null
            if (sorted.size <= 2) return sorted.first()

            val median = sorted[sorted.size / 2]
            val filtered = sorted.filter { it <= max(median * 2L, median + 160L) }
            val stable = if (filtered.isEmpty()) sorted else filtered
            return when {
                stable.size >= 4 -> stable[1]
                stable.size >= 3 -> stable[1]
                else -> stable.first()
            }
        }

        private fun hostFromUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                java.net.URI(url).host
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class HostTarget(
    val host: String,
    val port: Int = 443
)

data class PlatformLatencySnapshot(
    val latestLatencyMs: Long,
    val smoothedLatencyMs: Long,
    val lastSuccessAt: Long,
    val failCount: Int,
    val requestId: Long
)

object PlatformLatencyStore {
    private const val ALPHA = 0.40
    private val states = mutableMapOf<String, MutablePlatformLatencyState>()

    @Synchronized
    fun beginRequest(key: String): Long {
        val state = stateFor(key)
        state.requestId += 1L
        return state.requestId
    }

    @Synchronized
    fun requestId(key: String): Long = stateFor(key).requestId

    @Synchronized
    fun snapshot(key: String): PlatformLatencySnapshot {
        return stateFor(key).snapshot()
    }

    @Synchronized
    fun applyResult(key: String, requestId: Long, rawMs: Long): PlatformLatencySnapshot? {
        val state = stateFor(key)
        if (state.requestId != requestId) return null

        if (rawMs < 0L) {
            state.failCount += 1
            if (state.smoothedLatencyMs < 0L) {
                state.latestLatencyMs = -1L
                state.smoothedLatencyMs = -1L
            }
            return state.snapshot()
        }

        state.latestLatencyMs = rawMs
        state.failCount = 0
        state.lastSuccessAt = SystemClock.elapsedRealtime()
        val previous = state.smoothedLatencyMs
        state.smoothedLatencyMs = when {
            previous < 0L -> rawMs
            rawMs > max(previous * 3L, previous + 220L) -> previous
            else -> (previous * (1.0 - ALPHA) + rawMs * ALPHA).roundToLong().coerceAtLeast(1L)
        }
        return state.snapshot()
    }

    @Synchronized
    fun setFixed(key: String, value: Long): PlatformLatencySnapshot {
        val state = stateFor(key)
        state.requestId += 1L
        state.latestLatencyMs = value
        state.smoothedLatencyMs = value
        state.lastSuccessAt = if (value >= 0L) SystemClock.elapsedRealtime() else 0L
        state.failCount = 0
        return state.snapshot()
    }

    @Synchronized
    fun reset(key: String) {
        states.remove(key)
    }

    @Synchronized
    fun resetAll() {
        states.clear()
    }

    private fun stateFor(key: String): MutablePlatformLatencyState {
        return states.getOrPut(key) { MutablePlatformLatencyState() }
    }

    private class MutablePlatformLatencyState {
        var latestLatencyMs: Long = -1L
        var smoothedLatencyMs: Long = -1L
        var lastSuccessAt: Long = 0L
        var failCount: Int = 0
        var requestId: Long = 0L

        fun snapshot(): PlatformLatencySnapshot = PlatformLatencySnapshot(
            latestLatencyMs = latestLatencyMs,
            smoothedLatencyMs = smoothedLatencyMs,
            lastSuccessAt = lastSuccessAt,
            failCount = failCount,
            requestId = requestId
        )
    }
}
