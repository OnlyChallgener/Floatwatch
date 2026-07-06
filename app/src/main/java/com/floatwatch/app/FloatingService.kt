package com.floatwatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingService : Service() {
    companion object {
        const val EXTRA_PLATFORM_NAME = "platform_name"
        const val EXTRA_PLATFORM_URL = "platform_url"
        const val EXTRA_OFFSET_MS = "offset_ms"
        const val EXTRA_REFRESH_INTERVAL_MS = "refresh_interval_ms"
        const val EXTRA_STOPWATCH_MODE = "stopwatch_mode"

        private const val CHANNEL_ID = "floatwatch_overlay"
        private const val NOTIFICATION_ID = 10086

        private const val ONE_UI_CARD = 0xF21D212B.toInt()
        private const val ONE_UI_CARD_COMPACT = 0xF2222632.toInt()
        private const val ONE_UI_TEXT = 0xFFFFFFFF.toInt()
        private const val ONE_UI_MUTED = 0xFFB8C0CC.toInt()
        private const val ONE_UI_STROKE = 0x22FFFFFF
        private const val ONE_UI_GREEN = 0xFF4ADE80.toInt()
        private const val ONE_UI_ORANGE = 0xFFFBBF24.toInt()
        private const val ONE_UI_RED = 0xFFF87171.toInt()
        private const val ONE_UI_GRAY = 0xFFCBD5E1.toInt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var clockJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var topRow: LinearLayout
    private lateinit var statusDot: View
    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var latencyView: TextView
    private lateinit var hintView: TextView

    private var platformName = "系统时间"
    private var platformUrl: String? = null
    private var offsetMs = 0L
    private var serverOffsetMs = 0L
    private var latestLatencyMs = 0L
    private var refreshIntervalMs = 5000L
    private var stopwatchMode = false
    private var compact = false
    private var stopwatchStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        platformName = intent?.getStringExtra(EXTRA_PLATFORM_NAME) ?: "系统时间"
        platformUrl = intent?.getStringExtra(EXTRA_PLATFORM_URL)
        offsetMs = intent?.getLongExtra(EXTRA_OFFSET_MS, 0L) ?: 0L
        refreshIntervalMs = intent?.getLongExtra(EXTRA_REFRESH_INTERVAL_MS, 5000L) ?: 5000L
        stopwatchMode = intent?.getBooleanExtra(EXTRA_STOPWATCH_MODE, false) ?: false
        stopwatchStartMs = System.currentTimeMillis()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        removeOverlay()
        addOverlay()
        startClockLoop()
        startRefreshLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        removeOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = roundedBg(ONE_UI_CARD, 24f, 1, ONE_UI_STROKE, this)
            elevation = dp(14).toFloat()
            alpha = 0.98f
        }

        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusDot = View(this).apply {
            background = roundedBg(ONE_UI_GREEN, 999f, view = this)
        }

        titleView = TextView(this).apply {
            text = platformName
            textSize = 12.5f
            setTextColor(ONE_UI_MUTED)
            includeFontPadding = false
            maxLines = 1
            bold()
        }

        latencyView = TextView(this).apply {
            text = if (platformUrl == null) "0 ms" else "-- ms"
            textSize = 11.5f
            setTextColor(ONE_UI_GREEN)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = roundedBg(0x1F4ADE80, 999f, 1, 0x334ADE80, this)
            bold()
        }

        topRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        topRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(7)
            rightMargin = dp(10)
        })
        topRow.addView(latencyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)))

        timeView = TextView(this).apply {
            text = "--:--:--.-"
            textSize = 25f
            setTextColor(ONE_UI_TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = 0.02f
            bold()
        }

        hintView = TextView(this).apply {
            text = if (stopwatchMode) "秒表 · 单击收起 · 拖动移动" else "时间校准 · 单击收起 · 拖动移动"
            textSize = 10.5f
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        root.addView(topRow, LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(timeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })
        root.addView(hintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(120)
        }

        attachDragAndClick(root, lp)
        overlayView = root
        params = lp
        windowManager.addView(root, lp)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        params = null
    }

    private fun attachDragAndClick(view: LinearLayout, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    view.animate().alpha(0.88f).setDuration(90L).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    safeUpdate(view, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().alpha(0.98f).setDuration(120L).start()
                    if (!moved) {
                        compact = !compact
                        applyCompactMode(view)
                    } else {
                        snapToNearestEdge(view, lp)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyCompactMode(root: LinearLayout) {
        if (compact) {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER
            root.setPadding(dp(14), dp(8), dp(14), dp(8))
            root.background = roundedBg(ONE_UI_CARD_COMPACT, 999f, 1, ONE_UI_STROKE, root)
            topRow.visibility = View.GONE
            hintView.visibility = View.GONE
            latencyView.visibility = View.GONE
            timeView.textSize = 19f
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.gravity = Gravity.CENTER
            root.setPadding(dp(14), dp(11), dp(14), dp(11))
            root.background = roundedBg(ONE_UI_CARD, 24f, 1, ONE_UI_STROKE, root)
            topRow.visibility = View.VISIBLE
            hintView.visibility = View.VISIBLE
            latencyView.visibility = View.VISIBLE
            timeView.textSize = 25f
        }
        root.post {
            params?.let { lp -> safeUpdate(root, lp) }
        }
    }

    private fun snapToNearestEdge(view: View, lp: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val margin = dp(10)
        val maxX = max(margin, metrics.widthPixels - view.width - margin)
        val maxY = max(margin, metrics.heightPixels - view.height - dp(32))
        val centerX = lp.x + view.width / 2
        lp.x = if (centerX < metrics.widthPixels / 2) margin else maxX
        lp.y = min(max(lp.y, margin), maxY)
        safeUpdate(view, lp)
    }

    private fun safeUpdate(view: View, lp: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, lp)
        } catch (_: Exception) {
        }
    }

    private fun startClockLoop() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                val text = if (stopwatchMode) {
                    formatStopwatch(System.currentTimeMillis() - stopwatchStartMs)
                } else {
                    formatTime(System.currentTimeMillis() + offsetMs + serverOffsetMs)
                }
                timeView.text = text
                delay(100L)
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                refreshLatency()
                delay(refreshIntervalMs)
            }
        }
    }

    private suspend fun refreshLatency() {
        val url = platformUrl
        if (url == null) {
            latestLatencyMs = 0L
            serverOffsetMs = 0L
            updateLatencyUi(0L)
            return
        }

        val result = LatencyTester.test(url)
        latestLatencyMs = result.latencyMs
        serverOffsetMs = result.serverOffsetMs ?: 0L
        updateLatencyUi(latestLatencyMs)
    }

    private fun updateLatencyUi(value: Long) {
        val color = latencyColor(value)
        latencyView.text = latencyText(value)
        latencyView.setTextColor(color)
        latencyView.background = roundedBg(latencyPillBg(value), 999f, 1, latencyPillStroke(value), latencyView)
        statusDot.background = roundedBg(color, 999f, view = statusDot)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("悬浮秒表运行中")
            .setContentText("Floatwatch One UI HUD 已开启")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floatwatch Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun latencyText(value: Long): String {
        return when {
            value < 0L -> "失败"
            else -> "$value ms"
        }
    }

    private fun latencyColor(value: Long): Int {
        return when {
            value < 0L -> ONE_UI_GRAY
            value <= 80L -> ONE_UI_GREEN
            value <= 150L -> ONE_UI_ORANGE
            else -> ONE_UI_RED
        }
    }

    private fun latencyPillBg(value: Long): Int {
        return when {
            value < 0L -> 0x26334455
            value <= 80L -> 0x244ADE80
            value <= 150L -> 0x26FBBF24
            else -> 0x26F87171
        }
    }

    private fun latencyPillStroke(value: Long): Int {
        return when {
            value < 0L -> 0x40334455
            value <= 80L -> 0x444ADE80
            value <= 150L -> 0x44FBBF24
            else -> 0x44F87171
        }
    }

    private fun formatTime(millis: Long): String {
        return SimpleDateFormat("HH:mm:ss.S", Locale.CHINA).format(Date(millis))
    }

    private fun formatStopwatch(elapsedMs: Long): String {
        val minutes = elapsedMs / 60000
        val seconds = (elapsedMs % 60000) / 1000
        val millis = elapsedMs % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
