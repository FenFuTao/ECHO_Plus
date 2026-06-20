package com.example.echo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.echo.util.AppLogger
import com.example.echo.util.ConfigManager
import com.example.echo.util.PanelConfig
import com.google.android.material.navigation.NavigationView

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
    private var panelConfig = PanelConfig()

    private var isDraggingV = false
    private var protocolView: View? = null
    private var settingsView: View? = null

    private var isDraggingH = false
    private var startX = 0f
    private var startY = 0f
    private var startLeftWeight = 2f
    private var startRightWeight = 1f
    private var startPlotWeight = 1f
    private var startOutputWeight = 1f

    companion object {
        private const val MENU_WIDTH_DP = 280f
        private const val ICON_DP = 40
        private const val BAR_NARROW = 40
        private const val BAR_WIDE = 56
        private const val PROTOCOL_MENU_MARKER = -1
        private const val SETTINGS_MENU_MARKER = -2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        panelConfig = ConfigManager.loadConfig()
        applyColors()
        applyPanelConfig()
        applyMenuBarLayout()

        setupButtons(); setupDividers()
        navA.translationX = -getMenuPx(); navA.visibility = View.GONE
        navB.translationX = -getMenuPx(); navB.visibility = View.GONE
        scrimOverlay.setOnClickListener { closeMenu() }
        workArea.setOnClickListener { if (isMenuOpen) closeMenu() }
    }

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
        val decorMt = dpToPx((6 * ratio).toInt())
        val btnTopMt = dpToPx((6 * ratio).toInt())

        menuBar.layoutParams.width = barPx
        (workArea.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (scrimOverlay.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navA.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navB.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        rootContainer.requestLayout()

        menuBarDecoration.apply {
            val lp = layoutParams
            lp.width = iconSz; lp.height = iconSz
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
        if (isMenuOpen && currentMenuRes == menuRes) return
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
                    isConnected = !isConnected
                    (v as ImageButton).setImageResource(
                        if (isConnected) R.drawable.ic_connect_on else R.drawable.ic_connect_off
                    )
                    val s = if (isConnected) "已连接" else "已断开"
                    AppLogger.i("MainActivity", "连接状态: $s")
                    val t = Toast.makeText(this, s, Toast.LENGTH_SHORT); t.show()
                    Handler(Looper.getMainLooper()).postDelayed({ t.cancel() }, 500)
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

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                AppLogger.i("MainActivity", "数据引擎选择: $selected")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupProtocolSpinner() {
        val spinner = protocolView?.findViewById<Spinner>(R.id.protocolSpinner) ?: return
        val options = listOf(
            getString(R.string.protocol_option_serial),
            getString(R.string.protocol_option_udp),
            getString(R.string.protocol_option_tcp_client),
            getString(R.string.protocol_option_tcp_server),
            getString(R.string.protocol_option_demo)
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

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                AppLogger.i("MainActivity", "协议接口选择: $selected")

                val serialPanel = protocolView?.findViewById<View>(R.id.serialConfigPanel)
                val udpPanel = protocolView?.findViewById<View>(R.id.udpConfigPanel)
                val tcpClientPanel = protocolView?.findViewById<View>(R.id.tcpClientConfigPanel)
                val tcpServerPanel = protocolView?.findViewById<View>(R.id.tcpServerConfigPanel)
                serialPanel?.visibility = View.GONE
                udpPanel?.visibility = View.GONE
                tcpClientPanel?.visibility = View.GONE
                tcpServerPanel?.visibility = View.GONE
                when (position) {
                    0 -> serialPanel?.visibility = View.VISIBLE
                    1 -> udpPanel?.visibility = View.VISIBLE
                    2 -> tcpClientPanel?.visibility = View.VISIBLE
                    3 -> tcpServerPanel?.visibility = View.VISIBLE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

    private fun applyPanelConfig() {
        (leftPanel.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.leftPanelWeight
        (paramsContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.rightPanelWeight
        (plotContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.plotWeight
        (outputContainer.layoutParams as LinearLayout.LayoutParams).weight = panelConfig.outputWeight
    }

    private fun setupDividers() {
        findViewById<View>(R.id.vDivider).setOnTouchListener { _, e -> onVDividerTouch(e); true }
        findViewById<View>(R.id.hDivider).setOnTouchListener { _, e -> onHDividerTouch(e); true }
    }

    private fun onVDividerTouch(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { isDraggingV = true; startX = e.rawX; startLeftWeight = panelConfig.leftPanelWeight; startRightWeight = panelConfig.rightPanelWeight }
            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingV) return; val ww = workArea.width; if (ww <= 0) return
                val dx = e.rawX - startX; val tw = startLeftWeight + startRightWeight; val nl = startLeftWeight + dx * tw / ww; val nr = tw - nl
                if (nl < tw * 0.15f || nr < tw * 0.15f) return; panelConfig.leftPanelWeight = nl; panelConfig.rightPanelWeight = nr
                (leftPanel.layoutParams as LinearLayout.LayoutParams).weight = nl; (paramsContainer.layoutParams as LinearLayout.LayoutParams).weight = nr
            }
            MotionEvent.ACTION_UP -> { if (isDraggingV) { isDraggingV = false; ConfigManager.saveConfig(panelConfig) } }
        }
    }

    private fun onHDividerTouch(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { isDraggingH = true; startY = e.rawY; startPlotWeight = panelConfig.plotWeight; startOutputWeight = panelConfig.outputWeight }
            MotionEvent.ACTION_MOVE -> {
                if (!isDraggingH) return; val ph = leftPanel.height; if (ph <= 0) return
                val dy = e.rawY - startY; val tw = startPlotWeight + startOutputWeight; val np = startPlotWeight + dy * tw / ph; val no = tw - np
                if (np < tw * 0.15f || no < tw * 0.15f) return; panelConfig.plotWeight = np; panelConfig.outputWeight = no
                (plotContainer.layoutParams as LinearLayout.LayoutParams).weight = np; (outputContainer.layoutParams as LinearLayout.LayoutParams).weight = no
            }
            MotionEvent.ACTION_UP -> { if (isDraggingH) { isDraggingH = false; ConfigManager.saveConfig(panelConfig) } }
        }
    }

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
            R.id.conn_connect -> { isConnected = true; findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_on); AppLogger.i("MainActivity", "已连接"); val t = Toast.makeText(this, "已连接", Toast.LENGTH_SHORT); t.show(); Handler(Looper.getMainLooper()).postDelayed({ t.cancel() }, 500) }
            R.id.conn_disconnect -> { isConnected = false; findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(R.drawable.ic_connect_off); AppLogger.i("MainActivity", "已断开"); val t = Toast.makeText(this, "已断开", Toast.LENGTH_SHORT); t.show(); Handler(Looper.getMainLooper()).postDelayed({ t.cancel() }, 500) }
        }
        closeMenu(); true
    }
}