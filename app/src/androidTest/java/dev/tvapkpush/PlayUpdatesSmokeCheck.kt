package dev.tvapkpush

import android.app.Instrumentation
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.BundleCursor
import com.aurora.gplayapi.model.StreamBundle
import com.aurora.gplayapi.model.StreamCluster
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PlayUpdatesSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "play-updates-$token").apply { mkdirs() }
        val preferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token.$name".also { preferences.add(it) }, mode)
            override fun startForegroundService(service: Intent) = ComponentName(base, "UpdatesFixtureService")
        }
        val library = DownloadLibrary(context)
        val source = File(base.applicationInfo.sourceDir)
        val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
        val hash = VerifiedDownload.digest(source, JobControl())
        val id = UUID.randomUUID().toString()
        val metadataCalls = AtomicInteger()
        val holdMetadata = AtomicBoolean(false)
        val metadataRunning = AtomicBoolean(false)
        val failMetadata = AtomicBoolean(false)
        val finishUpdate = AtomicBoolean(false)
        val finishResult = AtomicBoolean(false)
        var controller: PlayController? = null
        var activity: MainActivity? = null
        var page: PlayPage? = null
        fun <T> main(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun await(condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (!main(condition)) {
                check(SystemClock.uptimeMillis() < deadline) { "Updates check timed out" }
                SystemClock.sleep(30)
            }
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun find(view: View, text: String) = descendants(view).first { it.isShown && it is TextView && it.text.toString() == text }
        try {
            val repository = object : PlayRepository(context, library) {
                override fun details(packageName: String, profile: DeviceProfile, control: JobControl): App {
                    metadataCalls.incrementAndGet()
                    metadataRunning.set(true)
                    try {
                        while (holdMetadata.get()) { control.check(); SystemClock.sleep(20) }
                        if (failMetadata.get()) throw IOException("Update metadata fixture failure")
                        return App(packageName = packageName, displayName = "Saved APK", versionCode = version + 1, isFree = true)
                    } finally { metadataRunning.set(false) }
                }
                override fun search(query: String, next: BundleCursor?, control: JobControl) = StreamBundle(query,
                    clusters = listOf(StreamCluster(query, apps = (1..18).map {
                        App(packageName = "dev.fixture.result$it", displayName = "Result $it", isFree = true)
                    } + App(packageName = base.packageName, displayName = "Saved result", isFree = true))))
                override fun download(task: DownloadTask, control: JobControl, progress: (String, Long, Long) -> Unit): dev.tvapkpush.core.ApkFile {
                    var bytes = 0L
                    while (!(if (task.packageName == base.packageName) finishUpdate.get() else finishResult.get())) {
                        control.check()
                        progress("Downloading fixture", (++bytes).coerceAtMost(900), 1024)
                        SystemClock.sleep(150)
                    }
                    return library.list().single().copy(libraryId = task.id, packageName = task.packageName,
                        versionCode = version + 1, label = if (task.packageName == base.packageName) "Updated APK" else task.label)
                }
            }
            val profile = repository.profiles.first { it.id == "native" }
            val other = repository.profiles.first { it.id != "native" }
            val staging = library.staging(id)
            source.copyTo(File(staging, "base.apk"))
            val original = library.save(id, staging, "Saved APK", base.packageName, version,
                PlayRepository.profileLabel(profile), listOf(hash), JobControl())
            val play = main { PlayController(context, library, repository, { true }) }.also { controller = it }
            await { !play.state.busy }
            report("Checking version discovery, throttling and metadata failure isolation.")
            main { check(play.checkUpdates()); play.search("fixtures") }
            await { !play.state.checkingUpdates && !play.state.searching }
            main {
                check(play.state.updateFor(original)?.app?.versionCode == version + 1)
                check(!play.checkUpdates()) { "Cached metadata was requested again" }
                check(play.state.tasks.isEmpty()) { "Update discovery started a download" }
            }
            check(metadataCalls.get() == 1)

            val current = (instrumentation.startActivitySync(Intent(base, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also { activity = it }
            val ui = main {
                (current.application as PushApplication).play.leaveForeground()
                PlayPage(current, null, {}, {}, play).also { page = it; current.setContentView(it.view); it.start() }
            }
            instrumentation.waitForIdleSync()
            report("Checking saved-first sorting and retained result views during queue transitions.")
            val resultLabel = main { find(ui.view, "Result 2") }
            val resultRow = main { resultLabel.parent.parent as ViewGroup }
            val image = main { descendants(resultRow).filterIsInstance<ImageView>().first() }
            var detachments = 0
            main { resultRow.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { detachments++ }
            }) }
            val scroll = main { descendants(ui.view).filterIsInstance<ScrollView>().single() }
            main {
                val savedRow = find(ui.view, "Saved result").parent.parent as View
                check((savedRow.parent as ViewGroup).indexOfChild(savedRow) == 0)
                check(descendants(savedRow).filterIsInstance<TextView>().any { it.text.toString() == "更新" })
                scroll.scrollTo(0, (420 * current.resources.displayMetrics.density).toInt())
            }
            instrumentation.waitForIdleSync()
            fun windowY(view: View) = IntArray(2).also(view::getLocationInWindow)[1]
            val anchor = main { find(ui.view, "Result 7") }
            val anchorY = main { windowY(anchor) }
            val searchOrder = main { play.state.apps.map { it.packageName } }
            main { play.download(play.state.apps.first { it.displayName == "Result 18" }); play.serviceStarted({}) }
            await { play.state.tasks.firstOrNull()?.bytes?.let { it >= 3 } == true }
            instrumentation.waitForIdleSync()
            main {
                check(find(ui.view, "Result 2") === resultLabel && resultLabel.parent.parent === resultRow)
                check(descendants(resultRow).filterIsInstance<ImageView>().first() === image && detachments == 0)
                check(kotlin.math.abs(windowY(anchor) - anchorY) <= 2) { "Queue insertion moved the scroll anchor" }
                val activeRow = find(ui.view, "Result 18").parent.parent as View
                check((activeRow.parent as ViewGroup).indexOfChild(activeRow) == 18)
                check(play.state.apps.map { it.packageName } == searchOrder)
                play.pauseDownload(play.state.tasks.single().id)
            }
            await { play.state.tasks.single().phase == DownloadPhase.PAUSED }
            main {
                check(find(ui.view, "Result 2") === resultLabel && detachments == 0)
                ui.showLibrary()
            }
            instrumentation.waitForIdleSync()
            main { check(windowY(find(ui.view, "Saved APK")) < windowY(find(ui.view, "Result 18"))) }

            report("Checking completion keeps result order until the next search.")
            main {
                find(ui.view, "Google Play").performClick()
                play.retryDownload(play.state.tasks.single().id)
                play.serviceStarted({})
            }
            instrumentation.waitForIdleSync()
            val completionAnchorY = main { windowY(anchor) }
            finishResult.set(true)
            await { play.state.tasks.isEmpty() }
            instrumentation.waitForIdleSync()
            main {
                check(play.state.apps.map { it.packageName } == searchOrder) { "Completion reordered search results" }
                val completedRow = find(ui.view, "Result 18").parent.parent as ViewGroup
                check((completedRow.parent as ViewGroup).indexOfChild(completedRow) == 18)
                check(descendants(completedRow).filterIsInstance<TextView>().any { it.text.toString() == "推送" })
                check(find(ui.view, "Result 2") === resultLabel && detachments == 0)
                check(kotlin.math.abs(windowY(anchor) - completionAnchorY) <= 2) { "Completion moved the scroll anchor" }
                play.search("fixtures")
            }
            await { !play.state.searching }
            instrumentation.waitForIdleSync()
            main {
                check(play.state.apps.take(2).map { it.packageName } == listOf("dev.fixture.result18", base.packageName))
                val completedRow = find(ui.view, "Result 18").parent.parent as View
                check((completedRow.parent as ViewGroup).indexOfChild(completedRow) == 0) { "New search did not prioritize completed downloads" }
            }

            report("Checking original-profile updates, duplicate clicks and retained older files.")
            main {
                play.selectProfile(other.id)
                play.updateDownload(id)
                play.updateDownload(id)
                play.serviceStarted({})
            }
            await { play.state.tasks.any { it.packageName == base.packageName && it.phase == DownloadPhase.RUNNING } }
            main {
                val updates = play.state.tasks.filter { it.packageName == base.packageName }
                check(updates.size == 1 && updates.single().profile.fingerprint == profile.fingerprint)
                check(play.state.profileId == other.id)
            }
            finishUpdate.set(true)
            await { play.state.tasks.none { it.packageName == base.packageName } }
            main { check(play.state.updateFor(original) == null && play.state.downloads.size == 3) }
            check(VerifiedDownload.digest(library.open(id, JobControl()).file, JobControl()) == hash)
            failMetadata.set(true)
            main { check(play.checkUpdates(force = true)) }
            await { !play.state.checkingUpdates }
            main { check(play.state.updateError.isNotBlank() && !play.state.error && !play.state.busy) }
            val checked = metadataCalls.get()
            main { check(!play.checkUpdates()) }
            check(metadataCalls.get() == checked)
            failMetadata.set(false)
            holdMetadata.set(true)
            main { check(play.checkUpdates(force = true)) }
            await { metadataRunning.get() }
            main { play.leaveForeground() }
            await { !metadataRunning.get() }
            main { check(!play.state.checkingUpdates) }
            return "Update discovery, version/profile matching, manual retry, duplicate prevention, retained files, search-time sorting, stable completion order/views/icons/scroll and cancellation passed.\n"
        } finally {
            holdMetadata.set(false)
            controller?.let { play ->
                main { page?.stop(false); play.leaveForeground(); play.state.tasks.toList().forEach { play.cancel(it.id) } }
                await { play.state.tasks.isEmpty() }
                main { play.serviceStopped() }
            }
            activity?.let { main { it.finish() } }
            if (library.contains(id)) library.delete(id) else library.discard(id)
            preferences.forEach { base.deleteSharedPreferences(it) }
            root.deleteRecursively()
        }
    }
}
