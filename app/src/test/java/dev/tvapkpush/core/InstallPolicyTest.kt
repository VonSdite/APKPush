package dev.tvapkpush.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InstallPolicyTest {
    private val apk = ApkFile(File("sample.apk"), "Example", "org.example.tv", "1.2", 12, 29, setOf("arm64-v8a"))
    private val tv = TvInfo("Android TV", 31, setOf("arm64-v8a", "armeabi-v7a"), "serial", 0, null, true)

    @Test fun compatibleAndArchitectureIndependentPackagesPass() {
        InstallPolicy.check(apk, tv)
        InstallPolicy.check(apk.copy(abis = emptySet()), tv.copy(abis = setOf("x86")))
    }

    @Test fun oldSdkAndWrongArchitectureAreDistinct() {
        assertEquals("INSTALL_FAILED_OLDER_SDK", assertThrows(PushFailure::class.java) { InstallPolicy.check(apk, tv.copy(sdk = 28)) }.code)
        assertEquals("INSTALL_FAILED_NO_MATCHING_ABIS", assertThrows(PushFailure::class.java) { InstallPolicy.check(apk, tv.copy(abis = setOf("x86_64"))) }.code)
    }

    @Test fun packageManagerErrorsPreserveCodesAndRawDetails() {
        listOf("UPDATE_INCOMPATIBLE", "VERSION_DOWNGRADE", "NO_MATCHING_ABIS", "OLDER_SDK", "INSUFFICIENT_STORAGE", "USER_RESTRICTED", "MISSING_SPLIT").forEach {
            val output = "Failure [INSTALL_FAILED_$it: sample detail]"
            val error = InstallPolicy.installError(output)
            assertEquals("INSTALL_FAILED_$it", error.code)
            assertTrue(error.userMessage.contains("sample detail"))
        }
    }

    @Test fun packageNamesAndPortsCannotInjectShellCommands() {
        listOf("org.example;reboot", "org.example\nreboot", "org.example/../../", "org.a'", "a.").forEach {
            assertThrows(PushFailure::class.java) { InputRules.packageName(it) }
        }
        assertEquals("org.example.tv_app", InputRules.packageName("org.example.tv_app"))
        listOf("0", "65536", "5555;reboot", "-1").forEach { assertThrows(PushFailure::class.java) { InputRules.port(it) } }
        assertEquals(5555, InputRules.port("5555"))
    }

    @Test fun manualAddressesAreLiteralAndValidated() {
        assertEquals("192.168.1.10", InputRules.host(" 192.168.1.10 "))
        assertEquals("::1", InputRules.host("[::1]"))
        listOf("999.2.3.4", "tv.example.com", "http://192.168.1.1", "1.2.3", "1.2.3.4;id").forEach {
            assertThrows(PushFailure::class.java) { InputRules.host(it) }
        }
    }

    @Test fun versionParsingSupportsLongVersionCodes() {
        assertEquals(4294967300L, InstallPolicy.installedVersion("  versionCode=4294967300 minSdk=29 targetSdk=34\n"))
        assertNull(InstallPolicy.installedVersion("versionName=1.0"))
    }

    @Test fun cleanupPathsOnlyAcceptTaskUuids() {
        val id = "5baf952f-9348-4e10-a4a7-a277869411cb"
        assertEquals("/data/local/tmp/tvapk-$id.apk", PendingInstall.remotePath(id))
        listOf("../../etc", "foo;reboot", "1-1-1-1-1").forEach {
            assertThrows(IllegalArgumentException::class.java) { PendingInstall.remotePath(it) }
        }
    }

    @Test fun cancellationClosesExistingAndLateResources() {
        val control = JobControl()
        var closed = 0
        control.attach(java.io.Closeable { closed++ })
        control.cancel()
        assertEquals(1, closed)
        assertThrows(java.util.concurrent.CancellationException::class.java) { control.attach(java.io.Closeable { closed++ }) }
        assertEquals(2, closed)
        assertThrows(java.util.concurrent.CancellationException::class.java) { control.check() }
    }
}
