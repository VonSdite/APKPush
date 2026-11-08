package dev.tvapkpush.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.InputRules
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class ApkImporter(private val context: Context) {
    private val directory = File(context.cacheDir, "apk-imports").apply { mkdirs() }
    private data class Source(val uri: Uri, val offset: Long, val length: Long, val stamp: FileStamp)
    private var lastSource: Source? = null
    private var lastApk: ApkFile? = null
    private var lastPrepared: FileStamp? = null

    fun discardStale() {
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }?.forEach { it.delete() }
    }

    fun import(uri: Uri, control: JobControl, existing: ApkFile? = null, progress: (Long, Long) -> Unit): ApkFile {
        if (uri.scheme != "content") throw PushFailure("URI_UNSUPPORTED", "请通过系统文件选择器或文件管理器分享 APK")
        val signal = CancellationSignal()
        val cancelSignal = Closeable { signal.cancel() }
        control.attach(cancelSignal)
        val destination = File(directory, "${UUID.randomUUID()}.apk")
        var source: Source? = null
        try {
            var name = "selected.apk"
            var expected = -1L
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) expected = cursor.getLong(sizeIndex)
                }
            } finally { cursor?.close() }
            if (!name.endsWith(".apk", ignoreCase = true)) throw PushFailure("APK_FORMAT", "请选择单个 .apk 文件；暂不支持 .apks、.xapk 或 .apkm")
            if (expected > MAX_SIZE) throw PushFailure("APK_TOO_LARGE", "APK 大于 2 GiB，超出当前版本的文件上限")
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                ?: throw PushFailure("URI_UNREADABLE", "无法读取文件，请重新选择或分享 APK")
            try {
                source = FileStamp.read(descriptor.fileDescriptor)?.let { Source(uri, descriptor.startOffset, descriptor.declaredLength, it) }
                if (descriptor.declaredLength >= 0) expected = descriptor.declaredLength
                else source?.let { expected = it.stamp.size - it.offset }
                if (expected > MAX_SIZE) throw PushFailure("APK_TOO_LARGE", "APK 大于 2 GiB，超出当前版本的文件上限")
                control.check()
                if (source != null && source == lastSource && existing != null && existing === lastApk) {
                    val unchanged = if (existing.source != null) runCatching { existing.source.checkUnchanged() }.isSuccess
                        else lastPrepared != null && FileStamp.read(existing.file) == lastPrepared
                    if (unchanged) return existing
                }
                val direct = DirectApkSource.from(descriptor)
                if (direct != null) {
                    try {
                        val apk = parse(direct.file).copy(source = direct, downloadSize = direct.size)
                        direct.checkUnchanged()
                        control.check()
                        return remember(apk, source)
                    } catch (error: Exception) {
                        direct.close()
                        control.check()
                    }
                }
                if (expected > 0) {
                    val storage = context.getSystemService(StorageManager::class.java)
                    val volume = storage.getUuidForPath(directory)
                    if (storage.getAllocatableBytes(volume) < expected + SPACE_RESERVE) throw PushFailure("LOCAL_STORAGE", "手机缓存空间不足，请清理后重新选择 APK")
                    storage.allocateBytes(volume, expected + SPACE_RESERVE)
                }
                descriptor.createInputStream().use { input ->
                    control.attach(input)
                    try {
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var copied = 0L
                            while (true) {
                                control.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                if (copied > MAX_SIZE) throw PushFailure("APK_TOO_LARGE", "APK 大于 2 GiB，超出当前版本的文件上限")
                                if (StatFs(directory.path).availableBytes < count + SPACE_RESERVE) throw PushFailure("LOCAL_STORAGE", "手机缓存空间不足，请清理后重新选择 APK")
                                output.write(buffer, 0, count)
                                progress(copied, expected)
                            }
                            if (copied == 0L || (expected >= 0 && expected != copied)) throw PushFailure("APK_INCOMPLETE", "文件为空或长度不完整，请重新选择已下载完成的 APK")
                        }
                    } finally {
                        control.detach(input)
                    }
                    if (source?.stamp != FileStamp.read(descriptor.fileDescriptor)) source = null
                }
            } finally { descriptor.close() }
            control.check()
            return remember(parse(destination), source)
        } catch (error: Exception) {
            destination.delete()
            throw error
        } finally {
            control.detach(cancelSignal)
        }
    }

    private fun remember(apk: ApkFile, source: Source?): ApkFile {
        lastSource = source
        lastApk = apk
        lastPrepared = FileStamp.read(apk.file)
        return apk
    }

    private fun parse(file: File): ApkFile {
        val abis = ZipFile(file).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") == null) throw PushFailure("APK_INVALID", "文件不含 AndroidManifest.xml，不是有效 APK")
            zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") && it.split('/').size == 3 }
                .map { it.split('/')[1] }.toSet()
        }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA)
            ?: throw PushFailure("APK_INVALID", "无法解析 APK，请选择完整且未损坏的独立安装包")
        val app = info.applicationInfo ?: throw PushFailure("APK_INVALID", "APK 缺少应用信息")
        if (!info.splitNames.isNullOrEmpty() || app.metaData?.getBoolean("com.android.vending.splits.required", false) == true) {
            throw PushFailure("APK_SPLIT", "这个 APK 需要拆分包，请选择可独立安装的完整 APK")
        }
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        return ApkFile(file, app.loadLabel(context.packageManager).toString(), InputRules.packageName(info.packageName),
            info.versionName.orEmpty().ifBlank { info.longVersionCode.toString() }, info.longVersionCode, app.minSdkVersion, abis)
    }

    companion object {
        const val MAX_SIZE = 2L * 1024 * 1024 * 1024
        private const val SPACE_RESERVE = 16L * 1024 * 1024
    }
}
