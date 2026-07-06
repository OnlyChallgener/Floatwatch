package com.floatwatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tester = LatencyTester()

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var rootView: LinearLayout
    private lateinit var mainText: TextView
    private lateinit var subText: TextView

    private var attached = false
    private var compact = false

    private var stopwatchStartMs = 0L
    private var lastMode = MODE_CLOCK
    private var lastSourceId = "system"

    private var networkLatencyMs = 0L
    private var networkBaseEpochMs: Long? = null
    private var networkBaseElapsedMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        stopwatchStartMs = SystemClock.elapsedRealtime()
        startForeground(1001, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addFloatingView()
        startDisplayLoop()
        startNetworkLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (attached) {
            runCatching { windowManager.removeView(rootView) }
            attached = false
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "floatwatch_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "悬浮秒表",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("悬浮秒表运行中")
            .setContentText("返回应用可关闭悬浮窗")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build()
    }

    private fun addFloatingView() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = roundBg(
                Color.argb(218, 15, 15, 18),
                dp(16),
                Color.argb(36, 255, 255, 255),
                dp(1),
            )
            elevation = dp(8).toFloat()
        }

        mainText = TextView(this).apply {
            text = "00:00:00.0"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
        }
        subText = TextView(this).apply {
            text = "系统 · 0 ms"
            textSize = 11f
            setTextColor(Color.rgb(67, 216, 117))
            setPadding(0, dp(5), 0, 0)
            includeFontPadding = false
        }
        rootView.addView(mainText)
        rootView.addView(subText)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(132)
        }

        bindDragAndClick()
        windowManager.addView(rootView, params)
        attached = true
    }

    private fun bindDragAndClick() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L

        rootView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    downTime = SystemClock.elapsedRealtime()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching { windowManager.updateViewLayout(rootView, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val moved = isSmallMove(event.rawX - downX, event.rawY - downY, dp(8))
                    val quick = SystemClock.elapsedRealtime() - downTime < 260
                    if (moved && quick) toggleCompact()
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleCompact() {
        compact = !compact
        subText.visibility = if (compact) View.GONE else View.VISIBLE
        mainText.textSize = if (compact) 20f else 24f
        rootView.setPadding(
            if (compact) dp(10) else dp(13),
            if (compact) dp(6) else dp(9),
            if (compact) dp(10) else dp(13),
            if (compact) dp(6) else dp(9),
        )
    }

    private fun startDisplayLoop() {
        scope.launch {
            while (isActive) {
                updateFloatingText()
                delay(100L)
            }
        }
    }

    private fun updateFloatingText() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val source = TimeSources.byId(prefs.getString(KEY_SOURCE_ID, "system"))
        val mode = prefs.getString(KEY_MODE, MODE_CLOCK) ?: MODE_CLOCK
        val offsetMs = prefs.getLong(KEY_OFFSET_MS, 0L)

        if (mode != lastMode) {
            if (mode == MODE_STOPWATCH) stopwatchStartMs = SystemClock.elapsedRealtime()
            lastMode = mode
        }

        if (mode == MODE_STOPWATCH) {
            val elapsed = SystemClock.elapsedRealtime() - stopwatchStartMs
            mainText.text = formatStopwatch(elapsed)
            subText.text = "秒表"
            subText.setTextColor(Color.rgb(67, 216, 117))
            return
        }

        val displayEpoch = currentDisplayEpochMs(source, networkBaseEpochMs, networkBaseElapsedMs, offsetMs)
        val latency = if (source.url == null) 0L else networkLatencyMs
        mainText.text = formatClockTime(displayEpoch)
        subText.text = "${source.name} · ${latencyText(latency)}"
        subText.setTextColor(latencyColor(latency))
    }

    private fun startNetworkLoop() {
        scope.launch {
            while (isActive) {
                val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                val source = TimeSources.byId(prefs.getString(KEY_SOURCE_ID, "system"))
                val refreshMs = prefs.getLong(KEY_REFRESH_MS, DEFAULT_REFRESH_MS).coerceIn(3000L, 60000L)

                if (source.id != lastSourceId) {
                    lastSourceId = source.id
                    networkLatencyMs = if (source.url == null) 0L else -1L
                    networkBaseEpochMs = null
                    networkBaseElapsedMs = 0L
                }

                if (source.url == null) {
                    networkLatencyMs = 0L
                    networkBaseEpochMs = null
                } else {
                    val result = withContext(Dispatchers.IO) { tester.measure(source) }
                    networkLatencyMs = result.latencyMs
                    if (result.serverEpochMs != null) {
                        networkBaseEpochMs = result.serverEpochMs
                        networkBaseElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
                delay(refreshMs)
            }
        }
    }
}
