package dev.tvapkpush.install

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import dev.tvapkpush.core.ApkFile
import dev.tvapkpush.core.InstallPolicy
import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import dev.tvapkpush.core.TvInfo

internal object LocalInstaller {
    fun stage(context: Context, apk: ApkFile, control: JobControl): Int {
        InstallPolicy.check(apk, TvInfo(Build.MODEL, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toSet(), "", 0,
            StatFs(context.filesDir.path).availableBytes, false))
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(apk.packageName)
            setAppLabel(apk.label)
            setSize(apk.size)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        control.check()
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.files.forEachIndexed { index, file ->
                    control.check()
                    session.openWrite(if (index == 0) "base.apk" else "split-$index.apk", 0, file.length()).use { output ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                control.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                        session.fsync(output)
                    }
                }
                control.check()
            }
            return id
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            throw error
        }
    }

    fun failureMessage(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "系统阻止了安装，请检查安装权限和手机上的提示。"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "与本机已安装应用冲突，请检查签名或版本。"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装包与本机不兼容，请将下载机型设为本机后重新下载。"
        PackageInstaller.STATUS_FAILURE_INVALID -> "安装包无效或缺少必要文件，请重新下载。"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "手机存储空间不足，请清理后重试。"
        else -> "系统未完成安装，请查看系统提示后重试。"
    }

    fun preparationMessage(error: Exception): String = if (error is PushFailure) error.userMessage
        else "无法准备安装包，请检查文件和手机存储空间后重试。"
}
