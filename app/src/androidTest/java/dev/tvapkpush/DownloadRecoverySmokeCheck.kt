package dev.tvapkpush

import android.app.Instrumentation
import android.content.ContextWrapper
import android.util.AtomicFile
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.DownloadPhase
import dev.tvapkpush.play.DownloadTask
import dev.tvapkpush.play.DownloadTaskStore
import dev.tvapkpush.play.PlayRepository
import dev.tvapkpush.play.VerifiedDownload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object DownloadRecoverySmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "download-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = directory
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
        val library = DownloadLibrary(context)
        try {
            val repository = PlayRepository(context, library)
            val id = UUID.randomUUID().toString()
            val task = DownloadTask(id, base.packageName, "Recovery fixture", repository.profiles.first(),
                phase = DownloadPhase.WAITING, bytes = 16, total = 1024, iconUrl = "https://play-lh.googleusercontent.com/recovery-fixture")
            val store = DownloadTaskStore(context)
            store.saveAll(listOf(task))
            val partial = library.staging(id)
            File(partial, "base.apk").writeBytes(ByteArray(16) { it.toByte() })
            val orphan = library.staging(UUID.randomUUID().toString())
            File(orphan, "base.apk").writeBytes(byteArrayOf(1))
            report("Checking retained progress and device profile across task reload.")
            val restored = checkNotNull(DownloadTaskStore(context).readAll().singleOrNull())
            check(restored.id == id && restored.bytes == 16L)
            check(restored.profile.fingerprint == task.profile.fingerprint)
            check(restored.iconUrl == task.iconUrl && restored.restored().iconUrl == task.iconUrl)
            val oldRecord = File(directory, "play-download-task.json")
            oldRecord.writeText(JSONObject(oldRecord.readText()).getJSONArray("tasks").getJSONObject(0).apply { remove("iconUrl") }.toString() + "\n")
            check(store.readAll().singleOrNull()?.iconUrl == "") { "Older download records did not load" }
            store.saveAll(listOf(task))
            library.discardPartial(setOf(restored.id))
            check(File(partial, "base.apk").length() == 16L && !orphan.exists())

            report("Checking manual recovery states and actual retained file progress.")
            for (phase in DownloadPhase.entries) {
                store.saveAll(listOf(task.copy(phase = phase)))
                val expected = when (phase) {
                    DownloadPhase.QUEUED, DownloadPhase.RUNNING, DownloadPhase.WAITING -> DownloadPhase.FAILED
                    DownloadPhase.PAUSING -> DownloadPhase.PAUSED
                    else -> phase
                }
                check(store.readAll().singleOrNull()?.restored()?.phase == expected) { "Unexpected recovered download phase: $phase" }
            }
            store.saveAll(listOf(task))
            val plan = JSONObject().put("files", JSONArray().put(JSONObject().put("size", 1024)).put(JSONObject().put("size", 32)))
            File(partial, "plan.json").writeText(plan.toString())
            File(partial, "split-1.apk").writeBytes(ByteArray(32))
            check(library.partialProgress(id) == (48L to 1056L)) { "Retained file progress differs from actual bytes" }
            File(partial, "split-1.apk").delete()
            File(partial, "plan.json").delete()

            report("Checking task recovery after an interrupted atomic write.")
            AtomicFile(File(directory, "play-download-task.json")).startWrite().use { it.write("incomplete".toByteArray()) }
            check(DownloadTaskStore(context).readAll().singleOrNull()?.id == id)

            report("Checking completed APK reuse before the pending task is cleared.")
            val file = File(partial, "base.apk")
            File(base.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            val control = JobControl()
            val version = base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode
            val saved = library.save(id, partial, "Recovery fixture", base.packageName, version,
                PlayRepository.profileLabel(task.profile), listOf(VerifiedDownload.digest(file, control)), control)
            val recovered = repository.download(restored, control) { _, _, _ -> error("Completed download requested network work") }
            check(recovered.libraryId == saved.libraryId && library.list().size == 1)
            store.clear()
            check(DownloadTaskStore(context).readAll().singleOrNull() == null)
            return "Manual recovery states, profile snapshot, actual partial progress, atomic write recovery and completed APK reuse passed.\n"
        } finally {
            library.list().forEach { library.delete(checkNotNull(it.libraryId)) }
            directory.deleteRecursively()
        }
    }
}
