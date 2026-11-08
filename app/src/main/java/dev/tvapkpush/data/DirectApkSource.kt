package dev.tvapkpush.data

import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.tvapkpush.core.ApkSource
import dev.tvapkpush.core.PushFailure
import java.io.File

internal class DirectApkSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val stamp: FileStamp,
) : ApkSource {
    val file = File("/proc/self/fd/${descriptor.fd}")
    val size: Long get() = stamp.size
    private var closed = false

    override fun checkUnchanged() {
        if (closed || FileStamp.read(descriptor.fileDescriptor) != stamp) {
            throw PushFailure("APK_CHANGED", "安装包已变化或不可读取，请重新选择后推送")
        }
    }

    override fun close() {
        if (!closed) { closed = true; descriptor.close() }
    }

    override fun openStream(): java.io.InputStream {
        checkUnchanged()
        val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
        try {
            Os.lseek(duplicate.fileDescriptor, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.AutoCloseInputStream(duplicate)
        } catch (error: Exception) { duplicate.close(); throw error }
    }

    companion object {
        fun from(asset: AssetFileDescriptor): DirectApkSource? {
            val stamp = FileStamp.read(asset.fileDescriptor) ?: return null
            if (asset.startOffset != 0L || stamp.size <= 0 ||
                (asset.declaredLength >= 0 && asset.declaredLength != stamp.size)) return null
            // 保留只读句柄；偏移文件和管道使用导入缓存。
            val descriptor = ParcelFileDescriptor.dup(asset.fileDescriptor)
            val source = DirectApkSource(descriptor, stamp)
            return source
        }
    }
}
