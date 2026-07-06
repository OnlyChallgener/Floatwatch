package com.floatwatch.app

import android.content.Context
import android.graphics.Color

object ConfigStore {
    private const val PREF_NAME = "floatwatch_config"

    const val MODE_CLOCK = "clock"
    const val MODE_COUNTDOWN = "countdown"

    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val SIZE_SMALL = "small"
    const val SIZE_MEDIUM = "medium"
    const val SIZE_LARGE = "large"

    data class WatchConfig(
        val mode: String,
        val platformName: String,
        val platformShortName: String,
        val platformUrl: String?,
        val platformColor: Int,
        val offsetMs: Long,
        val autoRefresh: Boolean,
        val refreshIntervalMs: Long,
        val countdownMs: Long,
        val opacityPercent: Int,
        val size: String,
        val theme: String,
        val showPlatform: Boolean,
        val showLatency: Boolean,
        val x: Int,
        val y: Int
    )

    fun load(context: Context): WatchConfig {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val platformName = sp.getString("platformName", Platforms.items.first().name) ?: Platforms.items.first().name
        val platform = Platforms.items.firstOrNull { it.name == platformName } ?: Platforms.items.first()
        return WatchConfig(
            mode = sp.getString("mode", MODE_CLOCK) ?: MODE_CLOCK,
            platformName = platform.name,
            platformShortName = platform.shortName,
            platformUrl = platform.url,
            platformColor = platform.color,
            offsetMs = sp.getLong("offsetMs", 0L),
            autoRefresh = sp.getBoolean("autoRefresh", true),
            refreshIntervalMs = sp.getLong("refreshIntervalMs", 5000L),
            countdownMs = sp.getLong("countdownMs", 30000L),
            opacityPercent = sp.getInt("opacityPercent", 88),
            size = sp.getString("size", SIZE_MEDIUM) ?: SIZE_MEDIUM,
            theme = sp.getString("theme", THEME_DARK) ?: THEME_DARK,
            showPlatform = sp.getBoolean("showPlatform", true),
            showLatency = sp.getBoolean("showLatency", true),
            x = sp.getInt("x", Int.MIN_VALUE),
            y = sp.getInt("y", 180)
        )
    }

    fun savePlatform(context: Context, platform: Platform) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("platformName", platform.name)
            .apply()
    }

    fun saveMode(context: Context, mode: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("mode", mode)
            .apply()
    }

    fun saveOffset(context: Context, offsetMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("offsetMs", offsetMs)
            .apply()
    }

    fun saveRefresh(context: Context, autoRefresh: Boolean, intervalMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("autoRefresh", autoRefresh)
            .putLong("refreshIntervalMs", intervalMs)
            .apply()
    }

    fun saveCountdown(context: Context, countdownMs: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong("countdownMs", countdownMs)
            .apply()
    }

    fun saveStyle(context: Context, opacityPercent: Int, size: String, theme: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("opacityPercent", opacityPercent.coerceIn(45, 100))
            .putString("size", size)
            .putString("theme", theme)
            .apply()
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("x", x)
            .putInt("y", y)
            .apply()
    }
}

fun latencyColor(value: Long): Int = when {
    value < 0 -> Color.rgb(148, 163, 184)
    value <= 80 -> Color.rgb(34, 197, 94)
    value <= 150 -> Color.rgb(245, 158, 11)
    else -> Color.rgb(239, 68, 68)
}

fun latencyText(value: Long): String = if (value < 0) "-- ms" else "$value ms"
