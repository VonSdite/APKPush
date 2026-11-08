package dev.tvapkpush.core

import dev.tvapkpush.adb.AdbSession
import dev.tvapkpush.data.TaskJournal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CancellationException

data class InstalledTarget(val device: TvDevice, val packageName: String, val versionCode: Long, val userId: Int)

data class Outcome(val progress: Progress, val installed: InstalledTarget? = null, val decision: PendingInstall? = null)

class Installer(private val journal: TaskJournal) {
    fun install(
        device: TvDevice,
        apk: ApkFile,
        tv: TvInfo,
        session: AdbSession,
        control: JobControl,
        progress: (Progress) -> Unit,
    ): Outcome {
        val remote = TvRemote(session)
        if (journal.pending(device.id).any { it.submitted }) throw PushFailure("PENDING_RESULT", "此设备有待确认的安装，请先点击“连接 / 核对”")
        cleanUploads(device, tv, remote)
        apk.source?.checkUnchanged()
        InstallPolicy.check(apk, tv)
        control.check()
        var task = PendingInstall(UUID.randomUUID().toString(), device.id, tv.serial, apk.packageName, apk.versionCode, apk.label, tv.userId,
            fileCount = apk.files.size)
        journal.put(task)
        var outcome = try {
            val started = System.nanoTime()
            progress(Progress(Stage.UPLOADING, "正在发送 ${apk.label}", 0, apk.size))
            var completed = 0L
            apk.files.forEachIndexed { index, file ->
                control.check()
                val uploading: (Long) -> Unit = { sent ->
                    control.check()
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                    val bytes = completed + sent
                    progress(Progress(Stage.UPLOADING, "正在发送 ${apk.label}", bytes, apk.size, (bytes * 1_000_000_000.0 / elapsed).toLong()))
                }
                if (index == 0 && apk.source != null) {
                    apk.source.openStream().use { input -> session.push(input, apk.size, task.remotePaths[index], apk.sha256, uploading) }
                    completed += apk.size
                } else {
                    session.push(file, task.remotePaths[index], if (index == 0) apk.sha256 else null, uploading)
                    completed += file.length()
                }
            }
            apk.source?.checkUnchanged()
            if (apk.splits.isNotEmpty()) {
                control.check()
                val created = session.shell("pm install-create --user ${tv.userId} -r -S ${apk.size}")
                val id = Regex("Success: created install session \\[(\\d+)]").find(created.output)?.groupValues?.get(1)?.toIntOrNull()
                if (created.exitCode != 0 || id == null) throw InstallPolicy.installError(created.output)
                task = task.copy(sessionId = id)
                journal.put(task)
                apk.files.forEachIndexed { index, file ->
                    control.check()
                    val written = session.shell("pm install-write -S ${file.length()} $id part$index.apk ${task.remotePaths[index]}", 120_000)
                    if (written.exitCode != 0 || !written.output.trimStart().startsWith("Success")) {
                        throw InstallPolicy.installError(written.output)
                    }
                }
            }
            control.check()
            val submitted = task.copy(submitted = true)
            journal.put(submitted)
            task = submitted
            progress(Progress(Stage.INSTALLING, "设备正在安装；此时停止等待不会撤销设备上的安装"))
            val command = task.sessionId?.let { "pm install-commit $it" }
                ?: "pm install --user ${tv.userId} -r ${task.remotePath}"
            val result = session.shell(command, 120_000)
            if (result.exitCode != 0 || result.output.contains("Failure [") || result.output.contains("Error:")) {
                val rejected = task.copy(submitted = false)
                journal.put(rejected)
                task = rejected
                throw InstallPolicy.installError(result.output)
            }
            if (result.output.lineSequence().none { it.trim() == "Success" }) throw PushFailure("INSTALL_UNCONFIRMED", "设备未返回明确的安装结果，请重新连接核对")
            if (task.sessionId != null) {
                task = task.copy(sessionId = null)
                journal.put(task)
            }
            progress(Progress(Stage.VERIFYING, "正在核对设备上的包名、用户和版本"))
            val version = remote.installedVersion(apk.packageName, tv.userId)
            if (version != apk.versionCode) throw PushFailure("VERSION_UNCONFIRMED", "安装命令已完成，但设备上的版本与预期不一致，请重新连接核对")
            val verified = task.copy(submitted = false)
            journal.put(verified)
            task = verified
            Outcome(Progress(Stage.SUCCESS, "${apk.label} ${apk.versionName} 已安装到 ${device.name}\n已核对包名和版本（${apk.versionCode}）"),
                InstalledTarget(device, apk.packageName, apk.versionCode, tv.userId))
        } catch (error: Exception) {
            if (task.submitted) Outcome(Progress(Stage.UNCERTAIN, "安装结果待确认。设备可能仍在安装，请重新连接后核对。\n${errorMessage(error, control.cancelled)}"))
            else Outcome(Progress(if (control.cancelled) Stage.CANCELLED else Stage.FAILED, errorMessage(error, control.cancelled)))
        }
        if (!task.submitted) {
            try {
                remote.remove(task)
                journal.remove(task.id)
            } catch (_: Exception) {
                outcome = outcome.copy(progress = outcome.progress.copy(message = outcome.progress.message + "\n临时 APK 将在下次连接此设备时清理。"))
            }
        }
        return outcome
    }

    fun recover(device: TvDevice, tv: TvInfo, remote: TvRemote): Outcome? {
        cleanUploads(device, tv, remote)
        val task = journal.pending(device.id).firstOrNull { it.submitted } ?: return null
        checkIdentity(task, tv)
        val version = remote.installedVersion(task.packageName, task.userId)
        val message = when (version) {
            task.versionCode -> "设备上已存在 ${task.label} 的预期版本（$version）。中断的安装是否结束仍需在设备上确认。"
            null -> "设备用户 ${task.userId} 中尚未找到 ${task.label}。请检查设备是否仍在安装或等待确认。"
            else -> "设备上的版本为 $version，预期为 ${task.versionCode}。请检查设备是否仍在安装。"
        }
        return Outcome(Progress(Stage.UNCERTAIN, "$message\n确认设备任务已结束后，可清理记录并重新安装。"),
            if (version == task.versionCode) InstalledTarget(device, task.packageName, task.versionCode, task.userId) else null, task)
    }

    fun acknowledge(taskId: String, device: TvDevice, tv: TvInfo, remote: TvRemote): Outcome {
        val task = journal.pending(device.id).firstOrNull { it.id == taskId }
            ?: throw PushFailure("TASK_MISSING", "任务已处理，请重新核对")
        checkIdentity(task, tv)
        remote.remove(task)
        journal.remove(task.id)
        return Outcome(Progress(Stage.IDLE, "已清理中断任务的临时文件和记录，可以选择 APK 再次安装"))
    }

    private fun cleanUploads(device: TvDevice, tv: TvInfo, remote: TvRemote) {
        journal.pending(device.id).filter { !it.submitted }.forEach {
            checkIdentity(it, tv)
            remote.remove(it)
            journal.remove(it.id)
        }
    }

    private fun checkIdentity(task: PendingInstall, tv: TvInfo) {
        if (task.serial.isNotBlank() && task.serial != tv.serial) throw PushFailure("DEVICE_CHANGED", "这个地址上的设备身份与原任务不一致，请检查设备 IP；任务记录已保留")
    }

    companion object {
        fun errorMessage(error: Exception, cancelled: Boolean = false): String = when {
            cancelled || error is CancellationException -> "已停止当前操作"
            error is PushFailure -> "${error.userMessage}\n错误码：${error.code}"
            error is SocketTimeoutException -> "连接或操作超时。请检查设备地址、网络调试开关和设备授权提示。\n错误码：TIMEOUT"
            error is ConnectException -> "无法连接设备。请检查 IP、实际调试端口和同一局域网内的设备互访。\n错误码：CONNECT_FAILED"
            error is SecurityException -> "文件读取权限或调试身份不可用，请重新选择文件并检查系统权限。\n错误码：ACCESS_DENIED"
            else -> "操作未完成，请检查文件、网络和设备授权后重试。\n错误码：${error.javaClass.simpleName}"
        }
    }
}
