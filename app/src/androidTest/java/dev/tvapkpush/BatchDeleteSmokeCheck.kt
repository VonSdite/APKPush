package dev.tvapkpush

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.PlayController
import dev.tvapkpush.play.VerifiedDownload
import org.json.JSONObject
import java.io.File
import java.util.UUID

object BatchDeleteSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "batch-delete-$token").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token.$name".also { preferences.add(it) }, mode)
        }
        val library = DownloadLibrary(context)
        val ids = (1..4).map { UUID.randomUUID().toString() }
        val records = mutableMapOf<String, String>()
        var activity: MainActivity? = null
        var page: PlayPage? = null
        var controller: PlayController? = null
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (!main(condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Batch deletion check timed out" }
                SystemClock.sleep(30)
            }
        }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun find(text: String): View = views(checkNotNull(page).view).first {
            it.isShown && (it.contentDescription?.toString() == text || (it is TextView && it.text.toString() == text))
        }
        fun tile(index: Int) = find("Batch fixture $index").parent.parent as View
        fun sheetAction(text: String) {
            var node: AccessibilityNodeInfo? = null
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (node == null) {
                node = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                    ?.firstOrNull { it.isClickable && it.text?.toString() == text }
                check(SystemClock.uptimeMillis() < deadline) { "Confirmation action was unavailable: $text" }
                if (node == null) SystemClock.sleep(50)
            }
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
        }
        fun record(id: String) = File(context.filesDir, "play-downloads/$id/download.json")
        fun publicExists(id: String): Boolean {
            val uri = Uri.parse(JSONObject(records.getValue(id)).getString("publicUri"))
            return base.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { it.moveToFirst() } == true
        }
        try {
            val source = File(base.applicationInfo.sourceDir)
            val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
            val hash = VerifiedDownload.digest(source, JobControl())
            ids.forEachIndexed { index, id ->
                val folder = library.staging(id)
                source.copyTo(File(folder, "base.apk"))
                library.save(id, folder, "Batch fixture ${index + 1}", base.packageName, version,
                    "Batch test fixture", listOf(hash), JobControl())
                records[id] = record(id).readText()
            }
            val play = main { PlayController(context, library, isOnline = { false }) }.also { controller = it }
            await { !play.state.busy }
            val current = (instrumentation.startActivitySync(Intent(base, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { activity = it }
            main {
                (current.application as PushApplication).play.leaveForeground()
                page = PlayPage(current, null, {}, {}, play).also { current.setContentView(it.view); it.start(); it.showLibrary() }
            }
            instrumentation.waitForIdleSync()
            report("Checking long press, row toggles, select all and accessible selection state.")
            main {
                check(tile(1).performLongClick())
                check(find("删除 (1)").isEnabled)
                val selection = find("选择 Batch fixture 1").createAccessibilityNodeInfo()
                check(selection.isCheckable && selection.isChecked)
                tile(2).performClick()
                check(find("删除 (2)").isEnabled)
                find("全选").performClick()
                check(find("删除 (4)").isEnabled)
                find("取消全选").performClick()
                check(!find("删除 (0)").isEnabled)
                tile(1).performClick()
                tile(2).performClick()
                val saved = checkNotNull(page).saveState()
                page?.stop(false)
                page = PlayPage(current, saved, {}, {}, play).also { current.setContentView(it.view); it.start() }
            }
            instrumentation.waitForIdleSync()
            main {
                check(find("删除 (2)").isEnabled)
                check(find("选择 Batch fixture 1").isSelected && find("选择 Batch fixture 2").isSelected)
                find("删除 (2)").performClick()
            }
            sheetAction("取消")
            check(ids.all { library.contains(it) && publicExists(it) }) { "Cancelling confirmation deleted a fixture" }
            report("Checking confirmed batch deletion preserves unselected downloads.")
            main { find("删除 (2)").performClick() }
            sheetAction("确认")
            await { !play.state.libraryBusy && play.state.downloads.size == 2 }
            check(ids.take(2).none { library.contains(it) || publicExists(it) }) { "Deleted files remained in storage" }
            check(ids.drop(2).all { library.contains(it) && publicExists(it) }) { "Unselected files were deleted" }
            main {
                check(!checkNotNull(page).cancelSelection()) { "Successful deletion did not leave selection mode" }
                check(tile(3).performLongClick())
                check(checkNotNull(page).cancelSelection())
                check(tile(3).performLongClick())
                find("Google Play").performClick()
                check(!checkNotNull(page).cancelSelection()) { "Switching sections retained selection mode" }
                page.showLibrary()
                check(tile(3).performLongClick())
                tile(4).performClick()
            }
            report("Checking partial failure retains failed selections and permits manual retry.")
            record(ids[2]).writeText("{\n")
            main { find("删除 (2)").performClick() }
            sheetAction("确认")
            await { !play.state.libraryBusy && play.state.downloads.size == 1 }
            main {
                check(play.state.downloads.single().libraryId == ids[2])
                check(play.state.libraryMessage.contains("1 项未能删除"))
                check(find("删除 (1)").isEnabled && find("选择 Batch fixture 3").isSelected)
            }
            check(publicExists(ids[2]) && !publicExists(ids[3]))
            record(ids[2]).writeText(records.getValue(ids[2]))
            main { find("删除 (1)").performClick() }
            sheetAction("确认")
            await { !play.state.libraryBusy && play.state.downloads.isEmpty() }
            check(ids.none { library.contains(it) || publicExists(it) })
            main { check(find("暂无下载").isShown) }
            return "Long press, toggles, select all, accessible state, saved selection, confirmation cancellation, batch deletion, preserved unselected files, partial failure and retry passed.\n"
        } finally {
            controller?.let { play -> await { !play.state.busy && !play.state.libraryBusy } }
            main { page?.stop(false); activity?.finish() }
            ids.forEach { id ->
                if (library.contains(id)) {
                    records[id]?.let { record(id).writeText(it) }
                    library.delete(id)
                } else library.discard(id)
            }
            preferences.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
