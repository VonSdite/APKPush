package dev.tvapkpush.core

import dev.tvapkpush.adb.AdbSession
import dev.tvapkpush.adb.FakeAdbd
import dev.tvapkpush.adb.ShellResult
import dev.tvapkpush.adb.testSigner
import org.junit.Assert.*
import org.junit.Test

class TvRemoteTest {
    @Test fun launchUsesTheEntryForTheDeviceType() {
        for (television in listOf(false, true)) {
            var resolved = ""
            FakeAdbd(command = { command ->
                when {
                    command.startsWith("cmd package resolve-activity") -> {
                        resolved = command
                        ShellResult(0, "dev.sample.app/.MainActivity\n")
                    }
                    command.startsWith("am start") -> ShellResult(0, "Starting: Intent\n")
                    else -> error("Unexpected command")
                }
            }).use { server ->
                AdbSession(testSigner()).use { session ->
                    session.connect("127.0.0.1", server.port) {}
                    TvRemote(session).launch("dev.sample.app", 0, television)
                }
            }
            assertTrue(resolved.contains(if (television) "LEANBACK_LAUNCHER" else "category.LAUNCHER "))
        }
    }

    @Test fun televisionFeatureIdentifiesEmulatorWithGenericCharacteristics() {
        FakeAdbd(command = { command ->
            when {
                command.startsWith("getprop") -> ShellResult(0, "AOSP TV on x86\n31\nx86\nx86\nEMULATOR31\nemulator\n0\n")
                command.startsWith("df ") -> ShellResult(0, "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-0 100 40 60 40% /data\n")
                command == "pm list features" -> ShellResult(0, "feature:android.software.leanback\n")
                else -> error("Unexpected command")
            }
        }).use { server ->
            AdbSession(testSigner()).use { session ->
                session.connect("127.0.0.1", server.port) {}
                val info = TvRemote(session).info()
                assertTrue(info.isTelevision)
                assertEquals(31, info.sdk)
                assertEquals(setOf("x86"), info.abis)
                assertEquals(60L * 1024, info.freeBytes)
                assertEquals(0, info.userId)
            }
        }
    }
}
