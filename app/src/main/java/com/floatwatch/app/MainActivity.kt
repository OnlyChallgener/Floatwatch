package com.floatwatch.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tester = LatencyTester()

    private val cardHolders = mutableMapOf<String, CardHolder>()
    private val latencyMap = mutableMapOf<String, Long>()
    private val baseMap = mutableMapOf<String, Pair<Long, Long>>()

    private lateinit var statusText: TextView
    private lateinit var offsetText: TextView
    private lateinit var startButton: Button
    private var floatingStarted = false
    private var activityStopwatchStartMs = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureDefaultPrefs()
        requestNotificationPermissionIfNeed()
        setContentView(buildContentView())
        refreshSelectedStyle()
        startTickerLoop()
        startLatencyLoop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureDefaultPrefs() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        if (!prefs.contains(KEY_SOURCE_ID)) {
            prefs.edit()
                .putString(KEY_SOURCE_ID, "system")
                .putString(KEY_MODE, MODE_CLOCK)
                .putLong(KEY_OFFSET_MS, 0L)
                .putLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS)
                .putBoolean(KEY_SHOW_PLATFORM, true)
                .apply()
        }
    }

    private fun buildContentView(): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 250))
        }

        val scroll = ScrollView(this).apply { isFillViewport = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(132))
        }
        scroll.addView(content)

        content.addView(buildTopControls())
        content.addView(buildStatusCard())
        content.addView(buildSourceGrid())
        content.addView(buildTips())

        frame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        frame.addView(buildBottomBar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        return frame
    }

    private fun buildTopControls(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        row.addView(pillButton("时钟") {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString(KEY_MODE, MODE_CLOCK)
                .apply()
            refreshSelectedStyle()
        })
        row.addView(space(dp(8), 1))
        row.addView(pillButton("秒表") {
            activityStopwatchStartMs = SystemClock.elapsedRealtime()
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putString(KEY_MODE, MODE_STOPWATCH)
                .apply()
            refreshSelectedStyle()
        })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(pillButton("权限") { openOverlayPermission() })
        return row
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = roundBg(Color.rgb(20, 20, 22), dp(18))
        }

        statusText = TextView(this).apply {
            text = "系统  00:00:00.0  0 ms"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
        }
        offsetText = TextView(this).apply {
            text = "偏移 0 ms · 每 5 秒刷新"
            textSize = 12f
            setTextColor(Color.rgb(190, 190, 190))
            setPadding(0, dp(8), 0, 0)
            includeFontPadding = false
        }
        card.addView(statusText)
        card.addView(offsetText)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
        return card
    }

    private fun buildSourceGrid(): View {
        val grid = GridLayout(this).apply {
            columnCount = 3
            useDefaultMargins = false
        }
        TimeSources.all.forEach { source ->
            val holder = buildSourceCard(source)
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(118)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(holder.root, lp)
            cardHolders[source.id] = holder
            latencyMap[source.id] = if (source.url == null) 0L else -1L
        }
        return grid
    }

    private fun buildSourceCard(source: TimeSource): CardHolder {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundBg(Color.WHITE, dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString(KEY_SOURCE_ID, source.id)
                    .apply()
                refreshSelectedStyle()
            }
        }

        val icon = TextView(this).apply {
            text = source.shortName
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = ovalBg(source.color)
            includeFontPadding = false
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))

        val name = TextView(this).apply {
            text = source.name
            textSize = 15f
            setTextColor(Color.rgb(28, 28, 30))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            includeFontPadding = false
        }
        root.addView(name)

        val time = TextView(this).apply {
            text = "--:--:--.-"
            textSize = 12f
            setTextColor(Color.rgb(45, 45, 45))
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            includeFontPadding = false
        }
        root.addView(time)

        val latency = TextView(this).apply {
            text = if (source.url == null) "0 ms" else "-- ms"
            textSize = 12f
            setTextColor(latencyColor(if (source.url == null) 0L else -1L))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(5), 0, 0)
            includeFontPadding = false
        }
        root.addView(latency)

        return CardHolder(source, root, time, latency)
    }

    private fun buildTips(): View {
        val text = TextView(this).apply {
            text = "使用须知\n1. 平台时间来自 HTTP Date 响应头，部分网站可能无时间头或拒绝请求\n2. 延迟是 HTTP 请求耗时，不等同于 ICMP Ping\n3. 悬浮窗需要手动授予“显示在其他应用上层”权限\n4. 自用测试版：默认 5 秒刷新一次，避免过高频率请求"
            textSize = 12f
            setTextColor(Color.rgb(118, 118, 118))
            setLineSpacing(dp(2).toFloat(), 1.0f)
            setPadding(dp(2), dp(18), dp(2), dp(12))
        }
        return text
    }

    private fun buildBottomBar(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = roundBg(Color.WHITE, dp(24), Color.rgb(235, 235, 235), dp(1))
            elevation = dp(10).toFloat()
        }

        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        offsetRow.addView(miniButton("-50ms") { changeOffset(-50L) })
        offsetRow.addView(miniButton("重置") { setOffset(0L) })
        offsetRow.addView(miniButton("+50ms") { changeOffset(50L) })
        offsetRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        offsetRow.addView(miniButton("5s") { setRefresh(5000L) })
        offsetRow.addView(miniButton("10s") { setRefresh(10000L) })
        box.addView(offsetRow)

        startButton = Button(this).apply {
            text = "开启悬浮"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(Color.rgb(69, 210, 107), dp(16))
            setOnClickListener { toggleFloating() }
        }
        box.addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56),
        ).apply { topMargin = dp(10) })
        return box
    }

    private fun startTickerLoop() {
        scope.launch {
            while (isActive) {
                updateTimes()
                delay(100L)
            }
        }
    }

    private fun startLatencyLoop() {
        scope.launch {
            while (isActive) {
                refreshAllLatencies()
                val refresh = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS)
                    .coerceIn(3000L, 60000L)
                delay(refresh)
            }
        }
    }

    private suspend fun refreshAllLatencies() = coroutineScope {
        TimeSources.all.filter { it.url != null }.forEach { source ->
            launch {
                val result = tester.measure(source)
                withContext(Dispatchers.Main.immediate) {
                    latencyMap[source.id] = result.latencyMs
                    if (result.serverEpochMs != null) {
                        baseMap[source.id] = result.serverEpochMs to SystemClock.elapsedRealtime()
                    }
                    updateSingleCard(source)
                    updateStatusText()
                }
            }
        }
    }

    private fun updateTimes() {
        TimeSources.all.forEach { updateSingleCard(it) }
        updateStatusText()
    }

    private fun updateSingleCard(source: TimeSource) {
        val holder = cardHolders[source.id] ?: return
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val offsetMs = prefs.getLong(KEY_OFFSET_MS, 0L)
        val base = baseMap[source.id]
        val now = currentDisplayEpochMs(source, base?.first, base?.second ?: 0L, offsetMs)
        val latency = latencyMap[source.id] ?: if (source.url == null) 0L else -1L
        holder.time.text = formatClockTime(now)
        holder.latency.text = latencyText(latency)
        holder.latency.setTextColor(latencyColor(latency))
    }

    private fun updateStatusText() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val source = TimeSources.byId(prefs.getString(KEY_SOURCE_ID, "system"))
        val mode = prefs.getString(KEY_MODE, MODE_CLOCK) ?: MODE_CLOCK
        val offsetMs = prefs.getLong(KEY_OFFSET_MS, 0L)
        val refreshMs = prefs.getLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS)

        if (mode == MODE_STOPWATCH) {
            val elapsed = SystemClock.elapsedRealtime() - activityStopwatchStartMs
            statusText.text = "秒表  ${formatStopwatch(elapsed)}"
            offsetText.text = "点击开启悬浮后，悬浮窗显示独立秒表"
            return
        }

        val base = baseMap[source.id]
        val now = currentDisplayEpochMs(source, base?.first, base?.second ?: 0L, offsetMs)
        val latency = latencyMap[source.id] ?: if (source.url == null) 0L else -1L
        statusText.text = "${source.name}  ${formatClockTime(now)}  ${latencyText(latency)}"
        val sign = when {
            offsetMs > 0 -> "+"
            else -> ""
        }
        offsetText.text = "偏移 $sign$offsetMs ms · 每 ${refreshMs / 1000} 秒刷新"
    }

    private fun refreshSelectedStyle() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val selected = prefs.getString(KEY_SOURCE_ID, "system") ?: "system"
        cardHolders.forEach { (id, holder) ->
            val isSelected = id == selected
            holder.root.background = if (isSelected) {
                roundBg(Color.rgb(239, 255, 244), dp(16), Color.rgb(69, 210, 107), dp(2))
            } else {
                roundBg(Color.WHITE, dp(16))
            }
        }
        updateStatusText()
    }

    private fun toggleFloating() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        floatingStarted = !floatingStarted
        val intent = Intent(this, FloatingWatchService::class.java)
        if (floatingStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            startButton.text = "关闭悬浮"
            startButton.background = roundBg(Color.rgb(34, 34, 36), dp(16))
        } else {
            stopService(intent)
            startButton.text = "开启悬浮"
            startButton.background = roundBg(Color.rgb(69, 210, 107), dp(16))
        }
    }

    private fun changeOffset(delta: Long) {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val next = (prefs.getLong(KEY_OFFSET_MS, 0L) + delta).coerceIn(-3000L, 3000L)
        setOffset(next)
    }

    private fun setOffset(value: Long) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putLong(KEY_OFFSET_MS, value).apply()
        updateStatusText()
    }

    private fun setRefresh(value: Long) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putLong(KEY_REFRESH_MS, value).apply()
        updateStatusText()
    }

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun pillButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(35, 35, 35))
            background = roundBg(Color.WHITE, dp(18), Color.rgb(235, 235, 235), dp(1))
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38),
            )
        }
    }

    private fun miniButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.rgb(55, 55, 55))
            background = roundBg(Color.rgb(245, 246, 248), dp(12))
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34),
            ).apply { rightMargin = dp(6) }
        }
    }

    private fun space(width: Int, height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
        }
    }

    data class CardHolder(
        val source: TimeSource,
        val root: LinearLayout,
        val time: TextView,
        val latency: TextView,
    )
}
