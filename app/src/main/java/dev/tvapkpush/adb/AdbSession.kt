package dev.tvapkpush.adb

import dev.tvapkpush.core.PushFailure
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val output: String)

class AdbSession(private val signer: AdbSigner) : Closeable {
    private val socket = Socket()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "adb-timeout").apply { isDaemon = true }
    }
    @Volatile private var deadline = Long.MAX_VALUE
    @Volatile private var lastActivity = System.nanoTime()
    @Volatile private var idleNanos = Long.MAX_VALUE
    @Volatile private var timedOut = false
    private var maxPayload = 4096
    private var nextId = 1
    private var skipChecksum = true
    private var features = emptySet<String>()

    init {
        watchdog.scheduleWithFixedDelay({
            val now = System.nanoTime()
            if (now >= deadline || now - lastActivity >= idleNanos) {
                timedOut = true
                runCatching { socket.close() }
            }
        }, 250, 250, TimeUnit.MILLISECONDS)
    }

    fun connect(host: String, port: Int, awaitingAuthorization: () -> Unit) = limits(70_000, 60_000) {
        socket.connect(InetSocketAddress(host, port), 8_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 60_000
        send(AdbPacket("CNXN", 0x01000001, 256 * 1024, "host::features=shell_v2;\u0000".toByteArray()))
        var signed = false
        var offered = false
        while (true) {
            val packet = receive()
            when (packet.command) {
                "CNXN" -> {
                    if (packet.arg1 !in 4096..AdbPacket.MAX_PAYLOAD) throw IOException("Invalid ADB maximum payload")
                    maxPayload = minOf(packet.arg1, 256 * 1024)
                    skipChecksum = packet.arg0 >= 0x01000001
                    val banner = packet.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                    features = banner.substringAfter("features=", "").substringBefore(';').split(',').toSet()
                    break
                }
                "AUTH" -> {
                    if (packet.arg0 != 1 || packet.data.size != 20) throw IOException("Invalid ADB authentication challenge")
                    if (!signed) {
                        send(AdbPacket("AUTH", 2, 0, signer.sign(packet.data)))
                        signed = true
                    } else {
                        if (offered) throw PushFailure("AUTH_REJECTED", "设备未接受调试授权，请在设备上允许此手机连接")
                        awaitingAuthorization()
                        send(AdbPacket("AUTH", 3, 0, signer.publicKey()))
                        offered = true
                    }
                }
                "STLS" -> throw PushFailure("PAIRING_REQUIRED", "这是无线配对连接端口。当前版本支持传统网络 ADB，请使用设备的网络调试端口")
                else -> throw IOException("Unexpected ADB handshake command: ${packet.command}")
            }
        }
    }

    fun shell(command: String, timeoutMs: Long = 15_000): ShellResult = limits(timeoutMs, timeoutMs) {
        require(!command.contains('\u0000')) { "Invalid shell command" }
        val v2 = "shell_v2" in features
        val marker = "TVAPK_EXIT_${UUID.randomUUID().toString().replace("-", "")}_"
        val destination = if (v2) "shell,v2,raw:$command" else
            "shell:$command; tvapk_status=\$?; printf '\\n$marker%d\\n' \"\$tvapk_status\""
        open(destination).use { stream ->
            if (v2) readShellV2(stream) else {
                val output = readLimited(stream, 2 * 1024 * 1024).toString(Charsets.UTF_8)
                val match = Regex("(?:\\r?\\n)${marker}(\\d+)\\r?\\n?$").find(output)
                    ?: throw IOException("Missing shell exit status")
                ShellResult(match.groupValues[1].toInt(), output.substring(0, match.range.first))
            }
        }
    }

    fun push(file: File, remotePath: String, sha256: String? = null, progress: (Long) -> Unit) =
        file.inputStream().use { push(it, file.length(), remotePath, sha256, progress) }

    fun push(input: InputStream, expected: Long, remotePath: String, sha256: String? = null, progress: (Long) -> Unit) = limits(Long.MAX_VALUE, 15_000) {
        require(Regex("/data/local/tmp/tvapk-[0-9a-f-]{36}(?:-[0-9]{1,3})?\\.apk").matches(remotePath)) { "Invalid remote APK path" }
        open("sync:").use { stream ->
            val name = "$remotePath,33206".toByteArray(Charsets.UTF_8)
            stream.write(syncHeader("SEND", name.size) + name)
            var sent = 0L
            val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
            run {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (sent + count > expected) throw IOException("APK length changed while uploading")
                    digest?.update(buffer, 0, count)
                    stream.write(syncHeader("DATA", count) + buffer.copyOf(count))
                    sent += count
                    progress(sent)
                }
            }
            if (sent != expected) throw IOException("APK length changed while uploading")
            if (digest != null && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } != sha256) {
                throw PushFailure("DOWNLOAD_HASH", "安装包已损坏或被修改，请重新下载")
            }
            stream.write(syncHeader("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val reply = AdbPacket.readExactly(stream, 8)
            val status = reply.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val length = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (status == "FAIL" && length in 0..8192) {
                val reason = AdbPacket.readExactly(stream, length).toString(Charsets.UTF_8)
                throw PushFailure("UPLOAD_REJECTED", "设备未能保存 APK：${reason.take(500)}")
            }
            if (status != "OKAY" || length != 0) throw IOException("Invalid ADB sync completion")
            stream.write(syncHeader("QUIT", 0))
        }
    }

    private fun readShellV2(input: InputStream): ShellResult {
        val output = ByteArrayOutputStream()
        var exit: Int? = null
        while (true) {
            val id = input.read()
            if (id < 0) break
            val length = ByteBuffer.wrap(AdbPacket.readExactly(input, 4)).order(ByteOrder.LITTLE_ENDIAN).int
            if (length !in 0..(2 * 1024 * 1024 - output.size())) throw IOException("Shell output exceeds limit")
            val payload = AdbPacket.readExactly(input, length)
            when (id) {
                1, 2 -> output.write(payload)
                3 -> {
                    if (exit != null || payload.size != 1) throw IOException("Invalid shell exit packet")
                    exit = payload[0].toInt() and 255
                }
                else -> throw IOException("Unexpected shell packet: $id")
            }
        }
        return ShellResult(exit ?: throw IOException("Shell closed without exit status"), output.toString("UTF-8"))
    }

    private fun open(service: String): Stream {
        val localId = nextId++
        send(AdbPacket("OPEN", localId, 0, (service + '\u0000').toByteArray(Charsets.UTF_8)))
        while (true) {
            val packet = receive()
            if (packet.arg1 != localId && packet.command == "CLSE") continue
            if (packet.arg1 == localId && packet.command == "OKAY" && packet.arg0 != 0) return Stream(localId, packet.arg0)
            if (packet.command == "CLSE") throw IOException("ADB service rejected: ${service.substringBefore(':')}")
            throw IOException("Unexpected ADB service response")
        }
    }

    private inner class Stream(private val localId: Int, private val remoteId: Int) : InputStream() {
        private val pending = ArrayDeque<ByteArray>()
        private var buffered = 0
        private var current = byteArrayOf()
        private var offset = 0
        private var closed = false

        private fun process(): String {
            val packet = receive()
            if (packet.arg1 != localId || packet.arg0 != remoteId) {
                if (packet.command == "CLSE") return "STALE"
                throw IOException("ADB stream identifier mismatch")
            }
            when (packet.command) {
                "WRTE" -> {
                    buffered += packet.data.size
                    if (buffered > 2 * 1024 * 1024) throw IOException("ADB stream buffer exceeds limit")
                    if (packet.data.isNotEmpty()) pending.add(packet.data)
                    send(AdbPacket("OKAY", localId, remoteId))
                }
                "CLSE" -> {
                    closed = true
                    send(AdbPacket("CLSE", localId, remoteId))
                }
                "OKAY" -> Unit
                else -> throw IOException("Unexpected ADB stream command: ${packet.command}")
            }
            return packet.command
        }

        fun write(data: ByteArray) {
            var position = 0
            while (position < data.size) {
                if (closed) throw IOException("ADB stream is closed")
                val count = minOf(maxPayload, data.size - position)
                send(AdbPacket("WRTE", localId, remoteId, data.copyOfRange(position, position + count)))
                while (process() != "OKAY") {
                    if (closed) throw IOException("ADB stream closed before write acknowledgement")
                }
                position += count
            }
        }

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            while (offset == current.size) {
                if (pending.isNotEmpty()) {
                    current = pending.removeFirst()
                    buffered -= current.size
                    offset = 0
                    break
                }
                if (closed) return -1
                process()
            }
            val count = minOf(length, current.size - offset)
            current.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }

        override fun close() {
            if (!closed) {
                closed = true
                send(AdbPacket("CLSE", localId, remoteId))
            }
        }
    }

    private fun send(packet: AdbPacket) {
        packet.write(socket.getOutputStream())
        lastActivity = System.nanoTime()
    }

    private fun receive(): AdbPacket = AdbPacket.read(socket.getInputStream(), skipChecksum).also {
        lastActivity = System.nanoTime()
    }

    private fun <T> limits(totalMs: Long, idleMs: Long, block: () -> T): T {
        lastActivity = System.nanoTime()
        deadline = if (totalMs == Long.MAX_VALUE) Long.MAX_VALUE else lastActivity + TimeUnit.MILLISECONDS.toNanos(totalMs)
        idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMs)
        if (socket.isConnected && !socket.isClosed) socket.soTimeout = idleMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        try {
            return block()
        } catch (error: IOException) {
            if (timedOut) throw SocketTimeoutException("ADB operation timed out").apply { initCause(error) }
            throw error
        } finally {
            deadline = Long.MAX_VALUE
            idleNanos = Long.MAX_VALUE
        }
    }

    override fun close() {
        watchdog.shutdownNow()
        socket.close()
    }

    companion object {
        fun syncHeader(command: String, value: Int): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(command.toByteArray(Charsets.US_ASCII)).putInt(value).array()

        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("Shell output exceeds limit")
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
