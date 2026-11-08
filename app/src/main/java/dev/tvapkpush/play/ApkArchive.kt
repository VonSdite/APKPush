package dev.tvapkpush.play

import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class DownloadPart(val name: String, val size: Long, val sha256: String)

internal object ApkArchive {
    fun write(files: List<File>, output: OutputStream, control: JobControl) {
        if (files.size == 1) files.single().inputStream().use { copy(it, output, control) }
        else ZipOutputStream(output).use { zip ->
            zip.setLevel(0)
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { copy(it, zip, control) }
                zip.closeEntry()
            }
        }
    }

    fun extract(input: InputStream, parts: List<DownloadPart>, folder: File, control: JobControl) {
        require(parts.size in 1..128 && parts.map { it.name }.distinct().size == parts.size)
        require(parts.indices.all { parts[it].name == if (it == 0) "base.apk" else "split-$it.apk" })
        require(parts.all { it.size in 1..VerifiedDownload.MAX_SIZE } && parts.sumOf { it.size } <= VerifiedDownload.MAX_SIZE)
        if (parts.size == 1) File(folder, parts.single().name).outputStream().use { copyVerified(input, it, parts.single(), control) }
        else ZipInputStream(input).use { zip ->
            val remaining = parts.associateBy { it.name }.toMutableMap()
            while (true) {
                control.check()
                val entry = zip.nextEntry ?: break
                val part = remaining.remove(entry.name) ?: invalid()
                if (entry.isDirectory) invalid()
                File(folder, part.name).outputStream().use { copyVerified(zip, it, part, control) }
                zip.closeEntry()
            }
            if (remaining.isNotEmpty()) invalid()
        }
    }

    fun copyVerified(input: InputStream, output: OutputStream, part: DownloadPart, control: JobControl) {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val buffer = ByteArray(128 * 1024)
        while (true) {
            control.check()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            if (bytes > part.size) invalid()
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        if (bytes != part.size || digest.digest().hex() != part.sha256) invalid()
    }

    private fun copy(input: InputStream, output: OutputStream, control: JobControl) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            control.check()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalid(): Nothing = throw PushFailure("DOWNLOAD_HASH", "下载文件不完整或已损坏，请重新下载")
}
