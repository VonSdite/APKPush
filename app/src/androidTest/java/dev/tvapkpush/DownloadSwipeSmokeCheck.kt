package dev.tvapkpush

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object DownloadSwipeSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val rows = mutableListOf<DownloadSwipeRow>()
        val tiles = mutableListOf<View>()
        var pushes = 0
        var details = 0
        var deletions = 0
        var active: DownloadSwipeRow? = null
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        try {
            val scroll = main {
                val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                repeat(12) { index ->
                    val tile = LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(96)
                        setBackgroundColor(Color.WHITE)
                        addView(TextView(activity).apply { text = "Download $index" }, LinearLayout.LayoutParams(0, -1, 1f))
                        addView(Button(activity).apply {
                            text = "Push"
                            setOnClickListener { pushes++ }
                        }, LinearLayout.LayoutParams(dp(80), -1))
                        setOnClickListener { details++ }
                    }
                    val row = DownloadSwipeRow(activity, tile, index != 2, "Download $index", {
                        if (active !== it) active?.close()
                        active = it
                    }, { deletions++ })
                    rows.add(row)
                    tiles.add(tile)
                    list.addView(row, LinearLayout.LayoutParams(-1, dp(96)))
                }
                ScrollView(activity).apply {
                    addView(list)
                    setOnScrollChangeListener { _, _, y, _, oldY -> if (y != oldY) active?.close() }
                    activity.setContentView(this)
                }
            }
            instrumentation.waitForIdleSync()
            check(main { scroll.width > dp(240) && scroll.height > dp(300) }) { "Gesture viewport is too small" }
            check(main { tiles[0].height == rows[0].height }) { "Gesture fixture foreground does not fill the row" }
            fun event(action: Int, down: Long, x: Float, y: Float) {
                main {
                    val motion = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    try { scroll.dispatchTouchEvent(motion) } finally { motion.recycle() }
                }
            }
            fun gesture(index: Int, from: Float, to: Float, vertical: Int = 0, cancel: Boolean = false) {
                val (width, y) = main { scroll.width.toFloat() to (rows[index].top + rows[index].height / 2 - scroll.scrollY).toFloat() }
                val down = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, down, width * from, y)
                repeat(12) { step ->
                    SystemClock.sleep(18)
                    val fraction = (step + 1) / 12f
                    event(MotionEvent.ACTION_MOVE, down, width * (from + (to - from) * fraction), y + dp(vertical) * fraction)
                }
                event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, down, width * to, y + dp(vertical))
                SystemClock.sleep(240)
            }
            fun tap(index: Int, x: Float) {
                val (width, y) = main { scroll.width.toFloat() to (rows[index].top + rows[index].height / 2 - scroll.scrollY).toFloat() }
                val down = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, down, width * x, y)
                event(MotionEvent.ACTION_UP, down, width * x, y)
                SystemClock.sleep(240)
            }

            report("Checking normal taps and left swipes starting on the push button.")
            tap(0, .94f)
            tap(0, .2f)
            check(pushes == 1 && details == 1 && deletions == 0) { "Closed row tap routing failed: pushes=$pushes details=$details deletions=$deletions" }
            gesture(0, .94f, .25f)
            check(main { rows[0].isOpen && tiles[0].translationX < 0 }) { "Left swipe did not open the menu" }
            check(pushes == 1 && details == 1 && deletions == 0) { "Swipe triggered an item action" }
            tap(0, .2f)
            check(main { !rows[0].isOpen && tiles[0].translationX == 0f }) { "Foreground tap did not close the menu" }
            check(details == 1) { "Closing the menu opened details" }
            gesture(0, .9f, .2f)
            gesture(0, .2f, .9f)
            check(main { !rows[0].isOpen }) { "Right swipe did not close the menu" }
            gesture(0, .9f, .2f, cancel = true)
            check(main { !rows[0].isOpen && tiles[0].translationX == 0f }) { "Cancelled gesture left the menu open" }

            report("Checking single-menu selection, explicit deletion and vertical scrolling.")
            gesture(0, .9f, .2f)
            gesture(1, .9f, .2f)
            check(main { !rows[0].isOpen && rows[1].isOpen }) { "Multiple menus remained open" }
            check(deletions == 0) { "Swiping deleted an item" }
            tap(1, .94f)
            check(deletions == 1 && main { !rows[1].isOpen }) { "Delete action did not fire exactly once" }
            gesture(1, .9f, .2f)
            gesture(1, .4f, .4f, vertical = -90, cancel = true)
            check(main { scroll.scrollY > 0 && !rows[1].isOpen }) { "Vertical scrolling was blocked by an open menu" }
            check(pushes == 1 && details == 1 && deletions == 1) { "Scrolling triggered an item action" }
            main { scroll.scrollTo(0, 0) }

            report("Checking disabled deletion and accessibility actions.")
            gesture(2, .9f, .2f)
            check(main { !rows[2].isOpen && tiles[2].translationX == 0f }) { "Disabled row exposed deletion" }
            main {
                check(!tiles[2].performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)) { "Disabled row accepted accessible deletion" }
                check(tiles[0].performAccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, null)) { "Accessible deletion was unavailable" }
                check(deletions == 2) { "Accessible deletion callback count was incorrect" }
            }
            return "Swipe reveal, child tap routing, close/cancel, single menu, vertical scrolling, disabled deletion and accessibility passed.\n"
        } finally {
            main { activity.finish() }
        }
    }
}
