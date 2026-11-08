package dev.tvapkpush

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object AppSheet {
    private class SheetDialog(private val activity: Activity) : Dialog(activity), Application.ActivityLifecycleCallbacks {
        override fun onStart() {
            super.onStart()
            activity.registerActivityLifecycleCallbacks(this)
        }

        override fun onStop() {
            activity.unregisterActivityLifecycleCallbacks(this)
            super.onStop()
        }

        override fun onActivityStopped(activity: Activity) { dismiss() }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    fun show(activity: Activity, title: String, content: (LinearLayout, Dialog) -> Unit): Dialog =
        show(activity, title, expanded = false, content = content)

    fun show(activity: Activity, title: String, expanded: Boolean,
        fixedContent: ((LinearLayout, Dialog) -> Unit)? = null, content: (LinearLayout, Dialog) -> Unit): Dialog {
        val dialog = SheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 24))
            background = rounded(activity, Color.WHITE, 24)
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(activity, title, 18, AppPalette.INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ImageButton(activity).apply {
            setImageDrawable(UiIcon(UiIcon.Kind.CLOSE, AppPalette.SECONDARY))
            setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
            background = RippleDrawable(ColorStateList.valueOf(0x12000000), null, rounded(activity, Color.WHITE, 22))
            contentDescription = "关闭$title"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)).apply { marginStart = dp(activity, 12) })
        root.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 16) })
        fixedContent?.invoke(root, dialog)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        content(body, dialog)
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(body) }
        root.addView(scroll, if (expanded) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.28f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            navigationBarColor = Color.WHITE
        }
        dialog.show()
        val width = minOf(activity.resources.displayMetrics.widthPixels, dp(activity, 560))
        val maximum = (activity.resources.displayMetrics.heightPixels * .88f).toInt()
        var previousHeight = -1
        fun resize() {
            val frame = Rect()
            root.getWindowVisibleDisplayFrame(frame)
            val available = if (frame.height() > 0) minOf(maximum, frame.height()) else maximum
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(available, if (expanded) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
            val height = minOf(root.measuredHeight, available)
            if (height != previousHeight) {
                previousHeight = height
                dialog.window?.setLayout(width, height)
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener { resize() }
        resize()
        return dialog
    }

    fun message(body: LinearLayout, value: String) {
        body.addView(label(body.context, value, 15, AppPalette.SECONDARY).apply {
            setLineSpacing(dp(context, 4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(body.context, 20) })
    }

    fun details(body: LinearLayout, vararg values: Pair<String, String>) {
        val context = body.context
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        values.forEach { (title, value) ->
            val stacked = context.resources.configuration.fontScale > 1.3f
            val row = LinearLayout(context).apply {
                orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(context, 8), 0, dp(context, 8))
            }
            row.addView(label(context, title, 13, AppPalette.SECONDARY), LinearLayout.LayoutParams(if (stacked) -1 else dp(context, 74), -2))
            row.addView(label(context, value, 14, AppPalette.INK).apply { setTextIsSelectable(true) },
                if (stacked) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 6) }
                else LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(context, 12) })
            group.addView(row)
        }
        body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 20) })
    }

    fun action(body: LinearLayout, title: String, primary: Boolean = false, destructive: Boolean = false, action: () -> Unit): Button {
        val context = body.context
        return Button(context).apply {
            text = title
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create(if (primary) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setTextColor(if (destructive) 0xFFC83C46.toInt() else if (title == "取消") AppPalette.SECONDARY else AppPalette.BLUE)
            minHeight = dp(context, 48)
            minimumHeight = minHeight
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            background = actionBackground(context, destructive)
            stateListAnimator = null
            setOnClickListener { action() }
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(context, 8) })
        }
    }

    fun confirm(activity: Activity, title: String, messageText: String, actionTitle: String, destructive: Boolean = false, confirm: () -> Unit) =
        show(activity, title) { body, dialog ->
            message(body, messageText)
            action(body, actionTitle, primary = !destructive, destructive = destructive) { dialog.dismiss(); confirm() }
            action(body, "取消") { dialog.dismiss() }
        }

    private fun actionBackground(context: Context, destructive: Boolean): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(if (destructive) 0x12C83C46 else 0x120071E3), null, rounded(context, Color.WHITE, 12))

    private fun label(context: Context, value: String, size: Int, color: Int, medium: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rounded(context: Context, color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }
}
