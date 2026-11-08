package dev.tvapkpush.play

import dev.tvapkpush.core.JobControl
import dev.tvapkpush.core.PushFailure
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.io.IOException
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import okhttp3.ResponseBody

class VerifiedDownloadTest {
    private val bytes = ByteArray(65536) { (it % 199).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun fixture(code: Int = 200, action: (VerifiedDownload, File) -> Unit) {
        val file = File(System.getProperty("java.io.tmpdir"), "play-${UUID.randomUUID()}.apk")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test response")
                .code(code).body(bytes.toResponseBody()).build()
        }.build()
        try { action(VerifiedDownload(client), file) } finally { file.delete() }
    }

    @Test fun verifiedFileContainsAllBytesAndReportsProgress() = fixture { downloader, file ->
        var last = 0L
        assertEquals(hash, downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl()) { last = it })
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(bytes.size.toLong(), last)
    }

    @Test fun checksumMismatchDeletesPartialFile() = fixture { downloader, file ->
        val error = assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), "0".repeat(64), "", 100000, JobControl()) {}
        }
        assertEquals("DOWNLOAD_HASH", error.code)
        assertFalse(file.exists())
    }

    @Test fun truncatedResponseIsNotSaved() = fixture { downloader, file ->
        val error = assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size + 1L, hash, "", 100000, JobControl()) {}
        }
        assertEquals("DOWNLOAD_INCOMPLETE", error.code)
        assertFalse(file.exists())
    }

    @Test fun expiredLinkIsNotSavedAsApk() = fixture(403) { downloader, file ->
        val error = assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl()) {}
        }
        assertEquals("DOWNLOAD_HTTP_403", error.code)
        assertFalse(file.exists())
    }

    @Test fun cancellationRemovesUnfinishedFile() = fixture { downloader, file ->
        val control = JobControl()
        assertThrows(CancellationException::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, control) { control.cancel() }
        }
        assertFalse(file.exists())
    }

    @Test fun insecureUrlAndMissingHashAreRejected() = fixture { downloader, file ->
        assertEquals("DOWNLOAD_URL", assertThrows(PushFailure::class.java) {
            downloader.fetch("http://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl()) {}
        }.code)
        assertEquals("DOWNLOAD_HASH", assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), "", "", 100000, JobControl()) {}
        }.code)
        assertFalse(file.exists())
    }

    @Test fun partialFileResumesFromItsExactOffset() = resumeFixture(206, "bytes 16384-65535/65536", bytes.copyOfRange(16384, bytes.size)) { downloader, file, requests ->
        file.writeBytes(bytes.copyOfRange(0, 16384))
        var first = -1L
        assertEquals(hash, downloader.fetch("https://example.com/refreshed.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {
            if (first < 0) first = it
        })
        assertEquals(listOf("bytes=16384-"), requests)
        assertEquals(16384L, first)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun serverIgnoringRangeReplacesPartialInsteadOfAppending() = resumeFixture(200, null, bytes) { downloader, file, requests ->
        file.writeBytes(bytes.copyOfRange(0, 16384))
        downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
        assertEquals(listOf("bytes=16384-"), requests)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun incorrectRangeIsDiscardedBeforeAnyAppend() = resumeFixture(206, "bytes 0-65535/65536", bytes) { downloader, file, _ ->
        file.writeBytes(bytes.copyOfRange(0, 16384))
        assertEquals("DOWNLOAD_RANGE", assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
        }.code)
        assertFalse(file.exists())
    }

    @Test fun completedPartIsVerifiedWithoutNetwork() = resumeFixture(500, null, bytes) { downloader, file, requests ->
        file.writeBytes(bytes)
        assertEquals(hash, downloader.fetch("https://example.com/expired.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {})
        assertTrue(requests.isEmpty())
    }

    @Test fun corruptedCompletePartIsDownloadedAgain() = resumeFixture(200, null, bytes) { downloader, file, requests ->
        file.writeBytes(ByteArray(bytes.size))
        downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
        assertEquals(listOf<String?>(null), requests)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun refreshedUrlKeepsExistingBytesOnAuthorizationFailure() = resumeFixture(403, null, byteArrayOf()) { downloader, file, _ ->
        val prefix = bytes.copyOfRange(0, 16384)
        file.writeBytes(prefix)
        assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/expired.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
        }
        assertArrayEquals(prefix, file.readBytes())
    }

    @Test fun networkFailureKeepsWrittenBytesForNextAttempt() {
        val file = File(System.getProperty("java.io.tmpdir"), "play-${UUID.randomUUID()}.apk")
        val prefix = bytes.copyOfRange(0, 8192)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = bytes.size.toLong()
                override fun source() = object : Source {
                    var sent = false
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (sent) throw IOException("Simulated network loss")
                        sent = true
                        sink.write(prefix)
                        return prefix.size.toLong()
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test response").code(200).body(body).build()
        }.build()
        try {
            assertThrows(IOException::class.java) {
                VerifiedDownload(client).fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
            }
            assertArrayEquals(prefix, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun processStyleInterruptionPreservesPartialButNeverAcceptsItsHash() = resumeFixture(200, null, bytes) { downloader, file, _ ->
        val control = JobControl()
        assertThrows(CancellationException::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, control, resume = true) {
                if (it > 0) control.cancel()
            }
        }
        assertTrue(file.length() > 0)
    }

    @Test fun resumedFileWithCorruptPrefixFailsFinalHash() = resumeFixture(206, "bytes 16384-65535/65536", bytes.copyOfRange(16384, bytes.size)) { downloader, file, _ ->
        file.writeBytes(ByteArray(16384))
        assertEquals("DOWNLOAD_HASH", assertThrows(PushFailure::class.java) {
            downloader.fetch("https://example.com/base.apk", file, bytes.size.toLong(), hash, "", 100000, JobControl(), resume = true) {}
        }.code)
        assertFalse(file.exists())
    }

    private fun resumeFixture(code: Int, range: String?, body: ByteArray,
                              action: (VerifiedDownload, File, List<String?>) -> Unit) {
        val file = File(System.getProperty("java.io.tmpdir"), "play-${UUID.randomUUID()}.apk")
        val requests = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request().header("Range"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test response")
                .code(code).body(body.toResponseBody()).apply { if (range != null) header("Content-Range", range) }.build()
        }.build()
        try { action(VerifiedDownload(client), file, requests) } finally { file.delete() }
    }
}
