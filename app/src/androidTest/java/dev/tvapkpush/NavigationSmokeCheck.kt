package dev.tvapkpush

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.VerifiedDownload
import java.io.File
import java.util.UUID

object NavigationSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val automation = instrumentation.uiAutomation
        val application = instrumentation.targetContext.applicationContext as PushApplication
        val library = application.downloads
        val id = UUID.randomUUID().toString()
        val folder = library.staging(id)
        val control = JobControl()
        var activity: MainActivity? = null
        try {
            report("Preparing an owned APK fixture for navigation checks.")
            val file = File(folder, "base.apk")
            File(application.applicationInfo.sourceDir).copyTo(file)
            val info = application.packageManager.getPackageInfo(application.packageName, 0)
            library.save(id, folder, "APK Push UI Test", application.packageName, info.longVersionCode,
                "UI test fixture", listOf(VerifiedDownload.digest(file, control)), control)
            activity = instrumentation.startActivitySync(Intent(application, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.waitForIdleSync()
            var current = checkNotNull(activity)
            report("Checking tab selection, input preservation and download library navigation.")
            main(instrumentation) {
                find(current, "应用标签").performClick()
                (find(current, "搜索应用") as EditText).setText("Fossify")
                find(current, "下载分类").performClick()
                find(current, "推送标签").performClick()
                find(current, "应用标签").performClick()
                check(find(current, "下载分类").isSelected) { "Library selection was lost during tab switching" }
                find(current, "搜索分类").performClick()
                check((find(current, "搜索应用") as EditText).text.toString() == "Fossify") { "Search input was lost during tab switching" }
            }
            report("Checking recreation with the app tab selected and a sheet open.")
            var dismissCount = 0
            val sheet = main(instrumentation) {
                AppSheet.show(current, "Navigation test") { body, dialog ->
                    AppSheet.message(body, "Activity lifecycle check")
                    dialog.setOnDismissListener { dismissCount++ }
                }
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                main(instrumentation) { current.recreate() }
                current = monitor.waitForActivityWithTimeout(10_000) as? MainActivity ?: error("Activity recreation timed out")
                activity = current
            } finally { instrumentation.removeMonitor(monitor) }
            instrumentation.waitForIdleSync()
            await { main(instrumentation) { current.hasWindowFocus() && !application.play.state.busy } }
            report("Checking restored state and opening APK sources.")
            main(instrumentation) {
                check(!sheet.isShowing) { "Sheet remained visible after activity recreation" }
                check(dismissCount == 1) { "Sheet dismiss listener did not run exactly once" }
                check(find(current, "应用标签").isSelected) { "Active tab was lost during recreation" }
                check((find(current, "搜索应用") as EditText).text.toString() == "Fossify") { "Search input was lost during recreation" }
                find(current, "推送标签").performClick()
                val source = find(current, "选择 APK")
                check(source.isEnabled && source.performClick()) { "APK source selector was disabled" }
            }
            report("Selecting the download library from the APK source sheet.")
            var libraryAction: AccessibilityNodeInfo? = null
            await {
                libraryAction = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("从下载库选择")
                    ?.singleOrNull { it.isClickable && it.text?.toString() == "从下载库选择" }
                libraryAction != null
            }
            check(libraryAction!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Library source action was unavailable" }
            automation.waitForIdle(100, 5_000)
            main(instrumentation) {
                check(find(current, "下载分类").isSelected) { "Library shortcut opened the wrong section" }
            }
            await { !application.play.state.busy }
            main(instrumentation) {
                fixtureAction(current).performClick()
                check(find(current, "推送标签").isSelected) { "Selecting a download did not open the push tab" }
            }
            await { !application.controller.state.busy }
            check(application.controller.state.apk?.libraryId == id) { "Selected download was not passed to the push page" }
            main(instrumentation) { find(current, "应用标签").performClick() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            main(instrumentation) {
                check(find(current, "推送标签").isSelected) { "Back did not return to the push tab" }
                check(!current.isFinishing) { "Back unexpectedly finished the activity" }
            }
            return "Bottom tabs, preserved input and section, activity recreation, sheet dismissal, library-to-push selection and Back navigation passed.\n"
        } finally {
            main(instrumentation) {
                application.controller.clearDownload(id)
                application.play.delete(id)
                activity?.finish()
            }
            await { !application.play.state.busy && !application.play.state.libraryBusy }
            if (folder.exists()) folder.deleteRecursively()
            if (library.list().any { it.libraryId == id }) library.delete(id)
        }
    }

    private fun find(activity: MainActivity, description: String): View =
        views(activity.window.decorView).first { it.contentDescription?.toString() == description }

    private fun fixtureAction(activity: MainActivity): Button {
        val title = views(activity.window.decorView).filterIsInstance<TextView>().first { it.isShown && it.text == "APK Push UI Test" }
        var parent = title.parent as? View
        while (parent != null) {
            views(parent).filterIsInstance<Button>().singleOrNull { it.isShown && it.text == "推送" }?.let { return it }
            parent = parent.parent as? View
        }
        error("Owned fixture push action was not found")
    }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun <T> main(instrumentation: Instrumentation, action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "UI operation timed out" }
            SystemClock.sleep(50)
        }
    }
}
