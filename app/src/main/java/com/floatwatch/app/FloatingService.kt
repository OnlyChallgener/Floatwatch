package com.floatwatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.widget.Button
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class FloatingService : Service() {
    companion object {
        const val ACTION_SHOW = "com.floatwatch.app.action.SHOW"
        const val ACTION_HIDE = "com.floatwatch.app.action.HIDE"
        const val ACTION_STOP = "com.floatwatch.app.action.STOP"
        const val ACTION_TOGGLE_COMPACT = "com.floatwatch.app.action.TOGGLE_COMPACT"
        const val ACTION_PAUSE_REFRESH = "com.floatwatch.app.action.PAUSE_REFRESH"

        private const val CHANNEL_ID = "floatwatch_running"
        private const val NOTIFICATION_ID = 24021
    }

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var clockJob: Job? = null
    private var refreshJob: Job? = null

    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var compact = false
    private var paused = false

    private var cfg: ConfigStore.WatchConfig? = null
    private var latestLatencyMs: Long = 0L
    private var serverOffsetMs: Long = 0L
    private var countdownEndAtMs: Long = 0L

    private lateinit var statusDot: View
    private lateinit var sourceView: TextView
    private lateinit var latencyView: TextView
    private lateinit var timeView: TextView
    private lateinit var hintView: TextView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
    }

    override fun onDestroy() {
        removeFloatingView()
        removeMenu()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_SHOW
        startForeground(NOTIFICATION_ID, buildNotification())
        when (action) {
            ACTION_SHOW -> {
                paused = false
                showFloatingView()
            }
            ACTION_HIDE -> removeFloatingView()
            ACTION_STOP -> {
                removeFloatingView()
                removeMenu()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_TOGGLE_COMPACT -> {
                compact = !compact
                showFloatingView()
            }
            ACTION_PAUSE_REFRESH -> {
                paused = !paused
                updateHint()
            }
            else -> showFloatingView()
        }
        return START_STICKY
    }

    private fun showFloatingView() {
        if (!Settings.canDrawOverlays(this)) return
        removeFloatingView()
        removeMenu()

        cfg = ConfigStore.load(this)
        latestLatencyMs = if (cfg?.platformUrl == null) 0L else -1L
        serverOffsetMs = 0L
        if (cfg?.mode == ConfigStore.MODE_COUNTDOWN) {
            countdownEndAtMs = System.currentTimeMillis() + (cfg?.countdownMs ?: 30000L)
        }

        val view = if (compact) buildCompactView() else buildFullView()
        floatingView = view

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            val savedX = cfg?.x ?: Int.MIN_VALUE
            x = if (savedX == Int.MIN_VALUE) windowManager.defaultDisplayWidth() - dp(190) else savedX
            y = cfg?.y ?: dp(180)
        }
        floatingParams = params
        windowManager.addView(view, params)

        makeDraggable(view, params)
        startClockLoop()
        startRefreshLoop()
    }

    private fun buildFullView(): LinearLayout {
        val dark = cfg?.theme != ConfigStore.THEME_LIGHT
        val opacity = cfg?.opacityPercent ?: 88
        val bgColor = if (dark) alphaColor(Color.rgb(15, 23, 42), opacity) else alphaColor(Color.WHITE, opacity)
        val primaryText = if (dark) Color.WHITE else Color.rgb(15, 23, 42)
        val secondaryText = if (dark) Color.rgb(203, 213, 225) else Color.rgb(71, 85, 105)
        val scale = sizeScale()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((dp(14) * scale).roundToInt(), (dp(12) * scale).roundToInt(), (dp(14) * scale).roundToInt(), (dp(10) * scale).roundToInt())
            background = roundedBg(bgColor, 28f, 1, if (dark) Color.argb(60, 255, 255, 255) else Color.rgb(226, 232, 240), this)
            elevation = dp(12).toFloat()
            alpha = 1f

            val top = LinearLayout(this@FloatingService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusDot = View(this@FloatingService).apply { background = roundedBg(latencyColor(latestLatencyMs), 999f, view = this) }
            sourceView = TextView(this@FloatingService).apply {
                text = cfg?.platformName ?: "系统时间"
                textSize = 13f * scale
                setTextColor(secondaryText)
                bold()
            }
            latencyView = TextView(this@FloatingService).apply {
                text = latencyText(latestLatencyMs)
                textSize = 12f * scale
                gravity = Gravity.CENTER
                setTextColor(latencyColor(latestLatencyMs))
                bold()
                background = roundedBg(if (dark) Color.argb(42, 255, 255, 255) else Color.rgb(248, 250, 252), 999f, 1, Color.argb(35, 148, 163, 184), this)
            }
            top.addView(statusDot, LinearLayout.LayoutParams((dp(8) * scale).roundToInt(), (dp(8) * scale).roundToInt()))
            top.addView(sourceView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = (dp(8) * scale).roundToInt() })
            top.addView(latencyView, LinearLayout.LayoutParams((dp(70) * scale).roundToInt(), (dp(28) * scale).roundToInt()))

            timeView = TextView(this@FloatingService).apply {
                text = "--:--:--.-"
                textSize = 30f * scale
                setTextColor(primaryText)
                includeFontPadding = false
                bold()
            }
            hintView = TextView(this@FloatingService).apply {
                text = "单击精简 · 长按菜单 · 通知栏可关闭"
                textSize = 10f * scale
                setTextColor(secondaryText)
            }
            addView(top, LinearLayout.LayoutParams((dp(190) * scale).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(timeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (dp(8) * scale).roundToInt() })
            addView(hintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (dp(6) * scale).roundToInt() })

            setOnClickListener { compact = true; showFloatingView() }
            setOnLongClickListener { showFloatingMenu(); true }
        }
    }

    private fun buildCompactView(): LinearLayout {
        val dark = cfg?.theme != ConfigStore.THEME_LIGHT
        val opacity = cfg?.opacityPercent ?: 88
        val bgColor = if (dark) alphaColor(Color.rgb(15, 23, 42), opacity) else alphaColor(Color.WHITE, opacity)
        val primaryText = if (dark) Color.WHITE else Color.rgb(15, 23, 42)
        val scale = sizeScale()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((dp(12) * scale).roundToInt(), (dp(8) * scale).roundToInt(), (dp(12) * scale).roundToInt(), (dp(8) * scale).roundToInt())
            background = roundedBg(bgColor, 999f, 1, if (dark) Color.argb(55, 255, 255, 255) else Color.rgb(226, 232, 240), this)
            elevation = dp(10).toFloat()
            statusDot = View(this@FloatingService).apply { background = roundedBg(latencyColor(latestLatencyMs), 999f, view = this) }
            timeView = TextView(this@FloatingService).apply {
                text = "--:--:--.-"
                textSize = 18f * scale
                setTextColor(primaryText)
                includeFontPadding = false
                bold()
            }
            latencyView = TextView(this@FloatingService).apply {
                text = latencyText(latestLatencyMs)
                textSize = 11f * scale
                setTextColor(latencyColor(latestLatencyMs))
                bold()
            }
            sourceView = TextView(this@FloatingService)
            hintView = TextView(this@FloatingService)
            addView(statusDot, LinearLayout.LayoutParams((dp(7) * scale).roundToInt(), (dp(7) * scale).roundToInt()))
            addView(timeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (dp(8) * scale).roundToInt() })
            if (cfg?.showLatency != false) {
                addView(latencyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (dp(8) * scale).roundToInt() })
            }
            setOnClickListener { compact = false; showFloatingView() }
            setOnLongClickListener { showFloatingMenu(); true }
        }
    }

    private fun makeDraggable(view: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    v.alpha = 0.88f
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    safeUpdate(v, lp)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    if (moved) {
                        snapToEdge(v, lp)
                        ConfigStore.savePosition(this, lp.x, lp.y)
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val screenWidth = windowManager.defaultDisplayWidth()
        val screenHeight = windowManager.defaultDisplayHeight()
        val viewWidth = max(view.width, dp(120))
        val margin = dp(10)
        lp.x = if (lp.x + viewWidth / 2 < screenWidth / 2) margin else screenWidth - viewWidth - margin
        lp.y = lp.y.coerceIn(dp(40), screenHeight - dp(120))
        safeUpdate(view, lp)
    }

    private fun showFloatingMenu() {
        removeMenu()
        val baseParams = floatingParams ?: return
        val darkBg = alphaColor(Color.rgb(15, 23, 42), 94)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBg(darkBg, 24f, 1, Color.argb(65, 255, 255, 255), this)
            elevation = dp(14).toFloat()
        }
        val title = TextView(this).apply {
            text = "悬浮菜单"
            textSize = 13f
            setTextColor(Color.rgb(203, 213, 225))
            bold()
        }
        menu.addView(title)
        menu.addView(menuButton(if (compact) "完整模式" else "精简模式") {
            compact = !compact
            removeMenu()
            showFloatingView()
        })
        menu.addView(menuButton(if (paused) "恢复刷新" else "暂停刷新") {
            paused = !paused
            removeMenu()
            updateHint()
        })
        menu.addView(menuButton("隐藏悬浮窗") {
            removeMenu()
            removeFloatingView()
        })
        menu.addView(menuButton("停止运行") {
            removeMenu()
            removeFloatingView()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        })

        val params = WindowManager.LayoutParams().apply {
            width = dp(164)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            x = baseParams.x
            y = baseParams.y + dp(88)
        }
        menuView = menu
        windowManager.addView(menu, params)
    }

    private fun menuButton(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        background = roundedBg(Color.argb(38, 255, 255, 255), 14f, view = this)
        setOnClickListener { action() }
    }.also { button ->
        button.setPadding(0, 0, 0, 0)
        button.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) }
    }

    private fun removeFloatingView() {
        clockJob?.cancel()
        refreshJob?.cancel()
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingView = null
        floatingParams = null
    }

    private fun removeMenu() {
        menuView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        menuView = null
    }

    private fun safeUpdate(view: View, lp: WindowManager.LayoutParams) {
        try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    private fun startClockLoop() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                if (::timeView.isInitialized) timeView.text = displayText()
                updateHint()
                delay(100L)
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        val current = cfg ?: return
        if (!current.autoRefresh) return
        refreshJob = scope.launch {
            while (isActive) {
                if (!paused) refreshLatency()
                delay(current.refreshIntervalMs)
            }
        }
    }

    private suspend fun refreshLatency() {
        val url = cfg?.platformUrl
        if (url == null) {
            latestLatencyMs = 0L
            serverOffsetMs = 0L
            updateLatencyUi()
            return
        }
        val result = LatencyTester.test(url)
        latestLatencyMs = result.latencyMs
        serverOffsetMs = result.serverOffsetMs ?: 0L
        updateLatencyUi()
    }

    private fun updateLatencyUi() {
        if (!::latencyView.isInitialized) return
        latencyView.text = latencyText(latestLatencyMs)
        latencyView.setTextColor(latencyColor(latestLatencyMs))
        if (::statusDot.isInitialized) statusDot.background = roundedBg(latencyColor(latestLatencyMs), 999f, view = statusDot)
    }

    private fun updateHint() {
        if (!::hintView.isInitialized) return
        val text = when {
            paused -> "已暂停刷新 · 长按菜单可恢复"
            cfg?.mode == ConfigStore.MODE_COUNTDOWN -> "倒计时模式 · 长按菜单可关闭"
            else -> "单击精简 · 长按菜单 · 通知栏可关闭"
        }
        hintView.text = text
    }

    private fun displayText(): String {
        val current = cfg ?: return "--:--:--.-"
        return if (current.mode == ConfigStore.MODE_COUNTDOWN) {
            val remain = max(0L, countdownEndAtMs - System.currentTimeMillis() + current.offsetMs)
            if (remain == 0L) {
                if (::timeView.isInitialized) timeView.setTextColor(Color.rgb(248, 113, 113))
            }
            formatCountdown(remain)
        } else {
            val time = System.currentTimeMillis() + current.offsetMs + serverOffsetMs
            SimpleDateFormat("HH:mm:ss.S", Locale.getDefault()).format(Date(time))
        }
    }

    private fun formatCountdown(ms: Long): String {
        val tenths = ms / 100L
        val minutes = tenths / 600L
        val seconds = (tenths / 10L) % 60L
        val tenth = tenths % 10L
        return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, tenth)
    }

    private fun sizeScale(): Float = when (cfg?.size) {
        ConfigStore.SIZE_SMALL -> 0.86f
        ConfigStore.SIZE_LARGE -> 1.14f
        else -> 1.0f
    }

    private fun buildNotification(): Notification {
        val showIntent = PendingIntent.getService(this, 1, Intent(this, FloatingService::class.java).apply { action = ACTION_SHOW }, pendingFlags())
        val hideIntent = PendingIntent.getService(this, 2, Intent(this, FloatingService::class.java).apply { action = ACTION_HIDE }, pendingFlags())
        val stopIntent = PendingIntent.getService(this, 3, Intent(this, FloatingService::class.java).apply { action = ACTION_STOP }, pendingFlags())
        val pauseIntent = PendingIntent.getService(this, 4, Intent(this, FloatingService::class.java).apply { action = ACTION_PAUSE_REFRESH }, pendingFlags())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Floatwatch 正在运行")
            .setContentText("通知栏可显示、隐藏、暂停或停止悬浮窗")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "显示", showIntent)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopIntent)
            .addAction(android.R.drawable.ic_menu_upload, "隐藏", hideIntent)
            .build()
    }

    private fun pendingFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Floatwatch", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun WindowManager.defaultDisplayWidth(): Int {
        return resources.displayMetrics.widthPixels
    }

    private fun WindowManager.defaultDisplayHeight(): Int {
        return resources.displayMetrics.heightPixels
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
