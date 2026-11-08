package dev.tvapkpush.core

import dev.tvapkpush.adb.AdbSession
import dev.tvapkpush.adb.FakeAdbd
import dev.tvapkpush.adb.ShellResult
import dev.tvapkpush.adb.testSigner
import dev.tvapkpush.data.TaskJournal
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class InstallerTest {
    private class Journal : TaskJournal {
        val tasks = linkedMapOf<String, PendingInstall>()
        override fun pending(deviceId: String) = tasks.values.filter { it.deviceId == deviceId }
        override fun put(task: PendingInstall) { tasks[task.id] = task }
        override fun remove(id: String) { tasks.remove(id) }
    }

    private val tv = TvInfo("Test TV", 31, setOf("arm64-v8a"), "tv-serial", 10, 1_000_000, true)

    private fun fixture(action: (ApkFile, Journal) -> Unit) {
        val file = File(System.getProperty("java.io.tmpdir"), "install-${UUID.randomUUID()}.apk").apply { writeBytes(ByteArray(9000) { it.toByte() }) }
        try {
            action(ApkFile(file, "Example", "org.example.tv", "2.0", 20, 29, emptySet()), Journal())
        } finally { file.delete() }
    }

    private fun command(command: String, packageName: String = "org.example.tv", version: Long = 20): ShellResult = when {
        command.startsWith("pm list packages ") -> ShellResult(0, "package:$packageName\n")
        command.startsWith("dumpsys package ") -> ShellResult(0, "  versionCode=$version minSdk=29 targetSdk=34\n")
        command.startsWith("pm install ") -> ShellResult(0, "Success\n")
        command.startsWith("rm -f ") -> ShellResult(0, "")
        else -> error("Unexpected command: $command")
    }

    @Test fun installVerifiesSelectedPackageAndUserThenCleansRemoteFile() = fixture { apk, journal ->
        FakeAdbd(command = { command(it) }).use { server ->
            val device = TvDevice("tv", "Living room", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val stages = mutableListOf<Stage>()
                val result = Installer(journal).install(device, apk, tv, session, JobControl()) { stages.add(it.stage) }
                assertEquals(Stage.SUCCESS, result.progress.stage)
                assertEquals(apk.packageName, result.installed?.packageName)
                assertEquals(10, result.installed?.userId)
                assertTrue(journal.tasks.isEmpty())
                assertTrue(server.commands.any { it.startsWith("pm install --user 10 -r /data/local/tmp/tvapk-") })
                assertTrue(server.commands.contains("pm list packages --user 10 org.example.tv"))
                assertTrue(server.commands.last().startsWith("rm -f /data/local/tmp/tvapk-"))
                assertTrue(stages.containsAll(listOf(Stage.UPLOADING, Stage.INSTALLING, Stage.VERIFYING)))
            }
        }
    }

    @Test fun changedSourceAfterTransferPreventsInstallationAndCleansUpload() = fixture { apk, journal ->
        var checks = 0
        val source = object : ApkSource {
            override fun checkUnchanged() {
                if (++checks == 2) throw PushFailure("APK_CHANGED", "Source changed")
            }
            override fun close() = Unit
            override fun openStream() = apk.file.inputStream()
        }
        FakeAdbd(command = { command(it) }).use { server ->
            val device = TvDevice("tv", "Test TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk.copy(source = source), tv, session, JobControl()) {}
                assertEquals(Stage.FAILED, result.progress.stage)
                assertFalse(server.commands.any { it.startsWith("pm install") })
                assertTrue(server.commands.last().startsWith("rm -f "))
                assertTrue(journal.tasks.isEmpty())
            }
        }
    }

    @Test fun corruptedUploadNeverReachesPackageManager() = fixture { apk, journal ->
        FakeAdbd(command = { command(it) }).use { server ->
            val device = TvDevice("tv", "Test TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk.copy(sha256 = "0".repeat(64)), tv, session, JobControl()) {}
                assertEquals(Stage.FAILED, result.progress.stage)
                assertFalse(server.commands.any { it.startsWith("pm install") })
            }
        }
    }

    @Test fun differentPackageUsesItsOwnVersionAndLaunchTarget() = fixture { apk, journal ->
        val different = apk.copy(packageName = "dev.another.player", versionCode = 41)
        FakeAdbd(command = { command(it, different.packageName, 41) }).use { server ->
            val device = TvDevice("tv", "Bedroom", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, different, tv, session, JobControl()) {}
                assertEquals(Stage.SUCCESS, result.progress.stage)
                assertEquals("dev.another.player", result.installed?.packageName)
                assertTrue(server.commands.contains("dumpsys package dev.another.player"))
            }
        }
    }

    @Test fun installationRejectionKeepsOriginalAppAndCleansUpload() = fixture { apk, journal ->
        FakeAdbd(command = { if (it.startsWith("pm install ")) ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]") else command(it) }).use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk, tv, session, JobControl()) {}
                assertEquals(Stage.FAILED, result.progress.stage)
                assertTrue(result.progress.message.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
                assertTrue(journal.tasks.isEmpty())
                assertFalse(server.commands.any { it.contains("uninstall") || it.contains(" -d ") || it.contains(" -g ") })
                assertTrue(server.commands.last().startsWith("rm -f "))
            }
        }
    }

    @Test fun transferCompleteWithoutPackageManagerReplyRemainsUncertain() = fixture { apk, journal ->
        FakeAdbd().use { server ->
            server.dropInstall = true
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk, tv, session, JobControl()) {}
                assertEquals(Stage.UNCERTAIN, result.progress.stage)
                assertEquals(apk.size, server.uploaded.size().toLong())
                assertTrue(journal.tasks.values.single().submitted)
                assertNull(result.installed)
            }
        }
    }

    @Test fun mismatchingVersionIsNotReportedAsSuccess() = fixture { apk, journal ->
        FakeAdbd(command = { command(it, version = 19) }).use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk, tv, session, JobControl()) {}
                assertEquals(Stage.UNCERTAIN, result.progress.stage)
                assertTrue(journal.tasks.values.single().submitted)
                assertFalse(server.commands.any { it.startsWith("rm ") })
            }
        }
    }

    @Test fun uploadCancellationLeavesOnlyCleanupWork() = fixture { apk, journal ->
        FakeAdbd().use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            val control = JobControl()
            AdbSession(testSigner()).use { session ->
                control.attach(session)
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk, tv, session, control) {
                    if (it.stage == Stage.UPLOADING && it.bytes > 0) control.cancel()
                }
                assertEquals(Stage.CANCELLED, result.progress.stage)
                assertFalse(journal.tasks.values.single().submitted)
                assertFalse(server.commands.any { it.startsWith("pm install ") })
            }
        }
    }

    @Test fun cancellationAfterSubmissionRequiresResultReconciliation() = fixture { apk, journal ->
        FakeAdbd().use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            val control = JobControl()
            AdbSession(testSigner()).use { session ->
                control.attach(session)
                session.connect(device.host, device.port) {}
                val result = Installer(journal).install(device, apk, tv, session, control) {
                    if (it.stage == Stage.INSTALLING) control.cancel()
                }
                assertEquals(Stage.UNCERTAIN, result.progress.stage)
                assertTrue(journal.tasks.values.single().submitted)
            }
        }
    }

    @Test fun reconnectChecksResultAndRequiresDecisionBeforeRetry() = fixture { apk, journal ->
        val task = PendingInstall(UUID.randomUUID().toString(), "tv", tv.serial, apk.packageName, 20, "Example", 10, true)
        journal.put(task)
        FakeAdbd(command = { command(it) }).use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val installer = Installer(journal)
                val result = installer.recover(device, tv, TvRemote(session))!!
                assertEquals(Stage.UNCERTAIN, result.progress.stage)
                assertEquals(task, result.decision)
                assertNotNull(result.installed)
                assertThrows(PushFailure::class.java) { installer.install(device, apk, tv, session, JobControl()) {} }
                assertTrue(server.commands.none { it.startsWith("pm install ") || it.startsWith("rm ") })
                installer.acknowledge(task.id, device, tv, TvRemote(session))
                assertTrue(journal.tasks.isEmpty())
                assertTrue(server.commands.last().startsWith("rm -f "))
            }
        }
    }

    @Test fun reconnectDoesNotCleanFilesOnDifferentDevice() = fixture { apk, journal ->
        journal.put(PendingInstall(UUID.randomUUID().toString(), "tv", "other-serial", apk.packageName, 20, "Example", 10, false))
        FakeAdbd().use { server ->
            val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
            AdbSession(testSigner()).use { session ->
                session.connect(device.host, device.port) {}
                val error = assertThrows(PushFailure::class.java) { Installer(journal).recover(device, tv, TvRemote(session)) }
                assertEquals("DEVICE_CHANGED", error.code)
                assertTrue(server.commands.isEmpty())
                assertFalse(journal.tasks.isEmpty())
            }
        }
    }

    @Test fun splitSetUsesOneSessionAndCommitsOnlyAfterEveryWrite() = fixture { base, journal ->
        val split = File(base.file.parentFile, "split-${UUID.randomUUID()}.apk").apply { writeBytes(ByteArray(800)) }
        try {
            val apk = base.copy(splits = listOf(split))
            FakeAdbd(command = {
                when {
                    it.startsWith("pm install-create ") -> ShellResult(0, "Success: created install session [77]\n")
                    it.startsWith("pm install-write ") || it == "pm install-commit 77" -> ShellResult(0, "Success\n")
                    else -> command(it)
                }
            }).use { server ->
                val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
                AdbSession(testSigner()).use { session ->
                    session.connect(device.host, device.port) {}
                    val result = Installer(journal).install(device, apk, tv, session, JobControl()) {}
                    assertEquals(result.progress.message, Stage.SUCCESS, result.progress.stage)
                    assertEquals(2, server.paths.size)
                    assertEquals(apk.size, server.uploaded.size().toLong())
                    assertTrue(server.commands.contains("pm install-create --user 10 -r -S ${apk.size}"))
                    val writes = server.commands.filter { it.startsWith("pm install-write ") }
                    assertEquals(2, writes.size)
                    assertTrue(server.commands.indexOf(writes.last()) < server.commands.indexOf("pm install-commit 77"))
                    assertEquals(1, server.commands.count { it == "pm install-commit 77" })
                    assertFalse(server.commands.any { it.startsWith("pm install ") })
                    assertTrue(server.commands.last().contains("-1.apk"))
                    assertTrue(journal.tasks.isEmpty())
                }
            }
        } finally { split.delete() }
    }

    @Test fun splitCommitDisconnectPreservesSessionAndAllCleanupPaths() = fixture { base, journal ->
        val split = File(base.file.parentFile, "split-${UUID.randomUUID()}.apk").apply { writeBytes(ByteArray(800)) }
        try {
            FakeAdbd(command = {
                if (it.startsWith("pm install-create ")) ShellResult(0, "Success: created install session [77]\n")
                else ShellResult(0, "Success\n")
            }).use { server ->
                server.dropInstall = true
                val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
                AdbSession(testSigner()).use { session ->
                    session.connect(device.host, device.port) {}
                    val result = Installer(journal).install(device, base.copy(splits = listOf(split)), tv, session, JobControl()) {}
                    assertEquals(Stage.UNCERTAIN, result.progress.stage)
                    val pending = journal.tasks.values.single()
                    assertTrue(pending.submitted)
                    assertEquals(77, pending.sessionId)
                    assertEquals(2, pending.remotePaths.size)
                    assertTrue(server.commands.none { it.startsWith("rm ") || it.startsWith("pm install-abandon ") })
                }
            }
        } finally { split.delete() }
    }

    @Test fun failedSplitWriteAbandonsSessionWithoutCommitting() = fixture { base, journal ->
        val split = File(base.file.parentFile, "split-${UUID.randomUUID()}.apk").apply { writeBytes(ByteArray(800)) }
        try {
            FakeAdbd(command = {
                when {
                    it.startsWith("pm install-create ") -> ShellResult(0, "Success: created install session [77]\n")
                    it.startsWith("pm install-write ") -> ShellResult(1, "Failure [INSTALL_FAILED_INVALID_APK]")
                    it == "pm install-abandon 77" -> ShellResult(0, "Success\n")
                    else -> command(it)
                }
            }).use { server ->
                val device = TvDevice("tv", "TV", "127.0.0.1", server.port)
                AdbSession(testSigner()).use { session ->
                    session.connect(device.host, device.port) {}
                    val result = Installer(journal).install(device, base.copy(splits = listOf(split)), tv, session, JobControl()) {}
                    assertEquals(Stage.FAILED, result.progress.stage)
                    assertFalse(server.commands.any { it.startsWith("pm install-commit ") })
                    assertTrue(result.progress.message + server.commands.toString(), server.commands.contains("pm install-abandon 77"))
                    assertTrue(journal.tasks.isEmpty())
                }
            }
        } finally { split.delete() }
    }
}
