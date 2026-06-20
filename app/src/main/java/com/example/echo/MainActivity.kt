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
    private var isShowingProtocol = false

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

    // ====================== 主题配色 ======================

    private fun applyColors() {
        val primaryColor = android.graphics.Color.parseColor(panelConfig.primaryColorHex)
        val menuBarBg = android.graphics.Color.parseColor(panelConfig.menuBarBgHex)

        // 应用主色调
        val window = window
        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor

        // 设置菜单栏背景
        menuBar.setBackgroundColor(menuBarBg)

        // 设置菜单页顶部标签背景为主色调
        navA.getHeaderView(0)?.setBackgroundColor(primaryColor)
        navB.getHeaderView(0)?.setBackgroundColor(primaryColor)

        AppLogger.i("MainActivity", "配色: primary=${panelConfig.primaryColorHex}, menuBar=${panelConfig.menuBarBgHex}")
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    // ====================== 动态菜单栏布局 ======================

    private fun applyMenuBarLayout() {
        val barDp = panelConfig.menuBarWidthDp.coerceIn(BAR_NARROW, BAR_WIDE)
        val barPx = dpToPx(barDp)
        val ratio = barDp.toFloat() / 48f
        val iconSz = dpToPx((ICON_DP * ratio).toInt())
        val pad = dpToPx((6 * ratio).toInt())
        val ms = dpToPx((8 * ratio).toInt())
        val decorMt = dpToPx((6 * ratio).toInt())
        val btnTopMt = dpToPx((6 * ratio).toInt())

        menuBar.layoutParams.width = barPx; menuBar.requestLayout()
        (workArea.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (scrimOverlay.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navA.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navB.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx

        // 装饰圆角方形
        menuBarDecoration.apply {
            val lp = layoutParams
            lp.width = iconSz; lp.height = iconSz
            (lp as ViewGroup.MarginLayoutParams).topMargin = decorMt
            layoutParams = lp
        }

        val connectSz = dpToPx((ICON_DP * 1.15 * ratio).toInt()) // 连接按钮比普通图标大15%

        val ids = intArrayOf(R.id.menuBarConnect, R.id.menuBarProtocol, R.id.menuBarCommand, R.id.menuBarControl, R.id.menuBarSetting)
        for (id in ids) {
            val btn = findViewById<ImageButton>(id)
            val lp = btn.layoutParams as ViewGroup.MarginLayoutParams
            val sz = if (id == R.id.menuBarConnect) connectSz else iconSz
            lp.width = sz; lp.height = sz
            lp.topMargin = if (id == R.id.menuBarConnect) btnTopMt else ms
            btn.layoutParams = lp; btn.setPadding(pad, pad, pad, pad)
        }
        // 更新高亮指示器尺寸 - 保持 64:48 (高:宽) 比例匹配 bg_menu_highlight 矢量图
        menuBarHighlight.layoutParams.height = (barPx * 64f / 48f).toInt()
        AppLogger.i("MainActivity", "菜单栏: ${barDp}dp, 图标: ${(ICON_DP * ratio).toInt()}dp")
    }

    private fun setBarAndSave(barDp: Int, label: String) {
        panelConfig.menuBarWidthDp = barDp; applyMenuBarLayout(); ConfigManager.saveConfig(panelConfig)
        Toast.makeText(this, "菜单栏: $label", Toast.LENGTH_SHORT).show()
    }

    // ====================== 菜单高亮 ======================

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
        clearMenuBarHighlight() // 先隐藏高亮框
        val btn = findViewById<ImageButton>(btnId)
        // 在布局完成后定位，再显现，避免出现位置跳跃
        // 使用 layoutParams.height 而非实测 height，因为 GONE 状态下实测 height=0
        menuBarHighlight.post {
            menuBarHighlight.translationY = btn.y + (btn.height - menuBarHighlight.layoutParams.height) / 2f
            menuBarHighlight.visibility = View.VISIBLE
        }
    }

    // ====================== 菜单控制 ======================

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
        // 如果当前显示协议页面，先恢复 paramsContainer
        if (isShowingProtocol) {
            restoreParamsContainerDefault()
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
        nav.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.navHeaderTitle)?.text = title
        nav.menu.clear(); nav.inflateMenu(menuRes); nav.setNavigationItemSelectedListener(listener)
    }

    private fun setupButtons() {
        setupMenuButton(R.id.menuBarSetting, R.string.nav_header_settings, R.menu.nav_drawer, settingsListener)
        setupProtocolButton()
        setupMenuButton(R.id.menuBarCommand, R.string.nav_header_command, R.menu.menu_command, commandListener)
        setupMenuButton(R.id.menuBarControl, R.string.nav_header_control, R.menu.menu_control, controlListener)
        setupConnectButton()
    }

    /** 菜单按钮：按下放大，松开恢复并执行菜单切换 */
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

    /** 连接按钮：按下时立即切换状态并放大，松开恢复 */
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

    /** 协议按钮：按下放大，松开恢复，切换协议页面显示 */
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

    /** 切换协议页面的显示/隐藏 */
    private fun toggleProtocolPage() {
        if (isShowingProtocol) {
            // 已显示协议页面，再次点击则关闭
            restoreParamsContainerDefault()
            clearMenuBarHighlight()
            isShowingProtocol = false
        } else {
            showProtocolPage()
        }
    }

    /** 显示协议与连接页面 */
    private fun showProtocolPage() {
        // 关闭已打开的侧边菜单
        if (isMenuOpen) closeMenu()

        highlightMenuBarButton(R.id.menuBarProtocol)

        // 首次使用时 inflate 布局
        if (protocolView == null) {
            protocolView = layoutInflater.inflate(R.layout.page_protocol, paramsContainer, false)
            setupProtocolSpinner()
            setupProtocolDocButton()
        }

        // 替换 paramsContainer 内容
        paramsContainer.removeAllViews()
        paramsContainer.addView(protocolView)
        paramsContainer.setPadding(0, 0, 0, 0)
        isShowingProtocol = true
    }

    /** 恢复 paramsContainer 为默认的"参数列表"占位内容 */
    private fun restoreParamsContainerDefault() {
        paramsContainer.removeAllViews()
        paramsContainer.setPadding(4, 4, 4, 4)
        val defaultText = TextView(this)
        defaultText.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        defaultText.text = getString(R.string.param_title)
        defaultText.setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
        defaultText.textSize = 12f
        paramsContainer.addView(defaultText)
        isShowingProtocol = false
    }

    /** 配置协议接口下拉选择框 */
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
                val infoText = protocolView?.findViewById<TextView>(R.id.protocolSelectionInfo)
                infoText?.text = "已选择: $selected"
                AppLogger.i("MainActivity", "协议接口选择: $selected")

                // 更新状态提示
                val badge = protocolView?.findViewById<TextView>(R.id.protocolStatusBadge)
                badge?.text = if (position == 0) getString(R.string.protocol_status_disconnected) else "待连接"
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 配置"?"文档按钮 */
    private fun setupProtocolDocButton() {
        val docBtn = protocolView?.findViewById<ImageButton>(R.id.protocolDocBtn) ?: return
        docBtn.setOnClickListener {
            AppLogger.i("MainActivity", "打开协议文档")
            Toast.makeText(this, "打开协议文档", Toast.LENGTH_SHORT).show()
            // 可以在这里打开文档 URL，例如：
            // val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.example.com/protocol"))
            // startActivity(intent)
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

    private val settingsListener = NavigationView.OnNavigationItemSelectedListener { item ->
        when (item.itemId) {
            R.id.nav_bar_small -> setBarAndSave(BAR_NARROW, "窄 (适合手机)")
            R.id.nav_bar_large -> setBarAndSave(BAR_WIDE, "宽 (适合平板)")
            R.id.nav_btn1 -> AppLogger.i("MainActivity", "设置: 测试按钮一")
            R.id.nav_btn2 -> AppLogger.i("MainActivity", "设置: 测试按钮二")
            R.id.nav_btn3 -> AppLogger.i("MainActivity", "设置: 测试按钮三")
            R.id.nav_btn4 -> AppLogger.i("MainActivity", "设置: 测试按钮四")
        }
        closeMenu(); true
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
