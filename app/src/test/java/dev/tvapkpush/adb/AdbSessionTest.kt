package dev.tvapkpush.adb

import dev.tvapkpush.core.PushFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun testSigner(): AdbSigner {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    return object : AdbSigner {
        override fun sign(token: ByteArray) = Signature.getInstance("NONEwithRSA").run {
            initSign(pair.private)
            update(AdbAuth.digestInfo(token))
            sign()
        }
        override fun publicKey() = AdbAuth.publicKey(pair.public as RSAPublicKey)
    }
}

class AdbSessionTest {
    @Test fun uploadValidatesExpectedDigestBeforeCompletingSync() {
        val file = File(System.getProperty("java.io.tmpdir"), "digest-${UUID.randomUUID()}.apk").apply { writeBytes(ByteArray(9000) { it.toByte() }) }
        try {
            val correct = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            listOf(correct, "0".repeat(64)).forEach { hash ->
                FakeAdbd().use { server ->
                    AdbSession(testSigner()).use { session ->
                        session.connect("127.0.0.1", server.port) {}
                        val action = { session.push(file, "/data/local/tmp/tvapk-${UUID.randomUUID()}.apk", hash) {} }
                        if (hash == correct) action()
                        else assertEquals("DOWNLOAD_HASH", assertThrows(PushFailure::class.java) { action() }.code)
                        assertFalse(server.commands.any { it.startsWith("pm install") })
                    }
                }
            }
        } finally { file.delete() }
    }

    @Test fun firstAuthorizationAndShellV2Work() {
        FakeAdbd(authorize = true, command = { ShellResult(7, "permission denied\n") }).use { server ->
            AdbSession(testSigner()).use { session ->
                var waiting = false
                session.connect("127.0.0.1", server.port) { waiting = true }
                assertTrue(waiting)
                assertTrue(server.offeredPublicKey)
                val result = session.shell("pm list packages")
                assertEquals(7, result.exitCode)
                assertEquals("permission denied\n", result.output)
            }
        }
    }

    @Test fun legacyShellCollectsExitStatusInsteadOfInferringSuccess() {
        FakeAdbd(shellV2 = false, command = { ShellResult(1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]") }).use { server ->
            AdbSession(testSigner()).use { session ->
                session.connect("127.0.0.1", server.port) {}
                val result = session.shell("pm install -r /data/local/tmp/test.apk")
                assertEquals(1, result.exitCode)
                assertEquals("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", result.output)
            }
        }
    }

    @Test fun syncPushPreservesBinaryContentsAndReportsAcknowledgedBytes() {
        val file = File(System.getProperty("java.io.tmpdir"), "upload-${UUID.randomUUID()}.apk")
        val payload = ByteArray(160_123) { (it * 41).toByte() }
        file.writeBytes(payload)
        try {
            FakeAdbd().use { server ->
                AdbSession(testSigner()).use { session ->
                    session.connect("127.0.0.1", server.port) {}
                    val progress = mutableListOf<Long>()
                    val path = "/data/local/tmp/tvapk-${UUID.randomUUID()}.apk"
                    session.push(file, path) { progress.add(it) }
                    assertArrayEquals(payload, server.uploaded.toByteArray())
                    assertEquals("$path,33206", server.paths.single())
                    assertEquals(payload.size.toLong(), progress.last())
                    assertTrue(progress.zipWithNext().all { it.first < it.second })
                    assertEquals(0, session.shell("echo done").exitCode)
                }
            }
        } finally { file.delete() }
    }

    @Test fun syncFailureNeverLooksLikeSuccessfulUpload() {
        val file = File(System.getProperty("java.io.tmpdir"), "upload-${UUID.randomUUID()}.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            FakeAdbd().use { server ->
                server.syncFailure = "No space left on device"
                AdbSession(testSigner()).use { session ->
                    session.connect("127.0.0.1", server.port) {}
                    val error = assertThrows(PushFailure::class.java) {
                        session.push(file, "/data/local/tmp/tvapk-${UUID.randomUUID()}.apk") {}
                    }
                    assertEquals("UPLOAD_REJECTED", error.code)
                    assertTrue(error.userMessage.contains("No space"))
                }
            }
        } finally { file.delete() }
    }

    @Test fun stalledShellTimesOut() {
        FakeAdbd().use { server ->
            server.stallShell = true
            AdbSession(testSigner()).use { session ->
                session.connect("127.0.0.1", server.port) {}
                assertThrows(SocketTimeoutException::class.java) { session.shell("sleep 10", 250) }
            }
        }
    }

    @Test fun closeInterruptsBlockedReads() {
        FakeAdbd().use { server ->
            server.stallShell = true
            val session = AdbSession(testSigner())
            session.connect("127.0.0.1", server.port) {}
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<Boolean> {
                    try { session.shell("sleep 100"); false } catch (_: java.io.IOException) { true }
                }
                session.close()
                assertTrue(future.get(2, TimeUnit.SECONDS))
            } finally { session.close(); worker.shutdownNow() }
        }
    }
}
