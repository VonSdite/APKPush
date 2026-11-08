package dev.tvapkpush.play

import com.aurora.gplayapi.model.App
import com.aurora.gplayapi.model.AppDependencies
import com.aurora.gplayapi.model.AppDependentPackage
import com.aurora.gplayapi.model.AppLibrary
import com.aurora.gplayapi.model.Restriction
import dev.tvapkpush.core.PushFailure
import org.junit.Assert.*
import org.junit.Test

class PlayAppPolicyTest {
    private val app = App(packageName = "com.chess", versionCode = 280110, isFree = true)

    @Test fun requiredPlayServicesDoesNotBlockDownloadingApks() {
        PlayRepository.validateApp(app.copy(dependencies = AppDependencies(
            dependentPackages = listOf("com.google.android.gms"),
            packages = listOf(AppDependentPackage("com.google.android.gms", 12451000, type = 1)),
        )))
    }

    @Test fun optionalRuntimePackagesAndOrdinarySplitsAreAllowed() {
        PlayRepository.validateApp(app.copy(dependencies = AppDependencies(
            dependentPackages = listOf("com.example.companion"),
            packages = listOf(AppDependentPackage("com.example.companion", isOptional = true)),
            dependentSplits = listOf("config.arm64_v8a", "config.zh"),
        )))
    }

    @Test fun runtimePackagesDoNotHideARequiredSharedLibrary() {
        val error = assertThrows(PushFailure::class.java) {
            PlayRepository.validateApp(app.copy(dependencies = AppDependencies(
                dependentPackages = listOf("com.google.android.gms"),
                dependentLibraries = listOf(App("com.google.android.trichromelibrary", versionCode = 1)),
            )))
        }
        assertEquals("PLAY_DEPENDENCY", error.code)
        assertTrue(error.userMessage.contains("com.google.android.trichromelibrary"))
    }

    @Test fun structuredSdkLibrariesAreAlsoRejected() {
        val error = assertThrows(PushFailure::class.java) {
            PlayRepository.validateApp(app.copy(dependencies = AppDependencies(
                libraries = listOf(AppLibrary("com.example.sdk", versionCode = 1, type = 2)),
            )))
        }
        assertEquals("PLAY_DEPENDENCY", error.code)
    }

    @Test fun assetModulesRemainUnsupported() {
        assertEquals("PLAY_ASSETS", assertThrows(PushFailure::class.java) {
            PlayRepository.validateApp(app.copy(dependencies = AppDependencies(assetModuleNames = listOf("game_data"))))
        }.code)
    }

    @Test fun paidRestrictedAndInvalidVersionsRemainRejected() {
        assertEquals("PLAY_PAID", assertThrows(PushFailure::class.java) { PlayRepository.validateApp(app.copy(isFree = false)) }.code)
        assertEquals("PLAY_RESTRICTED", assertThrows(PushFailure::class.java) { PlayRepository.validateApp(app.copy(restriction = Restriction.GENERIC)) }.code)
        assertEquals("PLAY_VERSION", assertThrows(PushFailure::class.java) { PlayRepository.validateApp(app.copy(versionCode = 0)) }.code)
    }
}
