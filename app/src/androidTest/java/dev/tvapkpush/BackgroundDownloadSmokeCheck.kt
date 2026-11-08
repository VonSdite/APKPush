package dev.tvapkpush

import android.app.Instrumentation
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.aurora.gplayapi.model.App
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.DownloadPhase
import dev.tvapkpush.play.DownloadTaskStore
import java.io.File

object BackgroundDownloadSmokeCheck {
    fun runLive(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val application = instrumentation.targetContext.applicationContext as PushApplication
        val play = application.play
        val manager = application.getSystemService(ConnectivityManager::class.java)
        val notifications = application.getSystemService(NotificationManager::class.java)
        check(manager.activeNetwork == null) { "Live background check must start offline" }
        check(DownloadTaskStore(application).readAll().isEmpty()) { "A user download task is already present" }
        val activity = instrumentation.startActivitySync(Intent(application, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var id: String? = null
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        fun stopped(phase: DownloadPhase) = await {
            play.state.tasks.singleOrNull()?.phase == phase && !play.state.busy && !play.state.backgroundDownload &&
                notifications.activeNotifications.none { it.id == 21 }
        }
        fun stable(phase: DownloadPhase) {
            val retained = application.downloads.partialProgress(checkNotNull(id))
            val deadline = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < deadline) {
                check(play.state.tasks.singleOrNull()?.id == id && play.state.tasks.singleOrNull()?.phase == phase && !play.state.busy) { "Stopped download resumed without user input" }
                check(application.downloads.partialProgress(id!!) == retained) { "Stopped download wrote additional bytes" }
                SystemClock.sleep(100)
            }
        }
        try {
            await { !play.state.busy && !play.state.backgroundDownload }
            instrumentation.runOnMainSync {
                play.download(App(packageName = "org.fossify.math", displayName = "Fossify Calculator"))
                id = play.state.tasks.singleOrNull()?.id
            }
            check(id != null) { "Live download was not created" }
            stopped(DownloadPhase.FAILED)
            instrumentation.runOnMainSync { activity.finish() }
            report("Restoring connectivity and verifying that the failed task stays stopped.")
            shell("svc wifi enable")
            shell("svc data enable")
            await { manager.activeNetwork != null }
            stable(DownloadPhase.FAILED)
            action(notifications, "重试").send()
            await { play.state.downloading }
            await(120_000) { (play.state.tasks.singleOrNull()?.bytes ?: 0L) > 524288 || play.state.tasks.singleOrNull()?.phase == DownloadPhase.FAILED }
            check(play.state.downloading) { "Explicit retry failed before partial bytes were written" }

            report("Pausing an active download through its notification.")
            action(notifications, "暂停").send()
            stopped(DownloadPhase.PAUSED)
            val pausedBytes = checkNotNull(application.downloads.partialProgress(id!!)).first
            check(pausedBytes > 0 && DownloadTaskStore(application).readAll().singleOrNull()?.bytes == pausedBytes) { "Pause did not persist actual retained progress" }
            stable(DownloadPhase.PAUSED)
            report("Continuing the same task from retained bytes=$pausedBytes.")
            action(notifications, "继续").send()
            await { play.state.downloading }
            await(120_000) { (play.state.tasks.singleOrNull()?.bytes ?: 0L) > pausedBytes + 524288 || play.state.tasks.singleOrNull()?.phase == DownloadPhase.FAILED }
            check(play.state.downloading && play.state.tasks.singleOrNull()?.id == id) { "Explicit continue did not resume the same task" }

            report("Interrupting networking after partial APK bytes have been written.")
            shell("svc wifi disable")
            shell("svc data disable")
            stopped(DownloadPhase.FAILED)
            val interruptedBytes = checkNotNull(application.downloads.partialProgress(id!!)).first
            check(interruptedBytes > pausedBytes) { "Continued transfer did not retain additional progress" }
            shell("svc wifi enable")
            shell("svc data enable")
            await { manager.activeNetwork != null }
            stable(DownloadPhase.FAILED)
            report("Retrying explicitly after network restoration: bytes=$interruptedBytes.")
            action(notifications, "重试").send()
            await { play.state.downloading || play.state.tasks.singleOrNull() == null }
            var lastProgress = -1L
            val deadline = SystemClock.uptimeMillis() + 240_000
            while (play.state.tasks.singleOrNull() != null) {
                check(SystemClock.uptimeMillis() < deadline) { "Live background download timed out" }
                val state = play.state
                check(state.tasks.singleOrNull()?.phase != DownloadPhase.FAILED) { "Live background download failed" }
                if ((state.tasks.singleOrNull()?.bytes ?: 0L) / 1048576 != lastProgress) {
                    lastProgress = (state.tasks.singleOrNull()?.bytes ?: 0L) / 1048576
                    report("Live background progress: bytes=${(state.tasks.singleOrNull()?.bytes ?: 0L)}, total=${(state.tasks.singleOrNull()?.total ?: 0L)}.")
                }
                SystemClock.sleep(100)
            }
            val saved = application.downloads.open(checkNotNull(id), JobControl())
            check(saved.packageName == "org.fossify.math" && saved.files.isNotEmpty())
            check(DownloadTaskStore(application).readAll().isEmpty()) { "Completed task record remains" }
            return "Live background pause, explicit continue, network loss, manual retry and APK hashes passed: bytes=${saved.size}, files=${saved.files.size}.\n"
        } finally {
            instrumentation.runOnMainSync {
                if (id != null && play.state.tasks.singleOrNull()?.id == id) play.cancel(checkNotNull(id))
                activity.finish()
            }
            await { !play.state.busy && !play.state.backgroundDownload }
            if (id != null && application.downloads.list().any { it.libraryId == id }) {
                instrumentation.runOnMainSync { play.delete(id!!) }
                await { !play.state.libraryBusy }
            }
            shell("svc wifi disable")
            shell("svc data disable")
            notifications.cancel(22)
        }
    }

    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val application = instrumentation.targetContext.applicationContext as PushApplication
        val play = application.play
        val manager = application.getSystemService(ConnectivityManager::class.java)
        val notifications = application.getSystemService(NotificationManager::class.java)
        check(manager.activeNetwork == null) { "Offline service check requires networking to be disabled" }
        check(DownloadTaskStore(application).readAll().isEmpty()) { "A user download task is already present" }
        val activity = instrumentation.startActivitySync(Intent(application, MainActivity::class.java)
            .putExtra("show_downloads", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val fixture = App(packageName = "dev.tvapkpush.fixture.background", displayName = "Background test")
        var page: PlayPage? = null
        var id: String? = null
        fun stopped(phase: DownloadPhase) = await {
            play.state.tasks.singleOrNull()?.phase == phase && !play.state.busy && !play.state.backgroundDownload &&
                notifications.activeNotifications.none { it.id == 21 }
        }
        try {
            await { !play.state.busy && !play.state.backgroundDownload }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { page = PlayPage(activity, null, {}, {}).also { it.start() } }
            report("Checking that offline failure stops the service and retains a manual retry action.")
            instrumentation.runOnMainSync { play.download(fixture); id = play.state.tasks.singleOrNull()?.id }
            check(id != null) { "Download task was not created" }
            stopped(DownloadPhase.FAILED)
            check(DownloadTaskStore(application).readAll().singleOrNull()?.id == id) { "Failed task was not persisted" }
            instrumentation.runOnMainSync { tap(checkNotNull(page).view, "重试") }
            stopped(DownloadPhase.FAILED)
            check(play.state.tasks.singleOrNull()?.id == id) { "Manual retry replaced the pending task" }
            val staleCancel = action(notifications, "取消")
            staleCancel.send()
            await { play.state.tasks.singleOrNull() == null && !play.state.busy }
            await { notifications.activeNotifications.none { it.id == 21 } }
            check(DownloadTaskStore(application).readAll().isEmpty()) { "Cancelled task would restart after process recreation" }

            report("Pausing before the service starts and checking paused-file cleanup and stale actions.")
            instrumentation.runOnMainSync {
                play.download(fixture)
                id = play.state.tasks.singleOrNull()?.id
                tap(checkNotNull(page).view, "暂停")
            }
            stopped(DownloadPhase.PAUSED)
            val folder = application.downloads.staging(checkNotNull(id))
            File(folder, "base.apk").writeBytes(ByteArray(32))
            val staleResume = action(notifications, "继续")
            staleCancel.send()
            SystemClock.sleep(1000)
            check(play.state.tasks.singleOrNull()?.id == id && play.state.tasks.singleOrNull()?.phase == DownloadPhase.PAUSED) { "Stale cancellation affected another task" }
            instrumentation.runOnMainSync { activity.finish() }
            check(!play.state.backgroundDownload) { "Paused task retained an active service" }
            action(notifications, "取消").send()
            await { play.state.tasks.singleOrNull() == null && !play.state.busy }
            check(!folder.exists() && DownloadTaskStore(application).readAll().singleOrNull() == null) { "Paused cancellation retained unfinished files" }
            staleResume.send()
            SystemClock.sleep(1000)
            check(play.state.tasks.singleOrNull() == null && DownloadTaskStore(application).readAll().singleOrNull() == null) { "Stale resume recreated a cancelled task" }
            await { notifications.activeNotifications.none { it.id == 21 } }
            return "Offline failure, explicit retry, queued pause, notification cancellation, partial cleanup and stale action isolation passed.\n"
        } finally {
            instrumentation.runOnMainSync {
                if (id != null && play.state.tasks.singleOrNull()?.id == id) play.cancel(checkNotNull(id))
                page?.stop(false)
                page?.destroy()
                activity.finish()
            }
            await { play.state.tasks.singleOrNull()?.id != id || id == null }
            notifications.cancel(22)
        }
    }

    private fun action(manager: NotificationManager, title: String): PendingIntent {
        fun find() = manager.activeNotifications.flatMap { it.notification.actions?.toList().orEmpty() }.firstOrNull { it.title == title }
        await { find() != null }
        return checkNotNull(find()).actionIntent
    }

    private fun tap(root: View, title: String) {
        fun views(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
        }
        val button = views(root).filterIsInstance<Button>().first { it.visibility == View.VISIBLE && it.text == title }
        check(button.isEnabled && button.performClick()) { "Download action is unavailable: $title" }
    }

    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Background service check timed out" }
            SystemClock.sleep(50)
        }
    }
}
