package com.floatwatch.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private var selectedPlatform: Platform = Platforms.items.first()
    private var mode: String = ConfigStore.MODE_CLOCK
    private var offsetMs: Long = 0L
    private var latestLatencyMs: Long = 0L
    private var serverOffsetMs: Long = 0L
    private var autoRefresh: Boolean = true
    private var refreshIntervalMs: Long = 5000L
    private var countdownMs: Long = 30000L
    private var opacityPercent: Int = 88
    private var floatingSize: String = ConfigStore.SIZE_MEDIUM
    private var floatingTheme: String = ConfigStore.THEME_DARK

    private lateinit var statusSource: TextView
    private lateinit var statusTime: TextView
    private lateinit var statusLatency: TextView
    private lateinit var statusMode: TextView
    private lateinit var grid: GridLayout
    private lateinit var mainButton: Button

    private val cardViews = mutableMapOf<String, LinearLayout>()
    private val cardLatencyViews = mutableMapOf<String, TextView>()
    private val cardTimeViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadState()
        requestNotificationPermissionIfNeeded()
        buildUi()
        startClockLoop()
        startRefreshLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadState() {
        val cfg = ConfigStore.load(this)
        selectedPlatform = Platforms.items.firstOrNull { it.name == cfg.platformName } ?: Platforms.items.first()
        mode = cfg.mode
        offsetMs = cfg.offsetMs
        autoRefresh = cfg.autoRefresh
        refreshIntervalMs = cfg.refreshIntervalMs
        countdownMs = cfg.countdownMs
        opacityPercent = cfg.opacityPercent
        floatingSize = cfg.size
        floatingTheme = cfg.theme
        latestLatencyMs = if (selectedPlatform.url == null) 0L else -1L
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(View(this), LinearLayout.LayoutParams(0, dp(42), 1f))
        topBar.addView(topButton("权限") { openOverlayPermission() }, LinearLayout.LayoutParams(dp(76), dp(38)))
        topBar.addView(topButton("设置") { showSettingsSheet() }, LinearLayout.LayoutParams(dp(76), dp(38)).apply { leftMargin = dp(8) })
        root.addView(topBar)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBg(Color.WHITE, 24f, view = this)
            elevation = 2.2f * resources.displayMetrics.density
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusSource = TextView(this).apply {
            text = selectedPlatform.name
            textSize = 15f
            setTextColor(Color.rgb(17, 24, 39))
            bold()
        }
        statusMode = TextView(this).apply {
            text = if (mode == ConfigStore.MODE_COUNTDOWN) "倒计时" else "时钟"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(34, 197, 94))
            bold()
            background = roundedBg(Color.rgb(220, 252, 231), 999f, view = this)
        }
        statusLatency = TextView(this).apply {
            text = latencyText(latestLatencyMs)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(latencyColor(latestLatencyMs))
            bold()
            background = roundedBg(Color.rgb(248, 250, 252), 999f, 1, Color.rgb(226, 232, 240), this)
        }
        statusRow.addView(statusSource, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(statusMode, LinearLayout.LayoutParams(dp(72), dp(28)))
        statusRow.addView(statusLatency, LinearLayout.LayoutParams(dp(78), dp(28)).apply { leftMargin = dp(8) })

        statusTime = TextView(this).apply {
            text = formatDisplayTime()
            textSize = 36f
            setTextColor(Color.rgb(15, 23, 42))
            includeFontPadding = false
            bold()
        }
        statusCard.addView(statusRow)
        statusCard.addView(statusTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        grid = GridLayout(this).apply { columnCount = 3 }
        Platforms.items.forEach { platform ->
            val card = platformCard(platform)
            cardViews[platform.name] = card
            grid.addView(card)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val settingsButton = Button(this).apply {
            text = "悬浮设置"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.rgb(17, 24, 39))
            background = roundedBg(Color.WHITE, 18f, 1, Color.rgb(226, 232, 240), this)
            setOnClickListener { showSettingsSheet() }
        }
        mainButton = Button(this).apply {
            text = "开启悬浮"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            bold()
            background = gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 18f, this)
            setOnClickListener { startFloating() }
        }
        actions.addView(settingsButton, LinearLayout.LayoutParams(0, dp(54), 0.85f))
        actions.addView(mainButton, LinearLayout.LayoutParams(0, dp(54), 1.15f).apply { leftMargin = dp(10) })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })

        setContentView(root)
        updateSelectedCards()
        updateStatus()
    }

    private fun platformCard(platform: Platform): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(9), dp(12), dp(9), dp(10))
            background = roundedBg(Color.WHITE, 18f, view = this)
            elevation = 1.0f * resources.displayMetrics.density
            setOnClickListener {
                selectedPlatform = platform
                latestLatencyMs = if (platform.url == null) 0L else -1L
                serverOffsetMs = 0L
                ConfigStore.savePlatform(this@MainActivity, platform)
                updateSelectedCards()
                updateStatus()
                startRefreshLoop()
            }
        }
        val icon = TextView(this).apply {
            text = platform.shortName
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            bold()
            background = roundedBg(platform.color, 999f, view = this)
        }
        val name = TextView(this).apply {
            text = platform.name
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 41, 59))
            bold()
        }
        val time = TextView(this).apply {
            text = currentClockText()
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 23, 42))
            includeFontPadding = false
        }
        val latency = TextView(this).apply {
            text = if (platform.url == null) "0 ms" else "-- ms"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(100, 116, 139))
        }
        cardTimeViews[platform.name] = time
        cardLatencyViews[platform.name] = latency
        card.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        card.addView(latency, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })

        val cardWidth = ((resources.displayMetrics.widthPixels - dp(44)) / 3f).roundToInt()
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            height = dp(128)
            setMargins(dp(4), dp(5), dp(4), dp(5))
        }
        return card
    }

    private fun updateSelectedCards() {
        cardViews.forEach { (name, card) ->
            if (name == selectedPlatform.name) {
                card.background = roundedBg(Color.rgb(240, 253, 244), 18f, 2, Color.rgb(34, 197, 94), card)
                card.elevation = 3.0f * resources.displayMetrics.density
            } else {
                card.background = roundedBg(Color.WHITE, 18f, view = card)
                card.elevation = 1.0f * resources.displayMetrics.density
            }
        }
    }

    private fun showSettingsSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val cfg = ConfigStore.load(this)
        mode = cfg.mode
        offsetMs = cfg.offsetMs
        autoRefresh = cfg.autoRefresh
        refreshIntervalMs = cfg.refreshIntervalMs
        countdownMs = cfg.countdownMs
        opacityPercent = cfg.opacityPercent
        floatingSize = cfg.size
        floatingTheme = cfg.theme

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
            background = roundedBg(Color.WHITE, 30f, view = this)
        }

        val handle = View(this).apply { background = roundedBg(Color.rgb(203, 213, 225), 999f, view = this) }
        root.addView(handle, LinearLayout.LayoutParams(dp(46), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        val closeRow = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val close = TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            bold()
            background = roundedBg(Color.rgb(82, 82, 91), 12f, view = this)
            setOnClickListener { dialog.dismiss() }
        }
        closeRow.addView(close, LinearLayout.LayoutParams(dp(44), dp(36)))
        root.addView(closeRow)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = roundedBg(Color.rgb(241, 245, 249), 999f, view = this)
        }
        val clockBtn = Button(this).apply { text = "时钟模式"; oneUiButton(mode == ConfigStore.MODE_CLOCK) }
        val countdownBtn = Button(this).apply { text = "倒计时模式"; oneUiButton(mode == ConfigStore.MODE_COUNTDOWN) }
        fun refreshModeButtons() {
            clockBtn.oneUiButton(mode == ConfigStore.MODE_CLOCK)
            countdownBtn.oneUiButton(mode == ConfigStore.MODE_COUNTDOWN)
        }
        clockBtn.setOnClickListener { mode = ConfigStore.MODE_CLOCK; ConfigStore.saveMode(this, mode); refreshModeButtons() }
        countdownBtn.setOnClickListener { mode = ConfigStore.MODE_COUNTDOWN; ConfigStore.saveMode(this, mode); refreshModeButtons() }
        modeRow.addView(clockBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        modeRow.addView(countdownBtn, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(4) })
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(6) })

        val offsetValue = TextView(this)
        root.addView(settingTitle("时间偏移", if (offsetMs < 0) "提前" else "延后"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        offsetValue.apply {
            text = offsetLabel(offsetMs)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 23, 42))
            bold()
        }
        root.addView(offsetValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val offsetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val minus = squareButton("−") { setOffsetAndRefresh(offsetMs - 10, offsetValue) }
        val plus = squareButton("+") { setOffsetAndRefresh(offsetMs + 10, offsetValue) }
        val offsetSeek = SeekBar(this).apply {
            max = 1000
            progress = (offsetMs + 500).toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) setOffsetAndRefresh((progress - 500).toLong(), offsetValue)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        minus.setOnClickListener { setOffsetAndRefresh(offsetMs - 10, offsetValue); offsetSeek.progress = (offsetMs + 500).toInt().coerceIn(0, 1000) }
        plus.setOnClickListener { setOffsetAndRefresh(offsetMs + 10, offsetValue); offsetSeek.progress = (offsetMs + 500).toInt().coerceIn(0, 1000) }
        offsetRow.addView(minus, LinearLayout.LayoutParams(dp(40), dp(40)))
        offsetRow.addView(offsetSeek, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        offsetRow.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(offsetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(4) })

        val latencyTitle = settingTitle("网络延迟", latencyText(latestLatencyMs))
        root.addView(latencyTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val refreshRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val autoSwitch = Switch(this).apply {
            text = "自动刷新"
            textSize = 14f
            setTextColor(Color.rgb(51, 65, 85))
            isChecked = autoRefresh
            setOnCheckedChangeListener { _, checked ->
                autoRefresh = checked
                ConfigStore.saveRefresh(this@MainActivity, autoRefresh, refreshIntervalMs)
                startRefreshLoop()
            }
        }
        val refreshBtn = topButton("立即刷新") { refreshSelectedOnce() }
        refreshRow.addView(autoSwitch, LinearLayout.LayoutParams(0, dp(44), 1f))
        refreshRow.addView(refreshBtn, LinearLayout.LayoutParams(dp(104), dp(40)))
        root.addView(refreshRow)

        val intervalRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val i3 = choiceButton("3秒", refreshIntervalMs == 3000L) { setInterval(3000L); dialog.dismiss(); showSettingsSheet() }
        val i5 = choiceButton("5秒", refreshIntervalMs == 5000L) { setInterval(5000L); dialog.dismiss(); showSettingsSheet() }
        val i10 = choiceButton("10秒", refreshIntervalMs == 10000L) { setInterval(10000L); dialog.dismiss(); showSettingsSheet() }
        intervalRow.addView(i3, LinearLayout.LayoutParams(0, dp(42), 1f))
        intervalRow.addView(i5, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        intervalRow.addView(i10, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        root.addView(intervalRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(4) })

        val countdownValue = TextView(this)
        root.addView(settingTitle("倒计时", countdownLabel(countdownMs)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        countdownValue.apply {
            text = countdownLabel(countdownMs)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 23, 42))
            bold()
        }
        root.addView(countdownValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val countdownSeek = SeekBar(this).apply {
            max = 300
            progress = (countdownMs / 1000L).toInt().coerceIn(1, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        countdownMs = progress.coerceAtLeast(1) * 1000L
                        countdownValue.text = countdownLabel(countdownMs)
                        ConfigStore.saveCountdown(this@MainActivity, countdownMs)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(countdownSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5L, 10L, 30L, 60L).forEachIndexed { index, seconds ->
            quickRow.addView(choiceButton(if (seconds < 60) "${seconds}秒" else "1分", false) {
                countdownMs = seconds * 1000L
                countdownSeek.progress = seconds.toInt()
                countdownValue.text = countdownLabel(countdownMs)
                ConfigStore.saveCountdown(this, countdownMs)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { if (index > 0) leftMargin = dp(8) })
        }
        root.addView(quickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))

        root.addView(settingTitle("悬浮样式", "${opacityPercent}%"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        val opacitySeek = SeekBar(this).apply {
            max = 55
            progress = opacityPercent - 45
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        opacityPercent = progress + 45
                        ConfigStore.saveStyle(this@MainActivity, opacityPercent, floatingSize, floatingTheme)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(opacitySeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sizeRow.addView(choiceButton("小", floatingSize == ConfigStore.SIZE_SMALL) { saveSize(ConfigStore.SIZE_SMALL); dialog.dismiss(); showSettingsSheet() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        sizeRow.addView(choiceButton("中", floatingSize == ConfigStore.SIZE_MEDIUM) { saveSize(ConfigStore.SIZE_MEDIUM); dialog.dismiss(); showSettingsSheet() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        sizeRow.addView(choiceButton("大", floatingSize == ConfigStore.SIZE_LARGE) { saveSize(ConfigStore.SIZE_LARGE); dialog.dismiss(); showSettingsSheet() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        root.addView(sizeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(2) })

        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        themeRow.addView(choiceButton("深色", floatingTheme == ConfigStore.THEME_DARK) { saveTheme(ConfigStore.THEME_DARK); dialog.dismiss(); showSettingsSheet() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        themeRow.addView(choiceButton("浅色", floatingTheme == ConfigStore.THEME_LIGHT) { saveTheme(ConfigStore.THEME_LIGHT); dialog.dismiss(); showSettingsSheet() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        root.addView(themeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })

        val start = Button(this).apply {
            text = if (isServiceLikelyRunning()) "更新悬浮设置" else "开启悬浮时钟"
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            bold()
            background = gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 18f, this)
            setOnClickListener {
                saveAllSettings()
                startFloating()
                dialog.dismiss()
            }
        }
        root.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(18) })

        val scroller = ScrollView(this).apply { addView(root) }
        dialog.setContentView(scroller)
        dialog.setOnShowListener {
            val win = dialog.window
            win?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win?.setGravity(Gravity.BOTTOM)
            win?.setDimAmount(0.45f)
            win?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private fun settingTitle(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val l = TextView(this@MainActivity).apply {
                text = left
                textSize = 16f
                setTextColor(Color.rgb(15, 23, 42))
                bold()
            }
            val r = TextView(this@MainActivity).apply {
                text = right
                textSize = 14f
                gravity = Gravity.END
                setTextColor(Color.rgb(71, 85, 105))
            }
            addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun topButton(text: String, action: (View) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.rgb(17, 24, 39))
        background = roundedBg(Color.WHITE, 999f, 1, Color.rgb(226, 232, 240), this)
        setOnClickListener(action)
    }

    private fun squareButton(text: String, action: (View) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 21f
        setTextColor(Color.rgb(71, 85, 105))
        bold()
        background = roundedBg(Color.rgb(248, 250, 252), 12f, view = this)
        setOnClickListener(action)
    }

    private fun choiceButton(text: String, selected: Boolean, action: (View) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        oneUiButton(selected)
        setOnClickListener(action)
    }

    private fun setOffsetAndRefresh(value: Long, label: TextView) {
        offsetMs = value.coerceIn(-500L, 500L)
        label.text = offsetLabel(offsetMs)
        ConfigStore.saveOffset(this, offsetMs)
        updateStatus()
    }

    private fun setInterval(value: Long) {
        refreshIntervalMs = value
        ConfigStore.saveRefresh(this, autoRefresh, refreshIntervalMs)
        startRefreshLoop()
    }

    private fun saveSize(value: String) {
        floatingSize = value
        ConfigStore.saveStyle(this, opacityPercent, floatingSize, floatingTheme)
    }

    private fun saveTheme(value: String) {
        floatingTheme = value
        ConfigStore.saveStyle(this, opacityPercent, floatingSize, floatingTheme)
    }

    private fun saveAllSettings() {
        ConfigStore.savePlatform(this, selectedPlatform)
        ConfigStore.saveMode(this, mode)
        ConfigStore.saveOffset(this, offsetMs)
        ConfigStore.saveRefresh(this, autoRefresh, refreshIntervalMs)
        ConfigStore.saveCountdown(this, countdownMs)
        ConfigStore.saveStyle(this, opacityPercent, floatingSize, floatingTheme)
    }

    private fun refreshSelectedOnce() {
        val url = selectedPlatform.url
        if (url == null) {
            latestLatencyMs = 0L
            serverOffsetMs = 0L
            updateStatus()
            return
        }
        scope.launch {
            val result = LatencyTester.test(url)
            latestLatencyMs = result.latencyMs
            serverOffsetMs = result.serverOffsetMs ?: 0L
            updateStatus()
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        if (!autoRefresh) return
        refreshJob = scope.launch {
            while (isActive) {
                refreshSelectedOnce()
                delay(refreshIntervalMs)
            }
        }
    }

    private fun startClockLoop() {
        scope.launch {
            while (isActive) {
                updateStatusTimeOnly()
                delay(100L)
            }
        }
    }

    private fun updateStatusTimeOnly() {
        if (::statusTime.isInitialized) statusTime.text = formatDisplayTime()
        val now = currentClockText()
        cardTimeViews.values.forEach { it.text = now }
    }

    private fun updateStatus() {
        if (!::statusSource.isInitialized) return
        statusSource.text = selectedPlatform.name
        statusMode.text = if (mode == ConfigStore.MODE_COUNTDOWN) "倒计时" else "时钟"
        statusLatency.text = latencyText(latestLatencyMs)
        statusLatency.setTextColor(latencyColor(latestLatencyMs))
        cardLatencyViews[selectedPlatform.name]?.text = latencyText(latestLatencyMs)
        cardLatencyViews[selectedPlatform.name]?.setTextColor(latencyColor(latestLatencyMs))
        updateStatusTimeOnly()
    }

    private fun formatDisplayTime(): String {
        return if (mode == ConfigStore.MODE_COUNTDOWN) countdownLabel(countdownMs) else currentClockText()
    }

    private fun currentClockText(): String {
        val time = System.currentTimeMillis() + offsetMs + serverOffsetMs
        return SimpleDateFormat("HH:mm:ss.S", Locale.getDefault()).format(Date(time))
    }

    private fun countdownLabel(valueMs: Long): String {
        val totalTenths = valueMs / 100L
        val minutes = totalTenths / 600L
        val seconds = (totalTenths / 10L) % 60L
        val tenth = totalTenths % 10L
        return String.format(Locale.getDefault(), "%02d:%02d.%d", minutes, seconds, tenth)
    }

    private fun offsetLabel(value: Long): String {
        return when {
            value < 0 -> "提前 ${-value} 毫秒"
            value > 0 -> "延后 ${value} 毫秒"
            else -> "0 毫秒"
        }
    }

    private fun startFloating() {
        saveAllSettings()
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_SHOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1024)
        }
    }

    private fun isServiceLikelyRunning(): Boolean = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
