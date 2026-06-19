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

    private var isMenuOpen = false
    private var activeIsA = true
    private var currentMenuRes = 0
    private var selectedMenuBtnId = 0
    private var isConnected = false
    private var panelConfig = PanelConfig()

    private var isDraggingV = false
    private var isDraggingH = false
    private var startX = 0f
    private var startY = 0f
    private var startLeftWeight = 2f
    private var startRightWeight = 1f
    private var startPlotWeight = 1f
    private var startOutputWeight = 1f

    private var currentIconPx = 0

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

        panelConfig = ConfigManager.loadConfig()
        applyPanelConfig()
        applyMenuBarLayout()

        setupButtons(); setupDividers()
        navA.translationX = -getMenuPx(); navA.visibility = View.GONE
        navB.translationX = -getMenuPx(); navB.visibility = View.GONE
        scrimOverlay.setOnClickListener { closeMenu() }
        workArea.setOnClickListener { if (isMenuOpen) closeMenu() }
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
        val mt = dpToPx((12 * ratio).toInt())
        val ms = dpToPx((8 * ratio).toInt())
        currentIconPx = iconSz

        menuBar.layoutParams.width = barPx; menuBar.requestLayout()
        (workArea.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (scrimOverlay.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navA.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx
        (navB.layoutParams as ViewGroup.MarginLayoutParams).marginStart = barPx

        val ids = intArrayOf(R.id.menuBarConnect, R.id.menuBarProtocol, R.id.menuBarCommand, R.id.menuBarControl, R.id.menuBarSetting)
        for (id in ids) {
            val btn = findViewById<ImageButton>(id)
            val lp = btn.layoutParams as ViewGroup.MarginLayoutParams
            lp.width = iconSz; lp.height = iconSz
            lp.topMargin = if (id == R.id.menuBarConnect) mt else ms
            btn.layoutParams = lp; btn.setPadding(pad, pad, pad, pad)
        }
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
            findViewById<ImageButton>(id)?.let {
                it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val lp = it.layoutParams; lp.width = currentIconPx; lp.height = currentIconPx
                it.layoutParams = lp
            }
        }
        findViewById<ImageButton>(R.id.menuBarSetting)?.setImageResource(R.drawable.ic_settings)
    }

    private fun highlightMenuBarButton(btnId: Int) {
        clearMenuBarHighlight()
        findViewById<ImageButton>(btnId)?.let {
            it.setBackgroundResource(R.drawable.bg_menu_highlight)
            val lp = it.layoutParams
            val barPx = dpToPx(currentBarDp())
            lp.width = barPx; lp.height = barPx
            it.layoutParams = lp
        }
        if (btnId == R.id.menuBarSetting) {
            findViewById<ImageButton>(R.id.menuBarSetting)?.setImageResource(R.drawable.ic_settings_dark)
        }
    }

    private fun currentBarDp(): Int = panelConfig.menuBarWidthDp.coerceIn(BAR_NARROW, BAR_WIDE)

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
        findViewById<ImageButton>(R.id.menuBarSetting).setOnClickListener {
            switchMenuSimultaneously(R.string.nav_header_settings, R.menu.nav_drawer, R.id.menuBarSetting, settingsListener)
        }
        findViewById<ImageButton>(R.id.menuBarProtocol).setOnClickListener {
            switchMenuSimultaneously(R.string.nav_header_protocol, R.menu.menu_protocol, R.id.menuBarProtocol, protocolListener)
        }
        findViewById<ImageButton>(R.id.menuBarCommand).setOnClickListener {
            switchMenuSimultaneously(R.string.nav_header_command, R.menu.menu_command, R.id.menuBarCommand, commandListener)
        }
        findViewById<ImageButton>(R.id.menuBarControl).setOnClickListener {
            switchMenuSimultaneously(R.string.nav_header_control, R.menu.menu_control, R.id.menuBarControl, controlListener)
        }
        findViewById<ImageButton>(R.id.menuBarConnect).setOnClickListener {
            isConnected = !isConnected
            findViewById<ImageButton>(R.id.menuBarConnect).setImageResource(
                if (isConnected) R.drawable.ic_connect_on else R.drawable.ic_connect_off
            )
            val s = if (isConnected) "已连接" else "已断开"
            AppLogger.i("MainActivity", "连接状态: $s")
            val t = Toast.makeText(this, s, Toast.LENGTH_SHORT); t.show()
            Handler(Looper.getMainLooper()).postDelayed({ t.cancel() }, 500)
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
    private val protocolListener = NavigationView.OnNavigationItemSelectedListener { item -> AppLogger.i("MainActivity", "协议: ${item.title}"); closeMenu(); true }
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
