package dev.tvapkpush.adb

import java.net.InetSocketAddress
import java.net.Socket

data class AdbProbeResult(val name: String = "Android 设备", val requiresPairing: Boolean = false)

object AdbProbe {
    fun inspect(socket: Socket, host: String, port: Int, timeoutMs: Int = 500): AdbProbeResult? {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        AdbPacket("CNXN", 0x01000001, 4096, "host::\u0000".toByteArray()).write(socket.getOutputStream())
        // 发现阶段仅识别握手响应，不发送密钥、不请求授权、不执行设备命令。
        val packet = AdbPacket.read(socket.getInputStream(), skipChecksum = true)
        return when (packet.command) {
            "AUTH" -> if (packet.arg0 == 1 && packet.arg1 == 0 && packet.data.size == 20) AdbProbeResult() else null
            "CNXN" -> {
                val banner = packet.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                if (packet.arg0 !in 0x01000000..0x01000001 || packet.arg1 !in 4096..AdbPacket.MAX_PAYLOAD ||
                    !banner.startsWith("device::")) return null
                val model = banner.substringAfter("device::").split(';')
                    .firstOrNull { it.startsWith("ro.product.model=") }?.substringAfter('=')
                    ?.filter { !it.isISOControl() }?.take(60)?.trim().orEmpty()
                AdbProbeResult(model.ifBlank { "Android 设备" })
            }
            "STLS" -> if (packet.arg0 == 0x01000000 && packet.data.isEmpty()) AdbProbeResult(requiresPairing = true) else null
            else -> null
        }
    }
}
