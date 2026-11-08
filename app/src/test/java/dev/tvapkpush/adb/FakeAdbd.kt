package dev.tvapkpush.adb

import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FakeAdbd(
    private val shellV2: Boolean = true,
    private val authorize: Boolean = false,
    private val command: (String) -> ShellResult = { ShellResult(0, "Success\n") },
) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile var dropInstall = false
    @Volatile var stallShell = false
    @Volatile var syncFailure: String? = null
    @Volatile var offeredPublicKey = false
    val uploaded = ByteArrayOutputStream()
    val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
    val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val work = executor.submit {
        try {
            server.accept().use { peer ->
                socket = peer
                peer.soTimeout = 5000
                serve(peer)
            }
        } catch (_: EOFException) {
        } catch (_: SocketException) {
        }
    }

    private fun serve(peer: Socket) {
        fun send(packet: AdbPacket) = packet.write(peer.getOutputStream())
        fun read() = AdbPacket.read(peer.getInputStream())
        assertEquals("CNXN", read().command)
        if (authorize) {
            send(AdbPacket("AUTH", 1, 0, ByteArray(20) { 7 }))
            val signature = read()
            assertEquals("AUTH", signature.command)
            assertEquals(2, signature.arg0)
            assertEquals(256, signature.data.size)
            send(AdbPacket("AUTH", 1, 0, ByteArray(20) { 9 }))
            val public = read()
            assertEquals("AUTH", public.command)
            assertEquals(3, public.arg0)
            assertTrue(public.data.toString(Charsets.UTF_8).endsWith(" APKPush@android\u0000"))
            offeredPublicKey = true
        }
        send(AdbPacket("CNXN", 0x01000000, 4096, "device::features=${if (shellV2) "shell_v2" else ""};\u0000".toByteArray()))
        var remoteId = 20
        while (true) {
            val open = read()
            if (open.command == "CLSE") continue
            assertEquals("OPEN", open.command)
            val localId = open.arg0
            remoteId++
            val destination = open.data.toString(Charsets.UTF_8).trimEnd('\u0000')
            send(AdbPacket("OKAY", remoteId, localId))
            if (destination == "sync:") {
                val pending = ByteArrayOutputStream()
                var done = false
                while (true) {
                    val packet = read()
                    if (packet.command == "CLSE") {
                        send(AdbPacket("CLSE", remoteId, localId))
                        break
                    }
                    assertEquals("WRTE", packet.command)
                    assertEquals(localId, packet.arg0)
                    assertEquals(remoteId, packet.arg1)
                    pending.write(packet.data)
                    send(AdbPacket("OKAY", remoteId, localId))
                    var data = pending.toByteArray()
                    var consumed = 0
                    while (data.size - consumed >= 8) {
                        val kind = data.copyOfRange(consumed, consumed + 4).toString(Charsets.US_ASCII)
                        val size = ByteBuffer.wrap(data, consumed + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val length = if (kind == "DONE" || kind == "QUIT") 0 else size
                        if (data.size - consumed < 8 + length) break
                        val payload = data.copyOfRange(consumed + 8, consumed + 8 + length)
                        when (kind) {
                            "SEND" -> paths.add(payload.toString(Charsets.UTF_8))
                            "DATA" -> uploaded.write(payload)
                            "DONE" -> done = true
                            "QUIT" -> Unit
                            else -> error("Unexpected sync message: $kind")
                        }
                        consumed += 8 + length
                    }
                    pending.reset()
                    pending.write(data, consumed, data.size - consumed)
                    if (done) {
                        done = false
                        val failure = syncFailure?.toByteArray()
                        val status = if (failure == null) "OKAY".toByteArray() + byteArrayOf(0, 0, 0, 0)
                        else "FAIL".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(failure.size).array() + failure
                        send(AdbPacket("WRTE", remoteId, localId, status))
                        assertEquals("OKAY", read().command)
                    }
                }
            } else {
                val rawCommand = destination.substringAfter(':')
                val cleanCommand = rawCommand.substringBefore("; tvapk_status=")
                commands.add(cleanCommand)
                if (dropInstall && (cleanCommand.startsWith("pm install ") || cleanCommand.startsWith("pm install-commit "))) return
                if (stallShell) {
                    while (peer.getInputStream().read() != -1) Unit
                    return
                }
                val result = command(cleanCommand)
                val payload = if (shellV2) frame(1, result.output.toByteArray()) + frame(3, byteArrayOf(result.exitCode.toByte())) else {
                    val marker = Regex("TVAPK_EXIT_[a-f0-9]+_").find(rawCommand)!!.value
                    (result.output + "\n$marker${result.exitCode}\n").toByteArray()
                }
                for (chunk in payload.asList().chunked(37)) {
                    send(AdbPacket("WRTE", remoteId, localId, chunk.toByteArray()))
                    assertEquals("OKAY", read().command)
                }
                send(AdbPacket("CLSE", remoteId, localId))
                assertEquals("CLSE", read().command)
            }
        }
    }

    override fun close() {
        socket?.close()
        server.close()
        try { work.get(6, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
    }

    companion object {
        fun frame(id: Int, data: ByteArray) = byteArrayOf(id.toByte()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array() + data
    }
}
