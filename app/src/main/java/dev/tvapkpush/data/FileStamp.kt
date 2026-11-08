package dev.tvapkpush.data

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor

internal data class FileStamp(
    val device: Long,
    val inode: Long,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
) {
    companion object {
        fun read(file: File): FileStamp? = runCatching { from(Os.stat(file.path)) }.getOrNull()
        fun read(descriptor: FileDescriptor): FileStamp? = runCatching { from(Os.fstat(descriptor)) }.getOrNull()
        private fun from(stat: StructStat): FileStamp? = if (!OsConstants.S_ISREG(stat.st_mode)) null else
            FileStamp(stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec,
                stat.st_ctim.tv_sec, stat.st_ctim.tv_nsec)
    }
}
