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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var refreshJob: Job? = null

    private var selectedPlatform: Platform = Platforms.items.first()
    private var mode: String = ConfigStore.MODE_CLOCK
    /** offsetMs: 正数=提前，负数=延后 */
    private var offsetMs: Long = 0L
    private var latestLatencyMs: Long = 0L
    private var serverOffsetMs: Long = 0L
    private var autoRefresh: Boolean = true
    private var refreshIntervalMs: Long = 3000L
    private var countdownMs: Long = 0L
    private var countdownTargetText: String = ""
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
        countdownTargetText = cfg.countdownTargetText
        opacityPercent = cfg.opacityPercent
        floatingSize = cfg.size
        floatingTheme = cfg.theme
        latestLatencyMs = if (selectedPlatform.url == null) 0L else -1L
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(14))
            setBackgroundColor(Color.rgb(246, 247, 249))
        }

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
        statusRow.addView(statusLatency, LinearLayout.LayoutParams(dp(82), dp(28)).apply { leftMargin = dp(8) })

        statusTime = TextView(this).apply {
            text = formatDisplayTime()
            textSize = 35f
            setTextColor(Color.rgb(15, 23, 42))
            includeFontPadding = false
            bold()
        }
        statusCard.addView(statusRow)
        statusCard.addView(statusTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
            text = "设置"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.rgb(17, 24, 39))
            background = roundedBg(Color.WHITE, 18f, 1, Color.rgb(226, 232, 240), this)
            setOnClickListener { showAppSettingsSheet() }
        }
        mainButton = Button(this).apply {
            text = "开启悬浮时钟"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            bold()
            background = gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 18f, this)
            // 第一次点击只进入悬浮设置；设置好后，弹窗底部第二次点击才真正开启悬浮窗。
            setOnClickListener { showFloatingConfigSheet() }
        }
        actions.addView(settingsButton, LinearLayout.LayoutParams(0, dp(52), 0.72f))
        actions.addView(mainButton, LinearLayout.LayoutParams(0, dp(52), 1.28f).apply { leftMargin = dp(10) })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        setContentView(root)
        updateSelectedCards()
        updateStatus()
    }

    private fun platformCard(platform: Platform): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(9), dp(11), dp(9), dp(18))
            background = roundedBg(Color.WHITE, 18f, view = this)
            elevation = 1.0f * resources.displayMetrics.density
            setOnClickListener {
                selectedPlatform = platform
                latestLatencyMs = if (platform.url == null) 0L else -1L
                serverOffsetMs = 0L
                LatencyStabilizer.reset(platform.name)
                ConfigStore.savePlatform(this@MainActivity, platform)
                updateSelectedCards()
                updateStatus()
                startRefreshLoop()
            }
        }
        val icon = TextView(this).apply {
            text = platform.shortName
            textSize = 13.4f
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
            textSize = 11.8f
            gravity = Gravity.CENTER
            includeFontPadding = true
            setSingleLine(true)
            setTextColor(Color.rgb(100, 116, 139))
        }
        cardTimeViews[platform.name] = time
        cardLatencyViews[platform.name] = latency
        card.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        card.addView(latency, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { topMargin = dp(3) })

        val cardWidth = ((resources.displayMetrics.widthPixels - dp(56)) / 3f).roundToInt()
        card.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            height = dp(154)
            setMargins(dp(3), dp(5), dp(3), dp(5))
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

    
private fun showAppSettingsSheet() {
    val dialog = bottomDialog()
    val root = sheetRoot(compact = true)
    root.addView(sheetHeader("设置") { dialog.dismiss() })

    root.addView(sectionHeader("自动刷新", refreshIntervalLabel(refreshIntervalMs)), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(6) })
    val refreshRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    listOf("1s" to 1000L, "3s" to 3000L, "5s" to 5000L).forEachIndexed { index, (label, ms) ->
        refreshRow.addView(choiceButton(label, refreshIntervalMs == ms) {
            setInterval(ms)
            dialog.dismiss(); showAppSettingsSheet()
        }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index > 0) leftMargin = dp(8) })
    }
    root.addView(refreshRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

    val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    themeRow.addView(choiceButton("深色", floatingTheme == ConfigStore.THEME_DARK) {
        saveTheme(ConfigStore.THEME_DARK)
        dialog.dismiss(); showAppSettingsSheet()
    }, LinearLayout.LayoutParams(0, dp(42), 1f))
    themeRow.addView(choiceButton("浅色", floatingTheme == ConfigStore.THEME_LIGHT) {
        saveTheme(ConfigStore.THEME_LIGHT)
        dialog.dismiss(); showAppSettingsSheet()
    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
    root.addView(themeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) })

    root.addView(sectionHeader("透明度", "${opacityPercent}%"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply { topMargin = dp(8) })
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
    root.addView(opacitySeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
    showBottom(dialog, root)
}

private fun showFloatingConfigSheet() {
        val dialog = bottomDialog()
        val root = sheetRoot(compact = true)

        val cfg = ConfigStore.load(this)
        mode = cfg.mode
        offsetMs = cfg.offsetMs
        autoRefresh = cfg.autoRefresh
        refreshIntervalMs = cfg.refreshIntervalMs
        countdownMs = cfg.countdownMs
        countdownTargetText = cfg.countdownTargetText
        opacityPercent = cfg.opacityPercent
        floatingSize = cfg.size
        floatingTheme = cfg.theme

        root.addView(sheetHeader(null) { dialog.dismiss() })

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
        }
        val clockBtn = choiceButton("时钟模式", mode == ConfigStore.MODE_CLOCK) {
            ConfigStore.saveMode(this, ConfigStore.MODE_CLOCK)
            mode = ConfigStore.MODE_CLOCK
            dialog.dismiss(); showFloatingConfigSheet()
        }
        val countdownBtn = choiceButton("倒计时模式", mode == ConfigStore.MODE_COUNTDOWN) {
            ConfigStore.saveMode(this, ConfigStore.MODE_COUNTDOWN)
            mode = ConfigStore.MODE_COUNTDOWN
            dialog.dismiss(); showFloatingConfigSheet()
        }
        modeRow.addView(clockBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { bottomMargin = dp(4) })
        modeRow.addView(countdownBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(10); bottomMargin = dp(4) })
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(4) })

        var targetInput: EditText? = null
        if (mode == ConfigStore.MODE_COUNTDOWN) {
            root.addView(sectionHeader("结束时间", "优先于预设"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { topMargin = dp(8) })
            targetInput = EditText(this).apply {
                setText(countdownTargetText)
                hint = "例如 16:32:33"
                textSize = 14f
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                setTextColor(Color.rgb(15, 23, 42))
                setHintTextColor(Color.rgb(148, 163, 184))
                setPadding(dp(12), 0, dp(12), 0)
                background = roundedBg(Color.rgb(248, 250, 252), 14f, 1, Color.rgb(226, 232, 240), this)
            }
            root.addView(targetInput!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

            root.addView(sectionHeader("预设时间", ""), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)).apply { topMargin = dp(8) })
            val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipToPadding = false; setPadding(0, 0, 0, dp(6)) }
            listOf("30s" to 30L, "1min" to 60L, "3min" to 180L, "5min" to 300L).forEachIndexed { index, pair ->
                val (label, seconds) = pair
                quickRow.addView(choiceButton(label, countdownMs == seconds * 1000L && countdownTargetText.isBlank()) {
                    countdownMs = seconds * 1000L
                    countdownTargetText = ""
                    targetInput?.setText("")
                    ConfigStore.saveCountdown(this, countdownMs, "")
                    dialog.dismiss(); showFloatingConfigSheet()
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index > 0) leftMargin = dp(10); bottomMargin = dp(6) })
            }
            root.addView(quickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }

        addCompactOffsetSection(root)

        val start = Button(this).apply {
            text = "开启悬浮时钟"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            bold()
            background = gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 16f, this)
            setOnClickListener {
                countdownTargetText = targetInput?.text?.toString()?.trim() ?: countdownTargetText
                if (mode == ConfigStore.MODE_COUNTDOWN && countdownTargetText.isBlank() && countdownMs <= 0L) {
                    android.widget.Toast.makeText(this@MainActivity, "请选择结束时间或预设时间", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (mode == ConfigStore.MODE_COUNTDOWN && countdownTargetText.isNotBlank()) {
                    val targetMillis = parseFutureTargetMillis(countdownTargetText)
                    if (targetMillis == null) {
                        android.widget.Toast.makeText(this@MainActivity, "结束时间只能填写未来时间", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                saveAllSettings()
                startFloating()
                if (mode == ConfigStore.MODE_COUNTDOWN) {
                    countdownTargetText = ""
                    countdownMs = 0L
                    mainButton.postDelayed({ ConfigStore.saveCountdown(this@MainActivity, 0L, "") }, 600L)
                }
                dialog.dismiss()
            }
        }
        root.addView(start, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })

        val scroller = ScrollView(this).apply { addView(root) }
        showBottom(dialog, scroller)
    }

    
private fun addCompactOffsetSection(root: LinearLayout) {
    var isAhead = offsetMs >= 0L
    var offsetAbs = abs(offsetMs).coerceIn(0L, 1000L)

    val topRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val left = TextView(this).apply {
        text = "时间偏移(${if (isAhead) "提前" else "延后"}) ▼"
        textSize = 13.4f
        setTextColor(Color.rgb(30, 41, 59))
        bold()
    }
    val right = TextView(this).apply {
        text = "↻ 延迟${latestLatencyMs.coerceAtLeast(0L)}毫秒"
        textSize = 13.8f
        gravity = Gravity.END
        setTextColor(Color.rgb(30, 41, 59))
        bold()
        setOnClickListener {
            refreshSelectedOnce()
            postDelayed({ text = "↻ 延迟${latestLatencyMs.coerceAtLeast(0L)}毫秒" }, 250L)
        }
    }
    left.setOnClickListener {
        isAhead = !isAhead
        offsetMs = if (isAhead) offsetAbs else -offsetAbs
        ConfigStore.saveOffset(this@MainActivity, offsetMs)
        left.text = "时间偏移(${if (isAhead) "提前" else "延后"}) ▼"
        updateStatus()
    }
    topRow.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    topRow.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply { topMargin = dp(10) })

    val offsetValue = TextView(this).apply {
        text = "${offsetAbs}毫秒"
        textSize = 12.2f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(15, 23, 42))
        bold()
    }
    root.addView(offsetValue, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply { topMargin = dp(4) })

    val offsetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val offsetSeek = SeekBar(this).apply {
        max = 1000
        progress = offsetAbs.toInt()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                offsetAbs = progress.toLong()
                offsetValue.text = "${offsetAbs}毫秒"
                offsetMs = if (isAhead) offsetAbs else -offsetAbs
                ConfigStore.saveOffset(this@MainActivity, offsetMs)
                updateStatus()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    val minus = squareButton("−") {
        offsetAbs = (offsetAbs - 10L).coerceIn(0L, 1000L)
        offsetSeek.progress = offsetAbs.toInt()
    }
    val plus = squareButton("+") {
        offsetAbs = (offsetAbs + 10L).coerceIn(0L, 1000L)
        offsetSeek.progress = offsetAbs.toInt()
    }
    offsetRow.addView(minus, LinearLayout.LayoutParams(dp(32), dp(32)))
    offsetRow.addView(offsetSeek, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
    offsetRow.addView(plus, LinearLayout.LayoutParams(dp(32), dp(32)))
    root.addView(offsetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
}

// addLatencySection removed in V2.3

    // addAutoRefreshSection removed in V2.3

    // addStyleSection removed in V2.3

    private fun bottomDialog(): Dialog {
        return Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
    }

    private fun sheetRoot(compact: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            background = roundedBg(Color.WHITE, 26f, view = this)
        }
    }

    private fun sheetHeader(title: String?, closeAction: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val drag = View(this@MainActivity).apply { background = roundedBg(Color.rgb(203, 213, 225), 999f, view = this) }
            addView(drag, LinearLayout.LayoutParams(dp(44), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(4) })
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titleView = TextView(this@MainActivity).apply {
                text = title ?: ""
                textSize = 15f
                setTextColor(Color.rgb(15, 23, 42))
                bold()
            }
            val close = TextView(this@MainActivity).apply {
                text = "×"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                bold()
                background = roundedBg(Color.rgb(82, 82, 91), 10f, view = this)
                setOnClickListener { closeAction() }
            }
            row.addView(titleView, LinearLayout.LayoutParams(0, dp(34), 1f))
            row.addView(close, LinearLayout.LayoutParams(dp(34), dp(30)))
            addView(row)
        }
    }

    private fun showBottom(dialog: Dialog, content: View) {
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    private fun sectionHeader(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val l = TextView(this@MainActivity).apply {
                text = left
                textSize = 14f
                setTextColor(Color.rgb(15, 23, 42))
                bold()
            }
            val r = TextView(this@MainActivity).apply {
                text = right
                textSize = 12.5f
                gravity = Gravity.END
                setTextColor(Color.rgb(71, 85, 105))
            }
            addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun smallInfoRow(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val l = TextView(this@MainActivity).apply { text = left; textSize = 14f; setTextColor(Color.rgb(15, 23, 42)); bold() }
            val r = TextView(this@MainActivity).apply { text = right; textSize = 13f; gravity = Gravity.END; setTextColor(Color.rgb(71, 85, 105)) }
            addView(l, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun topButton(text: String, action: (View) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.rgb(17, 24, 39))
        background = roundedBg(Color.WHITE, 999f, 1, Color.rgb(226, 232, 240), this)
        setPadding(0, 0, 0, 0)
        setOnClickListener(action)
    }

    private fun squareButton(text: String, action: (View) -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.rgb(71, 85, 105))
        bold()
        background = roundedBg(Color.rgb(248, 250, 252), 10f, view = this)
        setPadding(0, 0, 0, 0)
        setOnClickListener(action)
    }

    private fun choiceButton(text: String, selected: Boolean, action: (View) -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13.4f
        gravity = Gravity.CENTER
        includeFontPadding = true
        setTextColor(if (selected) Color.WHITE else Color.rgb(17, 24, 39))
        background = if (selected) {
            gradientBg(Color.rgb(74, 222, 128), Color.rgb(34, 197, 94), 999f, this)
        } else {
            roundedBg(Color.rgb(241, 245, 249), 999f, 1, Color.rgb(226, 232, 240), this)
        }
        elevation = if (selected) 2.4f * resources.displayMetrics.density else 1.8f * resources.displayMetrics.density
        setPadding(0, dp(2), 0, dp(2))
        setOnClickListener(action)
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
        ConfigStore.saveOffset(this, offsetMs.coerceIn(-1000L, 1000L))
        ConfigStore.saveRefresh(this, autoRefresh, refreshIntervalMs)
        ConfigStore.saveCountdown(this, countdownMs, countdownTargetText)
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
            val result = LatencyTester.stableTest(url)
            latestLatencyMs = LatencyStabilizer.update(selectedPlatform.name, result.latencyMs)
            serverOffsetMs = result.serverOffsetMs ?: 0L
            updateStatus()
        }
    }

    private fun preciseLatencyTest() { refreshSelectedOnce() }

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


    private fun parseFutureTargetMillis(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val patterns = listOf("HH:mm:ss.S", "HH:mm:ss", "HH:mm")
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
                val parsed = parser.parse(trimmed) ?: continue
                val source = java.util.Calendar.getInstance().apply { time = parsed }
                val now = java.util.Calendar.getInstance()
                val target = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, source.get(java.util.Calendar.HOUR_OF_DAY))
                    set(java.util.Calendar.MINUTE, source.get(java.util.Calendar.MINUTE))
                    set(java.util.Calendar.SECOND, source.get(java.util.Calendar.SECOND))
                    set(java.util.Calendar.MILLISECOND, source.get(java.util.Calendar.MILLISECOND))
                }
                if (target.timeInMillis <= now.timeInMillis) return null
                return target.timeInMillis
            } catch (_: Exception) {}
        }
        return null
    }

    private fun refreshIntervalLabel(ms: Long): String = when (ms) {
        1000L -> "1s"
        3000L -> "3s"
        else -> "5s"
    }

    private fun startFloating() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_SHOW
            putExtra(FloatingService.EXTRA_RELOAD_CONFIG, true)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
