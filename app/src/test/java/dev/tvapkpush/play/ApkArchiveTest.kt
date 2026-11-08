package dev.tvapkpush.play

import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.tvapkpush.play.ApkArchive.hex

class ApkArchiveTest {
    private val bytes = ByteArray(270_000) { (it % 199).toByte() }
    private fun part(name: String, content: ByteArray = bytes) = DownloadPart(name, content.size.toLong(), MessageDigest.getInstance("SHA-256").digest(content).hex())

    private fun fixture(action: (File) -> Unit) {
        val folder = File(System.getProperty("java.io.tmpdir"), "archive-${UUID.randomUUID()}").apply { mkdirs() }
        try { action(folder) } finally { folder.deleteRecursively() }
    }

    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { archive -> names.forEach { name ->
            archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
        } }
    }.toByteArray()

    @Test fun singleApkRemainsAnOrdinaryFile() = fixture { folder ->
        val source = File(folder, "base.apk").apply { writeBytes(bytes) }
        val output = ByteArrayOutputStream()
        ApkArchive.write(listOf(source), output, JobControl())
        assertArrayEquals(bytes, output.toByteArray())
        val target = File(folder, "target").apply { mkdir() }
        ApkArchive.extract(output.toByteArray().inputStream(), listOf(part("base.apk")), target, JobControl())
        assertArrayEquals(bytes, File(target, "base.apk").readBytes())
    }

    @Test fun splitArchivePreservesEveryApk() = fixture { folder ->
        val names = listOf("base.apk", "split-1.apk", "split-2.apk")
        val files = names.map { File(folder, it).apply { writeBytes(bytes) } }
        val output = ByteArrayOutputStream()
        ApkArchive.write(files, output, JobControl())
        val target = File(folder, "target").apply { mkdir() }
        ApkArchive.extract(output.toByteArray().inputStream(), names.map { part(it) }, target, JobControl())
        assertEquals(names.toSet(), target.list()!!.toSet())
        names.forEach { assertArrayEquals(bytes, File(target, it).readBytes()) }
    }

    @Test fun missingSplitIsRejected() = fixture { folder ->
        assertThrows(PushFailure::class.java) {
            ApkArchive.extract(zip("base.apk").inputStream(), listOf(part("base.apk"), part("split-1.apk")), folder, JobControl())
        }
    }

    @Test fun pathTraversalAndUnexpectedFilesAreRejected() = fixture { folder ->
        for (name in listOf("../outside.apk", "/outside.apk", "other.apk")) {
            assertThrows(PushFailure::class.java) {
                ApkArchive.extract(zip("base.apk", name).inputStream(), listOf(part("base.apk"), part("split-1.apk")), folder, JobControl())
            }
        }
        assertEquals(setOf("base.apk"), folder.list()!!.toSet())
    }

    @Test fun duplicateEntriesAreRejected() = fixture { folder ->
        val raw = zip("base.apk", "evil.apk").toString(Charsets.ISO_8859_1).replace("evil.apk", "base.apk").toByteArray(Charsets.ISO_8859_1)
        assertThrows(PushFailure::class.java) {
            ApkArchive.extract(raw.inputStream(), listOf(part("base.apk"), part("split-1.apk")), folder, JobControl())
        }
    }

    @Test fun changedBytesAreRejectedEvenAtTheSameLength() = fixture { folder ->
        val changed = bytes.copyOf().apply { this[0] = 99 }
        assertThrows(PushFailure::class.java) {
            ApkArchive.extract(changed.inputStream(), listOf(part("base.apk")), folder, JobControl())
        }
    }

    @Test fun truncatedAndOversizedFilesAreRejected() = fixture { folder ->
        for (content in listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            assertThrows(PushFailure::class.java) {
                ApkArchive.extract(content.inputStream(), listOf(part("base.apk")), folder, JobControl())
            }
        }
    }

    @Test fun cancellationStopsArchiveReading() = fixture { folder ->
        val control = JobControl()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { control.requestCancellation() }
        }
        assertThrows(CancellationException::class.java) {
            ApkArchive.extract(input, listOf(part("base.apk")), folder, control)
        }
        assertTrue(File(folder, "base.apk").length() < bytes.size)
    }
}
