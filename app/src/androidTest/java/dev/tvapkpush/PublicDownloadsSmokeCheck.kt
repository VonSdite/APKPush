package dev.tvapkpush

import android.app.Instrumentation
import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import dev.tvapkpush.data.ApkImporter
import dev.tvapkpush.play.DownloadLibrary
import dev.tvapkpush.play.VerifiedDownload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object PublicDownloadsSmokeCheck {
    fun run(instrumentation: Instrumentation, report: (String) -> Unit): String {
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "public-download-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val library = DownloadLibrary(context)
        val ids = mutableListOf<String>()
        val extraUris = mutableListOf<Uri>()
        val directSources = mutableListOf<dev.tvapkpush.core.ApkSource>()
        val resolver = context.contentResolver
        val source = File(base.applicationInfo.sourceDir)
        val control = JobControl()
        val hash = VerifiedDownload.digest(source, control)
        val info = base.packageManager.getPackageInfo(base.packageName, 0)
        fun folder(id: String) = File(context.filesDir, "play-downloads/$id")
        fun record(id: String) = JSONObject(File(folder(id), "download.json").readText())
        fun uri(id: String) = Uri.parse(record(id).getString("publicUri"))
        fun exists(uri: Uri): Boolean {
            val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            try { return cursor?.moveToFirst() == true } finally { cursor?.close() }
        }
        fun checksum(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256")
            resolver.openInputStream(uri)!!.use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun legacy(): String {
            val id = UUID.randomUUID().toString().also { ids.add(it) }
            val destination = folder(id).apply { mkdirs() }
            source.copyTo(File(destination, "base.apk"))
            val record = JSONObject().put("id", id).put("label", "Public storage fixture").put("packageName", base.packageName)
                .put("versionCode", info.longVersionCode).put("versionName", info.versionName).put("minSdk", 29)
                .put("abis", JSONArray()).put("profile", "Storage test")
                .put("files", JSONArray().put(JSONObject().put("name", "base.apk").put("size", source.length()).put("sha256", hash)))
            File(destination, "download.json").writeText(record.toString() + "\n")
            return id
        }
        fun pending(id: String, complete: Boolean): Uri {
            val result = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${base.packageName}-${info.longVersionCode}-${id.take(8)}.apk")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/APK Push/")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Pending download creation failed")
            extraUris.add(result)
            resolver.openOutputStream(result)!!.use { output ->
                if (complete) source.inputStream().use { it.copyTo(output) } else output.write(byteArrayOf(1, 2, 3))
            }
            File(folder(id), "publication.json").writeText(JSONObject().put("uri", result.toString()).toString() + "\n")
            return result
        }
        try {
            report("Checking direct-source rejection for pipes and offset assets.")
            android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.content.res.AssetFileDescriptor(descriptor, 1, source.length() - 1).use { asset ->
                    check(dev.tvapkpush.data.DirectApkSource.from(asset) == null)
                }
            }
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            try {
                android.content.res.AssetFileDescriptor(pipe[0], 0, android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH).use { asset ->
                    check(dev.tvapkpush.data.DirectApkSource.from(asset) == null)
                }
            } finally { pipe.forEach { it.close() } }
            report("Checking public split archives and preparation without private download copies.")
            val splitId = UUID.randomUUID().toString().also { ids.add(it) }
            val staging = library.staging(splitId)
            source.copyTo(File(staging, "base.apk"))
            source.copyTo(File(staging, "split-1.apk"))
            val saved = library.save(splitId, staging, "Public split fixture", base.packageName, info.longVersionCode,
                "Storage test", listOf(hash, hash), control)
            val splitUri = uri(splitId)
            check(saved.size == source.length() * 2 && saved.downloadLocation.startsWith("Download/APK Push/"))
            check(saved.downloadLocation.endsWith(".apks") && !saved.file.exists())
            check(folder(splitId).list()!!.toSet() == setOf("download.json", "icon.png")) { "Unexpected private download files" }
            check(BitmapFactory.decodeFile(saved.iconFile?.path)?.width == 192) { "Downloaded icon was not saved" }
            val cursor = resolver.query(splitUri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!
            try { check(cursor.moveToFirst() && cursor.getInt(0) == 0) { "Completed download is hidden" } } finally { cursor.close() }
            val opened = library.open(splitId, control)
            check(opened.files.size == 2 && opened.files.all { VerifiedDownload.digest(it, control) == hash })
            val exported = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "public-export-test-$splitId.apks")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/APK Push/")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Export fixture creation failed")
            extraUris.add(exported)
            library.export(splitId, exported, control)
            check(checksum(exported) == record(splitId).getString("publicSha256")) { "Export changed the public archive" }
            library.release(splitId)
            check(!opened.file.exists() && exists(splitUri))
            check(library.open(splitId, control).files.all { it.length() == source.length() })
            library.delete(splitId)
            check(!exists(splitUri) && !folder(splitId).exists()) { "Deletion retained the public download" }

            report("Checking migration, stable download identity and repeated startup.")
            val legacyId = legacy()
            library.migrate(control)
            val legacyUri = uri(legacyId)
            val migrated = library.open(legacyId, control)
            check(migrated.libraryId == legacyId && migrated.downloadLocation.endsWith(".apk"))
            check(VerifiedDownload.digest(migrated.file, control) == hash)
            check(!File(folder(legacyId), "base.apk").exists())
            library.migrate(control)
            check(uri(legacyId) == legacyUri) { "Repeated migration duplicated the download" }

            report("Checking offline icon backfill without modifying public downloads.")
            val originalHash = checksum(legacyUri)
            check(File(folder(legacyId), "icon.png").delete())
            library.migrate(control)
            check(File(folder(legacyId), "icon.png").length() > 0 && checksum(legacyUri) == originalHash)
            library.release(legacyId)

            report("Checking repeated selection, missing preparation and clearing selection.")
            lateinit var push: PushController
            instrumentation.runOnMainSync { push = PushController(context, library); push.useDownload(legacyId) }
            fun idle() {
                val deadline = SystemClock.uptimeMillis() + 30_000
                var busy = true
                while (busy && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync { busy = push.state.busy }
                    if (busy) SystemClock.sleep(50)
                }
                check(!busy) { "Selection did not finish" }
            }
            idle()
            val selected = checkNotNull(push.state.apk)
            check(selected.source != null && selected.file.path.startsWith("/proc/self/fd/")) { "Public APK was copied instead of opened directly" }
            directSources.add(selected.source)
            val uploadedHash = MessageDigest.getInstance("SHA-256")
            selected.source.openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; uploadedHash.update(buffer, 0, count) }
            }
            check(uploadedHash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash)
            check(selected.sha256 == hash && !File(context.cacheDir, "play-apks/$legacyId").exists())
            instrumentation.runOnMainSync {
                push.useDownload(legacyId)
                check(!push.state.busy && push.state.apk === selected) { "Repeated selection reloaded the APK" }
            }
            selected.source.close()
            instrumentation.runOnMainSync { push.useDownload(legacyId) }
            idle()
            check(push.state.apk !== selected && push.state.apk!!.file.isFile) { "Missing cache was reused" }
            instrumentation.runOnMainSync { push.clearApk() }
            check(push.state.apk == null && !selected.file.exists() && exists(legacyUri)) { "Clearing selection removed the download" }

            report("Checking same URI reuse and changed source detection.")
            val importer = ApkImporter(context)
            val initialImport = importer.import(legacyUri, control) { _, _ -> }
            initialImport.source?.let(directSources::add)
            // 文件提供方可在首次读取后更新文件元数据，复用检查使用再次打开后的来源。
            val imported = importer.import(legacyUri, control, initialImport) { _, _ -> }
            imported.source?.let(directSources::add)
            check(importer.import(legacyUri, control, imported) { _, _ -> error("Unchanged APK was copied again") } === imported)
            val beforeWrite = checkNotNull(library.openForPush(legacyId, control).source)
            directSources.add(beforeWrite)
            resolver.openOutputStream(legacyUri, "wt")!!.use { output -> source.inputStream().use { it.copyTo(output) } }
            check(runCatching { beforeWrite.checkUnchanged() }.isFailure) { "Modified direct source was accepted" }
            beforeWrite.close()
            var copied = 0L
            val changed = importer.import(legacyUri, control, imported) { bytes, _ -> copied = bytes }
            check(changed !== imported && copied == if (changed.source == null) source.length() else 0L) { "Changed source was reused" }
            changed.source?.let(directSources::add)
            imported.source?.let { check(runCatching { it.checkUnchanged() }.isFailure) { "Modified source was accepted" } }
            if (changed.source != null) changed.source.close() else check(changed.file.delete())
            val reopened = importer.import(legacyUri, control, changed) { _, _ -> }
            check(reopened !== changed) { "Closed import was reused" }
            reopened.source?.let(directSources::add)

            report("Checking publication recovery after the durable record is committed.")
            val committedId = legacy()
            val committedUri = pending(committedId, complete = true)
            File(folder(committedId), "download.json").writeText(record(committedId).put("publicUri", committedUri.toString())
                .put("publicSize", source.length()).put("publicSha256", hash).toString() + "\n")
            library.migrate(control)
            check(uri(committedId) == committedUri && !File(folder(committedId), "base.apk").exists())
            check(library.open(committedId, control).size == source.length())

            report("Checking interrupted public copies reuse their pending download entry.")
            val interruptedId = legacy()
            val interruptedUri = pending(interruptedId, complete = false)
            library.migrate(control)
            check(uri(interruptedId) == interruptedUri) { "Pending download entry was duplicated" }
            check(VerifiedDownload.digest(library.open(interruptedId, control).file, control) == hash)

            report("Checking cancellation removes unpublished files without removing completed downloads.")
            val cancelledId = legacy()
            File(folder(cancelledId), "download.json").writeText(record(cancelledId).put("publicRequired", true).toString() + "\n")
            val cancelledUri = pending(cancelledId, complete = false)
            library.discard(cancelledId)
            check(!exists(cancelledUri) && !folder(cancelledId).exists())
            library.discard(legacyId)
            check(exists(legacyUri))

            report("Checking externally deleted and modified public files are not silently reused.")
            resolver.delete(legacyUri, null, null)
            check(library.list().none { it.libraryId == legacyId })
            check(runCatching { library.open(legacyId, control) }.exceptionOrNull() is PushFailure)
            resolver.openOutputStream(committedUri, "wt")!!.use { it.write(byteArrayOf(7, 8, 9)) }
            check(runCatching { library.open(committedId, control) }.exceptionOrNull() is PushFailure)
            return "Public APK/APKS storage, migration, cache preparation, export, deletion, two publication recovery states and external file checks passed.\n"
        } finally {
            directSources.forEach { it.close() }
            ids.forEach { id -> if (library.contains(id)) library.delete(id) else library.discard(id) }
            extraUris.forEach { resolver.delete(it, null, null) }
            root.deleteRecursively()
        }
    }
}
