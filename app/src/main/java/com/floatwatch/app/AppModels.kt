package com.floatwatch.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

const val PREF_NAME = "floatwatch_prefs"
const val KEY_SOURCE_ID = "source_id"
const val KEY_MODE = "mode"
const val KEY_OFFSET_MS = "offset_ms"
const val KEY_REFRESH_MS = "refresh_ms"
const val KEY_SHOW_PLATFORM = "show_platform"

const val MODE_CLOCK = "clock"
const val MODE_STOPWATCH = "stopwatch"
const val DEFAULT_REFRESH_MS = 5000L

data class TimeSource(
    val id: String,
    val name: String,
    val shortName: String,
    val color: Int,
    val url: String?,
)

data class LatencyResult(
    val sourceId: String,
    val latencyMs: Long,
    val serverEpochMs: Long?,
    val error: String? = null,
)

object TimeSources {
    val all = listOf(
        TimeSource("system", "系统", "系", Color.rgb(67, 160, 235), null),
        TimeSource("beijing", "北京", "北", Color.rgb(245, 190, 35), "https://www.aliyun.com"),
        TimeSource("jd", "京东", "京", Color.rgb(204, 30, 30), "https://api.m.jd.com"),
        TimeSource("taobao", "淘宝", "淘", Color.rgb(247, 92, 28), "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"),
        TimeSource("pdd", "拼多多", "拼", Color.rgb(210, 20, 38), "https://mobile.yangkeduo.com"),
        TimeSource("suning", "苏宁", "苏", Color.rgb(20, 20, 20), "https://f.suning.com/api/ct.do"),
        TimeSource("xiaomi", "小米", "米", Color.rgb(255, 105, 0), "https://www.mi.com"),
        TimeSource("huawei", "华为", "华", Color.rgb(220, 0, 30), "https://www.huawei.com"),
        TimeSource("douyin", "抖音", "抖", Color.rgb(15, 15, 20), "https://www.douyin.com"),
        TimeSource("kuaishou", "快手", "快", Color.rgb(255, 116, 0), "https://www.kuaishou.com"),
        TimeSource("bilibili", "B站", "B", Color.rgb(251, 113, 153), "https://www.bilibili.com"),
        TimeSource("meituan", "美团", "美", Color.rgb(255, 204, 0), "https://www.meituan.com"),
    )

    fun byId(id: String?): TimeSource = all.firstOrNull { it.id == id } ?: all.first()
}

fun parseHttpDate(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        parser.timeZone = TimeZone.getTimeZone("GMT")
        parser.parse(value)?.time
    }.getOrNull()
}

fun formatClockTime(epochMs: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss.S", Locale.US)
    formatter.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    return formatter.format(Date(epochMs))
}

fun formatStopwatch(elapsedMs: Long): String {
    val safe = elapsedMs.coerceAtLeast(0L)
    val minutes = safe / 60000
    val seconds = (safe / 1000) % 60
    val millis = safe % 1000
    return "%02d:%02d.%03d".format(Locale.US, minutes, seconds, millis)
}

fun latencyColor(latencyMs: Long): Int {
    return when {
        latencyMs < 0 -> Color.rgb(150, 150, 150)
        latencyMs <= 80 -> Color.rgb(42, 190, 96)
        latencyMs <= 150 -> Color.rgb(238, 154, 45)
        else -> Color.rgb(226, 70, 70)
    }
}

fun latencyText(latencyMs: Long): String {
    return if (latencyMs < 0) "-- ms" else "$latencyMs ms"
}

fun currentDisplayEpochMs(source: TimeSource, baseEpochMs: Long?, baseElapsedMs: Long, offsetMs: Long): Long {
    val raw = if (source.url == null || baseEpochMs == null) {
        System.currentTimeMillis()
    } else {
        baseEpochMs + (SystemClock.elapsedRealtime() - baseElapsedMs)
    }
    return raw + offsetMs
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun roundBg(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT, strokeWidth: Int = 0): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }
}

fun ovalBg(color: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}

fun TextView.boldMono() {
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

fun isSmallMove(dx: Float, dy: Float, thresholdPx: Int): Boolean {
    return abs(dx) + abs(dy) < thresholdPx
}
