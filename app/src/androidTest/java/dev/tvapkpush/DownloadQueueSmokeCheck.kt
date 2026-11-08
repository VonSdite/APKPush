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
import dev.tvapkpush.play.DownloadAction
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.DownloadPhase
import dev.tvapkpush.play.DownloadTask
import dev.tvapkpush.play.DownloadTaskStore
import dev.tvapkpush.play.PlayController
import dev.tvapkpush.play.PlayRepository
import dev.tvapkpush.play.VerifiedDownload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object DownloadQueueSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "download-queue-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun startForegroundService(service: Intent) = ComponentName(base, "QueueFixtureService")
        }
        val library = DownloadLibrary(context)
        val store = DownloadTaskStore(context)
        val online = AtomicBoolean(true)
        val started = Collections.synchronizedList(mutableListOf<String>())
        val complete = ConcurrentHashMap.newKeySet<String>()
        val fail = ConcurrentHashMap.newKeySet<String>()
        val holdExit = ConcurrentHashMap.newKeySet<String>()
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val owned = mutableSetOf<String>()
        val controllers = mutableListOf<PlayController>()
        val source = File(base.applicationInfo.sourceDir)
        val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
        val hash = VerifiedDownload.digest(source, JobControl())
        fun partial(id: String, bytes: Int) {
            val folder = library.staging(id)
            File(folder, "base.apk").writeBytes(ByteArray(bytes))
            File(folder, "plan.json").writeText(JSONObject().put("files", JSONArray().put(JSONObject().put("size", 8192))).toString() + "\n")
        }
        val repository = object : PlayRepository(context, library) {
            override fun search(query: String, next: BundleCursor?, control: JobControl) =
                StreamBundle(query, clusters = listOf(StreamCluster(query, apps = listOf(App(packageName = "dev.fixture.search", displayName = query)))))

            override fun download(task: DownloadTask, control: JobControl, progress: (String, Long, Long) -> Unit): ApkFile {
                val count = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                started.add(task.id)
                try {
                    partial(task.id, 1024)
                    progress("Downloading queue fixture", 1024, 8192)
                    while (task.id !in complete) {
                        control.check()
                        if (task.id in fail) throw IOException("Queue fixture failure")
                        SystemClock.sleep(20)
                    }
                    control.check()
                    val folder = library.staging(task.id)
                    source.copyTo(File(folder, "base.apk"), overwrite = true)
                    return library.save(task.id, folder, task.label, base.packageName, version,
                        PlayRepository.profileLabel(task.profile), listOf(hash), control)
                } finally {
                    while (task.id in holdExit) SystemClock.sleep(20)
                    running.decrementAndGet()
                }
            }
        }
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (!main(condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Download queue check timed out" }
                SystemClock.sleep(30)
            }
        }
        fun newController(): PlayController = main { PlayController(context, library, repository, online::get) }.also { controllers.add(it) }
        try {
            val play = newController()
            await { !play.state.busy && play.state.profiles.isNotEmpty() }
            report("Checking three-worker concurrency, FIFO admission and duplicate prevention.")
            val apps = (1..6).map { App(packageName = "dev.fixture.queue$it", displayName = "Queue $it") }
            main { apps.forEach(play::download); play.download(apps.first()) }
            val ids = main { play.state.tasks.map { it.id } }.also { owned.addAll(it) }
            check(ids.size == 6)
            main { play.serviceStarted({}) }
            await { running.get() == 3 && started.size == 3 && play.state.tasks.count { it.phase == DownloadPhase.RUNNING } == 3 }
            check(started.toSet() == ids.take(3).toSet())
            main { check(store.readAll().map { it.id } == ids) }
            main { play.search("while-running") }
            await { !play.state.searching && play.state.apps.singleOrNull()?.displayName == "while-running" }

            report("Checking queued pause/cancel and slot retention until a paused worker exits.")
            main { play.pauseDownload(ids[5]); play.cancel(ids[4]) }
            await { play.state.tasks.none { it.id == ids[4] } }
            holdExit.add(ids[0])
            main { play.pauseDownload(ids[0]) }
            SystemClock.sleep(250)
            main {
                check(play.state.tasks.first { it.id == ids[0] }.phase == DownloadPhase.PAUSING)
                check(play.state.tasks.first { it.id == ids[3] }.phase == DownloadPhase.QUEUED)
            }
            check(running.get() == 3 && ids[3] !in started)
            holdExit.remove(ids[0])
            await { play.state.tasks.first { it.id == ids[0] }.phase == DownloadPhase.PAUSED && ids[3] in started }
            main { check(store.readAll().first { it.id == ids[0] }.bytes == 1024L) }
            main { play.retryDownload(ids[0]); play.serviceStarted({}) }
            main { check(play.state.tasks.last().id == ids[0] && play.state.tasks.last().phase == DownloadPhase.QUEUED) }
            main { play.cancel(ids[1]) }
            await { started.count { it == ids[0] } == 2 && play.state.tasks.none { it.id == ids[1] } }
            check(peak.get() == 3)

            report("Checking completion admits the next task without restoring deleted library entries.")
            main { play.download(App(packageName = "dev.fixture.queue7", displayName = "Queue 7")); play.serviceStarted({}) }
            val seventh = main { play.state.tasks.last().id }.also { owned.add(it) }
            main { check(play.state.tasks.last().phase == DownloadPhase.QUEUED) }
            complete.add(ids[3])
            await { play.state.tasks.none { it.id == ids[3] } && seventh in started }
            main { play.delete(ids[3]) }
            await { !play.state.libraryBusy }
            check(!library.contains(ids[3]))

            report("Checking isolated failure and manual recovery after connectivity loss.")
            fail.add(ids[2])
            await { play.state.tasks.first { it.id == ids[2] }.phase == DownloadPhase.FAILED }
            main { check(play.state.tasks.first { it.id == seventh }.phase == DownloadPhase.RUNNING) }
            online.set(false)
            main { play.download(App(packageName = "dev.fixture.offline", displayName = "Offline fixture")) }
            val offline = main { play.state.tasks.last().id }.also { owned.add(it) }
            main { play.networkChanged() }
            await { !play.state.backgroundDownload && running.get() == 0 }
            main { check(play.state.tasks.first { it.id == offline }.phase == DownloadPhase.FAILED) }
            check(offline !in started)
            val attempts = started.size
            online.set(true)
            main { play.networkChanged() }
            SystemClock.sleep(250)
            check(started.size == attempts)
            main { play.retryDownload(ids[0]); play.serviceStarted({}) }
            await { play.state.tasks.first { it.id == ids[0] }.phase == DownloadPhase.RUNNING }
            complete.add(ids[0])
            await { play.state.tasks.none { it.id == ids[0] } }
            main {
                check(play.state.downloads.single().libraryId == ids[0])
                check(play.state.tasks.first { it.id == ids[5] }.phase == DownloadPhase.PAUSED)
                play.state.tasks.toList().forEach { play.cancel(it.id) }
            }
            await { play.state.tasks.isEmpty() }
            main { play.serviceStopped() }

            report("Checking queue recovery, retained partial files and task-specific notification actions.")
            val profile = repository.profiles.first()
            val restoredTasks = listOf(DownloadPhase.RUNNING, DownloadPhase.QUEUED, DownloadPhase.PAUSED, DownloadPhase.CANCELLED).mapIndexed { index, phase ->
                val id = UUID.randomUUID().toString().also { owned.add(it); partial(it, 300 + index) }
                DownloadTask(id, "dev.fixture.restore$index", "Restore $index", profile, phase = phase)
            }
            store.saveAll(restoredTasks)
            val orphan = library.staging(UUID.randomUUID().toString())
            val restored = newController()
            await { !restored.state.busy && restored.state.tasks.size == 3 }
            check(!orphan.exists())
            main {
                check(restored.state.tasks.map { it.phase } == listOf(DownloadPhase.FAILED, DownloadPhase.FAILED, DownloadPhase.PAUSED))
                check(restored.state.tasks.map { it.bytes } == listOf(300L, 301L, 302L))
                check(restored.state.tasks.all { it.profile.fingerprint == profile.fingerprint })
                restored.serviceStarted({}, "stale-task", DownloadAction.RESUME)
            }
            check(started.size == attempts + 1)
            main { restored.serviceStarted({}, restoredTasks[1].id, DownloadAction.RESUME) }
            await { restored.state.tasks.first { it.id == restoredTasks[1].id }.phase == DownloadPhase.RUNNING }
            main {
                check(restored.state.tasks.first { it.id == restoredTasks[0].id }.phase == DownloadPhase.FAILED)
                restored.serviceStarted({}, restoredTasks[1].id, DownloadAction.PAUSE)
            }
            await { restored.state.tasks.first { it.id == restoredTasks[1].id }.phase == DownloadPhase.PAUSED }
            check(peak.get() == 3)
            return "Three-download cap, FIFO queue, duplicate prevention, independent pause/cancel/failure, manual retry, queue recovery and retained files passed.\n"
        } finally {
            holdExit.clear()
            controllers.forEach { play ->
                main { play.cancelSearch(); play.state.tasks.toList().forEach { play.cancel(it.id) } }
                await { play.state.tasks.isEmpty() && !play.state.busy }
                main { play.serviceStopped() }
            }
            owned.forEach { if (library.contains(it)) library.delete(it) else library.discard(it) }
            store.clear()
            root.deleteRecursively()
        }
    }
}
