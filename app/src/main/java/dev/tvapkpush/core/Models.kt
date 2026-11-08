package dev.tvapkpush.core

import java.io.File
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.UUID

data class TvDevice(val id: String, val name: String, val host: String, val port: Int) {
    val address: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

data class ApkFile(
    val file: File,
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val abis: Set<String>,
    val splits: List<File> = emptyList(),
    val libraryId: String? = null,
    val downloadProfile: String = "",
    val downloadSize: Long? = null,
    val downloadLocation: String = "",
    val iconFile: File? = null,
    val source: ApkSource? = null,
    val sha256: String? = null,
) {
    val files: List<File> get() = listOf(file) + splits
    val size: Long get() = downloadSize ?: files.sumOf { it.length() }
}

interface ApkSource : Closeable {
    fun checkUnchanged()
    fun openStream(): InputStream
}

data class TvInfo(
    val model: String,
    val sdk: Int,
    val abis: Set<String>,
    val serial: String,
    val userId: Int,
    val freeBytes: Long?,
    val isTelevision: Boolean,
)

data class PendingInstall(
    val id: String,
    val deviceId: String,
    val serial: String,
    val packageName: String,
    val versionCode: Long,
    val label: String,
    val userId: Int,
    val submitted: Boolean = false,
    val fileCount: Int = 1,
    val sessionId: Int? = null,
) {
    init {
        require(fileCount in 1..128) { "Invalid APK file count" }
        require(sessionId == null || sessionId >= 0) { "Invalid install session" }
    }
    val remotePath: String get() = remotePath(id)
    val remotePaths: List<String> get() = (0 until fileCount).map {
        if (it == 0) remotePath else remotePath.removeSuffix(".apk") + "-$it.apk"
    }

    companion object {
        fun remotePath(id: String): String {
            require(UUID.fromString(id).toString() == id) { "Invalid task identifier" }
            return "/data/local/tmp/tvapk-$id.apk"
        }
    }
}

enum class Stage {
    IDLE, PREPARING, CONNECTING, AUTHORIZING, CHECKING, UPLOADING, INSTALLING, VERIFYING,
    SUCCESS, FAILED, CANCELLED, UNCERTAIN,
}

data class Progress(
    val stage: Stage = Stage.IDLE,
    val message: String = "选择 APK 和设备，开始安装",
    val bytes: Long = 0,
    val total: Long = 0,
    val bytesPerSecond: Long = 0,
)

class PushFailure(val code: String, val userMessage: String, cause: Throwable? = null) :
    IOException(code, cause)

object InputRules {
    fun host(value: String): String {
        val host = value.trim().removeSurrounding("[", "]")
        val valid = if (host.contains(':')) {
            host.length <= 64 && Regex("[0-9a-fA-F:.%A-Za-z0-9_-]+").matches(host) &&
                runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
        } else {
            val parts = host.split('.')
            parts.size == 4 && parts.all {
                it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) &&
                    it.toIntOrNull() in 0..255
            }
        }
        if (!valid) throw PushFailure("INVALID_ADDRESS", "请输入有效的设备 IPv4 或 IPv6 地址")
        return host
    }

    fun port(value: String): Int = value.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        ?: throw PushFailure("INVALID_PORT", "端口应为 1–65535 之间的数字")

    fun packageName(value: String): String {
        if (value.length > 255 || !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)) {
            throw PushFailure("INVALID_PACKAGE", "APK 的包名无效")
        }
        return value
    }
}

object InstallPolicy {
    fun check(apk: ApkFile, tv: TvInfo) {
        if (tv.sdk < apk.minSdk) throw PushFailure("INSTALL_FAILED_OLDER_SDK", "设备系统过旧：APK 至少需要 API ${apk.minSdk}，设备为 API ${tv.sdk}")
        if (apk.abis.isNotEmpty() && tv.abis.isNotEmpty() && apk.abis.intersect(tv.abis).isEmpty()) {
            throw PushFailure("INSTALL_FAILED_NO_MATCHING_ABIS", "CPU 架构不兼容：APK 为 ${apk.abis.joinToString()}，设备为 ${tv.abis.joinToString()}")
        }
        if (tv.freeBytes != null && tv.freeBytes < apk.size) {
            throw PushFailure("INSTALL_FAILED_INSUFFICIENT_STORAGE", "设备空间不足，无法暂存这个 APK；请清理空间后重试")
        }
    }

    fun installError(output: String): PushFailure {
        val code = Regex("INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z0-9_]+").find(output)?.value ?: "INSTALL_REJECTED"
        val message = when (code) {
            "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> "签名不匹配，不能覆盖设备上的应用；请使用相同签名的 APK"
            "INSTALL_FAILED_VERSION_DOWNGRADE" -> "APK 版本较旧，请选择相同或更高版本"
            "INSTALL_FAILED_NO_MATCHING_ABIS" -> "APK 与设备的 CPU 架构不兼容"
            "INSTALL_FAILED_OLDER_SDK" -> "设备 Android 版本低于 APK 的最低要求"
            "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "设备存储空间不足，请清理后重试"
            "INSTALL_FAILED_USER_RESTRICTED", "INSTALL_FAILED_VERIFICATION_FAILURE", "INSTALL_FAILED_ABORTED" -> "设备拒绝了安装，请检查设备上的确认提示与厂商安装限制"
            "INSTALL_FAILED_MISSING_SPLIT" -> "安装包缺少必需的拆分文件，请重新下载完整安装包"
            else -> "设备未完成安装，请检查 APK 和设备上的提示"
        }
        return PushFailure(code, "$message\n${output.trim().take(500)}")
    }

    fun installedVersion(output: String): Long? = Regex("(?m)^\\s*versionCode=(\\d+)\\b")
        .find(output)?.groupValues?.get(1)?.toLongOrNull()
}
