package dev.tvapkpush

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.abs

internal class DownloadSwipeRow(
    context: Context,
    private val content: View,
    initialCanDelete: Boolean,
    label: String,
    private val onInteraction: (DownloadSwipeRow) -> Unit,
    private val onDelete: () -> Unit,
) : FrameLayout(context) {
    private var canDelete = initialCanDelete
    private val distance = dp(84).toFloat()
    private val configuration = ViewConfiguration.get(context)
    private var startX = 0f
    private var startY = 0f
    private var startOffset = 0f
    private var dragging = false
    private var vertical = false
    private var closeOnTap = false
    private var tracker: VelocityTracker? = null
    var isOpen = false
        private set
    private val delete = Button(context).apply {
        text = "删除"
        textSize = 15f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xFFC83C46.toInt())
        background = RippleDrawable(ColorStateList.valueOf(0x14C83C46), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(12).toFloat() })
        stateListAnimator = null
        minWidth = 0; minimumWidth = 0
        minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        contentDescription = "删除 $label"
        visibility = View.INVISIBLE
        isEnabled = false
        setOnClickListener { close(); onDelete() }
    }

    init {
        addView(delete, LayoutParams(dp(76), -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        addView(content, LayoutParams(-1, -2))
        content.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (canDelete) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "删除"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (canDelete && action == AccessibilityNodeInfo.ACTION_DISMISS) { close(); onDelete(); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!canDelete) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                content.animate().cancel()
                onInteraction(this)
                startX = event.x; startY = event.y; startOffset = content.translationX
                dragging = false; vertical = false
                closeOnTap = startOffset < 0 && event.x < width + startOffset
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
                return closeOnTap
            }
            MotionEvent.ACTION_MOVE -> {
                if (startsDrag(event)) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!canDelete) return false
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                startsDrag(event)
                if (dragging) {
                    delete.visibility = View.VISIBLE
                    content.translationX = (startOffset + event.x - startX).coerceIn(-distance, 0f)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val velocity = tracker?.xVelocity ?: 0f
                    settle(when {
                        velocity < -configuration.scaledMinimumFlingVelocity -> true
                        velocity > configuration.scaledMinimumFlingVelocity -> false
                        else -> content.translationX < -distance * .45f
                    })
                } else if (closeOnTap) performClick()
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> { close(); endGesture() }
        }
        return true
    }

    override fun performClick(): Boolean { close(); return super.performClick() }

    private fun startsDrag(event: MotionEvent): Boolean {
        if (vertical || dragging) return dragging
        val dx = abs(event.x - startX)
        val dy = abs(event.y - startY)
        if (dy > configuration.scaledTouchSlop && dy > dx) { vertical = true; return false }
        if (dx > configuration.scaledTouchSlop && dx > dy * 1.25f) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging
    }

    fun close(animated: Boolean = true) = settle(false, animated)

    fun setDeletionEnabled(enabled: Boolean) {
        if (canDelete == enabled) return
        canDelete = enabled
        if (!enabled) close(animated = false)
    }

    private fun settle(open: Boolean, animated: Boolean = true) {
        isOpen = open
        delete.isEnabled = canDelete && open
        content.animate().cancel()
        val target = if (open) -distance else 0f
        if (open) delete.visibility = View.VISIBLE
        if (animated && content.translationX != target) {
            content.animate().translationX(target).setDuration(180).withEndAction {
                if (!isOpen) delete.visibility = View.INVISIBLE
            }.start()
        } else {
            content.translationX = target
            delete.visibility = if (open) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun endGesture() {
        tracker?.recycle(); tracker = null
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onDetachedFromWindow() {
        close(animated = false)
        endGesture()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
