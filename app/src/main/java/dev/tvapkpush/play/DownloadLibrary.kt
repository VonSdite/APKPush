package dev.tvapkpush.play

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import android.util.AtomicFile
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.InputRules
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import dev.tvapkpush.data.DirectApkSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import dev.tvapkpush.play.ApkArchive.hex

class DownloadLibrary(private val context: Context) {
    private val directory = File(context.filesDir, "play-downloads").apply { mkdirs() }
    private val cache = File(context.cacheDir, "play-apks").apply { mkdirs() }
    private val publicDownloads = PublicDownloads(context)

    fun discardCache() { cache.listFiles()?.forEach { it.deleteRecursively() } }

    fun release(id: String) { File(cache, validId(id)).deleteRecursively() }

    fun contains(id: String): Boolean = File(directory, validId(id)).isDirectory

    fun migrate(control: JobControl) {
        directory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.forEach { folder ->
            control.check()
            val data = record(folder.name)
            if (!data.has("publicUri") || File(folder, "publication.json").exists() || File(folder, "base.apk").exists()) {
                publish(folder.name, control)
            }
            if (!File(folder, "icon.png").isFile) backfillIcon(folder.name, control)
        }
    }

    private fun backfillIcon(id: String, control: JobControl) {
        val folder = File(cache, ".icon-${validId(id)}")
        try {
            val data = record(id)
            val parts = parts(data)
            if (StatFs(cache.path).availableBytes < parts.sumOf { it.size } + VerifiedDownload.SPACE_RESERVE) return
            folder.mkdirs()
            publicDownloads.input(Uri.parse(data.getString("publicUri"))).use { input ->
                control.attach(input)
                try { ApkArchive.extract(input, parts, folder, control) } finally { control.detach(input) }
            }
            ApkThumbnail.write(context, parts.map { File(folder, it.name) }, File(File(directory, id), "icon.png"))
        } catch (error: Exception) {
            control.check()
            Log.w("APKPush", "Download icon preparation failed: type=${error.javaClass.simpleName}")
        } finally { folder.deleteRecursively() }
    }

    fun discardPartial(keepIds: Set<String> = emptySet()) {
        directory.listFiles()?.filter { it.isDirectory && it.name.startsWith(".partial-") && it.name.removePrefix(".partial-") !in keepIds }
            ?.forEach { it.deleteRecursively() }
    }

    fun list(): List<ApkFile> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && !it.name.startsWith('.') }.sortedByDescending { it.lastModified() }
        .mapNotNull { folder ->
            runCatching { read(folder.name) }.getOrElse {
                Log.w("APKPush", "Download record unreadable: type=${it.javaClass.simpleName}")
                null
            }
        }

    fun staging(id: String): File = File(directory, ".partial-${validId(id)}").apply {
        if (!isDirectory && !mkdir()) throw PushFailure("LOCAL_STORAGE", "无法创建下载文件，请检查本机存储空间")
    }

    fun discard(id: String) {
        val folder = File(directory, ".partial-${validId(id)}")
        if (folder.exists() && !folder.deleteRecursively()) throw PushFailure("LOCAL_STORAGE", "无法清理未完成的下载")
        if (contains(id)) {
            val data = record(id)
            if (data.optBoolean("publicRequired") || (data.has("publicUri") &&
                    publicDownloads.info(Uri.parse(data.getString("publicUri")))?.pending == true)) delete(id)
        }
    }

    fun partialProgress(id: String): Pair<Long, Long>? = runCatching {
        val folder = File(directory, ".partial-${validId(id)}")
        val plan = AtomicFile(File(folder, "plan.json")).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val files = plan.getJSONArray("files")
        require(files.length() in 1..128)
        val sizes = (0 until files.length()).map { files.getJSONObject(it).getLong("size").also { size -> require(size in 1..VerifiedDownload.MAX_SIZE) } }
        val total = sizes.sum().also { require(it <= VerifiedDownload.MAX_SIZE) }
        sizes.indices.sumOf { File(folder, fileName(it)).length().coerceAtMost(sizes[it]) } to total
    }.getOrNull()

    fun save(id: String, folder: File, label: String, packageName: String, versionCode: Long,
             profile: String, hashes: List<String>, control: JobControl): ApkFile {
        val files = hashes.indices.map { File(folder, fileName(it)) }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(files.first().path, PackageManager.GET_META_DATA)
            ?: throw PushFailure("APK_INVALID", "下载的主 APK 无法解析")
        val app = info.applicationInfo ?: throw PushFailure("APK_INVALID", "安装包缺少应用信息")
        if (info.packageName != packageName || info.longVersionCode != versionCode) {
            throw PushFailure("APK_IDENTITY", "下载包的包名或版本与 Google Play 信息不符")
        }
        if (files.size == 1 && app.metaData?.getBoolean("com.android.vending.splits.required", false) == true) {
            throw PushFailure("APK_SPLIT", "Google Play 未返回必需的拆分包，请重试或更换下载机型")
        }
        val abis = files.flatMap { file ->
            control.check()
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) throw PushFailure("APK_INVALID", "下载文件不是有效的 APK")
                zip.entries().asSequence().map { it.name.split('/') }
                    .filter { it.size == 3 && it[0] == "lib" && it[2].endsWith(".so") }.map { it[1] }.toList()
            }
        }.toSet()
        val record = JSONObject().put("id", id).put("label", label).put("packageName", InputRules.packageName(packageName))
            .put("publicRequired", true)
            .put("versionCode", versionCode).put("versionName", info.versionName.orEmpty().ifBlank { versionCode.toString() })
            .put("minSdk", app.minSdkVersion).put("abis", JSONArray(abis.toList())).put("profile", profile)
            .put("files", JSONArray().apply { files.forEachIndexed { index, file ->
                put(JSONObject().put("name", file.name).put("size", file.length()).put("sha256", hashes[index]))
            } })
        writeDownloadJson(AtomicFile(File(folder, "download.json")), record)
        control.check()
        if (!folder.renameTo(File(directory, validId(id)))) throw PushFailure("LOCAL_STORAGE", "无法保存下载，请检查本机存储空间")
        try { publish(id, control) } catch (error: Exception) {
            val saved = File(directory, id)
            if (!this.record(id).has("publicUri")) saved.renameTo(folder)
            throw error
        }
        return read(id)
    }

    fun openForPush(id: String, control: JobControl): ApkFile {
        publish(id, control)
        val data = record(id)
        val parts = parts(data)
        if (parts.size == 1) {
            control.check()
            val apk = read(id)
            val asset = context.contentResolver.openAssetFileDescriptor(Uri.parse(data.getString("publicUri")), "r")
            try {
                asset?.let(DirectApkSource::from)?.let { source ->
                    try {
                        control.check()
                        if (source.size != parts.single().size) throw PushFailure("DOWNLOAD_INCOMPLETE", "安装包大小已变化，请重新下载")
                        source.checkUnchanged()
                        return apk.copy(file = source.file, source = source, sha256 = parts.single().sha256)
                    } catch (error: Exception) { source.close(); throw error }
                }
            } finally { asset?.close() }
        }
        return open(id, control)
    }

    fun open(id: String, control: JobControl): ApkFile {
        publish(id, control)
        val data = record(id)
        val apk = read(id)
        val parts = parts(data)
        val folder = File(cache, ".opening-${validId(id)}")
        folder.deleteRecursively()
        if (!folder.mkdir()) throw PushFailure("LOCAL_STORAGE", "无法准备安装包缓存，请检查存储空间")
        try {
            if (StatFs(cache.path).availableBytes < apk.size + VerifiedDownload.SPACE_RESERVE) {
                throw PushFailure("LOCAL_STORAGE", "手机缓存空间不足，请清理后重试")
            }
            publicDownloads.input(Uri.parse(data.getString("publicUri"))).use { input ->
                control.attach(input)
                try { ApkArchive.extract(input, parts, folder, control) } finally { control.detach(input) }
            }
            control.check()
            release(id)
            if (!folder.renameTo(File(cache, id))) throw PushFailure("LOCAL_STORAGE", "无法准备安装包，请重试")
            return apk
        } catch (error: Exception) {
            folder.deleteRecursively()
            if (error is PushFailure || error is CancellationException) throw error
            throw PushFailure("DOWNLOAD_READ", "无法读取下载文件，请检查文件和手机存储空间", error)
        }
    }

    fun delete(id: String) {
        val data = record(id)
        if (data.has("publicUri")) publicDownloads.delete(Uri.parse(data.getString("publicUri")))
        val publication = File(File(directory, validId(id)), "publication.json")
        if (publication.exists()) publicDownloads.delete(Uri.parse(JSONObject(publication.readText()).getString("uri")))
        release(id)
        if (!File(directory, validId(id)).deleteRecursively()) throw PushFailure("LOCAL_STORAGE", "无法删除下载文件")
    }

    internal fun <T> withInstallFiles(id: String, control: JobControl, install: (ApkFile) -> T): T {
        publish(id, control)
        val apk = read(id)
        val parts = parts(record(id))
        val folder = File(cache, ".install-${UUID.randomUUID()}")
        if (!folder.mkdir()) throw PushFailure("LOCAL_STORAGE", "无法准备安装包，请检查存储空间")
        try {
            if (StatFs(cache.path).availableBytes < apk.size + VerifiedDownload.SPACE_RESERVE) {
                throw PushFailure("LOCAL_STORAGE", "手机空间不足，请清理后重试")
            }
            publicDownloads.input(Uri.parse(record(id).getString("publicUri"))).use { input ->
                control.attach(input)
                try { ApkArchive.extract(input, parts, folder, control) } finally { control.detach(input) }
            }
            control.check()
            val files = parts.map { File(folder, it.name) }
            return install(apk.copy(file = files.first(), splits = files.drop(1)))
        } finally { folder.deleteRecursively() }
    }

    fun export(id: String, uri: Uri, control: JobControl) {
        publish(id, control)
        val data = record(id)
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw PushFailure("EXPORT_FAILED", "无法写入所选位置")
        try {
            control.attach(stream)
            copyPublic(data, stream, control)
        } catch (error: Exception) {
            runCatching { stream.close() }
            runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                else context.contentResolver.delete(uri, null, null)
            }
            throw error
        } finally { control.detach(stream); stream.close() }
    }

    private fun read(id: String): ApkFile {
        val data = record(id)
        val parts = parts(data)
        val public = data.has("publicUri")
        val location = if (public) {
            val info = publicDownloads.info(Uri.parse(data.getString("publicUri")))
                ?: throw PushFailure("DOWNLOAD_MISSING", "下载文件已移动或删除，请重新下载")
            if (info.pending || info.size != data.getLong("publicSize")) throw PushFailure("DOWNLOAD_INCOMPLETE", "下载文件不完整，请重新下载")
            info.path + info.name
        } else ""
        val files = parts.map { part ->
            File(File(if (public) cache else directory, validId(id)), part.name).also {
                if (!public && (!it.isFile || it.length() != part.size)) {
                    throw PushFailure("DOWNLOAD_INCOMPLETE", "本地文件不完整，请重新下载")
                }
            }
        }
        val abis = data.getJSONArray("abis")
        return ApkFile(files.first(), data.getString("label"), InputRules.packageName(data.getString("packageName")),
            data.getString("versionName"), data.getLong("versionCode"), data.getInt("minSdk"),
            (0 until abis.length()).map { abis.getString(it) }.toSet(), files.drop(1), id, data.getString("profile"),
            parts.sumOf { it.size }, location, File(File(directory, id), "icon.png").takeIf { it.isFile })
    }

    private fun publish(id: String, control: JobControl) {
        val folder = File(directory, validId(id))
        val data = record(id)
        val parts = parts(data)
        val files = parts.map { File(folder, it.name) }
        val publication = AtomicFile(File(folder, "publication.json"))
        if (data.has("publicUri")) {
            val uri = Uri.parse(data.getString("publicUri"))
            val info = publicDownloads.info(uri) ?: throw PushFailure("DOWNLOAD_MISSING", "下载文件已移动或删除，请重新下载")
            if (info.pending || files.any { it.exists() }) {
                verifyPublic(data, control)
                publicDownloads.publish(uri)
                if (files.all { it.isFile }) ApkThumbnail.write(context, files, File(folder, "icon.png"))
                files.forEach { it.delete() }
                File(folder, "plan.json").delete()
                publication.delete()
            }
            return
        }
        files.forEachIndexed { index, file ->
            control.check()
            if (!file.isFile || file.length() != parts[index].size || VerifiedDownload.digest(file, control) != parts[index].sha256) {
                throw PushFailure("DOWNLOAD_HASH", "本地文件已损坏，请删除后重新下载")
            }
        }
        if (StatFs(Environment.getExternalStorageDirectory().path).availableBytes <
            parts.sumOf { it.size } + VerifiedDownload.SPACE_RESERVE + 1024 * 1024) {
            throw PushFailure("LOCAL_STORAGE", "公共下载目录空间不足，请清理后重试；原文件已保留")
        }
        var uri: Uri? = null
        var recorded = false
        try {
            val name = "${data.getString("packageName")}-${data.getLong("versionCode")}-${id.take(8)}.${if (parts.size == 1) "apk" else "apks"}"
            uri = publicDownloads.create(name, parts.size > 1)
            writeDownloadJson(publication, JSONObject().put("uri", uri.toString()))
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(publicDownloads.output(uri), digest).use { output ->
                control.attach(output)
                try { ApkArchive.write(files, output, control) } finally { control.detach(output) }
            }
            control.check()
            val info = publicDownloads.info(uri) ?: throw PushFailure("DOWNLOAD_STORAGE", "公共下载文件无法读取，请重试")
            if (info.size <= 0) throw PushFailure("DOWNLOAD_STORAGE", "公共下载文件为空，请重试")
            data.put("publicUri", uri.toString()).put("publicSize", info.size).put("publicSha256", digest.digest().hex())
            data.remove("publicRequired")
            verifyPublic(data, control)
            // 记录先于公开文件提交，原 APK 在提交完成后清理。
            writeDownloadJson(AtomicFile(File(folder, "download.json")), data)
            recorded = true
            publicDownloads.publish(uri)
            ApkThumbnail.write(context, files, File(folder, "icon.png"))
            files.forEach { it.delete() }
            File(folder, "plan.json").delete()
            publication.delete()
        } catch (error: Exception) {
            if (!recorded) {
                uri?.let { runCatching { publicDownloads.delete(it) } }
                publication.delete()
            }
            if (error is PushFailure || error is CancellationException) throw error
            throw PushFailure("DOWNLOAD_STORAGE", "无法保存到 Download/APK Push，请检查存储空间后重试；原文件已保留", error)
        }
    }

    private fun copyPublic(data: JSONObject, output: OutputStream, control: JobControl) {
        publicDownloads.input(Uri.parse(data.getString("publicUri"))).use { input ->
            control.attach(input)
            try {
                ApkArchive.copyVerified(input, output, DownloadPart("", data.getLong("publicSize"), data.getString("publicSha256")), control)
            } finally { control.detach(input) }
        }
    }

    private fun verifyPublic(data: JSONObject, control: JobControl) = copyPublic(data, object : OutputStream() {
        override fun write(value: Int) {}
        override fun write(bytes: ByteArray, offset: Int, length: Int) {}
    }, control)

    private fun parts(data: JSONObject): List<DownloadPart> {
        val entries = data.getJSONArray("files")
        if (entries.length() !in 1..128) throw PushFailure("DOWNLOAD_RECORD", "本地下载记录无效")
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            if (entry.getString("name") != fileName(index) || entry.getLong("size") !in 1..VerifiedDownload.MAX_SIZE) {
                throw PushFailure("DOWNLOAD_RECORD", "本地下载记录无效")
            }
            DownloadPart(entry.getString("name"), entry.getLong("size"), entry.getString("sha256"))
        }.also { if (it.sumOf { part -> part.size } > VerifiedDownload.MAX_SIZE) throw PushFailure("DOWNLOAD_RECORD", "本地下载记录无效") }
    }

    private fun record(id: String) = AtomicFile(File(File(directory, validId(id)), "download.json"))
        .openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun validId(id: String): String {
        require(UUID.fromString(id).toString() == id) { "Invalid download identifier" }
        return id
    }

    companion object {
        fun fileName(index: Int) = if (index == 0) "base.apk" else "split-$index.apk"
    }
}
