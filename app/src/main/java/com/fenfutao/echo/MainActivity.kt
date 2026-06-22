package com.fenfutao.echo

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
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
    /** 上次已刷新到 outputText 的条目数 */
    private var lastDisplayedEntryCount = 0
    private val outputFlushRunnable = Runnable { doFlushOutput() }

    // ── 统一数据收发接口：各协议封装为 DataTransceiver ──
    private val tcpClientTransceiver = TcpClientTransceiver()
    private val tcpServerTransceiver = TcpServerTransceiver()
    private val serialTransceiver = NullTransceiver("串口")
    private val udpTransceiver = UdpTransceiver()
    private val demoTransceiver = NullTransceiver("Demo")
    private var currentTransceiver: DataTransceiver = serialTransceiver

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
        val visible: Boolean
    )

    private val outputDataEntries = mutableListOf<OutputEntry>()
    private lateinit var btnAbcHex: android.view.View
    private lateinit var btnTimestamp: android.view.View
    private lateinit var btnRx: android.view.View
    private lateinit var btnTx: android.view.View
    private lateinit var btnFontInc: android.view.View
    private lateinit var btnFontDec: android.view.View
    private lateinit var btnEncoding: android.view.View
    private lateinit var btnClear: android.view.View
    private lateinit var sendEditText: android.widget.EditText
    private lateinit var sendAbcHexBtn: android.view.View
    private lateinit var lineEndingSpinner: Spinner
    /** 标记当前 TextWatcher 的 afterTextChanged 是否由程序主动 setText 触发 */
    private var textWatcherSuspended = false

    // ── 分割线拖拽相关 ──
    private var isDraggingV = false
    private var protocolView: View? = null
    private var settingsView: View? = null
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
        // ── 分割面板最小尺寸 (dp) ──
        private const val MIN_PLOT_HEIGHT_DP = 80
        private const val MIN_OUTPUT_HEIGHT_DP = 80
        private const val MIN_LEFT_WIDTH_DP = 166 // plot(80) + divider(6) + output(80)
        private const val MIN_RIGHT_WIDTH_DP = 80
        /** 分割线拖拽刷新防抖间隔 (ms) */
        private const val LAYOUT_REFRESH_INTERVAL_MS = 16L // ~60fps
        /** 输出窗口最大缓存条目数 */
        private const val MAX_OUTPUT_ENTRIES = 10000
        /** 输出刷新批处理间隔 (ms)，约 30fps */
        private const val OUTPUT_FLUSH_INTERVAL_MS = 33L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 延时切换主题，让闪屏（Theme.ECHO.Splash）停留更久
        Handler(Looper.getMainLooper()).postDelayed({
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
        }, 1000L)
    }

    private fun initViews() {
        navA = findViewById(R.id.navViewA); navB = findViewById(R.id.navViewB)
        scrimOverlay = findViewById(R.id.scrimOverlay)
        workArea = findViewById(R.id.workArea); rootContainer = findViewById(R.id.rootContainer)
        menuBar = findViewById(R.id.menuBar)
        leftPanel = findViewById(R.id.leftPanel)
        plotContainer = findViewById(R.id.plotContainer)
        outputContainer = findViewById(R.id.outputContainer)
        paramsContainer = findViewById(R.id.paramsContainer)
        menuBarHighlight = findViewById(R.id.menuBarHighlight)
        menuBarDecoration = findViewById(R.id.menuBarDecoration)
        vDividerView = findViewById(R.id.vDivider)
        hDividerView = findViewById(R.id.hDivider)

        // 必须优先加载配置，后续 UI 初始化才能读到已保存的状态
        panelConfig = ConfigManager.loadConfig()
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
        // 为所有数据收发器设置统一的接收回调
        tcpClientTransceiver.setOnDataReceivedListener { data -> appendOutput(data, false) }
        tcpServerTransceiver.setOnDataReceivedListener { data -> appendOutput(data, false) }
        serialTransceiver.setOnDataReceivedListener { data -> appendOutput(data, false) }
        udpTransceiver.setOnDataReceivedListener { data -> appendOutput(data, false) }
        demoTransceiver.setOnDataReceivedListener { data -> appendOutput(data, false) }

        // 设置 TCP 客户端连接状态回调，超时/失败时自动恢复按钮状态
        tcpClientTransceiver.setOnStateChangedListener { connected, message ->
            val btn = findViewById<ImageButton>(R.id.menuBarConnect)
            if (connected) {
                isTcpConnecting = false
                isConnected = true
                btn.setImageResource(R.drawable.ic_connect_on)
                btn.alpha = 1f
            } else {
                if (isConnected || isTcpConnecting) {
                    isTcpConnecting = false
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    showToast(message)
                }
            }
        }
        // 设置 TCP 服务端连接状态回调（断开时恢复按钮）
        tcpServerTransceiver.setOnStateChangedListener { connected, message ->
            if (!connected && isConnected) {
                isConnected = false
                findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_off)
                showToast(message)
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
            } else {
                if (isConnected) {
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    btn.alpha = 1f
                    showToast(message)
                }
            }
        }
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
        if (isMenuOpen && (currentMenuRes == PROTOCOL_MENU_MARKER || currentMenuRes == SETTINGS_MENU_MARKER)) {
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
            if (child.tag == "protocol_view" || child.tag == "settings_view") {
                nav.removeView(child)
            }
        }
    }

    private fun setupButtons() {
        setupSettingsButton()
        setupProtocolButton()
        setupMenuButton(R.id.menuBarCommand, R.string.nav_header_command, R.menu.menu_command, commandListener)
        setupMenuButton(R.id.menuBarControl, R.string.nav_header_control, R.menu.menu_control, controlListener)
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

    private fun setupDataEngineSpinner() {
        val spinner = protocolView?.findViewById<Spinner>(R.id.dataEngineSpinner) ?: return
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
                AppLogger.i("MainActivity", "数据引擎选择: " + options[position])
                panelConfig.dataEngineSelection = position
                ConfigManager.saveConfig(panelConfig)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
        val toolbarH = dpToPx(40)
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
            sep.textSize = 17f
            sep.setTextColor(android.graphics.Color.parseColor("#424242"))
            sep.setPadding(dpToPx(4), 0, dpToPx(4), 0)
            return sep
        }
        fun makeBtn(text: String, fixedWidth: Boolean = true, highlightOnPress: Boolean = false): android.view.View {
            val defaultColor = android.graphics.Color.parseColor("#9E9E9E")
            val highlightColor = android.graphics.Color.parseColor("#4FC3F7")
            val bgHighlight = android.graphics.Color.parseColor("#194FC3F7")
            val tv = android.widget.TextView(this)
            tv.text = text; tv.textSize = 17f
            tv.typeface = android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD_ITALIC)
            tv.setPadding(dpToPx(3), 0, dpToPx(3), 0)
            tv.setTextColor(defaultColor)
            tv.gravity = android.view.Gravity.CENTER
            tv.minimumHeight = dpToPx(40)
            if (fixedWidth) { tv.minimumWidth = dpToPx(44) }
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
        btnAbcHex = makeBtn(if (outputShowHex) "Hex" else "Abc", true, true).apply { setOnClickListener { outputShowHex = !outputShowHex; (btnAbcHex as android.widget.TextView).text = if (outputShowHex) "Hex" else "Abc"; refreshOutputDisplay() } }
        toolbar.addView(btnAbcHex); toolbar.addView(makeSep())
        val clockWrap = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)) }
        btnTimestamp = clockWrap
        val clockView = ClockIconView(this).apply { layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40)); isActive = outputShowTimestamp }
        clockWrap.setOnClickListener { outputShowTimestamp = !outputShowTimestamp; clockView.isActive = outputShowTimestamp; refreshOutputDisplay() }
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
        btnRx = makeBtn("Rx", true).apply { (this as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputRxHighlight) "#4FC3F7" else "#9E9E9E")); setOnClickListener { outputRxHighlight = !outputRxHighlight; (btnRx as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputRxHighlight) "#4FC3F7" else "#9E9E9E")); refreshOutputDisplay() } }
        toolbar.addView(btnRx)
        btnTx = makeBtn("Tx", true).apply { (this as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputTxHighlight) "#4FC3F7" else "#9E9E9E")); setOnClickListener { outputTxHighlight = !outputTxHighlight; (btnTx as android.widget.TextView).setTextColor(android.graphics.Color.parseColor(if (outputTxHighlight) "#4FC3F7" else "#9E9E9E")); refreshOutputDisplay() } }
        toolbar.addView(btnTx); toolbar.addView(makeSep())
        btnFontInc = makeBtn("A+", true, true).apply { setOnClickListener { outputFontSize = (outputFontSize + 1f).coerceAtMost(24f); outputText.textSize = outputFontSize } }
        toolbar.addView(btnFontInc)
        btnFontDec = makeBtn("A-", true, true).apply { setOnClickListener { outputFontSize = (outputFontSize - 1f).coerceAtLeast(8f); outputText.textSize = outputFontSize } }
        toolbar.addView(btnFontDec); toolbar.addView(makeSep())
        btnEncoding = makeBtn(if (outputUseGbk) "GBK" else "UTF-8", false, true).apply { setOnClickListener { outputUseGbk = !outputUseGbk; (btnEncoding as android.widget.TextView).text = if (outputUseGbk) "GBK" else "UTF-8" } }
        toolbar.addView(btnEncoding)
        toolbar.addView(makeSep())
        btnClear = makeBtn("E", true, true).apply { setOnClickListener { outputDataEntries.clear(); outputText.text = ""; lastDisplayedEntryCount = 0; autoScrollEnabled = true; scrollToBottomBtn.visibility = View.GONE; outputScrollView.invalidate() } }
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
        scrollView.addView(outputText)

        // 跳底按钮（仅用户手动滚动后显示）
        scrollToBottomBtn = android.widget.TextView(this).apply {
            text = "↓"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
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
        scrollView.onUserScrolledListener = {
            autoScrollEnabled = false
            scrollToBottomBtn.visibility = View.VISIBLE
        }

        root.addView(scrollViewWrapper)

        // 发送条
        val sendBarH = dpToPx(40)
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
        sendEditText.layoutParams = LinearLayout.LayoutParams(0, dpToPx(34), 1f)
        sendEditText.textSize = 14f
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
        val spinnerLp = LinearLayout.LayoutParams(dpToPx(80), dpToPx(34))
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
            // 统一以字节的文本解码作为 display，由 appendOutput 根据 outputShowHex 决定最终格式
            val displayStr = bytes.decodeToString()
            appendOutput(displayStr, true)
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

    private fun appendOutput(data: String, isTx: Boolean) {
        // 跳过空内容行（如连续换行符产生的空行）
        if (data.isEmpty()) return
        val cleaned = data.replace("\r\n", "\n").replace("\r", "\n")
        // 清理后可能变成空字符串（如仅有 \r 的数据）
        if (cleaned.isEmpty()) return
        val ts = if (outputShowTimestamp) System.currentTimeMillis() else null
        // visible 由追加时的开关决定：Rx 受 outputRxHighlight 控制，Tx 受 outputTxHighlight 控制
        val visible = if (isTx) outputTxHighlight else outputRxHighlight
        synchronized(outputDataEntries) {
            outputDataEntries.add(OutputEntry(cleaned, ts, isTx, visible))
            // 限制最大条目数，防止无限增长导致内存溢出
            while (outputDataEntries.size > MAX_OUTPUT_ENTRIES) {
                outputDataEntries.removeAt(0)
            }
        }
        // 不再立即刷新 UI，改为调度批量刷新
        scheduleOutputFlush()
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
     * 执行批量刷新 — 增量追加新条目到 outputText，不再全量重建
     * 仅在 main thread 上调用（通过 Handler）
     */
    private fun doFlushOutput() {
        outputFlushPending = false
        val rxColor = android.graphics.Color.parseColor("#4FC3F7")
        val txColor = android.graphics.Color.parseColor("#FFB74D")
        val timestampColor = android.graphics.Color.parseColor("#9E9E9E")

        val newSpannable = SpannableStringBuilder()
        var hasNew = false
        var needsClear = false
        // 记录现有文本是否非空，用于判断是否需要前置换行
        val existingTextNonEmpty = outputText.text.isNotEmpty()

        synchronized(outputDataEntries) {
            // 若因条目被裁剪导致索引越界，触发全量重建
            if (lastDisplayedEntryCount > outputDataEntries.size) {
                lastDisplayedEntryCount = 0
                needsClear = true
            }

            if (lastDisplayedEntryCount >= outputDataEntries.size) {
                if (!needsClear) return
            } else {
                // 仅处理自上次刷新以来的新条目
                for (i in lastDisplayedEntryCount until outputDataEntries.size) {
                    val entry = outputDataEntries[i]
                    if (!entry.visible) continue

                    // 处理条目间的换行：
                    // - 第一个可见条目：如果现有文本已有内容，需要前置 \n
                    // - 后续可见条目：始终前置 \n
                    if (!hasNew) {
                        if (existingTextNonEmpty) {
                            newSpannable.append("\n")
                        }
                    } else {
                        newSpannable.append("\n")
                    }
                    hasNew = true

                    val lineStart = newSpannable.length
                    // 时间戳
                    if (entry.timestampMs != null) {
                        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))
                        newSpannable.append("[$ts] ")
                        newSpannable.setSpan(ForegroundColorSpan(timestampColor),
                            lineStart, newSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    // 数据内容
                    val contentStr: String
                    if (outputShowHex) {
                        contentStr = entry.text.toByteArray(kotlin.text.Charsets.UTF_8).joinToString(" ") { String.format("%02X", it) }
                    } else {
                        contentStr = entry.text
                    }
                    val contentStart = newSpannable.length
                    newSpannable.append(contentStr)
                    newSpannable.setSpan(ForegroundColorSpan(if (entry.isTx) txColor else rxColor),
                        contentStart, newSpannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                lastDisplayedEntryCount = outputDataEntries.size
            }
        }

        // UI 操作在同步块外执行
        if (needsClear) {
            outputText.text = ""
            autoScrollEnabled = true
            scrollToBottomBtn.visibility = View.GONE
        }
        if (hasNew) {
            outputText.append(newSpannable)
            // 自动滚动到底部（仅在用户未手动翻看历史时）
            if (autoScrollEnabled) {
                outputScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
            outputScrollView.invalidate()
        }
    }

    /**
     * 全量重建输出显示 — 当设置（Hex/Abc、时间戳、Rx/Tx过滤）变化时调用
     * 将 lastDisplayedEntryCount 置 0，由下次 doFlushOutput 触发全量重建
     */
    private fun refreshOutputDisplay() {
        synchronized(outputDataEntries) {
            lastDisplayedEntryCount = 0
        }
        // 取消待处理的增量刷新，立即调度一次全量刷新
        outputHandler.removeCallbacks(outputFlushRunnable)
        outputFlushPending = false
        outputHandler.post {
            val rxColor = android.graphics.Color.parseColor("#4FC3F7")
            val txColor = android.graphics.Color.parseColor("#FFB74D")
            val timestampColor = android.graphics.Color.parseColor("#9E9E9E")

            val spannable = SpannableStringBuilder()
            val entries: List<OutputEntry>
            synchronized(outputDataEntries) {
                entries = outputDataEntries.toList()
                lastDisplayedEntryCount = outputDataEntries.size
            }

            var firstVisible = true
            for (entry in entries) {
                if (!entry.visible) continue
                // 跳过空内容条目
                if (entry.text.isEmpty()) continue
                if (!firstVisible) spannable.append("\n")
                firstVisible = false
                val lineStart = spannable.length

                // 时间戳
                if (entry.timestampMs != null) {
                    val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))
                    spannable.append("[$ts] ")
                    spannable.setSpan(ForegroundColorSpan(timestampColor),
                        lineStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                // 数据内容
                val contentStr: String
                if (outputShowHex) {
                    contentStr = entry.text.toByteArray(kotlin.text.Charsets.UTF_8).joinToString(" ") { String.format("%02X", it) }
                } else {
                    contentStr = entry.text
                }
                val contentStart = spannable.length
                spannable.append(contentStr)
                spannable.setSpan(ForegroundColorSpan(if (entry.isTx) txColor else rxColor),
                    contentStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            outputText.text = spannable
            if (autoScrollEnabled) {
                outputScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
            outputScrollView.invalidate()
        }
    }

    /** 跳转至最新行并恢复自动滚动 */
    private fun scrollToLatestAndResume() {
        autoScrollEnabled = true
        scrollToBottomBtn.visibility = View.GONE
        outputScrollView.jumpToBottom()
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
                    showToast("UDP 已断开")
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
                        showToast("UDP 已启动，本地端口: $localPort")
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
                    showToast(if (wasConnecting) "已取消连接" else "TCP客户端已断开")
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
                        showToast("正在连接 $ip:$port ...")
                    }
                }
            }
            3 -> {
                // ── TCP 服务端 ──
                if (tcpServerTransceiver.isConnected()) {
                    tcpServerTransceiver.disconnect()
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    showToast("TCP服务端已停止")
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
                        showToast("TCP服务端已启动，端口: $port")
                    } else {
                        showToast("启动失败，端口可能被占用")
                    }
                }
            }
            else -> {
                // ── 串口 / UDP / Demo（暂未实现具体连接，使用 NullTransceiver） ──
                if (currentTransceiver.isConnected()) {
                    currentTransceiver.disconnect()
                    isConnected = false
                    btn.setImageResource(R.drawable.ic_connect_off)
                    showToast("${currentTransceiver.getProtocolName()} 已断开")
                } else {
                    if (currentTransceiver.connect()) {
                        isConnected = true
                        btn.setImageResource(R.drawable.ic_connect_on)
                        showToast("${currentTransceiver.getProtocolName()} 已连接")
                    }
                }
            }
        }
    }

    private fun disconnectCurrent() {
        if (!isConnected) return
        currentTransceiver.disconnect()
        isConnected = false; findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_off); showToast("已断开")
    }

    private fun showToast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun restartApp() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        finish()
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    private val commandListener = NavigationView.OnNavigationItemSelectedListener { item -> AppLogger.i("MainActivity", "命令: ${item.title}"); closeMenu(); true }
    private val controlListener = NavigationView.OnNavigationItemSelectedListener { item -> AppLogger.i("MainActivity", "控件: ${item.title}"); closeMenu(); true }
    private val connectionListener = NavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.conn_connect -> { handleConnectToggle(findViewById(R.id.menuBarConnect)) }
            R.id.conn_disconnect -> { disconnectCurrent() }
        }
        closeMenu(); true
    }
}