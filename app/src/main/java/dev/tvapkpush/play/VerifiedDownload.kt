package dev.tvapkpush.play

import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class VerifiedDownload(private val client: OkHttpClient) {
    fun fetch(url: String, destination: File, expectedSize: Long, sha256: String, sha1: String,
              limit: Long, control: JobControl, resume: Boolean = false, progress: (Long) -> Unit): String {
        val address = url.toHttpUrl()
        if (!address.isHttps) throw PushFailure("DOWNLOAD_URL", "下载地址未使用 HTTPS，请稍后重试")
        if (expectedSize < 0 || expectedSize > limit) throw PushFailure("DOWNLOAD_SIZE", "安装包超过 2 GiB 上限")
        if (!Regex("[a-fA-F0-9]{64}").matches(sha256) && !Regex("[a-fA-F0-9]{40}").matches(sha1)) {
            throw PushFailure("DOWNLOAD_HASH", "Google Play 未提供有效的文件校验值")
        }
        control.check()
        var offset = if (resume && destination.isFile) destination.length() else 0L
        if (offset > 0 && (expectedSize <= 0 || offset > expectedSize)) {
            destination.delete()
            offset = 0
        }
        if (offset > 0 && offset == expectedSize) {
            try {
                val hash = verify(destination, sha256, sha1, control)
                progress(offset)
                return hash
            } catch (error: PushFailure) {
                destination.delete()
                offset = 0
            }
        }
        val request = Request.Builder().url(address).header("Accept-Encoding", "identity")
        if (offset > 0) request.header("Range", "bytes=$offset-")
        val call = client.newCall(request.build())
        val cancellation = Closeable { call.cancel() }
        control.attach(cancellation)
        try {
            call.execute().use { response ->
                if (response.code == 416) throw PushFailure("DOWNLOAD_RANGE", "下载进度与服务器不一致，将重新获取文件")
                if (!response.isSuccessful) throw PushFailure("DOWNLOAD_HTTP_${response.code}",
                    if (response.code in listOf(401, 403, 404)) "下载链接已失效，请重新下载" else "下载服务暂时不可用，请稍后重试")
                val append = response.code == 206
                if (append) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    val values = range?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                    if (values == null || values[0] != offset || values[1] != expectedSize - 1 || values[2] != expectedSize) {
                        throw PushFailure("DOWNLOAD_RANGE", "服务器返回的续传范围无效，将重新获取文件")
                    }
                } else if (response.code != 200) {
                    throw PushFailure("DOWNLOAD_RANGE", "服务器返回的下载响应无效")
                }
                var bytes = if (append) offset else 0L
                progress(bytes)
                response.body.byteStream().use { input ->
                    FileOutputStream(destination, append).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            control.check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > limit || (expectedSize > 0 && bytes > expectedSize)) throw PushFailure("DOWNLOAD_SIZE", "下载文件长度与预期不符")
                            output.write(buffer, 0, count)
                            progress(bytes)
                        }
                    }
                }
                control.check()
                if (bytes == 0L || (expectedSize > 0 && bytes != expectedSize)) throw PushFailure("DOWNLOAD_INCOMPLETE", "下载不完整，请重新下载")
                return verify(destination, sha256, sha1, control)
            }
        } catch (error: Exception) {
            if (!resume || (error is PushFailure && error.code in setOf("DOWNLOAD_HASH", "DOWNLOAD_SIZE", "DOWNLOAD_RANGE"))) destination.delete()
            throw error
        } finally {
            control.detach(cancellation)
        }
    }

    private fun verify(file: File, sha256: String, sha1: String, control: JobControl): String {
        val hash256 = MessageDigest.getInstance("SHA-256")
        val hash1 = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                control.check()
                val count = input.read(buffer)
                if (count < 0) break
                hash256.update(buffer, 0, count)
                hash1.update(buffer, 0, count)
            }
        }
        val actual = hash256.digest().hex()
        if ((sha256.isNotBlank() && !actual.equals(sha256, true)) ||
            (sha1.isNotBlank() && !hash1.digest().hex().equals(sha1, true))) {
            throw PushFailure("DOWNLOAD_HASH", "文件校验失败，请重新下载")
        }
        return actual
    }

    companion object {
        const val MAX_SIZE = 2L * 1024 * 1024 * 1024
        const val SPACE_RESERVE = 32L * 1024 * 1024
        fun digest(file: File, control: JobControl): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    control.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
