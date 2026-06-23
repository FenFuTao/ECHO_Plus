package com.fenfutao.echo

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.PopupWindow
import androidx.core.view.WindowCompat
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fenfutao.echo.dataengine.DataEngine
import com.fenfutao.echo.dataengine.DataEngineOutput
import com.fenfutao.echo.dataengine.OutputType
import com.fenfutao.echo.dataengine.FireWaterEngine
import com.fenfutao.echo.dataengine.JustFloatEngine
import com.fenfutao.echo.dataengine.RawDataEngine
import com.fenfutao.echo.dataengine.ParamBuffer
import com.fenfutao.echo.dataengine.ImageBuffer
import com.fenfutao.echo.dataengine.ImageFrame
import java.io.File
import com.fenfutao.echo.transceiver.DataTransceiver
import com.fenfutao.echo.transceiver.NullTransceiver
import com.fenfutao.echo.transceiver.TcpClientTransceiver
import com.fenfutao.echo.transceiver.TcpServerTransceiver
import com.fenfutao.echo.transceiver.UdpTransceiver
import com.fenfutao.echo.util.AppLogger
import com.fenfutao.echo.util.ConfigManager
import com.fenfutao.echo.util.PanelConfig
import com.google.android.material.navigation.NavigationView
import android.widget.EditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var navA: NavigationView
    private lateinit var navB: NavigationView
    private lateinit var scrimOverlay: View
    private lateinit var workArea: View
    private lateinit var rootContainer: View
    private lateinit var menuBar: View
    private lateinit var leftPanel: LinearLayout
    private lateinit var plotContainer: FrameLayout
    private var glImageView: GLImageView? = null
    /** 图像显示子窗口容器（可拖动） */
    private var imageWindowContainer: FrameLayout? = null
    private lateinit var plotTitle: TextView
    private lateinit var outputContainer: FrameLayout
    private lateinit var paramsContainer: FrameLayout
    private lateinit var menuBarHighlight: View
    private lateinit var menuBarDecoration: View

    private var isMenuOpen = false
    private var activeIsA = true
    private var currentMenuRes = 0
    private var selectedMenuBtnId = 0
    private var isConnected = false
    private var isTcpConnecting = false
    private var selectedProtocolPosition = -1
    private var backPressedTime = 0L

    // ── 输出批量刷新优化 ──
    private val outputHandler = Handler(Looper.getMainLooper())
    private var outputFlushPending = false

    /** 已渲染到显示区的条目计数（从 outputDataEntries 开头起算，removeAt(0) 时同步递减） */
    private var lastDisplayedEntryCount = 0
    /**
     * 单显示缓冲区 — TextView 直接使用此 SpannableStringBuilder。
     * 递增追加新条目，超限时从开头裁剪最旧行。
     * 无需多段环形缓冲区：TextView 自带高效的增量布局。
     */
    private val displayBuffer = SpannableStringBuilder()
    /** 显示文本最大字符数，超限时从开头裁剪 */
    private val MAX_DISPLAY_CHARS = MAX_OUTPUT_TEXT_LENGTH / 2

    private val outputFlushRunnable = Runnable { doFlushOutput() }

    /** FPS 副标题定时刷新 */
    private val fpsUpdateRunnable = Runnable { updateParamHeaderTitle() }
    /** FPS 刷新间隔（毫秒） */
    private val fpsUpdateIntervalMs = 500L

    // ── 统一数据收发接口：各协议封装为 DataTransceiver ──
    private val tcpClientTransceiver = TcpClientTransceiver()
    private val tcpServerTransceiver = TcpServerTransceiver()
    private val serialTransceiver = NullTransceiver("串口")
    private val udpTransceiver = UdpTransceiver()
    private val demoTransceiver = NullTransceiver("Demo")
    private var currentTransceiver: DataTransceiver = serialTransceiver

    // ── 协议引擎 ──
    private var currentDataEngine: DataEngine = FireWaterEngine()
    private val fireWaterEngine = FireWaterEngine()
    private val justFloatEngine = JustFloatEngine()
    private val rawDataEngine = RawDataEngine()
    /** JustFloat 强制使用 Hex 模式，禁止切换到 Abc */
    private var justFloatForceHex = false

    /** 参数缓存区：记录首次连接锁定的回传参数列表 */
    private val paramBuffer = ParamBuffer()

    /** 图像缓存区：最新一帧图像的帧头 + hex 数据 */
    private val imageBuffer = ImageBuffer()

    private var panelConfig = PanelConfig()

    private lateinit var outputText: android.widget.TextView
    private lateinit var outputScrollView: OutputScrollView
    private var autoScrollEnabled = true
    private lateinit var scrollToBottomBtn: android.view.View
    private var outputShowHex: Boolean get() = panelConfig.outputShowHex; set(v) { panelConfig.outputShowHex = v; ConfigManager.saveConfig(panelConfig) }
    private var outputShowTimestamp: Boolean get() = panelConfig.outputShowTimestamp; set(v) { panelConfig.outputShowTimestamp = v; ConfigManager.saveConfig(panelConfig) }
    private var outputRxHighlight: Boolean get() = panelConfig.outputRxHighlight; set(v) { panelConfig.outputRxHighlight = v; ConfigManager.saveConfig(panelConfig) }
    private var outputTxHighlight: Boolean get() = panelConfig.outputTxHighlight; set(v) { panelConfig.outputTxHighlight = v; ConfigManager.saveConfig(panelConfig) }
    private var outputFontSize: Float get() = panelConfig.outputFontSize; set(v) { panelConfig.outputFontSize = v; ConfigManager.saveConfig(panelConfig) }
    private var outputUseGbk: Boolean get() = panelConfig.outputUseGbk; set(v) { panelConfig.outputUseGbk = v; ConfigManager.saveConfig(panelConfig) }
    private var sendHexMode: Boolean get() = panelConfig.sendHexMode; set(v) { panelConfig.sendHexMode = v; ConfigManager.saveConfig(panelConfig) }
    private class OutputEntry(
        val text: String,
        val timestampMs: Long?,
        val isTx: Boolean,
        /** 追加时根据 Rx/Tx 开关决定；false 表示隐藏，永不显示 */
        val visible: Boolean,
        /** RawData 等引擎产生的原始字节，Abc 模式需转义控制字符 */
        val escapeControlChars: Boolean = false,
        /** 输出类型，用于 Rx 过滤菜单区分采样数据包和图像数据包 */
        val type: OutputType = OutputType.TEXT,
        /** 是否为合规采样数据帧（受 rxShowSampleData 控制） */
        val isSamplingData: Boolean = false
    )

    private val outputDataEntries = mutableListOf<OutputEntry>()
    private lateinit var btnAbcHex: android.view.View
    private lateinit var btnTimestamp: android.view.View
    private lateinit var btnRx: android.view.View
    private lateinit var btnRxDropdown: android.view.View
    private lateinit var btnTx: android.view.View
    /** Rx 过滤器状态（默认从 panelConfig 加载） */
    private var rxShowSampleData: Boolean
        get() = panelConfig.rxShowSampleData
        set(v) { panelConfig.rxShowSampleData = v; ConfigManager.saveConfig(panelConfig) }
    private var rxShowImagePacket: Boolean
        get() = panelConfig.rxShowImagePacket
        set(v) { panelConfig.rxShowImagePacket = v; ConfigManager.saveConfig(panelConfig) }
    /** 图像子窗口显示开关（控制页"图像"复选框控制） */
    private var rxShowImageWindow: Boolean
        get() = panelConfig.rxShowImageWindow
        set(v) { panelConfig.rxShowImageWindow = v; ConfigManager.saveConfig(panelConfig) }

    private lateinit var btnFontInc: android.view.View
    private lateinit var btnFontDec: android.view.View
    private lateinit var btnEncoding: android.view.View
    private lateinit var btnClear: android.view.View
    private lateinit var sendEditText: android.widget.EditText
    private lateinit var sendAbcHexBtn: android.view.View
    private lateinit var lineEndingSpinner: Spinner

    /** 数据引擎 Spinner 引用（用于连接时锁定/断开解锁） */
    private var dataEngineSpinner: Spinner? = null
    /** 标记当前 TextWatcher 的 afterTextChanged 是否由程序主动 setText 触发 */
    private var textWatcherSuspended = false

    // ── 分割线拖拽相关 ──
    private var isDraggingV = false
    private var protocolView: View? = null
    private var settingsView: View? = null
    private var controlView: View? = null
    private lateinit var vDividerView: View
    private lateinit var hDividerView: View

    private var isDraggingH = false
    private var startX = 0f
    private var startY = 0f
    private var startLeftWeight = 2f
    private var startRightWeight = 1f
    private var startPlotWeight = 1f
    private var startOutputWeight = 1f
    /** 拖拽过程中累计的未刷新增量，防抖优化 */
    private var pendingLayoutRefresh = false

    companion object {
        private const val MENU_WIDTH_DP = 280f
        private const val ICON_DP = 40
        private const val BAR_NARROW = 40
        private const val BAR_WIDE = 56
        private const val PROTOCOL_MENU_MARKER = -1
        private const val SETTINGS_MENU_MARKER = -2
        private const val CONTROL_MENU_MARKER = -3
        // ── 分割面板最小尺寸 (dp) ──
        private const val MIN_PLOT_HEIGHT_DP = 80
        // 宽模式: toolbar(40) + 2行文本(~32) + sendBar(40) = 112
        private const val MIN_OUTPUT_HEIGHT_DP = 112
        private const val MIN_LEFT_WIDTH_DP = 166 // plot(80) + divider(6) + output(112)
        // 宽模式: checkbox(18) + margin(10) + I0标签(60) + margin(8) + 数值128.000000(~106) + padding(32) ≈ 250
        // 窄模式: checkbox(16) + margin(8) + I0标签(48) + margin(6) + 数值128.000000(~85) + padding(24) ≈ 187
        private const val MIN_RIGHT_WIDTH_DP = 200
        // ── 输出区工具栏高度 (dp)，随界面尺寸切换 ──
        private const val TOOLBAR_HEIGHT_NARROW = 28
        private const val TOOLBAR_HEIGHT_WIDE = 40
        private const val SENDBAR_HEIGHT_NARROW = 28
        private const val SENDBAR_HEIGHT_WIDE = 40
        /** 分割线拖拽刷新防抖间隔 (ms) */
        private const val LAYOUT_REFRESH_INTERVAL_MS = 16L // ~60fps
        /** 输出窗口后台数据缓冲最大条目数 */
        private const val MAX_OUTPUT_ENTRIES = 2500
        /** 输出窗口显示文本最大字符数，超限时截断前半部分 */
        private const val MAX_OUTPUT_TEXT_LENGTH = 500000
        /** 输出刷新批处理间隔 (ms)，约 30fps */
        private const val OUTPUT_FLUSH_INTERVAL_MS = 33L

        /** 16 色预定义调色板，按顺序分配给 In0 ~ In15，超出则循环 */
        val PARAM_COLORS = arrayOf(
            "#E53935", // 红
            "#1E88E5", // 蓝
            "#43A047", // 绿
            "#FB8C00", // 橙
            "#8E24AA", // 紫
            "#00ACC1", // 青
            "#F4511E", // 深橙
            "#3949AB", // 靛蓝
            "#C0CA33", // 黄绿
            "#00BCD4", // 浅青
            "#FF4081", // 粉红
            "#7CB342", // 草绿
            "#FF7043", // 珊瑚
            "#5C6BC0", // 紫蓝
            "#26A69A", // 青绿
            "#EC407A"  // 玫瑰红
        )

        /**
         * 将字符串编码为 UTF-8 字节后再转为连续十六进制字符串
         * 例: "AB" -> "4142"
         */
        fun String.toHex(): String =
            this.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }

        /**
         * 将连续十六进制字符串解码为字节数组
         * 例: "4142" -> [0x41, 0x42]
         * 自动忽略空格、制表符、换行符等空白字符
         */
        fun String.hexToBytes(): ByteArray {
            val clean = this.filter { it !in " \t\n\r" }
            if (clean.isEmpty() || clean.length % 2 != 0) return byteArrayOf()
            return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        /**
         * 将连续十六进制字符串解码为指定编码的文本
         * @param useGbk true 使用 GBK，false 使用 UTF-8
         */
        fun String.hexToString(useGbk: Boolean): String {
            val bytes = this.hexToBytes()
            return if (useGbk) {
                java.lang.String(bytes, java.nio.charset.Charset.forName("GBK")).toString()
            } else {
                bytes.decodeToString()
            }
        }

        /**
         * 将字符串中的控制字符转义为可见的转义序列
         * 例: "A\nB" → "A\\nB"
         */
        fun String.escapeDisplay(): String {
            val sb = StringBuilder(length)
            for (c in this) {
                when (c) {
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u0000' -> sb.append("\\0")
                    '\u0007' -> sb.append("\\a")   // Bell
                    '\u0008' -> sb.append("\\b")   // Backspace
                    '\u000C' -> sb.append("\\f")   // Form Feed
                    '\u000B' -> sb.append("\\v")   // Vertical Tab
                    '\u001B' -> sb.append("\\e")   // Escape
                    else -> {
                        val code = c.code
                        // 其他控制字符 (0x01-0x1F 中未列出的, 以及 0x7F DEL) 显示为空格
                        if (code in 0x01..0x1F || code == 0x7F) {
                            sb.append(' ')
                        } else {
                            sb.append(c)
                        }
                    }
                }
            }
            return sb.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_ECHO)
        setContentView(R.layout.activity_main)
        enableImmersiveMode()
        initViews()
        initTransceivers()
        setupOutputView()
        applyColors()
        applyPanelConfig()
        applyMenuBarLayout()
        setupButtons()
        setupDividers()
        navA.translationX = -getMenuPx(); navA.visibility = View.GONE
        navB.translationX = -getMenuPx(); navB.visibility = View.GONE
        scrimOverlay.setOnClickListener { closeMenu() }
        workArea.setOnClickListener { if (isMenuOpen) closeMenu() }
        enableDoubleBackExit()

        // ── 闪屏淡出动画：先死等 0.5s，再在 1s 内淡出 ──
        val rootGroup = rootContainer as android.widget.FrameLayout
        val splashOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(R.drawable.splash_background)
            isClickable = true
            isFocusable = true
        }
        rootGroup.addView(splashOverlay)
        Handler(Looper.getMainLooper()).postDelayed({
            splashOverlay.animate()
                .alpha(0f)
                .setDuration(1000L)
                .withEndAction {
                    rootGroup.removeView(splashOverlay)
                }
                .start()
        }, 500L)

        // ── 窄模式下缩小右侧数据区标题栏 ──
        if (panelConfig.menuBarWidthDp < 56) {
            val header = findViewById<android.widget.LinearLayout>(R.id.paramHeader)
            header.layoutParams = (header.layoutParams as android.widget.LinearLayout.LayoutParams).apply { height = dpToPx(36) }
            findViewById<android.widget.TextView>(R.id.paramHeaderTitle).textSize = 14f
            val subHeader = findViewById<android.widget.LinearLayout>(R.id.paramSubHeader)
            subHeader.layoutParams = (subHeader.layoutParams as android.widget.LinearLayout.LayoutParams).apply { height = dpToPx(20) }
            findViewById<android.widget.TextView>(R.id.paramSubHeaderLeft).textSize = 11f
            findViewById<android.widget.TextView>(R.id.paramSubHeaderFps).textSize = 11f
        }
    }

    private fun initViews() {
        navA = findViewById(R.id.navViewA); navB = findViewById(R.id.navViewB)
        scrimOverlay = findViewById(R.id.scrimOverlay)
        workArea = findViewById(R.id.workArea); rootContainer = findViewById(R.id.rootContainer)
        menuBar = findViewById(R.id.menuBar)
        leftPanel = findViewById(R.id.leftPanel)
        plotContainer = findViewById(R.id.plotContainer)
        plotTitle = findViewById(R.id.plotTitle)
        outputContainer = findViewById(R.id.outputContainer)
        paramsContainer = findViewById(R.id.paramsContainer)
        menuBarHighlight = findViewById(R.id.menuBarHighlight)
        menuBarDecoration = findViewById(R.id.menuBarDecoration)
        vDividerView = findViewById(R.id.vDivider)
        hDividerView = findViewById(R.id.hDivider)

        // 必须优先加载配置，后续 UI 初始化才能读到已保存的状态
        panelConfig = ConfigManager.loadConfig()

        // 设置参数列表页标题框背景色（与协议页标题框一致）
        val paramHeader = findViewById<View>(R.id.paramHeader)
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        paramHeader.setBackgroundColor(primaryColor)

        // 初始化参数标题文本（刚启动时未锁定，不显示位数）
        updateParamHeaderTitle()

        // 参数列表页标题栏右侧「E」按钮：清除缓存并重新学习参数位数
        val paramRelearnBtn = findViewById<View>(R.id.paramRelearnBtn)
        paramRelearnBtn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start(); true }
                MotionEvent.ACTION_MOVE -> {
                    val ib = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()
                    if (!ib && v.scaleX > 1.0f) { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start() }
                    else if (ib && v.scaleX <= 1.0f) { v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start() }
                    true
                }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).withEndAction { v.performClick() }.start(); true }
                MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); true }
                else -> true
            }
        }
        paramRelearnBtn.setOnClickListener {
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("警告")
                .setMessage("所有采样数据会被清除，不可恢复\n\n您确定要这么做吗？")
                .setNegativeButton("确认") { _, _ ->
                    paramBuffer.reset()
                    imageBuffer.reset()
                    updateParamHeaderTitle()
                    refreshParamViews()
                    updateImageInfo()
                    showToastShort("参数缓存已清除，等待新数据重新锁定")
                }
                .setPositiveButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    android.graphics.Color.parseColor("#9E9E9E")
                )
            }
            dialog.show()
        }
    }

    /**
     * 更新参数列表标题文本。
     * 若参数缓存已锁定，显示 "数据 xx"（xx 为锁定的参数位数）；
     * 若未锁定，仅显示 "数据"。
     */
    private fun updateParamHeaderTitle() {
        val titleView = findViewById<TextView>(R.id.paramHeaderTitle)
        val subHeader = findViewById<View>(R.id.paramSubHeader)
        val leftView = findViewById<TextView>(R.id.paramSubHeaderLeft)
        val fpsView = findViewById<TextView>(R.id.paramSubHeaderFps)
        titleView.text = getString(R.string.param_header_title)

        if (paramBuffer.isLocked) {
            val prefix = paramBuffer.lastPrefix
            val count = paramBuffer.expectedCount
            val fps = paramBuffer.getEstimatedFps()
            leftView.text = if (prefix.isEmpty()) "数据量: $count" else "$prefix    数据量: $count"
            fpsView.text = "FPS: $fps"
            subHeader.visibility = View.VISIBLE
            // ★ 每 0.5s 重新调度刷新 FPS
            outputHandler.removeCallbacks(fpsUpdateRunnable)
            outputHandler.postDelayed(fpsUpdateRunnable, fpsUpdateIntervalMs)
        } else {
            subHeader.visibility = View.GONE
            outputHandler.removeCallbacks(fpsUpdateRunnable)
        }
    }

    /**
     * 刷新参数列表显示。
     * 根据 paramBuffer 的最新条目，在 paramListView 中构建每行：
     *   In{序号}    数值（保留6位小数）
     */
    private fun refreshParamViews() {
        val listView = findViewById<LinearLayout>(R.id.paramListView)
        listView.removeAllViews()

        if (!paramBuffer.isLocked) return

        val entries = paramBuffer.getEntries()
        val latest = entries.lastOrNull() ?: return
        val names = paramBuffer.paramNames
        val values = latest.values

        val isWide = panelConfig.menuBarWidthDp >= 56
        // 窄模式下缩小参数列表容器内边距
        if (!isWide) {
            val scrollView = findViewById<View>(R.id.paramScrollView)
            scrollView.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), 0)
        }
        val density = resources.displayMetrics.density
        val inWidth = ((if (isWide) 60 else 48) * density).toInt()
        val paddingH = ((if (isWide) 8 else 6) * density).toInt()
        val paddingV = ((if (isWide) 6 else 4) * density).toInt()
        val checkboxSize = ((if (isWide) 18 else 16) * density).toInt()
        val checkboxMarginEnd = ((if (isWide) 10 else 8) * density).toInt()
        val dataTextSize = if (isWide) 18f else 15f

        for (i in 0 until minOf(names.size, values.size)) {
            val color = android.graphics.Color.parseColor(PARAM_COLORS[i % PARAM_COLORS.size])

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, paddingV, 0, paddingV)
            }

            // 可点击复选框：点击切换勾选/未勾选状态，不做其他响应
            var checked = false
            val checkbox = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(checkboxSize, checkboxSize).apply {
                    marginEnd = checkboxMarginEnd
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                background = resources.getDrawable(R.drawable.bg_checkbox_unchecked, theme)
                isClickable = false
                isFocusable = false
            }
            row.addView(checkbox)

            // 点击整行切换复选框状态（与控件页逻辑一致）
            row.setOnClickListener {
                checked = !checked
                checkbox.background = resources.getDrawable(
                    if (checked) R.drawable.bg_checkbox_checked
                    else R.drawable.bg_checkbox_unchecked,
                    theme
                )
            }

            // In{序号}
            val labelView = TextView(this).apply {
                text = "I$i"
                textSize = dataTextSize
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(inWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.START
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(labelView)

            // 数值（保留6位小数）
            val valueView = TextView(this).apply {
                val raw = values[i]
                val formatted = try {
                    val d = raw.toDouble()
                    String.format(java.util.Locale.US, "%.6f", d)
                } catch (_: NumberFormatException) {
                    raw
                }
                text = formatted
                textSize = dataTextSize
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = paddingH
                }
                gravity = android.view.Gravity.END
            }
            row.addView(valueView)

            listView.addView(row)
        }
    }

    private fun initTransceivers() {
        // 从配置中恢复上次使用的协议和收发器，无需打开协议页面即可正确连接
        selectedProtocolPosition = panelConfig.protocolSelection
        currentTransceiver = when (selectedProtocolPosition) {
            0 -> serialTransceiver
            1 -> udpTransceiver
            2 -> tcpClientTransceiver
            3 -> tcpServerTransceiver
            4 -> demoTransceiver
            else -> serialTransceiver
        }
        // 设置统一的协议引擎数据处理回调
        // 根据当前数据引擎选择，将原始字节数据交由对应的引擎处理
        setupDataEngineForTransceiver(tcpClientTransceiver, "TCP客户端")
        setupDataEngineForTransceiver(tcpServerTransceiver, "TCP服务端")
        setupDataEngineForTransceiver(serialTransceiver, "串口")
        setupDataEngineForTransceiver(udpTransceiver, "UDP")
        setupDataEngineForTransceiver(demoTransceiver, "Demo")

        // 同时保留字符串接收回调（用于直接显示等场景）
        tcpClientTransceiver.setOnDataReceivedListener { _ -> }
        tcpServerTransceiver.setOnDataReceivedListener { _ -> }
        serialTransceiver.setOnDataReceivedListener { _ -> }
        udpTransceiver.setOnDataReceivedListener { _ -> }
        demoTransceiver.setOnDataReceivedListener { _ -> }

        // 设置 TCP 客户端连接状态回调，超时/失败时自动恢复按钮状态
        tcpClientTransceiver.setOnStateChangedListener { connected, message ->
            val btn = findViewById<ImageButton>(R.id.menuBarConnect)
            if (connected) {
                isTcpConnecting = false
                isConnected = true
                btn.setImageResource(R.drawable.ic_connect_on)
                btn.alpha = 1f
                // 连接建立 -> 锁定数据引擎 Spinner
                setDataEngineSpinnerEnabled(false)
                onConnectionStateChanged(true)
            } else {
                if (isConnected || isTcpConnecting) {
                    isTcpConnecting = false
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    showToastShort(message)
                    // 连接断开 -> 解锁数据引擎 Spinner
                    setDataEngineSpinnerEnabled(true)
                    onConnectionStateChanged(false)
                }
            }
        }
        // 设置 TCP 服务端连接状态回调（断开时恢复按钮）
        tcpServerTransceiver.setOnStateChangedListener { connected, message ->
            if (!connected && isConnected) {
                isConnected = false
                findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_off)
                showToastShort(message)
                // 服务端异常停止 -> 解锁数据引擎 Spinner
                setDataEngineSpinnerEnabled(true)
                onConnectionStateChanged(false)
            }
        }
        // 设置 TCP 服务端连接数变化回调，动态更新协议页面的连接数量显示
        tcpServerTransceiver.setOnConnectionCountChangeListener { count ->
            protocolView?.findViewById<TextView>(R.id.tcpServerConnCount)?.text = count.toString()
        }
        // 设置 TCP 服务端客户端列表变化回调，动态更新当前连接 Spinner
        tcpServerTransceiver.setOnConnectionListChangeListener { addresses ->
            updateTcpServerConnSpinner(addresses)
        }

        // 设置 UDP 连接状态回调
        udpTransceiver.setOnStateChangedListener { connected, message ->
            val btn = findViewById<ImageButton>(R.id.menuBarConnect)
            if (connected) {
                isConnected = true
                btn.setImageResource(R.drawable.ic_connect_on)
                btn.alpha = 1f
                // 连接建立 -> 锁定数据引擎 Spinner
                setDataEngineSpinnerEnabled(false)
                onConnectionStateChanged(true)
            } else {
                if (isConnected) {
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    showToastShort(message)
                    // 连接断开 -> 解锁数据引擎 Spinner
                    setDataEngineSpinnerEnabled(true)
                    onConnectionStateChanged(false)
                }
            }
        }

        // 根据保存的配置初始化数据引擎
        selectDataEngine(panelConfig.dataEngineSelection)
    }

    // ====================== 协议引擎处理 ======================

    /**
     * 根据当前数据引擎选择，为指定的收发器设置原始数据回调
     */
    private fun setupDataEngineForTransceiver(transceiver: DataTransceiver, connectionName: String) {
        transceiver.setOnRawDataReceivedListener { data ->
            // ★ 引擎始终处理数据（ParamBuffer/ImageBuffer 不受 Rx 开关影响），
            //    Rx 开关仅控制输出窗口的显示。
            val rxEnabled = outputRxHighlight

            val outputs = currentDataEngine.feed(connectionName, data)
            val engineName = currentDataEngine.getEngineName()

            for (output in outputs) {
                // ── 图像数据包处理（不受 Rx 开关影响，始终进入 ImageBuffer）──
                if (output.type == OutputType.IMAGE_PACKET) {
                    val isDataPayload = imageBuffer.isCollecting
                    imageBuffer.feed(output.text, output.rawImageBytes,
                        output.imageWidth, output.imageHeight, output.imageFormat)
                    if (isDataPayload) {
                        // ★ 新图像帧数据载荷完成 → 触发 GPU 渲染
                        checkAndDisplayImage()
                    }
                    // 前导帧：根据显示开关决定是否进入输出显示区
                    if (!isDataPayload && outputRxHighlight && rxShowImagePacket) {
                        appendOutput(output.text, false, output.escapeControlChars, output.type)
                    }
                    continue
                }

                // ── 判断是否为合规采样数据帧 ──
                // RawData 引擎的 TEXT 不计入；仅 FireWater/JustFloat 的 TEXT 类型
                // 且被 paramBuffer.feed() 接受后才算合规采样帧。
                val isSamplingFrame = if (isConnected && output.type == OutputType.TEXT
                    && engineName != "RawData") {
                    val decoded = output.text.hexToString(outputUseGbk)
                    val accepted = paramBuffer.feed(engineName, decoded)
                    if (accepted) {
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .post { refreshParamViews() }
                    }
                    accepted
                } else {
                    false
                }

                // ★ Rx 关闭时不进入输出显示区，但 ParamBuffer/ImageBuffer 已处理完毕
                if (!rxEnabled) continue

                // ★ 采样帧标记传入 appendOutput，由 doFlushOutput 根据 rxShowSampleData 控制显示
                appendOutput(output.text, false, output.escapeControlChars, output.type, isSamplingFrame)
            }
            // 参数缓存锁定状态可能在 feed 后发生变化，更新标题
            if (paramBuffer.isLocked) {
                android.os.Handler(android.os.Looper.getMainLooper())
                    .post { updateParamHeaderTitle() }
            }
        }
    }

    /**
     * 切换数据引擎（由协议页面的数据引擎 Spinner 触发）
     */
    private fun selectDataEngine(engineIndex: Int) {
        // 重置旧引擎状态
        currentDataEngine.reset()
        // 切换引擎时重置参数缓存区（旧引擎的锁定计数不再适用）
        paramBuffer.reset()
        imageBuffer.reset()
        hideImageDisplay()
        updateImageInfo()
        updateParamHeaderTitle()
        refreshParamViews()

        currentDataEngine = when (engineIndex) {
            0 -> {
                AppLogger.i("MainActivity", "数据引擎切换为: FireWater")
                // 离开 JustFloat 时恢复 Hex/Abc 按钮（可能尚未初始化）
                if (justFloatForceHex && ::btnAbcHex.isInitialized) {
                    justFloatForceHex = false
                    btnAbcHex.isEnabled = true
                    btnAbcHex.alpha = 1f
                }
                justFloatForceHex = false
                fireWaterEngine
            }
            1 -> {
                AppLogger.i("MainActivity", "数据引擎切换为: JustFloat — 强制 Hex 模式")
                // ★ JustFloat 强制使用 Hex 模式并禁止切换
                justFloatForceHex = true
                outputShowHex = true
                // btnAbcHex 可能尚未初始化（初次启动时顺序在 setupOutputView 之前）
                if (::btnAbcHex.isInitialized) {
                    (btnAbcHex as? android.widget.TextView)?.text = "Hex"
                    btnAbcHex.isEnabled = false
                    btnAbcHex.alpha = 0.4f
                }
                justFloatEngine
            }
            2 -> {
                AppLogger.i("MainActivity", "数据引擎切换为: RawData")
                if (justFloatForceHex && ::btnAbcHex.isInitialized) {
                    justFloatForceHex = false
                    btnAbcHex.isEnabled = true
                    btnAbcHex.alpha = 1f
                }
                justFloatForceHex = false
                rawDataEngine
            }
            else -> {
                AppLogger.i("MainActivity", "数据引擎切换为: FireWater（默认）")
                fireWaterEngine
            }
        }

        // 为所有收发器重新设置引擎回调
        setupDataEngineForTransceiver(tcpClientTransceiver, "TCP客户端")
        setupDataEngineForTransceiver(tcpServerTransceiver, "TCP服务端")
        setupDataEngineForTransceiver(serialTransceiver, "串口")
        setupDataEngineForTransceiver(udpTransceiver, "UDP")
        setupDataEngineForTransceiver(demoTransceiver, "Demo")

        panelConfig.dataEngineSelection = engineIndex
        ConfigManager.saveConfig(panelConfig)

        AppLogger.i("MainActivity", "数据引擎已切换至: ${currentDataEngine.getEngineName()}")
        // 同步当前过滤状态到新引擎
        updateImagePacketFilter()
    }

    /**
     * 引擎始终不过滤图像数据（图像过滤仅在输出窗口显示层生效）。
     * 切换引擎时同步调用以确保新引擎处于正确状态。
     */
    private fun updateImagePacketFilter() {
        currentDataEngine.setImagePacketFiltered(false)
    }

    private fun enableDoubleBackExit() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - backPressedTime > 2000) {
                    backPressedTime = now
                    showToast("再按一次退出应用")
                } else {
                    finish()
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        window.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ — 使用 WindowInsetsController
                WindowCompat.setDecorFitsSystemWindows(this, false)
                insetsController?.apply {
                    hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Android 8-10 — 使用传统 SystemUiVisibility
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyColors() {
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        val menuBarBg = android.graphics.Color.parseColor(panelConfig.menuBarBgHex)

        val window = window
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor

        menuBar.setBackgroundColor(menuBarBg)

        navA.getHeaderView(0)?.setBackgroundColor(primaryColor)
        navB.getHeaderView(0)?.setBackgroundColor(primaryColor)

        AppLogger.i("MainActivity", "配色: primary=${panelConfig.primaryColorHex}, menuBar=${panelConfig.menuBarBgHex}")
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun applyMenuBarLayout() {
        val barDp = panelConfig.menuBarWidthDp.coerceIn(BAR_NARROW, BAR_WIDE)
        val barPx = dpToPx(barDp)
        val ratio = barDp.toFloat() / 48f
        val iconSz = dpToPx((ICON_DP * ratio).toInt())
        val pad = dpToPx((6 * ratio).toInt())
        val ms = dpToPx((8 * ratio).toInt())
        val decorMt = dpToPx((16 * ratio).toInt())
        val btnTopMt = dpToPx((6 * ratio).toInt())

        menuBar.layoutParams.width = barPx
        (workArea.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (scrimOverlay.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navA.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navB.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        rootContainer.requestLayout()

        menuBarDecoration.apply {
            val lp = layoutParams
            val logoSize = (iconSz * 0.75f).toInt()
            lp.width = logoSize; lp.height = logoSize
            (lp as ViewGroup.MarginLayoutParams).topMargin = decorMt
            layoutParams = lp
        }

        val connectSz = dpToPx((ICON_DP * 1.15 * ratio).toInt())

        val ids = intArrayOf(R.id.menuBarConnect, R.id.menuBarProtocol, R.id.menuBarCommand, R.id.menuBarControl, R.id.menuBarSetting)
        for (id in ids) {
            val btn = findViewById<ImageButton>(id)
            val lp = btn.layoutParams as ViewGroup.MarginLayoutParams
            val sz = if (id == R.id.menuBarConnect) connectSz else iconSz
            lp.width = sz; lp.height = sz
            lp.topMargin = if (id == R.id.menuBarConnect) btnTopMt else ms
            btn.layoutParams = lp; btn.setPadding(pad, pad, pad, pad)
        }
        menuBarHighlight.layoutParams.height = (barPx * 64f / 48f).toInt()
        AppLogger.i("MainActivity", "菜单栏: ${barDp}dp, 图标: ${(ICON_DP * ratio).toInt()}dp")
    }

    private fun setBarAndSave(barDp: Int, label: String) {
        panelConfig.menuBarWidthDp = barDp; applyMenuBarLayout(); ConfigManager.saveConfig(panelConfig)
        Toast.makeText(this, "菜单栏: $label", Toast.LENGTH_SHORT).show()
    }

    private val menuBarButtonIds = intArrayOf(
        R.id.menuBarConnect, R.id.menuBarProtocol,
        R.id.menuBarCommand, R.id.menuBarControl, R.id.menuBarSetting
    )

    private fun clearMenuBarHighlight() {
        for (id in menuBarButtonIds) {
            findViewById<ImageButton>(id)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        menuBarHighlight.visibility = View.GONE
    }

    private fun highlightMenuBarButton(btnId: Int) {
        clearMenuBarHighlight()
        val btn = findViewById<ImageButton>(btnId)
        menuBarHighlight.post {
            menuBarHighlight.translationY = btn.y + (btn.height - menuBarHighlight.layoutParams.height) / 2f
            menuBarHighlight.visibility = View.VISIBLE
        }
    }

    private fun getActiveNav(): NavigationView = if (activeIsA) navA else navB
    private fun getInactiveNav(): NavigationView = if (activeIsA) navB else navA

    private fun openMenu() {
        if (isMenuOpen) return
        val active = getActiveNav()
        active.translationX = -getMenuPx(); active.visibility = View.VISIBLE
        active.animate().cancel()
        active.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        scrimOverlay.apply { visibility = View.VISIBLE; alpha = 0f }
        scrimOverlay.animate().cancel()
        scrimOverlay.animate().alpha(0.45f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        isMenuOpen = true
    }

    private fun closeMenu() {
        if (!isMenuOpen) return
        val active = getActiveNav()
        active.animate().translationX(-getMenuPx()).setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { active.visibility = View.GONE }.start()
        scrimOverlay.animate().alpha(0f).setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { scrimOverlay.visibility = View.GONE }.start()
        isMenuOpen = false; clearMenuBarHighlight(); selectedMenuBtnId = 0
    }

    private fun getMenuPx(): Float = MENU_WIDTH_DP * resources.displayMetrics.density

    private fun switchMenuSimultaneously(
        titleRes: Int, menuRes: Int, btnId: Int,
        listener: NavigationView.OnNavigationItemSelectedListener
    ) {
        if (isMenuOpen && (currentMenuRes == PROTOCOL_MENU_MARKER || currentMenuRes == SETTINGS_MENU_MARKER || currentMenuRes == CONTROL_MENU_MARKER)) {
            removeCustomViewsFromNav(getActiveNav())
        }
        if (isMenuOpen && currentMenuRes == menuRes) {
            closeMenu()
            return
        }
        currentMenuRes = menuRes; selectedMenuBtnId = btnId
        val title = getString(titleRes); val menuPx = getMenuPx()
        if (!isMenuOpen) {
            highlightMenuBarButton(btnId); setNavContent(getActiveNav(), title, menuRes, listener); openMenu(); return
        }
        val oldNav = getActiveNav(); val newNav = getInactiveNav()
        setNavContent(newNav, title, menuRes, listener)
        newNav.translationX = -menuPx; newNav.visibility = View.VISIBLE; highlightMenuBarButton(btnId)
        oldNav.animate().cancel(); newNav.animate().cancel()
        oldNav.animate().translationX(-menuPx).setDuration(200).setInterpolator(DecelerateInterpolator())
            .withEndAction { oldNav.visibility = View.GONE }.start()
        newNav.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
        activeIsA = !activeIsA
    }

    private fun setNavContent(nav: NavigationView, title: String, menuRes: Int, listener: NavigationView.OnNavigationItemSelectedListener) {
        removeCustomViewsFromNav(nav)
        nav.getHeaderView(0)?.visibility = View.VISIBLE
        nav.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.navHeaderTitle)?.text = title
        nav.menu.clear(); nav.inflateMenu(menuRes); nav.setNavigationItemSelectedListener(listener)
    }

    private fun removeCustomViewsFromNav(nav: NavigationView) {
        for (i in nav.childCount - 1 downTo 0) {
            val child = nav.getChildAt(i)
            if (child.tag == "protocol_view" || child.tag == "settings_view" || child.tag == "control_view") {
                nav.removeView(child)
            }
        }
    }

    private fun setupButtons() {
        setupSettingsButton()
        setupProtocolButton()
        setupMenuButton(R.id.menuBarCommand, R.string.nav_header_command, R.menu.menu_command, commandListener)
        setupControlButton()
        setupConnectButton()
    }

    private fun setupMenuButton(btnId: Int, titleRes: Int, menuRes: Int, listener: NavigationView.OnNavigationItemSelectedListener) {
        findViewById<ImageButton>(btnId).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    switchMenuSimultaneously(titleRes, menuRes, btnId, listener)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> true
            }
        }
    }

    private fun setupConnectButton() {
        findViewById<ImageButton>(R.id.menuBarConnect).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    handleConnectToggle(v as ImageButton)
                    true
                }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start(); true }
                MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start(); true }
                else -> true
            }
        }
    }

    // ====================== 协议与连接页面 ======================

    private fun setupProtocolButton() {
        findViewById<ImageButton>(R.id.menuBarProtocol).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    toggleProtocolPage()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleProtocolPage() {
        if (isMenuOpen && currentMenuRes == PROTOCOL_MENU_MARKER) {
            closeMenu()
            removeCustomViewsFromNav(getActiveNav())
        } else {
            showProtocolPage()
        }
    }

    private fun showProtocolPage() {
        if (protocolView == null) {
            protocolView = layoutInflater.inflate(R.layout.page_protocol, null)
            setupDataEngineSpinner()
            setupProtocolSpinner()
            setupBaudRateSpinner()
            setupSerialSpinners()
            setupProtocolDocButton()
            setupLocalIpDisplay()
        }

        highlightMenuBarButton(R.id.menuBarProtocol)
        val title = getString(R.string.protocol_title)
        val menuPx = getMenuPx()

        if (!isMenuOpen) {
            val active = getActiveNav()
            setNavProtocolContent(active, title)
            openMenu()
        } else {
            val oldNav = getActiveNav()
            val newNav = getInactiveNav()
            setNavProtocolContent(newNav, title)
            newNav.translationX = -menuPx
            newNav.visibility = View.VISIBLE
            oldNav.animate().cancel()
            newNav.animate().cancel()
            oldNav.animate().translationX(-menuPx).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { oldNav.visibility = View.GONE }.start()
            newNav.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
            activeIsA = !activeIsA
        }

        currentMenuRes = PROTOCOL_MENU_MARKER
        selectedMenuBtnId = R.id.menuBarProtocol
    }

    private fun setNavProtocolContent(nav: NavigationView, title: String) {
        nav.getHeaderView(0)?.visibility = View.GONE
        nav.menu.clear()
        nav.setNavigationItemSelectedListener(null)
        removeCustomViewsFromNav(nav)
        protocolView?.let { v ->
            if (v.parent != null) {
                (v.parent as ViewGroup).removeView(v)
            }
            v.tag = "protocol_view"
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            nav.addView(v, lp)
        }
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        protocolView?.findViewById<View>(R.id.protocolHeader)?.setBackgroundColor(primaryColor)
    }

    /**
     * 启用或禁用数据引擎 Spinner。
     * 连接建立时禁用（防止切换协议引擎），断开后恢复。
     */
    private fun setDataEngineSpinnerEnabled(enabled: Boolean) {
        dataEngineSpinner?.let { spinner ->
            spinner.isEnabled = enabled
            // 修改透明度直观反映禁用状态
            spinner.alpha = if (enabled) 1f else 0.4f
            // 禁用时 Spinner 不响应点击
            spinner.isClickable = enabled
        }
    }

    private fun setupDataEngineSpinner() {
        val spinner = protocolView?.findViewById<Spinner>(R.id.dataEngineSpinner) ?: return
        dataEngineSpinner = spinner // 缓存引用供 setDataEngineSpinnerEnabled 使用
        val options = listOf(
            getString(R.string.protocol_option_firewater),
            getString(R.string.protocol_option_justfloat),
            getString(R.string.protocol_option_rawdata)
        )
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, options) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter
        spinner.setSelection(panelConfig.dataEngineSelection.coerceIn(0, options.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // 连接已建立时忽略 Spinner 的选中事件（视觉上已禁用，但防止程序触发）
                if (isConnected) return
                AppLogger.i("MainActivity", "数据引擎选择: " + options[position])
                selectDataEngine(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 根据当前连接状态同步 Spinner 可用性
        setDataEngineSpinnerEnabled(!isConnected)
    }

    private fun restoreTcpClientFields() {
        protocolView?.findViewById<EditText>(R.id.tcpClientServerIp)?.setText(panelConfig.tcpClientServerIp)
        protocolView?.findViewById<EditText>(R.id.tcpClientNetworkPort)?.setText(panelConfig.tcpClientNetworkPort)
        protocolView?.findViewById<EditText>(R.id.tcpClientHandshake)?.setText(panelConfig.tcpClientHandshake)
    }

    private fun restoreTcpServerFields() {
        protocolView?.findViewById<EditText>(R.id.tcpServerListenPort)?.setText(panelConfig.tcpServerListenPort)
        protocolView?.findViewById<EditText>(R.id.tcpServerHandshake)?.setText(panelConfig.tcpServerHandshake)
        val connCountText = protocolView?.findViewById<TextView>(R.id.tcpServerConnCount)
        connCountText?.text = tcpServerTransceiver.getManager().getConnectionCount().toString()
        // 刷新当前连接 Spinner，确保页面打开时与 Manager 状态一致
        updateTcpServerConnSpinner(tcpServerTransceiver.getManager().getClientAddresses())
        // 刷新本机IP信息
        updateLocalIpDisplay()
    }

    private fun restoreUdpFields() {
        protocolView?.findViewById<EditText>(R.id.udpRemoteIp)?.setText(panelConfig.udpRemoteIp)
        protocolView?.findViewById<EditText>(R.id.udpRemotePort)?.setText(panelConfig.udpRemotePort)
        protocolView?.findViewById<EditText>(R.id.udpLocalPort)?.setText(panelConfig.udpLocalPort)
    }

    private fun setupProtocolSpinner() {
        val spinner = protocolView?.findViewById<Spinner>(R.id.protocolSpinner) ?: return
        val options = listOf(getString(R.string.protocol_option_serial), getString(R.string.protocol_option_udp), getString(R.string.protocol_option_tcp_client), getString(R.string.protocol_option_tcp_server), getString(R.string.protocol_option_demo))
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, options) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppLogger.i("MainActivity", "协议接口选择: " + options[position])
                if ((selectedProtocolPosition == 1 || selectedProtocolPosition == 2 || selectedProtocolPosition == 3) && position != selectedProtocolPosition && isConnected) { disconnectCurrent() }
                selectedProtocolPosition = position
                // 切换当前数据收发器
                currentTransceiver = when (position) {
                    0 -> serialTransceiver
                    1 -> udpTransceiver
                    2 -> tcpClientTransceiver
                    3 -> tcpServerTransceiver
                    4 -> demoTransceiver
                    else -> serialTransceiver
                }
                val serialPanel = protocolView?.findViewById<View>(R.id.serialConfigPanel)
                val udpPanel = protocolView?.findViewById<View>(R.id.udpConfigPanel)
                val tcpClientPanel = protocolView?.findViewById<View>(R.id.tcpClientConfigPanel)
                val tcpServerPanel = protocolView?.findViewById<View>(R.id.tcpServerConfigPanel)
                serialPanel?.visibility = View.GONE; udpPanel?.visibility = View.GONE; tcpClientPanel?.visibility = View.GONE; tcpServerPanel?.visibility = View.GONE
                when (position) {
                    0 -> serialPanel?.visibility = View.VISIBLE
                    1 -> { udpPanel?.visibility = View.VISIBLE; restoreUdpFields() }
                    2 -> { tcpClientPanel?.visibility = View.VISIBLE; restoreTcpClientFields() }
                    3 -> { tcpServerPanel?.visibility = View.VISIBLE; restoreTcpServerFields() }
                }
                panelConfig.protocolSelection = position
                ConfigManager.saveConfig(panelConfig)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val savedPos = panelConfig.protocolSelection.coerceIn(0, options.size - 1)
        spinner.setSelection(savedPos)
    }

    private fun setupBaudRateSpinner() {
        val spinner = protocolView?.findViewById<Spinner>(R.id.baudRateSpinner) ?: return
        val baudRates = listOf(
            "4000000", "3500000", "3000000", "2500000", "2000000",
            "1500000", "1152000", "1000000", "921600", "576000",
            "500000", "460800", "230400", "115200", "74800",
            "57600", "38400", "19200", "9600", "4800", "2400", "1200"
        )
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, baudRates) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter
        val defaultIdx = baudRates.indexOf("115200").coerceAtLeast(0)
        spinner.setSelection(defaultIdx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppLogger.i("MainActivity", "波特率选择: ${baudRates[position]}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSerialSpinners() {
        setupChoiceSpinner(R.id.flowControlSpinner, listOf("None", "Hard", "Soft"))
        setupChoiceSpinner(R.id.paritySpinner, listOf("None", "Even", "Odd", "Space", "Mark"))
        setupChoiceSpinner(R.id.dataBitsSpinner, listOf("8", "7", "6", "5"))
        setupChoiceSpinner(R.id.stopBitsSpinner, listOf("1", "1.5", "2"))
        setupFlowSignalChips()
    }

    private fun setupFlowSignalChips() {
        val chipIds = intArrayOf(R.id.chipDtr, R.id.chipRts, R.id.chipBreak)
        for (id in chipIds) {
            val chip = protocolView?.findViewById<TextView>(id) ?: continue
            chip.setOnClickListener {
                chip.isSelected = !chip.isSelected
                val state = if (chip.isSelected) "选中" else "取消"
                AppLogger.i("MainActivity", "流控信号: ${chip.text} $state")
            }
        }
    }

    private fun setupChoiceSpinner(spinnerId: Int, options: List<String>) {
        val spinner = protocolView?.findViewById<Spinner>(spinnerId) ?: return
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, options) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppLogger.i("MainActivity", "串口参数 spinner#$spinnerId 选择: ${options[position]}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupProtocolDocButton() {
        val docBtn = protocolView?.findViewById<ImageButton>(R.id.protocolDocBtn) ?: return
        docBtn.setOnClickListener {
            AppLogger.i("MainActivity", "打开协议文档")
            Toast.makeText(this, "打开协议文档", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTcpServerConnSpinner(addresses: List<String>) {
        val spinner = protocolView?.findViewById<Spinner>(R.id.tcpServerConnSpinner) ?: return
        val isEmpty = addresses.isEmpty()
        // 无设备时用一个空字符串作为选项（Spinner 显示空白）
        val options = if (isEmpty) listOf("") else addresses
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, options) {
            override fun isEnabled(position: Int): Boolean = !isEmpty
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                // 无设备时显示占位文本且不可选（灰色）
                if (isEmpty) {
                    view.text = getString(R.string.tcp_server_conn_empty)
                    view.isEnabled = false
                }
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter
        // 有设备时始终选中第一个（按连接顺序排序）；无设备时无选中
        if (!isEmpty) {
            spinner.setSelection(0)
        }
    }

    // ────────────── 本机IP地址信息显示 ──────────────

    /**
     * 获取本机所有非回环 IPv4 地址（WiFi、移动数据、热点等网络接口）
     */
    private fun getLocalIpInfo(): String {
        val sb = StringBuilder()
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                // 跳过未启用的、回环的和虚拟接口
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue

                val interfaceName = networkInterface.name
                val addresses = networkInterface.inetAddresses
                var hasIpv4 = false
                val ips = StringBuilder()
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        if (ips.isNotEmpty()) ips.append(", ")
                        ips.append(addr.hostAddress)
                        hasIpv4 = true
                    }
                }
                if (hasIpv4) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(interfaceName).append(": ").append(ips)
                }
            }
        } catch (e: Exception) {
            return "获取IP失败: ${e.message}"
        }
        return sb.toString().ifEmpty { "无可用网络连接" }
    }

    /**
     * 更新协议页面的本机IP显示
     */
    private fun updateLocalIpDisplay() {
        val ipText = protocolView?.findViewById<TextView>(R.id.localIpInfo) ?: return
        ipText.text = getLocalIpInfo()
    }

    /**
     * 设置IP刷新按钮监听
     */
    private fun setupLocalIpDisplay() {
        val refreshBtn = protocolView?.findViewById<TextView>(R.id.refreshIpBtn) ?: return
        refreshBtn.setOnClickListener {
            updateLocalIpDisplay()
            Toast.makeText(this, "IP信息已刷新", Toast.LENGTH_SHORT).show()
        }
        // 首次进入页面时自动显示IP信息
        updateLocalIpDisplay()
    }

    // ====================== 设置页面 ======================

    private fun setupSettingsButton() {
        findViewById<ImageButton>(R.id.menuBarSetting).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    toggleSettingsPage()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> true
            }
        }
    }

    // ====================== 控件页面 ======================

    private fun setupControlButton() {
        findViewById<ImageButton>(R.id.menuBarControl).setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100).start()
                    toggleControlPage()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    true
                }
                else -> true
            }
        }
    }

    private fun toggleControlPage() {
        if (isMenuOpen && currentMenuRes == CONTROL_MENU_MARKER) {
            closeMenu()
            removeCustomViewsFromNav(getActiveNav())
        } else {
            showControlPage()
        }
    }

    private fun showControlPage() {
        if (controlView == null) {
            controlView = layoutInflater.inflate(R.layout.page_control, null)
            setupControlCheckboxes()
        }

        highlightMenuBarButton(R.id.menuBarControl)
        val title = getString(R.string.nav_header_control)
        val menuPx = getMenuPx()

        if (!isMenuOpen) {
            val active = getActiveNav()
            setNavControlContent(active, title)
            openMenu()
        } else {
            val oldNav = getActiveNav()
            val newNav = getInactiveNav()
            setNavControlContent(newNav, title)
            newNav.translationX = -menuPx
            newNav.visibility = View.VISIBLE
            oldNav.animate().cancel()
            newNav.animate().cancel()
            oldNav.animate().translationX(-menuPx).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { oldNav.visibility = View.GONE }.start()
            newNav.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
            activeIsA = !activeIsA
        }

        currentMenuRes = CONTROL_MENU_MARKER
        selectedMenuBtnId = R.id.menuBarControl
    }

    private fun setNavControlContent(nav: NavigationView, title: String) {
        nav.getHeaderView(0)?.visibility = View.GONE
        nav.menu.clear()
        nav.setNavigationItemSelectedListener(null)
        removeCustomViewsFromNav(nav)
        controlView?.let { v ->
            if (v.parent != null) {
                (v.parent as ViewGroup).removeView(v)
            }
            v.tag = "control_view"
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            nav.addView(v, lp)
        }
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        controlView?.findViewById<View>(R.id.controlHeader)?.setBackgroundColor(primaryColor)
    }

    private fun setupControlCheckboxes() {
        val cv = controlView ?: return
        var imageChecked = rxShowImageWindow
        var waveformChecked = false

        val imageCheckbox = cv.findViewById<View>(R.id.controlImageCheckbox)
        val imageRow = cv.findViewById<View>(R.id.controlImageRow)
        val waveformCheckbox = cv.findViewById<View>(R.id.controlWaveformCheckbox)
        val waveformRow = cv.findViewById<View>(R.id.controlWaveformRow)

        fun updateImageCheckbox() {
            imageCheckbox.setBackgroundResource(
                if (imageChecked) R.drawable.bg_checkbox_checked
                else R.drawable.bg_checkbox_unchecked
            )
        }

        fun updateWaveformCheckbox() {
            waveformCheckbox.setBackgroundResource(
                if (waveformChecked) R.drawable.bg_checkbox_checked
                else R.drawable.bg_checkbox_unchecked
            )
        }

        // 初始化 UI 状态
        updateImageCheckbox()
        updateWaveformCheckbox()

        imageRow.setOnClickListener {
            imageChecked = !imageChecked
            rxShowImageWindow = imageChecked
            updateImageCheckbox()
            if (imageChecked) {
                // 开启时：若缓存区有上一帧数据，直接显示
                restoreLastImage()
            } else {
                hideImageDisplay()
            }
            AppLogger.i("MainActivity", "控件-图像: ${if (imageChecked) "开" else "关"}")
        }

        waveformRow.setOnClickListener {
            waveformChecked = !waveformChecked
            updateWaveformCheckbox()
            AppLogger.i("MainActivity", "控件-波形图: ${if (waveformChecked) "开" else "关"}")
        }

        // 初始刷新图像信息显示
        updateImageInfo()

        // ── 还原图像位置与尺寸按钮 ──
        val resetRow = cv.findViewById<View>(R.id.controlImageResetRow)
        if (resetRow != null) {
            
        // ── 保存图像按钮 ──
        val saveRow = cv.findViewById<View>(R.id.controlImageSaveRow)
        if (saveRow != null) {
            saveRow.setOnClickListener { saveCurrentImage() }
        }
resetRow.setOnClickListener { resetImagePosition() }
        }
    }

    /** 还原图像窗口位置和尺寸为默认值，清除冷保存数据。 */
    private fun resetImagePosition() {
        panelConfig.imageWindowPosX = -1
        panelConfig.imageWindowPosY = -1
        panelConfig.imageWindowWidth = -1
        panelConfig.imageWindowHeight = -1
        ConfigManager.saveConfig(panelConfig)
        // 如果窗口已显示，移除并等待下次图像帧重建
        if (imageWindowContainer != null) {
            hideImageDisplay()
            showToastShort("图像窗口位置已还原，等待新图像帧刷新")
        } else {
            showToastShort("图像窗口位置已还原")
        }
    }

    /** 保存当前图像帧为 PNG 到 Download/ECHO+ 目录。 */
    private fun saveCurrentImage() {
        val bytes = imageBuffer.rawBytes
        val w = imageBuffer.imageWidth
        val h = imageBuffer.imageHeight
        val fmt = imageBuffer.imageFormat
        if (bytes == null || bytes.isEmpty() || w <= 0 || h <= 0) {
            showToastShort("无可用图像数据")
            return
        }
        Thread {
            try {
                // Grayscale8 → Bitmap (ARGB_8888)
                val pixels = IntArray(w * h)
                for (i in pixels.indices) {
                    val gray = bytes[i].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
                val bmp = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)

                val sdf = java.text.SimpleDateFormat("yy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                val ts = sdf.format(java.util.Date())
                val filename = "$ts.png"
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "ECHO+")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)

                file.outputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                bmp.recycle()

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToastShort("已保存: Download/ECHO+/$filename (${w}x$h)")
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToastShort("保存失败: ${e.message}")
                }
            }
        }.start()
    }

    /** 更新控件页图像信息面板（宽度、高度、格式）。可从任意线程安全调用。 */
    private fun updateImageInfo() {
        outputHandler.post {
            if (controlView == null) return@post
            val infoText = controlView!!.findViewById<TextView>(R.id.controlImageInfoText) ?: return@post
            val infoPanel = controlView!!.findViewById<View>(R.id.controlImageInfoPanel) ?: return@post

            val w = imageBuffer.imageWidth
            val h = imageBuffer.imageHeight
            val fmt = imageBuffer.imageFormat

            if (w > 0 && h > 0) {
                val fmtName = ImageFrame.getFormatName(fmt)
                infoText.text = "宽度: ${w}px\n高度: ${h}px\n格式: $fmtName"
                infoPanel.visibility = View.VISIBLE
            } else {
                infoPanel.visibility = View.GONE
            }
        }
    }

    private fun toggleSettingsPage() {
        if (isMenuOpen && currentMenuRes == SETTINGS_MENU_MARKER) {
            closeMenu()
            removeCustomViewsFromNav(getActiveNav())
        } else {
            showSettingsPage()
        }
    }

    private fun showSettingsPage() {
        if (settingsView == null) {
            settingsView = layoutInflater.inflate(R.layout.page_settings, null)
            setupSettingsSpinner()
            setupTcpTimeoutField()
            setupSettingsExportButton()
            setupSettingsResetButton()
        }

        highlightMenuBarButton(R.id.menuBarSetting)
        val title = getString(R.string.nav_header_settings)
        val menuPx = getMenuPx()

        if (!isMenuOpen) {
            val active = getActiveNav()
            setNavSettingsContent(active, title)
            openMenu()
        } else {
            val oldNav = getActiveNav()
            val newNav = getInactiveNav()
            setNavSettingsContent(newNav, title)
            newNav.translationX = -menuPx
            newNav.visibility = View.VISIBLE
            oldNav.animate().cancel()
            newNav.animate().cancel()
            oldNav.animate().translationX(-menuPx).setDuration(200).setInterpolator(DecelerateInterpolator())
                .withEndAction { oldNav.visibility = View.GONE }.start()
            newNav.animate().translationX(0f).setDuration(280).setInterpolator(DecelerateInterpolator()).start()
            activeIsA = !activeIsA
        }

        currentMenuRes = SETTINGS_MENU_MARKER
        selectedMenuBtnId = R.id.menuBarSetting
    }

    private fun setNavSettingsContent(nav: NavigationView, title: String) {
        nav.getHeaderView(0)?.visibility = View.GONE
        nav.menu.clear()
        nav.setNavigationItemSelectedListener(null)
        removeCustomViewsFromNav(nav)
        settingsView?.let { v ->
            if (v.parent != null) {
                (v.parent as ViewGroup).removeView(v)
            }
            v.tag = "settings_view"
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            nav.addView(v, lp)
        }
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        settingsView?.findViewById<View>(R.id.settingsHeader)?.setBackgroundColor(primaryColor)
    }

    private fun setupTcpTimeoutField() {
        val editText = settingsView?.findViewById<EditText>(R.id.tcpTimeoutEdit) ?: return
        editText.setText(panelConfig.tcpClientTimeoutSeconds.toString())
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveTcpTimeoutFromField(editText)
            }
        }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                saveTcpTimeoutFromField(editText)
                true
            } else false
        }
    }

    private fun saveTcpTimeoutFromField(editText: EditText) {
        val text = editText.text.toString()
        val value = text.toIntOrNull()
        if (value == null || value < 1 || value > 60) {
            editText.setText(panelConfig.tcpClientTimeoutSeconds.toString())
            showToast("超时时间需在 1~60 秒之间")
        } else if (value != panelConfig.tcpClientTimeoutSeconds) {
            panelConfig.tcpClientTimeoutSeconds = value
            ConfigManager.saveConfig(panelConfig)
        }
    }

    private fun setupSettingsSpinner() {
        val spinner = settingsView?.findViewById<Spinner>(R.id.settingsSpinner) ?: return
        val options = listOf(
            getString(R.string.settings_option_narrow),
            getString(R.string.settings_option_wide)
        )
        val adapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, options) {
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundResource(R.drawable.bg_spinner_dropdown_item)
                view.setPadding(20, 14, 20, 14)
                return view
            }
        }
        adapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        spinner.adapter = adapter

        val currentBarDp = panelConfig.menuBarWidthDp
        val defaultPos = if (currentBarDp >= 56) 1 else 0
        spinner.setSelection(defaultPos)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newBarDp = if (position == 0) BAR_NARROW else BAR_WIDE
                val label = if (position == 0) "窄（适合手机）" else "宽（适合平板）"

                // 如果已经是当前值，跳过
                if (newBarDp == panelConfig.menuBarWidthDp) return

                // 保存新配置到文件（不立即应用布局）
                panelConfig.menuBarWidthDp = newBarDp
                ConfigManager.saveConfig(panelConfig)

                // 询问是否立即重启以应用新的DPI配置
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("DPI配置已更改")
                    .setMessage("菜单栏宽度已设置为「$label」。\n是否立即重启应用以应用新的DPI适配？\n\n若不重启，配置将保存到文件，在下次手动重启后生效。")
                    .setPositiveButton("立即重启") { _, _ ->
                        restartApp()
                    }
                    .setNegativeButton("稍后重启") { _, _ ->
                        Toast.makeText(this@MainActivity, "配置已保存，重启后生效", Toast.LENGTH_SHORT).show()
                    }
                    .setCancelable(false)
                    .show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSettingsExportButton() {
        val row = settingsView?.findViewById<View>(R.id.settingsExportRow) ?: return

        row.setOnClickListener {
            val entries: List<OutputEntry>
            synchronized(outputDataEntries) {
                entries = outputDataEntries.toList()
            }

            if (entries.isEmpty()) {
                showToast("没有可导出的输出数据")
                return@setOnClickListener
            }

            Thread {
                try {
                    val ts = java.text.SimpleDateFormat("yy.MM.dd-HH-mm-ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val filename = "ECHO-$ts.log"

                    val content = buildString {
                        val timeFmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                        for (entry in entries) {
                            if (!entry.visible) continue
                            // 时间戳
                            if (entry.timestampMs != null) {
                                append("[").append(timeFmt.format(java.util.Date(entry.timestampMs))).append("] ")
                            }
                            // Rx/Tx 标记
                            append(if (entry.isTx) "[TX] " else "[RX] ")
                            // 类型标记
                            when (entry.type) {
                                com.fenfutao.echo.dataengine.OutputType.IMAGE_PACKET -> append("[IMAGE] ")
                                else -> {}
                            }
                            // 内容：hex 解码为可读文本
                            append(entry.text.hexToString(outputUseGbk))
                            append("\n")
                        }
                    }

                    // Android 10+ 使用 MediaStore 写入 Download 目录
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val contentValues = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        uri?.let {
                            contentResolver.openOutputStream(it)?.use { os ->
                                os.write(content.toByteArray(Charsets.UTF_8))
                            }
                        } ?: throw java.io.IOException("无法创建文件")
                    } else {
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!dir.exists()) dir.mkdirs()
                        val file = java.io.File(dir, filename)
                        file.writeText(content, Charsets.UTF_8)
                    }

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        showToastShort("已导出到 Download/$filename")
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        showToastShort("导出失败: ${e.message}")
                    }
                }
            }.start()
        }
    }

    private fun setupSettingsResetButton() {
        val row = settingsView?.findViewById<View>(R.id.settingsResetRow) ?: return

        row.setOnClickListener {
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("恢复默认配置")
                .setMessage("确定将所有配置恢复为出厂默认值吗？")
                .setNegativeButton("确认") { _, _ ->
                    val oldBarDp = panelConfig.menuBarWidthDp
                    panelConfig.resetToDefault()
                    ConfigManager.saveConfig(panelConfig)

                    if (panelConfig.menuBarWidthDp != oldBarDp) {
                        val label = if (panelConfig.menuBarWidthDp >= 56) "宽（适合平板）" else "窄（适合手机）"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("配置已重置")
                            .setMessage("配置已恢复为出厂默认值，菜单栏宽度已设置为「$label」。\n是否立即重启应用以应用新的DPI适配？\n\n若不重启，配置将保存到文件，在下次手动重启后生效。")
                            .setPositiveButton("立即重启") { _, _ ->
                                restartApp()
                            }
                            .setNegativeButton("稍后重启") { _, _ ->
                                Toast.makeText(this@MainActivity, "配置已重置，重启后生效", Toast.LENGTH_SHORT).show()
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "配置已重置为出厂默认值", Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton("取消", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    android.graphics.Color.parseColor("#9E9E9E")
                )
            }
            dialog.show()
        }
    }

    private fun applyPanelConfig() {
        (leftPanel.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.leftPanelWeight
        (paramsContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.rightPanelWeight
        (plotContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.plotWeight
        (outputContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.outputWeight
        workArea.requestLayout()
    }

    // ====================== 分割线拖拽调整尺寸 ======================

    private fun setupDividers() {
        vDividerView.setOnTouchListener { _, e ->
            onVDividerTouch(e)
            true // 消费事件，阻止传递
        }
        hDividerView.setOnTouchListener { _, e ->
            onHDividerTouch(e)
            true
        }
    }

    /**
     * 将像素最小值转换为对应的权重值（扣除固定尺寸分割线后）
     */
    private fun pixelToMinWeight(minPx: Int, totalWeight: Float, totalPx: Int, dividerPx: Int): Float {
        if (totalPx <= dividerPx) return 0f
        val avail = totalPx - dividerPx
        return minPx.toFloat() * totalWeight / avail
    }

    /**
     * 延迟执行布局刷新（防抖），避免每次 MOVE 都触发 requestLayout
     */
    private fun scheduleLayoutRefresh(container: View) {
        if (!pendingLayoutRefresh) {
            pendingLayoutRefresh = true
            container.postDelayed({
                container.requestLayout()
                pendingLayoutRefresh = false
            }, LAYOUT_REFRESH_INTERVAL_MS)
        }
    }

    // ────────────── 垂直分割线（左侧面板 / 参数列表） ──────────────

    private fun onVDividerTouch(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                isDraggingV = true
                startX = e.rawX
                startLeftWeight = panelConfig.leftPanelWeight
                startRightWeight = panelConfig.rightPanelWeight
                vDividerView.setBackgroundResource(R.drawable.bg_vdivider_dragging)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingV) return
                val ww = workArea.width
                val divW = vDividerView.width
                if (ww <= divW) return

                val dx = e.rawX - startX
                val tw = startLeftWeight + startRightWeight
                // 未限幅的新权重
                val nl = startLeftWeight + dx * tw / ww
                val nr = tw - nl

                // ── 最小尺寸限幅（像素精确） ──
                val minLeftPx = dpToPx(MIN_LEFT_WIDTH_DP)
                val minRightPx = dpToPx(MIN_RIGHT_WIDTH_DP)
                val minL = pixelToMinWeight(minLeftPx, tw, ww, divW)
                val minR = pixelToMinWeight(minRightPx, tw, ww, divW)
                if (nl < minL || nr < minR) return

                panelConfig.leftPanelWeight = nl
                panelConfig.rightPanelWeight = nr
                (leftPanel.layoutParams as LinearLayout.LayoutParams).weight = nl
                (paramsContainer.layoutParams as LinearLayout.LayoutParams).weight = nr

                scheduleLayoutRefresh(workArea)
            }

            MotionEvent.ACTION_UP -> {
                if (isDraggingV) {
                    isDraggingV = false
                    vDividerView.setBackgroundResource(R.drawable.bg_vdivider)
                    workArea.requestLayout()
                    ConfigManager.saveConfig(panelConfig)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isDraggingV = false
                vDividerView.setBackgroundResource(R.drawable.bg_vdivider)
            }
        }
    }

    // ────────────── 水平分割线（绘图窗口 / 输出窗口） ──────────────

    private fun onHDividerTouch(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                isDraggingH = true
                startY = e.rawY
                startPlotWeight = panelConfig.plotWeight
                startOutputWeight = panelConfig.outputWeight
                hDividerView.setBackgroundResource(R.drawable.bg_hdivider_dragging)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingH) return
                val ph = leftPanel.height
                val divH = hDividerView.height
                if (ph <= divH) return

                val dy = e.rawY - startY
                val tw = startPlotWeight + startOutputWeight
                // 未限幅的新权重
                val np = startPlotWeight + dy * tw / ph
                val no = tw - np

                // ── 最小尺寸限幅（像素精确） ──
                val minPlotPx = dpToPx(MIN_PLOT_HEIGHT_DP)
                val minOutPx = dpToPx(MIN_OUTPUT_HEIGHT_DP)
                val minP = pixelToMinWeight(minPlotPx, tw, ph, divH)
                val minO = pixelToMinWeight(minOutPx, tw, ph, divH)
                if (np < minP || no < minO) return

                panelConfig.plotWeight = np
                panelConfig.outputWeight = no
                (plotContainer.layoutParams as LinearLayout.LayoutParams).weight = np
                (outputContainer.layoutParams as LinearLayout.LayoutParams).weight = no

                scheduleLayoutRefresh(leftPanel)
            }

            MotionEvent.ACTION_UP -> {
                if (isDraggingH) {
                    isDraggingH = false
                    hDividerView.setBackgroundResource(R.drawable.bg_hdivider)
                    leftPanel.requestLayout()
                    ConfigManager.saveConfig(panelConfig)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                isDraggingH = false
                hDividerView.setBackgroundResource(R.drawable.bg_hdivider)
            }
        }
    }

    private inner class ClockIconView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#9E9E9E")
            strokeWidth = dpToPx(2).toFloat()
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val paintActive = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#4FC3F7")
            strokeWidth = dpToPx(2).toFloat()
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        var isActive = false
            set(value) { field = value; invalidate() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) * 0.45f
            val p = if (isActive) paintActive else paint
            canvas.drawLine(cx, cy, cx, cy - radius, p)
            val hourAngle = Math.toRadians(60.0)
            val hx = cx + (radius * kotlin.math.cos(hourAngle)).toFloat()
            val hy = cy + (radius * kotlin.math.sin(hourAngle)).toFloat()
            canvas.drawLine(cx, cy, hx, hy, p)
        }
    }

    private fun setupOutputView() {
        val isWide = panelConfig.menuBarWidthDp >= 56
        val toolbarH = dpToPx(if (isWide) TOOLBAR_HEIGHT_WIDE else TOOLBAR_HEIGHT_NARROW)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.setBackgroundColor(android.graphics.Color.parseColor("#2A2A2A"))
        toolbar.setPadding(dpToPx(8), 0, dpToPx(8), 0)
        toolbar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toolbarH)
        toolbar.gravity = android.view.Gravity.CENTER_VERTICAL
        fun makeSep(): android.widget.TextView {
            val sep = android.widget.TextView(this)
            sep.text = "|"
            sep.textSize = if (isWide) 17f else 14f
            sep.setTextColor(android.graphics.Color.parseColor("#424242"))
            sep.setPadding(dpToPx(4), 0, dpToPx(4), 0)
            return sep
        }
        fun makeBtn(text: String, fixedWidth: Boolean = true, highlightOnPress: Boolean = false): android.view.View {
            val defaultColor = android.graphics.Color.parseColor("#9E9E9E")
            val highlightColor = android.graphics.Color.parseColor("#4FC3F7")
            val bgHighlight = android.graphics.Color.parseColor("#194FC3F7")
            val tv = android.widget.TextView(this)
            tv.text = text; tv.textSize = if (isWide) 17f else 14f
            tv.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD_ITALIC)
            tv.setPadding(dpToPx(3), 0, dpToPx(3), 0)
            tv.setTextColor(defaultColor)
            tv.gravity = android.view.Gravity.CENTER
            tv.minimumHeight = dpToPx(if (isWide) 40 else 28)
            if (fixedWidth) { tv.minimumWidth = dpToPx(if (isWide) 44 else 30) }
            tv.isClickable = true
            tv.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { if (highlightOnPress) { tv.setTextColor(highlightColor); tv.setBackgroundColor(bgHighlight) }; v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start(); true }
                    MotionEvent.ACTION_MOVE -> { val ib = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat(); if (!ib && v.scaleX > 1.0f) { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).withEndAction { if (highlightOnPress) { tv.setTextColor(defaultColor); tv.setBackgroundColor(android.graphics.Color.TRANSPARENT) } }.start() } else if (ib && v.scaleX <= 1.0f) { if (highlightOnPress) { tv.setTextColor(highlightColor); tv.setBackgroundColor(bgHighlight) }; v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start() }; true }
                    MotionEvent.ACTION_UP -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).withEndAction { if (highlightOnPress) { tv.setTextColor(defaultColor); tv.setBackgroundColor(android.graphics.Color.TRANSPARENT) } }.start(); v.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).withEndAction { if (highlightOnPress) { tv.setTextColor(defaultColor); tv.setBackgroundColor(android.graphics.Color.TRANSPARENT) } }.start(); true }
                    else -> true
                }
            }
            return tv
        }
        btnAbcHex = makeBtn(if (outputShowHex) "Hex" else "Abc", true, true).apply {
            setOnClickListener {
                // JustFloat 强制 Hex 模式时禁止切换
                if (justFloatForceHex) return@setOnClickListener
                outputShowHex = !outputShowHex
                (btnAbcHex as android.widget.TextView).text = if (outputShowHex) "Hex" else "Abc"
                refreshOutputDisplay()
            }
        }
        toolbar.addView(btnAbcHex); toolbar.addView(makeSep())
        val clockSz = dpToPx(if (isWide) 40 else 28)
        val clockWrap = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(clockSz, clockSz) }
        btnTimestamp = clockWrap
        val clockView = ClockIconView(this).apply { layoutParams = FrameLayout.LayoutParams(clockSz, clockSz); isActive = outputShowTimestamp }
        clockWrap.setOnClickListener { outputShowTimestamp = !outputShowTimestamp; clockView.isActive = outputShowTimestamp }
        clockWrap.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start(); true }
                MotionEvent.ACTION_MOVE -> { val ib = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat(); if (!ib && v.scaleX > 1.0f) { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start() } else if (ib && v.scaleX <= 1.0f) { v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start() }; true }
                MotionEvent.ACTION_UP -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); v.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); true }
                else -> true
            }
        }
        clockWrap.addView(clockView); toolbar.addView(clockWrap)
        btnRx = makeBtn("Rx", true).apply { (this as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputRxHighlight) "#4FC3F7" else "#9E9E9E")); setOnClickListener { outputRxHighlight = !outputRxHighlight; (btnRx as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputRxHighlight) "#4FC3F7" else "#9E9E9E")); btnRxDropdown.invalidate(); btnRx.invalidate() } }
        toolbar.addView(btnRx)

        // ── Rx 下拉箭头（单个向下箭头 ∨）──
        btnRxDropdown = object : android.view.View(this) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#9E9E9E")
                strokeWidth = dpToPx(2).toFloat()
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            private val paintFiltered = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#FFA726")
                strokeWidth = dpToPx(2).toFloat()
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val size = minOf(w, h) * 0.30f
                // ★ 高亮条件：Rx 开启 且 至少一个子选项开启
                val p = if (outputRxHighlight && (rxShowSampleData || rxShowImagePacket)) paintFiltered else paint
                // 单个向下箭头 ∨
                canvas.drawLine(cx - size * 0.5f, cy - size * 0.2f, cx, cy + size * 0.4f, p)
                canvas.drawLine(cx, cy + size * 0.4f, cx + size * 0.5f, cy - size * 0.2f, p)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(if (isWide) 20 else 16), dpToPx(if (isWide) 40 else 28))
            isClickable = true
            setOnClickListener { showRxFilterPopup() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { v.animate().scaleX(1.20f).scaleY(1.20f).setDuration(50).start(); true }
                    MotionEvent.ACTION_MOVE -> { val ib = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat(); if (!ib && v.scaleX > 1.0f) v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); true }
                    MotionEvent.ACTION_UP -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); v.performClick(); true }
                    MotionEvent.ACTION_CANCEL -> { v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start(); true }
                    else -> true
                }
            }
        }
        toolbar.addView(btnRxDropdown)
        btnTx = makeBtn("Tx", true).apply { (this as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputTxHighlight) "#4FC3F7" else "#9E9E9E")); setOnClickListener { outputTxHighlight = !outputTxHighlight; (btnTx as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputTxHighlight) "#4FC3F7" else "#9E9E9E")); btnTx.invalidate() } }
        toolbar.addView(btnTx); toolbar.addView(makeSep())
        btnFontInc = makeBtn("A+", true, true).apply { setOnClickListener { outputFontSize = (outputFontSize + 1f).coerceAtMost(24f); outputText.textSize = outputFontSize } }
        toolbar.addView(btnFontInc)
        btnFontDec = makeBtn("A-", true, true).apply { setOnClickListener { outputFontSize = (outputFontSize - 1f).coerceAtLeast(8f); outputText.textSize = outputFontSize } }
        toolbar.addView(btnFontDec); toolbar.addView(makeSep())
        btnEncoding = makeBtn(if (outputUseGbk) "GBK" else "UTF-8", false, true).apply { setOnClickListener { outputUseGbk = !outputUseGbk; (btnEncoding as android.widget.TextView).text = if (outputUseGbk) "GBK" else "UTF-8" } }
        toolbar.addView(btnEncoding)
        toolbar.addView(makeSep())
        btnClear = makeBtn("E", true, true).apply { setOnClickListener { synchronized(outputDataEntries) { outputDataEntries.clear() }; displayBuffer.clear(); outputText.setText(displayBuffer, android.widget.TextView.BufferType.SPANNABLE); lastDisplayedEntryCount = 0; autoScrollEnabled = true; scrollToBottomBtn.visibility = View.GONE; outputScrollView.invalidate() } }
        toolbar.addView(btnClear)
        root.addView(toolbar)
        // 用 FrameLayout 包裹 ScrollView 以便叠加跳底按钮
        val scrollViewWrapper = FrameLayout(this)
        scrollViewWrapper.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

        val scrollView = OutputScrollView(this)
        scrollView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        scrollView.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        // 保存引用以便在输出刷新时使用 invalidate()
        outputScrollView = scrollView

        outputText = android.widget.TextView(this)
        outputText.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        outputText.textSize = outputFontSize
        outputText.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
        outputText.setLineSpacing(2f, 1f)
        outputText.typeface = ResourcesCompat.getFont(this, R.font.maple_mono)
        outputText.setText(displayBuffer, android.widget.TextView.BufferType.SPANNABLE)
        scrollView.addView(outputText)

        // 跳底按钮（仅用户手动滚动后显示，两根线组成的向下箭头）
        scrollToBottomBtn = object : android.view.View(this) {
            private val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#E0E0E0")
                strokeWidth = dpToPx(3).toFloat()
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                val cx = w / 2f
                val cy = h / 2f
                val size = minOf(w, h) * 0.35f
                // 向下的 V 形箭头
                canvas.drawLine(cx - size * 0.5f, cy - size * 0.2f, cx, cy + size * 0.4f, arrowPaint)
                canvas.drawLine(cx, cy + size * 0.4f, cx + size * 0.5f, cy - size * 0.2f, arrowPaint)
            }
        }.apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(dpToPx(40), dpToPx(40))
                setColor(android.graphics.Color.parseColor("#CC333333"))
            })
            layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                marginEnd = dpToPx(12)
                bottomMargin = dpToPx(12)
            }
            visibility = View.GONE
            setOnClickListener {
                scrollToLatestAndResume()
            }
        }
        scrollViewWrapper.addView(scrollView)
        scrollViewWrapper.addView(scrollToBottomBtn)

        // 监听用户手动滚动 → 停止自动滚动，显示跳底按钮
        // ★ 连接时 scrollLocked=true，此回调不会被触发；断开后才生效
        scrollView.onUserScrolledListener = {
            if (!isConnected) {
                autoScrollEnabled = false
                scrollToBottomBtn.visibility = View.VISIBLE
            }
        }

        root.addView(scrollViewWrapper)

        // 发送条
        val sendBarH = dpToPx(if (isWide) SENDBAR_HEIGHT_WIDE else SENDBAR_HEIGHT_NARROW)
        val sendBar = LinearLayout(this)
        sendBar.orientation = LinearLayout.HORIZONTAL
        sendBar.setBackgroundColor(android.graphics.Color.parseColor("#2A2A2A"))
        sendBar.setPadding(dpToPx(8), 0, dpToPx(8), 0)
        sendBar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, sendBarH)
        sendBar.gravity = android.view.Gravity.CENTER_VERTICAL

        sendAbcHexBtn = makeBtn(if (sendHexMode) "Hex" else "Abc", true, true).apply { setOnClickListener { sendHexMode = !sendHexMode; if (sendHexMode) { asciiToHex(panelConfig.sendBufferText) } else { hexToAscii(panelConfig.sendBufferText) }; (sendAbcHexBtn as android.widget.TextView).text = if (sendHexMode) "Hex" else "Abc"; sendEditText.setHint(if (sendHexMode) "输入Hex (0-9 A-F)..." else "输入发送数据...") } }
        sendBar.addView(sendAbcHexBtn)
        sendBar.addView(makeSep())

        sendEditText = android.widget.EditText(this)
        sendEditText.layoutParams = LinearLayout.LayoutParams(0, dpToPx(if (isWide) 34 else 24), 1f)
        sendEditText.textSize = if (isWide) 14f else 12f
        sendEditText.typeface = ResourcesCompat.getFont(this, R.font.maple_mono)
        sendEditText.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
        sendEditText.setHintTextColor(android.graphics.Color.parseColor("#616161"))
        sendEditText.setHint(if (sendHexMode) "输入Hex (0-9 A-F)..." else "输入发送数据...")
        sendEditText.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        sendEditText.setPadding(dpToPx(8), 0, dpToPx(8), 0)
        sendEditText.gravity = android.view.Gravity.CENTER_VERTICAL
        sendEditText.maxLines = 1
        // 恢复上次的发送缓冲区内容
        // 根据当前模式将 hex 核心缓冲区转成正确的显示格式
        if (sendHexMode) {
            val hex = panelConfig.sendBufferText
            val formatted = hex.chunked(2).joinToString(" ")
            sendEditText.setText(formatted)
            sendEditText.setSelection(formatted.length)
        } else {
            hexToAscii(panelConfig.sendBufferText)
        }
        // 实时保存缓冲区内容 + 输入过滤
        sendEditText.addTextChangedListener(object : android.text.TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdating || s == null || textWatcherSuspended) return
                if (sendHexMode) {
                    // Hex 模式：只保留 0-9 a-f A-F，自动格式化每两位加空格
                    val pure = s.toString().filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
                    val formatted = pure.chunked(2).joinToString(" ")
                    if (formatted != s.toString()) {
                        isUpdating = true
                        val cursor = sendEditText.selectionStart
                        val textBefore = s.substring(0, cursor.coerceIn(0, s.length))
                        val hexBefore = textBefore.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
                        s.replace(0, s.length, formatted)
                        val newCursor = if (hexBefore.isEmpty()) 0 else {
                            val pairs = (hexBefore.length + 1) / 2
                            (pairs * 3 - 1).coerceAtMost(formatted.length)
                        }
                        sendEditText.setSelection(newCursor)
                        isUpdating = false
                    }
                    panelConfig.sendBufferText = pure
                } else {
                    // Abc 模式：解析转义序列后转换为 hex 核心格式
                    val rawText = s.toString()
                    val bytes = parseEscapeText(rawText)
                    val hexStr = bytes.joinToString("") { String.format("%02X", it) }
                    panelConfig.sendBufferText = hexStr
                }
                ConfigManager.saveConfig(panelConfig)
            }
        })
        sendBar.addView(sendEditText)
        sendBar.addView(makeSep())

        val sendClearBtn = makeBtn("E", true, true).apply { setOnClickListener { sendEditText.setText("") } }
        sendBar.addView(sendClearBtn)
        sendBar.addView(makeSep())

        // ── 发送行尾追加选项 ──
        val lineEndingOptions = listOf("无追加", "\\n", "\\r", "\\n\\r", "\\r\\n")
        lineEndingSpinner = Spinner(this)
        val lineEndingAdapter = object : ArrayAdapter<String>(this, R.layout.spinner_item_protocol, lineEndingOptions) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                view.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                view.gravity = android.view.Gravity.CENTER
                view.setPadding(dpToPx(2), 0, dpToPx(2), 0)
                return view
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                view.setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                view.setPadding(20, 8, 20, 8)
                return view
            }
        }
        lineEndingAdapter.setDropDownViewResource(R.layout.spinner_item_protocol)
        lineEndingSpinner.adapter = lineEndingAdapter
        lineEndingSpinner.setSelection(panelConfig.sendLineEndingSelection.coerceIn(0, lineEndingOptions.size - 1))
        lineEndingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                panelConfig.sendLineEndingSelection = position
                ConfigManager.saveConfig(panelConfig)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        lineEndingSpinner.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
        lineEndingSpinner.setPadding(dpToPx(6), 0, dpToPx(6), 0)
        val spinnerLp = LinearLayout.LayoutParams(dpToPx(if (isWide) 80 else 52), dpToPx(if (isWide) 34 else 24))
        spinnerLp.gravity = android.view.Gravity.CENTER_VERTICAL
        lineEndingSpinner.layoutParams = spinnerLp
        sendBar.addView(lineEndingSpinner)
        sendBar.addView(makeSep())

        val sendBtn = makeBtn("发送", true, true).apply { setOnClickListener { sendData() } }
        sendBar.addView(sendBtn)

        root.addView(sendBar)
        outputContainer.addView(root)
    }

    /** Abc → Hex：将 hex 核心缓冲区格式化为带空格的 Hex 显示 */
    private fun asciiToHex(hexStr: String) {
        val formatted = hexStr.chunked(2).joinToString(" ")
        textWatcherSuspended = true
        sendEditText.setText(formatted)
        sendEditText.setSelection(formatted.length)
        textWatcherSuspended = false
    }

    /** 解析文本中的转义序列（如 \r \n \t \\ \0），返回实际字节 */
    private fun parseEscapeText(text: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n'  -> { bytes.add(0x0A); i += 2 }
                    'r'  -> { bytes.add(0x0D); i += 2 }
                    't'  -> { bytes.add(0x09); i += 2 }
                    '0'  -> { bytes.add(0x00); i += 2 }
                    '\\' -> { bytes.add(0x5C); i += 2 }
                    '\'' -> { bytes.add(0x27); i += 2 }
                    '"'  -> { bytes.add(0x22); i += 2 }
                    else -> { bytes.add(c.code.toByte()); i++ }
                }
            } else {
                bytes.add(c.code.toByte())
                i++
            }
        }
        return bytes.toByteArray()
    }

    /** 将字节转换为带转义序列的显示文本 */
    private fun toEscapeDisplay(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) {
            val v = b.toInt() and 0xFF
            when (v) {
                0x0A -> sb.append("\\n")
                0x0D -> sb.append("\\r")
                0x09 -> sb.append("\\t")
                0x00 -> sb.append("\\0")
                0x5C -> sb.append("\\\\")
                in 0x20..0x7E -> sb.append(v.toChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString()
    }

    /** Hex → Abc：将 Hex 解析为字节，控制字符显示为转义序列 */
    private fun hexToAscii(hexStr: String) {
        try {
            val pure = hexStr.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
            if (pure.isEmpty()) return
            val adjusted = if (pure.length % 2 != 0) pure + "0" else pure
            val bytes = adjusted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val display = toEscapeDisplay(bytes)
            textWatcherSuspended = true
            sendEditText.setText(display)
            sendEditText.setSelection(display.length)
            textWatcherSuspended = false
        } catch (_: Exception) {
            // 解析失败时不做任何操作，保持原内容
        }
    }

    private fun sendData() {
        try {
            // 始终从 hex 核心缓冲区读取并转换为字节发送
            val hex = panelConfig.sendBufferText
            if (hex.isEmpty()) return
            if (hex.length % 2 != 0) { showToast("Hex格式错误"); return }
            val bytes: ByteArray = try {
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: NumberFormatException) { showToast("Hex格式错误"); return }

            // ★ 发送流程无需经过协议引擎，直接使用缓冲区 hex 显示并发送
            //    直接将 hex 原文传递给显示函数（hex→UTF-8 由显示函数内部处理）
            appendOutput(hex, true)

            // 根据行尾选项，在发送数据末尾追加行尾符
            val lineEndingBytes: ByteArray = when (panelConfig.sendLineEndingSelection) {
                1 -> byteArrayOf(0x0A)       // \n
                2 -> byteArrayOf(0x0D)       // \r
                3 -> byteArrayOf(0x0A, 0x0D) // \n\r
                4 -> byteArrayOf(0x0D, 0x0A) // \r\n
                else -> ByteArray(0)          // 0: 无追加
            }
            val sendBytes = if (lineEndingBytes.isNotEmpty()) {
                bytes + lineEndingBytes
            } else {
                bytes
            }
            // 统一通过 DataTransceiver 接口发送，由接口根据当前连接方式分发
            Thread {
                try {
                    if (currentTransceiver.isConnected()) {
                        currentTransceiver.send(sendBytes)
                    }
                } catch (_: Exception) { }
            }.start()
        } catch (_: Exception) { }
    }

    private fun appendOutput(data: String, isTx: Boolean, escapeControlChars: Boolean = false, outputType: OutputType = OutputType.TEXT, isSamplingData: Boolean = false) {
        // 所有传入数据均为连续 hex 字符串，无需再做 \r\n 标准化
        if (data.isEmpty()) return
        // ★ 断开连接后不再处理数据（安全冗余，transceiver 端已清除 listener）
        if (!isConnected) return
        // ★ IMAGE_PACKET 在 setupDataEngineForTransceiver 的 lambda 中已提前处理，
        //    此处不应再收到 IMAGE_PACKET 类型。安全兜底：静默丢弃。
        if (outputType == OutputType.IMAGE_PACKET) return
        val ts = if (outputShowTimestamp) System.currentTimeMillis() else null

        // ── 输出显示缓冲（统一单缓冲区）──
        synchronized(outputDataEntries) {
            outputDataEntries.add(OutputEntry(data, ts, isTx, true, escapeControlChars, outputType, isSamplingData))
            // 超过上限时移除最旧条目，同步调整已显示计数
            while (outputDataEntries.size > MAX_OUTPUT_ENTRIES) {
                outputDataEntries.removeAt(0)
                if (lastDisplayedEntryCount > 0) lastDisplayedEntryCount--
            }
        }

        // ★ Rx/Tx 均关闭时不调度刷新，避免主线程无谓拥塞
        val shouldShow = (isTx && outputTxHighlight) || (!isTx && outputRxHighlight)
        if (shouldShow) {
            scheduleOutputFlush()
        }
    }

    /**
     * 调度批量刷新，防抖合并多次 appendOutput 调用
     */
    private fun scheduleOutputFlush() {
        if (!outputFlushPending) {
            outputFlushPending = true
            outputHandler.postDelayed(outputFlushRunnable, OUTPUT_FLUSH_INTERVAL_MS)
        }
    }

    /**
     * 可拖动/可缩放的图像子窗口。
     * 设计：
     *   - 外层 FrameLayout 作为容器，带颜色背景（即边框）
     *   - 内部 GLImageView 设 margin = BORDER_DP，居于边框内部
     *   - 触摸边框四角 → 等比例缩放
     *   - 触摸边框非角区域 → 移动窗口
     *   - 触摸内部图像区域 → 无操作
     *   - 受 rxShowImageWindow（控制页"图像"复选框）控制
     */
    private fun checkAndDisplayImage() {
        val frame = imageBuffer.consumeNewFrame() ?: return
        if (!frame.isGrayscale8) {
            AppLogger.i("MainActivity", "非 Grayscale8 图像帧（format=${frame.format}），跳过渲染")
            return
        }
        if (!frame.isValid) {
            AppLogger.w("MainActivity", "无效图像帧: ${frame.dataSize}B, ${frame.width}x${frame.height}")
            return
        }
        // ★ 图像窗口关闭时不渲染
        if (!rxShowImageWindow) return
        outputHandler.post {
            try {
                val density = resources.displayMetrics.density
                val TOUCH_EDGE_DP = 8 // 触摸检测宽度 dp（图像边缘向内 4dp + 容器 Padding 向外 4dp）
                val touchEdgePx = 0 // GLImageView 无内缩，触摸由容器 padding + 内部检测实现
                val imageAspect = frame.width.toFloat() / frame.height.toFloat()

                // 尺寸上限 = 绘图容器宽度（屏幕宽度）
                val maxWinPx = plotContainer.width.coerceAtLeast(100)
                // 还原时初始尺寸 = 480dp，上限为屏幕宽度
                val initDefaultPx = (480 * density).toInt().coerceAtMost(maxWinPx)
                var winW: Int; var winH: Int
                if (frame.width > frame.height) {
                    winW = initDefaultPx
                    winH = (winW / imageAspect).toInt()
                } else {
                    winH = initDefaultPx
                    winW = (winH * imageAspect).toInt()
                }

                // ── 首次创建 ──
                if (imageWindowContainer == null) {
                    // 读取保存的配置（冷保存）
                    val savedW = panelConfig.imageWindowWidth
                    val savedH = panelConfig.imageWindowHeight
                    val savedX = panelConfig.imageWindowPosX
                    val savedY = panelConfig.imageWindowPosY
                    val restoreSaved = savedW > 0 && savedH > 0

                    val container = FrameLayout(this@MainActivity)
                    // 容器尺寸 = 图像尺寸 + 8dp (4dp padding 每侧向外延伸)
                    val containerPadding = (4 * density).toInt()
                    container.layoutParams = FrameLayout.LayoutParams(
                        if (restoreSaved) savedW else winW + containerPadding * 2,
                        if (restoreSaved) savedH else winH + containerPadding * 2
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = if (restoreSaved) savedX else dpToPx(16)
                        topMargin = if (restoreSaved) savedY else dpToPx(16)
                    }
                    // 无边框背景，完全透明
                    container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    container.setPadding(containerPadding, containerPadding, containerPadding, containerPadding)
                    container.isClickable = true
                    container.isFocusable = true

                    // GLImageView（无内缩 margin，填满容器 padding 后的区域 = 图像尺寸）
                    val glv = GLImageView(this@MainActivity)
                    glv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    glv.isClickable = false
                    glv.isFocusable = false
                    container.addView(glv)
                    glImageView = glv

                    // ── 触摸交互：缩放 / 移动 ──
                    // 触摸区域枚举
                    var touchMode = 0 // 0=无, 1=移动, 2=缩放
                    var touchCorner = 0 // 缩放角: 0=TL,1=TR,2=BL,3=BR
                    var startRawX = 0f; var startRawY = 0f
                    var startL = 0; var startT = 0; var startW = 0; var startH = 0

                    container.setOnTouchListener { v, event ->
                        val lp = v.layoutParams as ViewGroup.MarginLayoutParams
                        val vw = v.width; val vh = v.height
                        val edgePx = (TOUCH_EDGE_DP * density).toInt()
                        val cornerPx = (TOUCH_EDGE_DP * 1.6f * density).toInt() // 角区域略大

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                val ex = event.x; val ey = event.y
                                // 判断是否在四角区域
                                val atTL = ex < cornerPx && ey < cornerPx
                                val atTR = ex > vw - cornerPx && ey < cornerPx
                                val atBL = ex < cornerPx && ey > vh - cornerPx
                                val atBR = ex > vw - cornerPx && ey > vh - cornerPx

                                if (atTL || atTR || atBL || atBR) {
                                    touchMode = 2
                                    touchCorner = if (atTL) 0 else if (atTR) 1 else if (atBL) 2 else 3
                                } else if (ex < edgePx || ex > vw - edgePx || ey < edgePx || ey > vh - edgePx) {
                                    touchMode = 1 // 在边框非角区域 → 移动
                                } else {
                                    touchMode = 0 // 图像内部 → 不处理
                                    return@setOnTouchListener false
                                }
                                startRawX = event.rawX; startRawY = event.rawY
                                startL = lp.leftMargin; startT = lp.topMargin
                                startW = vw; startH = vh
                                v.bringToFront(); v.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (touchMode == 0) return@setOnTouchListener false
                                val deltaX = (event.rawX - startRawX).toInt()
                                val deltaY = (event.rawY - startRawY).toInt()

                                if (touchMode == 1) {
                                    // ── 移动 ──
                                    val parentW = plotContainer.width
                                    val parentH = plotContainer.height
                                    lp.leftMargin = (startL + deltaX).coerceIn(-vw / 2, parentW - vw / 4)
                                    lp.topMargin = (startT + deltaY).coerceIn(-vh / 4, parentH - vh / 4)
                                    v.layoutParams = lp
                                } else {
                                    // ── 缩放（保持宽高比）──
                                    var newW = startW; var newH = startH
                                    when (touchCorner) {
                                        0 -> { // 左上角
                                            newW = (startW - deltaX).coerceAtLeast(50)
                                            newH = (newW / imageAspect).toInt().coerceAtLeast(50)
                                            newW = (newH * imageAspect).toInt()
                                            lp.leftMargin = startL + startW - newW
                                            lp.topMargin = startT + startH - newH
                                        }
                                        1 -> { // 右上角
                                            newW = (startW + deltaX).coerceAtLeast(50)
                                            newH = (newW / imageAspect).toInt().coerceAtLeast(50)
                                            newW = (newH * imageAspect).toInt()
                                            lp.leftMargin = startL
                                            lp.topMargin = startT + startH - newH
                                        }
                                        2 -> { // 左下角
                                            newW = (startW - deltaX).coerceAtLeast(50)
                                            newH = (newW / imageAspect).toInt().coerceAtLeast(50)
                                            newW = (newH * imageAspect).toInt()
                                            lp.leftMargin = startL + startW - newW
                                            lp.topMargin = startT
                                        }
                                        3 -> { // 右下角
                                            newW = (startW + deltaX).coerceAtLeast(50)
                                            newH = (newW / imageAspect).toInt().coerceAtLeast(50)
                                            newW = (newH * imageAspect).toInt()
                                            lp.leftMargin = startL
                                            lp.topMargin = startT
                                        }
                                    }
                                    lp.width = newW; lp.height = newH
                                    v.layoutParams = lp
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                touchMode = 0
                                // ★ 保存当前位置和尺寸到配置
                                val mlp = v.layoutParams as ViewGroup.MarginLayoutParams
                                panelConfig.imageWindowPosX = mlp.leftMargin
                                panelConfig.imageWindowPosY = mlp.topMargin
                                panelConfig.imageWindowWidth = mlp.width
                                panelConfig.imageWindowHeight = mlp.height
                                ConfigManager.saveConfig(panelConfig)
                                true
                            }
                            else -> false
                        }
                    }
                    imageWindowContainer = container
                    plotContainer.addView(container)
                }

                // ── 每次更新：更新 GL 纹理 ──
                imageWindowContainer!!.bringToFront()
                plotTitle.visibility = View.GONE
                glImageView?.setImageFrame(frame)
                // 更新控件页图像信息
                updateImageInfo()
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "图像渲染异常", e)
            }
        }
    }

    /** 从 ImageBuffer 缓存中恢复显示上一帧图像（适用于开关切换后重新显示）。 */
    private fun restoreLastImage() {
        val bytes = imageBuffer.rawBytes
        val w = imageBuffer.imageWidth
        val h = imageBuffer.imageHeight
        val fmt = imageBuffer.imageFormat
        if (bytes == null || bytes.isEmpty() || w <= 0 || h <= 0) return

        val frame = ImageFrame(bytes, w, h, fmt)
        if (!frame.isValid || !frame.isGrayscale8) return

        outputHandler.post {
            try {
                val density = resources.displayMetrics.density
                val maxWinPx = plotContainer.width.coerceAtLeast(100)
                val initDefaultPx = (480 * density).toInt().coerceAtMost(maxWinPx)
                val imageAspect = w.toFloat() / h.toFloat()
                var winW = initDefaultPx
                var winH = (winW / imageAspect).toInt()
                if (winH > maxWinPx) { winH = maxWinPx; winW = (winH * imageAspect).toInt() }

                if (imageWindowContainer == null) {
                    val savedW = panelConfig.imageWindowWidth
                    val savedH = panelConfig.imageWindowHeight
                    val savedX = panelConfig.imageWindowPosX
                    val savedY = panelConfig.imageWindowPosY
                    val restoreSaved = savedW > 0 && savedH > 0

                    val containerPadding = (4 * density).toInt()
                    val container = FrameLayout(this@MainActivity)
                    container.layoutParams = FrameLayout.LayoutParams(
                        if (restoreSaved) savedW else winW + containerPadding * 2,
                        if (restoreSaved) savedH else winH + containerPadding * 2
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        leftMargin = if (restoreSaved) savedX else dpToPx(16)
                        topMargin = if (restoreSaved) savedY else dpToPx(16)
                    }
                    container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    container.setPadding(containerPadding, containerPadding, containerPadding, containerPadding)
                    container.isClickable = true
                    container.isFocusable = true

                    val glv = GLImageView(this@MainActivity)
                    glv.layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    glv.isClickable = false
                    glv.isFocusable = false
                    container.addView(glv)
                    glImageView = glv

                    // ── 触摸交互 ──
                    var touchMode = 0; var touchCorner = 0
                    var startRawX = 0f; var startRawY = 0f
                    var startL = 0; var startT = 0; var startW = 0; var startH = 0
                    container.setOnTouchListener { v, event ->
                        val mlp = v.layoutParams as ViewGroup.MarginLayoutParams
                        val vv = v.width; val vh = v.height
                        val edgePx = (8 * density).toInt()
                        val cornerPx = (8 * 1.6f * density).toInt()
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                val ex = event.x; val ey = event.y
                                val atTL = ex < cornerPx && ey < cornerPx
                                val atTR = ex > vv - cornerPx && ey < cornerPx
                                val atBL = ex < cornerPx && ey > vh - cornerPx
                                val atBR = ex > vv - cornerPx && ey > vh - cornerPx
                                if (atTL || atTR || atBL || atBR) {
                                    touchMode = 2; touchCorner = if (atTL) 0 else if (atTR) 1 else if (atBL) 2 else 3
                                } else if (ex < edgePx || ex > vv - edgePx || ey < edgePx || ey > vh - edgePx) {
                                    touchMode = 1
                                } else { return@setOnTouchListener false }
                                startRawX = event.rawX; startRawY = event.rawY
                                startL = mlp.leftMargin; startT = mlp.topMargin
                                startW = vv; startH = vh; v.bringToFront(); v.invalidate(); true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (touchMode == 0) return@setOnTouchListener false
                                val dx = (event.rawX - startRawX).toInt(); val dy = (event.rawY - startRawY).toInt()
                                if (touchMode == 1) {
                                    val pw = plotContainer.width; val ph = plotContainer.height
                                    mlp.leftMargin = (startL + dx).coerceIn(-vv / 2, pw - vv / 4)
                                    mlp.topMargin = (startT + dy).coerceIn(-vh / 4, ph - vh / 4)
                                    v.layoutParams = mlp
                                } else {
                                    var nw = startW; var nh = startH
                                    when (touchCorner) {
                                        0 -> { nw = (startW - dx).coerceAtLeast(50); nh = (nw / imageAspect).toInt().coerceAtLeast(50); nw = (nh * imageAspect).toInt(); mlp.leftMargin = startL + startW - nw; mlp.topMargin = startT + startH - nh }
                                        1 -> { nw = (startW + dx).coerceAtLeast(50); nh = (nw / imageAspect).toInt().coerceAtLeast(50); nw = (nh * imageAspect).toInt(); mlp.leftMargin = startL; mlp.topMargin = startT + startH - nh }
                                        2 -> { nw = (startW - dx).coerceAtLeast(50); nh = (nw / imageAspect).toInt().coerceAtLeast(50); nw = (nh * imageAspect).toInt(); mlp.leftMargin = startL + startW - nw; mlp.topMargin = startT }
                                        3 -> { nw = (startW + dx).coerceAtLeast(50); nh = (nw / imageAspect).toInt().coerceAtLeast(50); nw = (nh * imageAspect).toInt(); mlp.leftMargin = startL; mlp.topMargin = startT }
                                    }
                                    mlp.width = nw; mlp.height = nh; v.layoutParams = mlp
                                }
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                touchMode = 0
                                val mlp2 = v.layoutParams as ViewGroup.MarginLayoutParams
                                panelConfig.imageWindowPosX = mlp2.leftMargin
                                panelConfig.imageWindowPosY = mlp2.topMargin
                                panelConfig.imageWindowWidth = mlp2.width
                                panelConfig.imageWindowHeight = mlp2.height
                                ConfigManager.saveConfig(panelConfig); true
                            }
                            else -> false
                        }
                    }
                    imageWindowContainer = container
                    plotContainer.addView(container)
                }
                imageWindowContainer!!.bringToFront()
                plotTitle.visibility = View.GONE
                glImageView?.setImageFrame(frame)
                updateImageInfo()
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "恢复图像显示异常", e)
            }
        }
    }

    /** 隐藏图像显示，移除子窗口，恢复绘图区标题 */
    private fun hideImageDisplay() {
        imageWindowContainer?.let { container ->
            plotContainer.removeView(container)
        }
        imageWindowContainer = null
        glImageView = null
        if (::plotTitle.isInitialized) {
            plotTitle.visibility = View.VISIBLE
        }
    }

    /**
     * 将显示缓冲区裁剪到字符上限以内。
     * 从开头删除最旧内容，在换行边界处切割以避免截断行。
     */
    private fun trimDisplayBuffer() {
        if (displayBuffer.length <= MAX_DISPLAY_CHARS) return
        // 多裁剪 10% 以避免频繁触发裁剪
        val target = displayBuffer.length - MAX_DISPLAY_CHARS + MAX_DISPLAY_CHARS / 10
        var cut = target.coerceIn(0, displayBuffer.length)
        while (cut < displayBuffer.length && displayBuffer[cut] != '\n') cut++
        if (cut < displayBuffer.length) cut++ // 跳过换行符
        displayBuffer.delete(0, cut)
    }

    /**
     * 构建单条条目的显示文本（不包含前置换行）
     * @return 构建好的 SpannableStringBuilder，若条目被过滤则返回 null
     */
    private fun buildEntryDisplay(entry: OutputEntry, rxColor: Int, txColor: Int, timestampColor: Int): SpannableStringBuilder? {
        if (!entry.visible) return null
        if ((entry.isTx && !outputTxHighlight) || (!entry.isTx && !outputRxHighlight)) return null
        if (!entry.isTx && entry.type == OutputType.IMAGE_PACKET && !rxShowImagePacket) return null
        if (!entry.isTx && entry.isSamplingData && !rxShowSampleData) return null

        val contentStr: String
        if (outputShowHex) {
            contentStr = entry.text.chunked(2).joinToString(" ")
        } else {
            val decoded = entry.text.hexToString(outputUseGbk).escapeDisplay()
            contentStr = decoded
        }
        if (contentStr.isEmpty()) return null

        val sb = SpannableStringBuilder()
        val lineStart = sb.length
        if (entry.timestampMs != null) {
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))
            sb.append("[$ts] ")
            sb.setSpan(ForegroundColorSpan(timestampColor), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val contentStart = sb.length
        sb.append(contentStr)
        sb.setSpan(
            ForegroundColorSpan(if (entry.isTx) txColor else rxColor),
            contentStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    /**
     * 增量刷新输出 — 处理新条目递增追加到单显示缓冲区。
     *
     * ★ 核心优化：使用 outputText.append() 做增量布局，而非每次 setText()。
     *    append() 仅触发新增内容的 measure/layout，不重建整棵 View 树。
     *    仅在缓冲区超限裁剪时才做一次 setText()（罕见操作）。
     *
     * 连接时 batchSize=500 加速出流；断开时 batchSize=200 降低历史回看负载。
     */
    private fun rebuildOutputText() {
        val rxColor = android.graphics.Color.parseColor("#4FC3F7")
        val txColor = android.graphics.Color.parseColor("#FFB74D")
        val timestampColor = android.graphics.Color.parseColor("#9E9E9E")

        // ★ 连接时更快出流，断开时保守以降低 CPU 占用
        val batchSize = if (isConnected) 500 else 200
        val chunk = SpannableStringBuilder()
        val processedCount: Int
        val hasMore: Boolean
        synchronized(outputDataEntries) {
            val pendingCount = outputDataEntries.size - lastDisplayedEntryCount
            if (pendingCount <= 0) return

            val batchEnd = minOf(lastDisplayedEntryCount + batchSize, outputDataEntries.size)

            for (i in lastDisplayedEntryCount until batchEnd) {
                val entry = outputDataEntries[i]
                val display = buildEntryDisplay(entry, rxColor, txColor, timestampColor) ?: continue
                if (chunk.isNotEmpty()) chunk.append('\n')
                chunk.append(display)
            }
            processedCount = batchEnd - lastDisplayedEntryCount
            lastDisplayedEntryCount = batchEnd
            hasMore = lastDisplayedEntryCount < outputDataEntries.size
        }

        if (chunk.isEmpty()) return

        // ★ 增量构建新条目的显示文本，追加到显示缓冲区
        if (displayBuffer.isNotEmpty()) {
            displayBuffer.append('\n')
        }
        displayBuffer.append(chunk)
        // ★ 裁剪旧行
        val needTrim = displayBuffer.length > MAX_DISPLAY_CHARS
        if (needTrim) {
            trimDisplayBuffer()
        }
        // ★ 统一 setText() 同步到 TextView（单源可靠，避免 append() 双对象不同步崩溃）
        outputText.setText(displayBuffer, android.widget.TextView.BufferType.SPANNABLE)

        outputScrollView.invalidate()

        // ★ 连接时始终自动滚动到底；断开时尊重 autoScrollEnabled
        if (isConnected || autoScrollEnabled) {
            outputScrollView.post {
                outputScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        // ★ 若还有剩余条目，立即继续处理
        if (hasMore) {
            outputHandler.post(outputFlushRunnable)
        }
    }

    /** 执行批量刷新 */
    private fun doFlushOutput() {
        outputFlushPending = false
        rebuildOutputText()
    }

    /**
     * 设置变化（Hex/Abc、时间戳、Rx/Tx 过滤）时触发重建。
     * ★ 不清空全部再重建（阻塞主线程），而是清空缓冲区后让增量刷新
     *    自然分批次重新构建（惰性加载），仅当前可见内容优先渲染。
     */
    private fun refreshOutputDisplay() {
        outputHandler.removeCallbacks(outputFlushRunnable)
        outputFlushPending = false
        displayBuffer.clear()
        lastDisplayedEntryCount = 0
        outputText.setText(displayBuffer, android.widget.TextView.BufferType.SPANNABLE)
        scheduleOutputFlush()
    }

    // ====================== Rx 过滤菜单 ======================

    /**
     * 显示 Rx 过滤下拉菜单（采样数据包 / 图像数据包）
     * 使用 PopupWindow 自绘，按下文本高亮为橙色 + 放大动画
     */
    private fun showRxFilterPopup() {
        val dp = resources.displayMetrics.density
        val itemH = dpToPx(44)
        val itemW = dpToPx(160)

        val defaultColor = android.graphics.Color.parseColor("#E0E0E0")
        val highlightColor = android.graphics.Color.parseColor("#FFA726")
        val bgNormal = android.graphics.Color.parseColor("#333333")
        val bgHighlight = android.graphics.Color.parseColor("#19FFA726")
        val sepColor = android.graphics.Color.parseColor("#555555")

        fun makeMenuItem(text: String, isChecked: Boolean, onClick: () -> Unit): android.widget.TextView {
            val tv = android.widget.TextView(this)
            tv.text = text
            tv.textSize = 16f
            // 初始状态：开启时显示橙色高亮
            tv.setTextColor(if (isChecked) highlightColor else defaultColor)
            tv.setPadding(dpToPx(16), 0, dpToPx(16), 0)
            tv.gravity = android.view.Gravity.CENTER_VERTICAL
            tv.minimumHeight = itemH
            tv.layoutParams = android.widget.LinearLayout.LayoutParams(itemW, itemH)
            tv.isClickable = true
            tv.setBackgroundColor(bgNormal)
            tv.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        tv.setTextColor(highlightColor)
                        tv.setBackgroundColor(bgHighlight)
                        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(50).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val ib = e.x in 0f..v.width.toFloat() && e.y in 0f..v.height.toFloat()
                        if (!ib && v.scaleX > 1.0f) {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).withEndAction {
                                tv.setBackgroundColor(bgNormal)
                            }.start()
                        } else if (ib && v.scaleX <= 1.0f) {
                            tv.setBackgroundColor(bgHighlight)
                            v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(50).start()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        tv.setTextColor(defaultColor)
                        tv.setBackgroundColor(bgNormal)
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
                        true
                    }
                    else -> true
                }
            }
            tv.setOnClickListener {
                onClick()
                // ★ 点击后立即切换文字颜色反映新状态（不依赖下次展开）
                val checked = if (text == "采样数据包") rxShowSampleData else rxShowImagePacket
                tv.setTextColor(if (checked) highlightColor else defaultColor)
                tv.setBackgroundColor(bgNormal)
            }
            return tv
        }

        // 容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgNormal)
        }

        // 采样数据包
        container.addView(makeMenuItem("采样数据包", rxShowSampleData) {
            rxShowSampleData = !rxShowSampleData
            btnRxDropdown.invalidate()
        })
        // 分隔线
        container.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(itemW, 1)
            setBackgroundColor(sepColor)
        })
        container.addView(makeMenuItem("图像数据包", rxShowImagePacket) {
            rxShowImagePacket = !rxShowImagePacket
            btnRxDropdown.invalidate()
        })

        container.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )

        val popup = PopupWindow(container, itemW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dpToPx(8).toFloat()
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.showAsDropDown(btnRxDropdown, 0, -dpToPx(4)) // 紧贴按钮下方
    }

    /** 跳转至最新行并恢复自动滚动（仅在断开状态下可用） */
    private fun scrollToLatestAndResume() {
        autoScrollEnabled = true
        scrollToBottomBtn.visibility = View.GONE
        outputScrollView.invalidate()
        outputScrollView.post {
            outputScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            outputScrollView.thumbVisible = false
            outputScrollView.invalidate()
        }
    }

    private fun getProtocolName(position: Int): String = when (position) { 0 -> "串口"; 1 -> "UDP"; 2 -> "TCP客户端"; 3 -> "TCP服务端"; 4 -> "Demo"; else -> "未知" }

        private fun handleConnectToggle(btn: ImageButton) {
        when (selectedProtocolPosition) {
            1 -> {
                // ── UDP ──
                if (udpTransceiver.isConnected() || udpTransceiver.isConnecting()) {
                    udpTransceiver.disconnect()
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    AppLogger.i("MainActivity", "UDP 已断开")
                    showToastShort("UDP 已断开")
                    setDataEngineSpinnerEnabled(true)
                    onConnectionStateChanged(false)
                } else {
                    val ip = protocolView?.findViewById<EditText>(R.id.udpRemoteIp)?.text?.toString()
                        ?: panelConfig.udpRemoteIp
                    val remotePortStr = protocolView?.findViewById<EditText>(R.id.udpRemotePort)?.text?.toString()
                        ?: panelConfig.udpRemotePort
                    val localPortStr = protocolView?.findViewById<EditText>(R.id.udpLocalPort)?.text?.toString()
                        ?: panelConfig.udpLocalPort
                    if (ip.isNullOrBlank()) { showToast("请输入远程IP"); return }
                    val remotePort = remotePortStr?.toIntOrNull()
                    if (remotePort == null || remotePort !in 1..65535) { showToast("请输入有效远程端口 (1-65535)"); return }
                    val localPort = localPortStr?.toIntOrNull()
                    if (localPort == null || localPort !in 1..65535) { showToast("请输入有效本地端口 (1-65535)"); return }
                    panelConfig.udpRemoteIp = ip; panelConfig.udpRemotePort = remotePortStr; panelConfig.udpLocalPort = localPortStr; ConfigManager.saveConfig(panelConfig)
                    // 配置收发器参数并通过统一接口连接
                    udpTransceiver.remoteHost = ip
                    udpTransceiver.remotePort = remotePort
                    udpTransceiver.localPort = localPort
                    if (udpTransceiver.connect()) {
                        isConnected = true
                        btn.setImageResource(R.drawable.ic_connect_on)
                        btn.alpha = 1f
                        showToastShort("UDP 已启动，本地端口: $localPort")
                        setDataEngineSpinnerEnabled(false)
                        onConnectionStateChanged(true)
                    }
                }
            }
            2 -> {
                // ── TCP 客户端 ──
                if (tcpClientTransceiver.isConnected() || isTcpConnecting) {
                    val wasConnecting = isTcpConnecting
                    tcpClientTransceiver.disconnect()
                    isTcpConnecting = false
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    AppLogger.i("MainActivity", "TCP客户端断开")
                    showToastShort(if (wasConnecting) "已取消连接" else "TCP客户端已断开")
                    onConnectionStateChanged(false)
                } else {
                    val ip = protocolView?.findViewById<EditText>(R.id.tcpClientServerIp)?.text?.toString()
                        ?: panelConfig.tcpClientServerIp
                    val portStr = protocolView?.findViewById<EditText>(R.id.tcpClientNetworkPort)?.text?.toString()
                        ?: panelConfig.tcpClientNetworkPort
                    val handshake = protocolView?.findViewById<EditText>(R.id.tcpClientHandshake)?.text?.toString()
                        ?: panelConfig.tcpClientHandshake
                    if (ip.isNullOrBlank()) { showToast("请输入服务器IP"); return }
                    val port = portStr?.toIntOrNull()
                    if (port == null || port !in 1..65535) { showToast("请输入有效端口 (1-65535)"); return }
                    val hs = handshake ?: ""
                    panelConfig.tcpClientServerIp = ip; panelConfig.tcpClientNetworkPort = portStr; panelConfig.tcpClientHandshake = hs; ConfigManager.saveConfig(panelConfig)
                    // 配置收发器参数并通过统一接口连接
                    tcpClientTransceiver.host = ip
                    tcpClientTransceiver.port = port
                    tcpClientTransceiver.handshake = hs
                    tcpClientTransceiver.timeoutMs = panelConfig.tcpClientTimeoutSeconds * 1000
                    if (tcpClientTransceiver.connect()) {
                        isTcpConnecting = true
                        btn.setImageResource(R.drawable.ic_connect_on)
                        btn.alpha = 0.5f
                        showToastShort("正在连接 $ip:$port ...")
                    }
                }
            }
            3 -> {
                // ── TCP 服务端 ──
                if (tcpServerTransceiver.isConnected()) {
                    tcpServerTransceiver.disconnect()
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    showToastShort("TCP服务端已停止")
                    setDataEngineSpinnerEnabled(true)
                    onConnectionStateChanged(false)
                } else {
                    val portStr = protocolView?.findViewById<EditText>(R.id.tcpServerListenPort)?.text?.toString()
                        ?: panelConfig.tcpServerListenPort
                    val handshakeStr = protocolView?.findViewById<EditText>(R.id.tcpServerHandshake)?.text?.toString()
                        ?: panelConfig.tcpServerHandshake
                    val port = portStr?.toIntOrNull()
                    if (port == null || port !in 1..65535) { showToast("请输入有效端口 (1-65535)"); return }
                    panelConfig.tcpServerListenPort = portStr
                    panelConfig.tcpServerHandshake = handshakeStr ?: ""
                    ConfigManager.saveConfig(panelConfig)
                    // 配置收发器参数并通过统一接口启动
                    tcpServerTransceiver.listenPort = port
                    tcpServerTransceiver.handshake = handshakeStr ?: ""
                    if (tcpServerTransceiver.connect()) {
                        isConnected = true
                        btn.setImageResource(R.drawable.ic_connect_on)
                        showToastShort("TCP服务端已启动，端口: $port")
                        setDataEngineSpinnerEnabled(false)
                        onConnectionStateChanged(true)
                    } else {
                        showToastShort("启动失败，端口可能被占用")
                    }
                }
            }
            else -> {
                // ── 串口 / Demo（暂未实现具体连接，使用 NullTransceiver） ──
                if (currentTransceiver.isConnected()) {
                    currentTransceiver.disconnect()
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    showToastShort("${currentTransceiver.getProtocolName()} 已断开")
                    setDataEngineSpinnerEnabled(true)
                    onConnectionStateChanged(false)
                } else {
                    if (currentTransceiver.connect()) {
                        isConnected = true
                        btn.setImageResource(R.drawable.ic_connect_on)
                        showToastShort("${currentTransceiver.getProtocolName()} 已连接")
                        setDataEngineSpinnerEnabled(false)
                        onConnectionStateChanged(true)
                    }
                }
            }
        }
    }

    /**
     * 连接/断开状态切换时统一处理滚动行为。
     * - connected=true：锁定滚动、强制回到底部、隐藏跳底按钮、启用自动滚动
     * - connected=false：解锁滚动、允许用户回看历史、数据停止涌入后显示跳底按钮
     */
    private fun onConnectionStateChanged(connected: Boolean) {
        outputScrollView.scrollLocked = connected
        if (connected) {
            autoScrollEnabled = true
            scrollToBottomBtn.visibility = View.GONE
            outputScrollView.post {
                outputScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                outputScrollView.thumbVisible = false
                outputScrollView.invalidate()
            }
        }
    }

    private fun disconnectCurrent() {
        if (!isConnected) return
        currentTransceiver.disconnect()
        isConnected = false; findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_off); showToastShort("已断开")
        // 断开连接时重置当前协议引擎的内部状态
        currentDataEngine.reset()
        // 断开连接 -> 解锁数据引擎 Spinner
        setDataEngineSpinnerEnabled(true)
        // 断开连接 -> 重置参数缓存区（下次连接重新锁定）
        paramBuffer.reset()
        imageBuffer.reset()
        hideImageDisplay()
        updateImageInfo()
        updateParamHeaderTitle()
        refreshParamViews()
        onConnectionStateChanged(false)
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    /** 显示短时 Toast（0.5 秒后自动消失），用于连接/断开等轻量提示 */
    private fun showToastShort(msg: String) {
        val toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        toast.show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toast.cancel() }, 500L)
    }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        finish()
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private val commandListener = NavigationView.OnNavigationItemSelectedListener { item -> AppLogger.i("MainActivity", "命令: ${item.title}"); closeMenu(); true }
    private val connectionListener = NavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.conn_connect -> { handleConnectToggle(findViewById(R.id.menuBarConnect)) }
            R.id.conn_disconnect -> { disconnectCurrent() }
        }
        closeMenu(); true
    }
}