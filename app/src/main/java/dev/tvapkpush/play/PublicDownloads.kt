package dev.tvapkpush.play

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import dev.tvapkpush.core.PushFailure
import java.io.FileNotFoundException

internal class PublicDownloads(context: Context) {
    private val resolver = context.contentResolver
    private val owner = context.packageName
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    data class Entry(val name: String, val path: String, val size: Long, val pending: Boolean)

    fun create(name: String, split: Boolean): Uri {
        // 同一下载的未发布条目可在进程中断后重新写入。
        @Suppress("DEPRECATION")
        val pendingCollection = MediaStore.setIncludePending(collection)
        val cursor = resolver.query(pendingCollection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1",
            arrayOf(name, DIRECTORY, owner), null)
        try {
            if (cursor != null && cursor.moveToFirst()) return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
        } finally { cursor?.close() }
        return resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY)
            put(MediaStore.MediaColumns.MIME_TYPE, if (split) "application/octet-stream" else "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw PushFailure("DOWNLOAD_STORAGE", "无法创建公共下载文件，请检查存储空间后重试")
    }

    fun info(uri: Uri): Entry? {
        val cursor = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null)
        try {
            if (cursor == null || !cursor.moveToFirst()) return null
            // 文件大小以描述符为准，媒体索引可能延后更新。
            val descriptor = try { resolver.openFileDescriptor(uri, "r") } catch (_: FileNotFoundException) { return null } ?: return null
            val size = try { descriptor.statSize } finally { descriptor.close() }
            return Entry(cursor.getString(0), cursor.getString(1), size, cursor.getInt(2) != 0)
        } finally { cursor?.close() }
    }

    fun input(uri: Uri) = resolver.openInputStream(uri)
        ?: throw PushFailure("DOWNLOAD_MISSING", "下载文件已移动或删除，请重新下载")

    fun output(uri: Uri) = resolver.openOutputStream(uri, "wt")
        ?: throw PushFailure("DOWNLOAD_STORAGE", "无法写入公共下载目录，请检查存储空间后重试")

    fun publish(uri: Uri) {
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) {
            throw PushFailure("DOWNLOAD_STORAGE", "无法完成公共下载文件的保存，请重试")
        }
    }

    fun delete(uri: Uri) { resolver.delete(uri, null, null) }

    companion object {
        val DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/APK Push/"
    }
}
