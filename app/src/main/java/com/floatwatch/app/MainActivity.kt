package com.floatwatch.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private var selectedPlatform: Platform = Platforms.items.first()
    private var offsetMs: Long = 0L
    private var serverOffsetMs: Long = 0L
    private var latestLatencyMs: Long = 0L
    private var refreshIntervalMs: Long = 5000L
    private var stopwatchMode = false

    private lateinit var statusSource: TextView
    private lateinit var statusTime: TextView
    private lateinit var statusLatency: TextView
    private lateinit var grid: GridLayout
    private val cardViews = mutableMapOf<String, LinearLayout>()
    private val cardLatencyViews = mutableMapOf<String, TextView>()
    private val cardTimeViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        buildUi()
        startClockLoop()
        startRefreshLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val spacer = View(this)
        topBar.addView(spacer, LinearLayout.LayoutParams(0, dp(44), 1f))

        val permissionButton = smallButton("权限") {
            openOverlayPermission()
        }
        val settingsButton = smallButton("5s") {
            refreshIntervalMs = if (refreshIntervalMs == 5000L) 10000L else 5000L
            (it as Button).text = if (refreshIntervalMs == 5000L) "5s" else "10s"
            startRefreshLoop()
        }
        topBar.addView(permissionButton)
        topBar.addView(settingsButton, marginLeft = dp(8))
        root.addView(topBar)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(Color.WHITE, 20f, view = this)
            elevation = 2f * density
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusSource = TextView(this).apply {
            text = selectedPlatform.name
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            bold()
        }
        statusLatency = TextView(this).apply {
            text = "0 ms"
            textSize = 14f
            gravity = Gravity.END
            setTextColor(Color.rgb(34, 197, 94))
            bold()
        }
        statusRow.addView(statusSource, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(statusLatency, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))

        statusTime = TextView(this).apply {
            text = formatTime(System.currentTimeMillis())
            textSize = 34f
            setTextColor(Color.rgb(17, 24, 39))
            includeFontPadding = false
            bold()
        }
        statusCard.addView(statusRow)
        statusCard.addView(statusTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        root.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        grid = GridLayout(this).apply {
            columnCount = 2
        }
        Platforms.items.forEach { platform ->
            val card = platformCard(platform)
            cardViews[platform.name] = card
            grid.addView(card)
        }

        val scroll = ScrollView(this).apply {
            addView(grid)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(14)
        })

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controlRow.addView(smallButton("-50ms") {
            offsetMs -= 50L
            updateStatus()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        controlRow.addView(smallButton("重置") {
            offsetMs = 0L
            updateStatus()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        controlRow.addView(smallButton("+50ms") {
            offsetMs += 50L
            updateStatus()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(controlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(10)
        })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        modeRow.addView(smallButton("时钟") {
            stopwatchMode = false
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        modeRow.addView(smallButton("秒表") {
            stopwatchMode = true
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })

        val startButton = Button(this).apply {
            text = "开启悬浮"
            textSize = 17f
            setTextColor(Color.WHITE)
            background = roundedBg(Color.rgb(34, 197, 94), 18f, view = this)
            setOnClickListener { startFloating() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
            topMargin = dp(10)
        })

        setContentView(root)
        updateSelectedCards()
    }

    private fun platformCard(platform: Platform): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = roundedBg(Color.WHITE, 18f, view = this)
            elevation = 1f * resources.displayMetrics.density
            setOnClickListener {
                selectedPlatform = platform
                latestLatencyMs = if (platform.url == null) 0L else -1L
                serverOffsetMs = 0L
                updateSelectedCards()
                updateStatus()
                startRefreshLoop()
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = platform.shortName
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            bold()
            background = roundedBg(platform.color, 999f, view = this)
        }
        val name = TextView(this).apply {
            text = platform.name
            textSize = 15f
            setTextColor(Color.rgb(31, 41, 55))
            bold()
        }
        top.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(10)
        })

        val time = TextView(this).apply {
            text = formatTime(System.currentTimeMillis())
            textSize = 18f
            setTextColor(Color.rgb(17, 24, 39))
            includeFontPadding = false
            bold()
        }
        val latency = TextView(this).apply {
            text = if (platform.url == null) "0 ms" else "-- ms"
            textSize = 13f
            setTextColor(Color.rgb(107, 114, 128))
        }
        cardTimeViews[platform.name] = time
        cardLatencyViews[platform.name] = latency

        card.addView(top)
        card.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        card.addView(latency, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val lp = GridLayout.LayoutParams().apply {
            width = (resources.displayMetrics.widthPixels - dp(16 * 2) - dp(10)) / 2
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setMargins(0, 0, dp(10), dp(10))
        }
        card.layoutParams = lp
        return card
    }

    private fun startClockLoop() {
        scope.launch {
            while (isActive) {
                updateStatus()
                updateCardTimes()
                delay(100L)
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                refreshSelectedLatency()
                delay(refreshIntervalMs)
            }
        }
    }

    private suspend fun refreshSelectedLatency() {
        val url = selectedPlatform.url
        if (url == null) {
            latestLatencyMs = 0L
            serverOffsetMs = 0L
            updateStatus()
            return
        }
        val result = LatencyTester.test(url)
        latestLatencyMs = result.latencyMs
        serverOffsetMs = result.serverOffsetMs ?: 0L
        cardLatencyViews[selectedPlatform.name]?.apply {
            text = latencyText(latestLatencyMs)
            setTextColor(latencyColor(latestLatencyMs))
        }
        updateStatus()
    }

    private fun updateStatus() {
        val now = System.currentTimeMillis() + offsetMs + serverOffsetMs
        statusSource.text = selectedPlatform.name
        statusTime.text = if (stopwatchMode) "秒表模式" else formatTime(now)
        statusLatency.text = latencyText(latestLatencyMs)
        statusLatency.setTextColor(latencyColor(latestLatencyMs))
    }

    private fun updateCardTimes() {
        val now = System.currentTimeMillis()
        cardTimeViews.forEach { (_, view) ->
            view.text = formatTime(now)
        }
    }

    private fun updateSelectedCards() {
        cardViews.forEach { (name, view) ->
            val selected = name == selectedPlatform.name
            view.background = if (selected) {
                roundedBg(0xFFEFFDF4.toInt(), 18f, 2, Color.rgb(34, 197, 94), view)
            } else {
                roundedBg(Color.WHITE, 18f, view = view)
            }
        }
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_PLATFORM_NAME, selectedPlatform.name)
            putExtra(FloatingService.EXTRA_PLATFORM_URL, selectedPlatform.url)
            putExtra(FloatingService.EXTRA_OFFSET_MS, offsetMs)
            putExtra(FloatingService.EXTRA_REFRESH_INTERVAL_MS, refreshIntervalMs)
            putExtra(FloatingService.EXTRA_STOPWATCH_MODE, stopwatchMode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun smallButton(textValue: String, action: (View) -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 14f
            setTextColor(Color.rgb(31, 41, 55))
            background = roundedBg(Color.WHITE, 14f, view = this)
            setOnClickListener(action)
            minHeight = 0
            minWidth = 0
            includeFontPadding = false
        }
    }

    private fun LinearLayout.addView(view: View, marginLeft: Int) {
        addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            leftMargin = marginLeft
        })
    }

    private fun latencyText(value: Long): String {
        return when {
            value < 0L -> "失败"
            else -> "$value ms"
        }
    }

    private fun latencyColor(value: Long): Int {
        return when {
            value < 0L -> Color.rgb(156, 163, 175)
            value <= 80L -> Color.rgb(34, 197, 94)
            value <= 150L -> Color.rgb(245, 158, 11)
            else -> Color.rgb(239, 68, 68)
        }
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss.S", Locale.CHINA).format(Date(millis))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
