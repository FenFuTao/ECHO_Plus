package com.fenfutao.echo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/**
 * 带厚实可拖动滚动条的自定义 ScrollView
 *
 * 解决默认 ScrollView 滚动条太细、难拖动的问题。
 * - 8dp 宽的半透明滑块，带圆角
 * - 支持触摸拖拽滑块
 * - 支持点击轨道快速跳转
 * - 内容不满一屏时自动隐藏
 * - 自动滚动到底部时隐藏，用户交互时显示
 */
class OutputScrollView(context: Context) : ScrollView(context) {

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99CCCCCC")
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#25CCCCCC")
        style = Paint.Style.FILL
    }

    private val scrollBarWidth: Float
    private val scrollBarMargin: Float
    /** 点击/拖拽区域的宽度（比滚动条本身宽，方便触摸） */
    private val scrollBarTouchWidth: Float
    private val minThumbHeight: Float
    private val cornerRadius: Float

    private var isDraggingThumb = false
    private var dragStartY = 0f
    private var dragStartScrollY = 0f

    /** 标记当前滚动是否由程序主动触发，防止误触 onUserScrolledListener */
    private var isProgrammaticScroll = false
    /** 用户手动滚动回调 */
    var onUserScrolledListener: (() -> Unit)? = null

    /**
     * 滚动锁定标志。
     * true 时拦截所有用户触摸事件，仅允许程序化滚动。
     * 用于连接状态下强制自动滚动到底部，禁止用户手动回看历史。
     */
    var scrollLocked: Boolean = false

    /**
     * 滚动条拇指可见性
     * - 程序化滚动到底部时 -> false（隐藏）
     * - 用户手动滚动或点击内容区 -> true（显示）
     */
    var thumbVisible: Boolean = true

    init {
        val density = resources.displayMetrics.density
        scrollBarWidth = 6f * density
        scrollBarMargin = 3f * density
        scrollBarTouchWidth = 16f * density
        minThumbHeight = 30f * density
        cornerRadius = 3f * density

        isVerticalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        setWillNotDraw(false)
    }

    override fun fullScroll(direction: Int): Boolean {
        isProgrammaticScroll = true
        return super.fullScroll(direction)
    }

    /** 判断当前是否已滚动到底部（含 15px 容差） */
    fun isAtBottom(): Boolean {
        val contentH = computeVerticalScrollRange()
        val viewH = height
        return (scrollY + viewH) >= contentH - 15
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        drawScrollbar(canvas)
        canvas.restore()
    }

    private fun drawScrollbar(canvas: Canvas) {
        val contentHeight = computeVerticalScrollRange().toFloat()
        val viewHeight = height.toFloat()
        if (contentHeight <= viewHeight) return
        if (!thumbVisible) return

        val scrollY = scrollY.toFloat()
        val thumbHeight = (viewHeight / contentHeight * viewHeight).coerceAtLeast(minThumbHeight)
        val thumbAvailable = viewHeight - thumbHeight - scrollBarMargin * 2f
        val scrollRange = contentHeight - viewHeight

        val thumbTop: Float = if (scrollRange > 0f) {
            (scrollY / scrollRange) * thumbAvailable + scrollBarMargin
        } else {
            scrollBarMargin
        }

        val right = width.toFloat() - scrollBarMargin
        val left = right - scrollBarWidth
        val trackTop = scrollBarMargin
        val trackBottom = viewHeight - scrollBarMargin

        val trackRect = RectF(left, trackTop, right, trackBottom)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint)

        val thumbRect = RectF(left, thumbTop, right, thumbTop + thumbHeight)
        canvas.drawRoundRect(thumbRect, cornerRadius, cornerRadius, thumbPaint)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()

        val contentHeight = computeVerticalScrollRange()
        val atBottom = (t + height) >= contentHeight - 15

        if (!isProgrammaticScroll) {
            // ★ 滚动锁定时，用户手动滚动不会触发 listener
            if (!atBottom && !scrollLocked) {
                onUserScrolledListener?.invoke()
            }
            thumbVisible = true
        } else {
            if (atBottom) {
                thumbVisible = false
            }
        }
        isProgrammaticScroll = false
    }

    private fun computeThumbTop(): Float {
        val contentHeight = computeVerticalScrollRange().toFloat()
        val viewHeight = height.toFloat()
        if (contentHeight <= viewHeight) return scrollBarMargin

        val thumbHeight = (viewHeight / contentHeight * viewHeight).coerceAtLeast(minThumbHeight)
        val thumbAvailable = viewHeight - thumbHeight - scrollBarMargin * 2f
        val scrollRange = contentHeight - viewHeight

        return if (scrollRange > 0f) {
            (scrollY.toFloat() / scrollRange) * thumbAvailable + scrollBarMargin
        } else {
            scrollBarMargin
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // ★ 滚动锁定时完全拦截触摸事件，仅允许程序化滚动
        if (scrollLocked) return false

        val contentHeight = computeVerticalScrollRange().toFloat()
        val viewHeight = height.toFloat()
        if (contentHeight <= viewHeight) return super.onTouchEvent(ev)

        val thumbHeight = (viewHeight / contentHeight * viewHeight).coerceAtLeast(minThumbHeight)
        val scrollRange = contentHeight - viewHeight
        val x = ev.x
        val y = ev.y

        val inScrollBarArea = x >= (width.toFloat() - scrollBarTouchWidth)

        if (!inScrollBarArea && ev.action == MotionEvent.ACTION_DOWN) {
            thumbVisible = true
            invalidate()
        }

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (inScrollBarArea) {
                    val currentThumbTop = computeThumbTop()
                    if (y >= currentThumbTop && y <= currentThumbTop + thumbHeight) {
                        isDraggingThumb = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        dragStartY = y
                        dragStartScrollY = scrollY.toFloat()
                        onUserScrolledListener?.invoke()
                        return true
                    } else if (y >= scrollBarMargin && y <= viewHeight - scrollBarMargin) {
                        onUserScrolledListener?.invoke()
                        val thumbAvailable = viewHeight - thumbHeight - scrollBarMargin * 2f
                        if (thumbAvailable > 0f) {
                            val ratio = ((y - scrollBarMargin) / thumbAvailable)
                                .coerceIn(0f, 1f)
                            val targetScroll = (ratio * scrollRange).toInt()
                            smoothScrollTo(0, targetScroll)
                        }
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingThumb) {
                    val dy = y - dragStartY
                    val thumbAvailable = viewHeight - thumbHeight - scrollBarMargin * 2f
                    if (thumbAvailable > 0f && scrollRange > 0f) {
                        val ratio = dy / thumbAvailable
                        val newScroll = (dragStartScrollY + ratio * scrollRange).toInt()
                            .coerceIn(0, scrollRange.toInt())
                        scrollTo(0, newScroll)
                    }
                    return true
                }
                return super.onTouchEvent(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingThumb) {
                    isDraggingThumb = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
                return super.onTouchEvent(ev)
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
