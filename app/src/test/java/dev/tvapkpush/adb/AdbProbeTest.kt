package dev.tvapkpush.adb

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdbProbeTest {
    private fun probe(reply: AdbPacket): AdbProbeResult? = ServerSocket(0).use { server ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val peer = worker.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    assertEquals("CNXN", AdbPacket.read(socket.getInputStream()).command)
                    reply.write(socket.getOutputStream())
                    assertEquals(-1, socket.getInputStream().read())
                }
                true
            }
            val result = Socket().use { AdbProbe.inspect(it, "127.0.0.1", server.localPort, 1_000) }
            assertTrue(peer.get(3, TimeUnit.SECONDS))
            result
        } finally { worker.shutdownNow() }
    }

    @Test fun recognizesAuthenticationWithoutOfferingAnyKey() {
        assertEquals(AdbProbeResult(), probe(AdbPacket("AUTH", 1, 0, ByteArray(20))))
    }

    @Test fun acceptsValidDeviceBannerAndExtractsModel() {
        assertEquals(AdbProbeResult("Android Tablet"), probe(AdbPacket("CNXN", 0x01000001, 4096,
            "device::ro.product.model=Android Tablet;features=shell_v2;\u0000".toByteArray())))
    }

    @Test fun recognizesTlsWithoutAttemptingAuthentication() {
        assertTrue(probe(AdbPacket("STLS", 0x01000000, 0))!!.requiresPairing)
    }

    @Test fun rejectsOtherServicesAndMalformedAdbResponses() {
        assertNull(probe(AdbPacket("HTTP", 0, 0)))
        assertNull(probe(AdbPacket("AUTH", 2, 0, ByteArray(20))))
        assertNull(probe(AdbPacket("AUTH", 1, 0, ByteArray(19))))
        assertNull(probe(AdbPacket("CNXN", 0x01000001, 4096, "host::\u0000".toByteArray())))
    }

    @Test fun silentEndpointTimesOut() {
        ServerSocket(0).use { server ->
            Socket().use { socket ->
                assertThrows(SocketTimeoutException::class.java) { AdbProbe.inspect(socket, "127.0.0.1", server.localPort, 80) }
            }
        }
    }
}
