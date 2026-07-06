package com.floatwatch.app

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

/**
 * Accurate time base for抢购/整点倒计时.
 * Time is driven by elapsedRealtime() so it will not jump if system wall clock changes.
 * NTP is used only to calibrate the base offset. If NTP fails, system clock is used as fallback.
 */
object TimeKeeper {
    @Volatile private var baseOffsetMs: Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    @Volatile private var lastSyncElapsedMs: Long = 0L

    fun now(): Long = SystemClock.elapsedRealtime() + baseOffsetMs

    suspend fun syncBest(): Boolean = withContext(Dispatchers.IO) {
        val servers = listOf("ntp.aliyun.com", "ntp.tencent.com", "time.cloudflare.com", "pool.ntp.org")
        var best: NtpSample? = null
        for (server in servers) {
            repeat(2) {
                val sample = query(server)
                if (sample != null && (best == null || sample.rttMs < best!!.rttMs)) best = sample
            }
            if (best != null && best!!.rttMs <= 80L) return@withContext applySample(best!!)
        }
        best?.let { applySample(it) } ?: false
    }

    private fun applySample(sample: NtpSample): Boolean {
        // Ignore obviously wrong samples.
        if (sample.rttMs !in 1..1500) return false
        synchronized(this) {
            baseOffsetMs = sample.serverTimeAtReturnMs - sample.elapsedAtReturnMs
            lastSyncElapsedMs = sample.elapsedAtReturnMs
        }
        return true
    }

    private data class NtpSample(
        val serverTimeAtReturnMs: Long,
        val elapsedAtReturnMs: Long,
        val rttMs: Long
    )

    private fun query(host: String): NtpSample? {
        return try {
            val buffer = ByteArray(48)
            buffer[0] = 0x1B
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 900
                val request = DatagramPacket(buffer, buffer.size, address, 123)
                val startElapsed = SystemClock.elapsedRealtime()
                socket.send(request)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val endElapsed = SystemClock.elapsedRealtime()
                val rtt = endElapsed - startElapsed
                val transmitTime = readTimeStamp(buffer, 40)
                if (transmitTime <= 0L || abs(rtt) > 3000L) return null
                // Approximate server time at receive moment by adding half RTT.
                NtpSample(
                    serverTimeAtReturnMs = transmitTime + rtt / 2L,
                    elapsedAtReturnMs = endElapsed,
                    rttMs = rtt
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = readUnsigned32(buffer, offset)
        val fraction = readUnsigned32(buffer, offset + 4)
        val ms = (seconds - 2208988800L) * 1000L + (fraction * 1000L) / 0x100000000L
        return ms
    }

    private fun readUnsigned32(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0..3) value = (value shl 8) or (buffer[offset + i].toLong() and 0xffL)
        return value
    }
}
