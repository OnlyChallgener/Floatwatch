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

class FloatingService : Service() {
    companion object {
        const val EXTRA_PLATFORM_NAME = "platform_name"
        const val EXTRA_PLATFORM_URL = "platform_url"
        const val EXTRA_OFFSET_MS = "offset_ms"
        const val EXTRA_REFRESH_INTERVAL_MS = "refresh_interval_ms"
        const val EXTRA_STOPWATCH_MODE = "stopwatch_mode"

        private const val CHANNEL_ID = "floatwatch_overlay"
        private const val NOTIFICATION_ID = 10086
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null
    private var clockJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var overlayView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    private lateinit var titleView: TextView
    private lateinit var timeView: TextView
    private lateinit var latencyView: TextView

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
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBg(0xDD111827.toInt(), 16f, view = this)
        }

        titleView = TextView(this).apply {
            text = platformName
            textSize = 12f
            setTextColor(0xFFE5E7EB.toInt())
            gravity = Gravity.CENTER
        }
        timeView = TextView(this).apply {
            text = "--:--:--.-"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            bold()
        }
        latencyView = TextView(this).apply {
            text = if (platformUrl == null) "0 ms" else "-- ms"
            textSize = 12f
            setTextColor(0xFF86EFAC.toInt())
            gravity = Gravity.CENTER
        }

        view.addView(titleView)
        view.addView(timeView)
        view.addView(latencyView)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(120)
        }

        attachDragAndClick(view, lp)
        overlayView = view
        params = lp
        windowManager.addView(view, lp)
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

    private fun attachDragAndClick(view: View, lp: WindowManager.LayoutParams) {
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) {
                        moved = true
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        compact = !compact
                        applyCompactMode()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyCompactMode() {
        titleView.visibility = if (compact) View.GONE else View.VISIBLE
        latencyView.visibility = if (compact) View.GONE else View.VISIBLE
        timeView.textSize = if (compact) 20f else 23f
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
            latencyView.text = "0 ms"
            latencyView.setTextColor(0xFF86EFAC.toInt())
            return
        }

        val result = LatencyTester.test(url)
        latestLatencyMs = result.latencyMs
        serverOffsetMs = result.serverOffsetMs ?: 0L
        latencyView.text = latencyText(latestLatencyMs)
        latencyView.setTextColor(latencyColor(latestLatencyMs))
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
            .setContentText("点击 App 可关闭或重新开启悬浮窗")
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
            value < 0L -> 0xFFD1D5DB.toInt()
            value <= 80L -> 0xFF86EFAC.toInt()
            value <= 150L -> 0xFFFBBF24.toInt()
            else -> 0xFFF87171.toInt()
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
