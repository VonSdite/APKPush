package dev.tvapkpush.discovery

import java.net.Inet4Address

object LanAddresses {
    fun nearby(address: Inet4Address, prefixLength: Int): List<String> {
        if (prefixLength !in 0..32 || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress) return emptyList()
        val local = address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        // 主动扫描最多覆盖 /22，先检查本机所在的 /24，再检查相邻地址。
        val prefix = prefixLength.coerceAtLeast(22)
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val first = local and mask
        val last = first or (mask xor 0xffffffffL)
        val hosts = if (prefix >= 31) first..last else (first + 1) until last
        return hosts.filter { it != local }.sortedBy { if (it shr 8 == local shr 8) 0 else 1 }.map { host ->
            listOf(24, 16, 8, 0).joinToString(".") { shift -> ((host shr shift) and 255).toString() }
        }
    }
}
