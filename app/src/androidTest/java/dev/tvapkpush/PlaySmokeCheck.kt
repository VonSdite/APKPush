package dev.tvapkpush

import android.app.Instrumentation
import android.content.ContentValues
import android.provider.MediaStore
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.PlayRepository
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object PlaySmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val library = DownloadLibrary(instrumentation.targetContext)
        val repository = PlayRepository(instrumentation.targetContext, library)
        report("Loading native and bundled device profiles.")
        val profiles = repository.profiles
        check(profiles.any { it.id == "bravia_vu2" }) { "TV download profile is missing" }
        val control = JobControl()
        report("Searching Google Play for Fossify Calculator.")
        val result = repository.search("Fossify Calculator", null, control)
        check(result.clusters.flatMap { it.apps }.any { it.packageName == "org.fossify.math" }) { "Live Play search did not find the expected app" }
        report("Resolving anonymous delivery for the native device profile.")
        var lastProgress = -1
        val apk = repository.download("org.fossify.math", profiles.first(), control) { _, completed, total ->
            if (total > 0) {
                val progress = (completed * 4 / total).toInt()
                if (progress != lastProgress) {
                    lastProgress = progress
                    report("Downloading APK files: bytes=$completed, total=$total.")
                }
            }
        }
        try {
            report("Verifying persisted download and file hashes.")
            val restored = DownloadLibrary(instrumentation.targetContext).open(apk.libraryId!!, control)
            check(restored.packageName == "org.fossify.math") { "Downloaded package mismatch" }
            check(restored.files.size == apk.files.size && restored.size == apk.size) { "Download persistence mismatch" }
            val resolver = instrumentation.targetContext.contentResolver
            report("Exporting and verifying the downloaded APK files.")
            val export = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "apk-push-smoke-${apk.libraryId}.${if (apk.splits.isEmpty()) "apk" else "apks"}")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Export document creation failed")
            try {
                library.export(apk.libraryId, export, control)
                if (apk.splits.isEmpty()) {
                    resolver.openInputStream(export)!!.use { check(it.readBytes().size.toLong() == apk.size) { "Export size mismatch" } }
                } else {
                    val names = mutableSetOf<String>()
                    ZipInputStream(resolver.openInputStream(export)!!).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            names.add(entry.name)
                            val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
                            val original = apk.files.single { it.name == entry.name }.readBytes()
                            check(digest.contentEquals(MessageDigest.getInstance("SHA-256").digest(original))) { "Exported split mismatch" }
                        }
                    }
                    check(names == apk.files.map { it.name }.toSet()) { "Export omitted splits" }
                }
            } finally { resolver.delete(export, null, null) }
            report("Document export verification passed.")
            return "Live Play search, anonymous delivery, verified download, persistent library and document export passed: files=${apk.files.size}, bytes=${apk.size}.\n"
        } finally { library.delete(apk.libraryId!!) }
    }
}
