package dev.tvapkpush.play

import com.aurora.gplayapi.device.DeviceRegistry
import com.aurora.gplayapi.model.App
import dev.tvapkpush.core.ApkFile
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlayCatalogTest {
    private val phone = DeviceRegistry.default
    private val other = DeviceRegistry.available().first { it.fingerprint != phone.fingerprint }
    private fun apk(version: Long = 10, profile: String = PlayRepository.profileLabel(phone)) =
        ApkFile(File("unused.apk"), "Saved", "dev.fixture.saved", "99.0", version, 29, emptySet(),
            libraryId = "saved-$version", downloadProfile = profile)
    private fun state(vararg saved: ApkFile) = PlayState(profiles = listOf(phone, other), profileId = phone.id, downloads = saved.toList())
    private fun latest(version: Long) = mapOf(PlayAppKey("dev.fixture.saved", phone.fingerprint) to
        App(packageName = "dev.fixture.saved", versionCode = version, versionName = "1.0", isFree = true))

    @Test fun versionCodeControlsUpdatesInsteadOfVersionText() {
        val saved = apk()
        assertNotNull(state(saved).copy(latest = latest(11)).updateFor(saved))
        assertNull(state(saved).copy(latest = latest(10)).updateFor(saved))
        assertNull(state(saved).copy(latest = latest(9)).updateFor(saved))
    }

    @Test fun originalProfileControlsUpdatesAfterSwitchingSearchProfile() {
        val saved = apk()
        val switched = state(saved).copy(profileId = other.id, latest = latest(11))
        assertEquals(phone.fingerprint, switched.updateFor(saved)!!.profile.fingerprint)
        assertNull(switched.savedApp(saved.packageName))
        assertNull(state(apk(profile = PlayRepository.profileLabel(other))).copy(latest = latest(11)).updateFor(apk(profile = PlayRepository.profileLabel(other))))
    }

    @Test fun unknownProfilesDoNotBorrowAnotherDeviceVersion() {
        val saved = apk(profile = "Unavailable device")
        assertNull(state(saved).copy(latest = latest(100)).updateFor(saved))
    }

    @Test fun completedUpdateSuppressesTheActionForRetainedOlderCopies() {
        val old = apk()
        val current = apk(11)
        val state = state(old, current).copy(latest = latest(11))
        assertEquals(current, state.savedApp(old.packageName))
        assertNull(state.updateFor(old))
        assertNull(state.updateFor(current))
    }

    @Test fun sortingKeepsSavedThenTasksThenOtherResultsWithStableRelevance() {
        val packages = listOf("free1", "active1", "saved", "free2", "active2")
        val state = state(apk()).copy(
            apps = packages.map { App(packageName = "dev.fixture.$it") },
            tasks = listOf(DownloadTask("a", "dev.fixture.active1", "A", phone),
                DownloadTask("b", "dev.fixture.active2", "B", phone, phase = DownloadPhase.PAUSED)),
        )
        assertEquals(listOf("saved", "active1", "active2", "free1", "free2"),
            state.orderedApps().map { it.packageName.substringAfterLast('.') })
        assertEquals(packages, state.apps.map { it.packageName.substringAfterLast('.') })
    }

    @Test fun tasksAndSavedPackagesFromOtherProfilesDoNotReplaceCurrentProfileActions() {
        val state = state(apk(profile = PlayRepository.profileLabel(other))).copy(
            tasks = listOf(DownloadTask("task", "dev.fixture.saved", "Other", other)))
        assertNull(state.savedApp("dev.fixture.saved"))
        assertNull(state.downloadTask("dev.fixture.saved"))
    }
}
