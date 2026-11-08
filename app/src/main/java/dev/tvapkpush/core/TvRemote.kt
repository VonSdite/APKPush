package dev.tvapkpush.core

import dev.tvapkpush.adb.AdbSession

class TvRemote(private val session: AdbSession) {
    fun info(): TvInfo {
        val result = session.shell("getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.product.cpu.abilist; getprop ro.product.cpu.abi; getprop ro.serialno; getprop ro.build.characteristics; am get-current-user")
        val values = result.output.replace("\r", "").trimEnd('\n').lines()
        if (result.exitCode != 0 || values.size != 7) throw PushFailure("DEVICE_INFO", "无法读取设备信息，请检查调试权限")
        val sdk = values[1].trim().toIntOrNull() ?: throw PushFailure("DEVICE_INFO", "无法读取设备 Android 版本")
        val userId = values[6].trim().toIntOrNull()?.takeIf { it >= 0 }
            ?: throw PushFailure("DEVICE_USER", "无法确定设备当前用户，请检查设备调试权限")
        val free = session.shell("df -Pk /data/local/tmp").let { df ->
            if (df.exitCode == 0) df.output.trim().lines().lastOrNull()?.trim()?.split(Regex("\\s+"))
                ?.let { fields -> if (fields.size >= 6) fields[fields.size - 3].toLongOrNull()?.times(1024) else null } else null
        }
        val isTelevision = values[5].split(',').any { it.trim() in setOf("tv", "television") } ||
            session.shell("pm list features").output.lineSequence().any {
                it.trim() in setOf("feature:android.software.leanback", "feature:android.hardware.type.television")
            }
        return TvInfo(values[0].trim().ifBlank { "Android 设备" }, sdk,
            values[2].ifBlank { values[3] }.split(',').map(String::trim).filter(String::isNotBlank).toSet(),
            values[4].trim().takeUnless { it == "unknown" }.orEmpty(), userId, free,
            isTelevision)
    }

    fun installedVersion(packageName: String, userId: Int): Long? {
        InputRules.packageName(packageName)
        require(userId >= 0) { "Invalid Android user identifier" }
        val packages = session.shell("pm list packages --user $userId $packageName")
        if (packages.exitCode != 0 || packages.output.contains("Error:") || packages.output.contains("Exception")) {
            throw PushFailure("VERIFY_QUERY", "无法查询设备当前用户的应用，请检查调试权限后重新核对")
        }
        if (packages.output.lineSequence().none { it.trim() == "package:$packageName" }) return null
        val details = session.shell("dumpsys package $packageName")
        if (details.exitCode != 0) throw PushFailure("VERIFY_QUERY", "应用已存在，但无法读取版本；请重新核对结果")
        return InstallPolicy.installedVersion(details.output)
            ?: throw PushFailure("VERIFY_QUERY", "应用已存在，但未返回有效版本；请在设备上检查")
    }

    fun remove(task: PendingInstall) {
        task.sessionId?.let { id ->
            val abandon = session.shell("pm install-abandon $id")
            if (abandon.exitCode != 0 && !abandon.output.contains("Unknown session") &&
                !abandon.output.contains("does not exist")) {
                throw PushFailure("SESSION_CLEANUP", "设备的安装会话尚未清理，下次连接时会重试")
            }
        }
        val result = session.shell("rm -f ${task.remotePaths.joinToString(" ")}")
        if (result.exitCode != 0) throw PushFailure("REMOTE_CLEANUP", "临时 APK 尚未清理，下次连接此设备时会重试")
    }

    fun launch(packageName: String, userId: Int, isTelevision: Boolean = false) {
        InputRules.packageName(packageName)
        require(userId >= 0) { "Invalid Android user identifier" }
        val categories = listOf("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")
        for (category in if (isTelevision) categories.reversed() else categories) {
            val resolve = session.shell("cmd package resolve-activity --brief --user $userId -a android.intent.action.MAIN -c $category $packageName")
            val component = resolve.output.lineSequence().map(String::trim).lastOrNull {
                Regex("${Regex.escape(packageName)}/[A-Za-z0-9_.$]+").matches(it)
            } ?: continue
            val result = session.shell("am start --user $userId -n '$component'")
            if (result.exitCode == 0 && !result.output.contains("Error:") && !result.output.contains("Exception")) return
            throw PushFailure("LAUNCH_FAILED", "设备未能打开应用：${result.output.trim().take(400)}")
        }
        throw PushFailure("NO_LAUNCHER", "应用已安装，但没有可打开的桌面入口")
    }
}
