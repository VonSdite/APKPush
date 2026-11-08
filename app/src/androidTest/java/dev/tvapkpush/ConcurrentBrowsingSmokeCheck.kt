package dev.tvapkpush

import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.BundleCursor
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.DownloadPhase
import dev.tvapkpush.play.DownloadTask
import dev.tvapkpush.play.DownloadTaskStore
import dev.tvapkpush.play.PlayController
import dev.tvapkpush.play.PlayRepository
import dev.tvapkpush.play.VerifiedDownload
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ConcurrentBrowsingSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "concurrent-browsing-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun startForegroundService(service: Intent) = ComponentName(base, "FixtureService")
        }
        val library = DownloadLibrary(context)
        val source = File(base.applicationInfo.sourceDir)
        val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
        val hash = VerifiedDownload.digest(source, JobControl())
        val ids = mutableSetOf<String>()
        fun save(id: String): ApkFile {
            val folder = library.staging(id)
            source.copyTo(File(folder, "base.apk"), overwrite = true)
            return library.save(id, folder, "Browsing fixture", base.packageName, version,
                "Concurrency test", listOf(hash), JobControl())
        }
        val finishDownload = AtomicBoolean(false)
        val searches = AtomicInteger()
        val repository = object : PlayRepository(context, library) {
            override fun search(query: String, next: BundleCursor?, control: JobControl): StreamBundle {
                searches.incrementAndGet()
                while (query.startsWith("slow")) { control.check(); SystemClock.sleep(20) }
                if (query == "fail") throw IOException("Search fixture failure")
                return StreamBundle(query, clusters = listOf(StreamCluster(query,
                    apps = listOf(App(packageName = "dev.fixture.$query", displayName = query)))))
            }
            override fun download(task: DownloadTask, control: JobControl, progress: (String, Long, Long) -> Unit): ApkFile {
                progress("Downloading fixture", 1024, 4096)
                while (!finishDownload.get()) { control.check(); SystemClock.sleep(20) }
                control.check()
                return save(task.id)
            }
        }
        var play: PlayController? = null
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (!main(condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Concurrent browsing check timed out" }
                SystemClock.sleep(30)
            }
        }
        try {
            val keptId = UUID.randomUUID().toString().also { ids.add(it); save(it) }
            val deletedId = UUID.randomUUID().toString().also { ids.add(it); save(it) }
            val controller = main { PlayController(context, library, repository) }.also { play = it }
            await { !controller.state.busy && controller.state.profiles.isNotEmpty() }
            val app = App(packageName = base.packageName, displayName = "Download fixture")
            main { controller.download(app); controller.serviceStarted({}) }
            val taskId = main { checkNotNull(controller.state.tasks.singleOrNull()).id }.also { ids.add(it) }
            await { controller.state.downloading && (controller.state.tasks.singleOrNull()?.bytes ?: 0L) == 1024L }

            report("Checking search replacement and errors while a download remains active.")
            main { controller.search("slowfirst") }
            await { searches.get() == 1 }
            main { controller.search("latest") }
            await { !controller.state.searching && controller.state.apps.singleOrNull()?.displayName == "latest" }
            main {
                check(controller.state.tasks.singleOrNull()?.id == taskId && controller.state.downloading)
                check((controller.state.tasks.singleOrNull()?.bytes ?: 0L) == 1024L && (controller.state.tasks.singleOrNull()?.total ?: 0L) == 4096L)
                check(controller.state.tasks.single().message == "Downloading fixture")
                controller.clearSearch()
                check(controller.state.query.isEmpty() && controller.state.apps.isEmpty() && controller.state.next == null)
                check(!controller.state.searched && controller.state.downloading)
                controller.search("fail")
            }
            await { !controller.state.searching && controller.state.searchError.isNotBlank() }
            main {
                check(controller.state.downloading && !controller.state.error)
                controller.clearSearch()
                check(controller.state.searchError.isEmpty() && controller.state.query.isEmpty())
            }

            report("Checking search clearing and immediate replacement without interrupting the download.")
            val previousSearches = searches.get()
            main { controller.search("slowclear") }
            await { searches.get() > previousSearches }
            main {
                controller.clearSearch()
                check(!controller.state.searching && !controller.state.searched && controller.state.apps.isEmpty())
                check(controller.state.query.isEmpty() && controller.state.searchError.isEmpty())
                controller.search("afterclear")
            }
            await { !controller.state.searching && controller.state.apps.singleOrNull()?.displayName == "afterclear" }
            main { check(controller.state.tasks.singleOrNull()?.id == taskId && controller.state.downloading) }

            report("Checking pause and browsing without automatic resumption or progress loss.")
            main { controller.search("slowpause"); controller.pauseDownload(taskId) }
            await { controller.state.tasks.singleOrNull()?.phase == DownloadPhase.PAUSED && !controller.state.busy }
            main { controller.cancelSearch(); controller.search("paused") }
            await { !controller.state.searching }
            main {
                check(controller.state.apps.single().displayName == "paused")
                check(controller.state.tasks.singleOrNull()?.id == taskId && controller.state.tasks.singleOrNull()?.phase == DownloadPhase.PAUSED)
                check((controller.state.tasks.singleOrNull()?.bytes ?: 0L) == 1024L && (controller.state.tasks.singleOrNull()?.total ?: 0L) == 4096L)
            }
            check(DownloadTaskStore(context).readAll().singleOrNull()?.bytes == 1024L)
            val push = main { PushController(context, library).also { it.useDownload(keptId) } }
            await { !push.state.busy }
            main { check(push.state.apk?.libraryId == keptId); push.clearApk(); controller.delete(deletedId) }
            await { !controller.state.libraryBusy }
            check(!library.contains(deletedId))

            report("Checking library deletion concurrent with completion and independent search cancellation.")
            main { controller.search("slowresume"); controller.retryDownload(taskId); controller.serviceStarted({}) }
            await { controller.state.downloading }
            main { controller.delete(keptId) }
            await { !controller.state.libraryBusy }
            finishDownload.set(true)
            await { controller.state.tasks.singleOrNull() == null && !controller.state.busy }
            main {
                check(controller.state.searching) { "Download completion cancelled the search" }
                check(controller.state.downloads.map { it.libraryId } == listOf(taskId)) { "Completion restored a deleted library item" }
                controller.cancelSearch()
            }
            check(DownloadTaskStore(context).readAll().singleOrNull() == null)

            report("Checking download cancellation leaves the search independently usable.")
            finishDownload.set(false)
            main { controller.download(app); controller.serviceStarted({}) }
            ids.add(main { checkNotNull(controller.state.tasks.singleOrNull()).id })
            await { controller.state.downloading }
            main { controller.search("slowcancel"); controller.state.tasks.toList().forEach { controller.cancel(it.id) } }
            await { controller.state.tasks.singleOrNull() == null && !controller.state.busy }
            main { check(controller.state.searching); controller.search("aftercancel") }
            await { !controller.state.searching }
            main { check(controller.state.apps.single().displayName == "aftercancel") }
            return "Concurrent search replacement/errors/clearing, pause, retained progress, library selection/deletion, completion merging and independent cancellation passed.\n"
        } finally {
            play?.let { controller ->
                main { controller.cancelSearch(); if (controller.state.tasks.singleOrNull() != null) controller.state.tasks.toList().forEach { controller.cancel(it.id) } }
                await { !controller.state.busy && !controller.state.libraryBusy && controller.state.tasks.isEmpty() }
                main { controller.serviceStopped() }
            }
            ids.forEach { if (library.contains(it)) library.delete(it) else library.discard(it) }
            root.deleteRecursively()
        }
    }
}
