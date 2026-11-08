package dev.tvapkpush.play

import com.aurora.gplayapi.device.DeviceProfile
import com.aurora.gplayapi.model.App
import dev.tvapkpush.core.ApkFile

data class PlayAppKey(val packageName: String, val profileFingerprint: String)
data class PlayUpdate(val app: App, val profile: DeviceProfile)

fun PlayState.savedApp(packageName: String, profile: DeviceProfile? = this.profile): ApkFile? {
    val label = profile?.let(PlayRepository::profileLabel) ?: return null
    return downloads.filter { it.packageName == packageName && it.downloadProfile == label }.maxByOrNull { it.versionCode }
}

fun PlayState.downloadTask(packageName: String, profile: DeviceProfile? = this.profile): DownloadTask? =
    tasks.firstOrNull { it.packageName == packageName && it.profile.fingerprint == profile?.fingerprint }

fun PlayState.profileFor(apk: ApkFile): DeviceProfile? = profiles.firstOrNull { PlayRepository.profileLabel(it) == apk.downloadProfile }

fun PlayState.updateFor(apk: ApkFile): PlayUpdate? {
    val profile = profileFor(apk) ?: return null
    val app = latest[PlayAppKey(apk.packageName, profile.fingerprint)] ?: return null
    val saved = savedApp(apk.packageName, profile) ?: return null
    return if (app.versionCode > saved.versionCode) PlayUpdate(app, profile) else null
}

fun PlayState.orderedApps(): List<App> {
    val profile = profile ?: return apps
    val label = PlayRepository.profileLabel(profile)
    val saved = downloads.filter { it.downloadProfile == label }.map { it.packageName }.toSet()
    val pending = tasks.filter { it.profile.fingerprint == profile.fingerprint }.map { it.packageName }.toSet()
    return apps.sortedBy {
        when (it.packageName) {
            in saved -> 0
            in pending -> 1
            else -> 2
        }
    }
}
