package dev.tvapkpush.discovery

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class LanAddressesTest {
    private fun hosts(ip: String, prefix: Int) = LanAddresses.nearby(InetAddress.getByName(ip) as Inet4Address, prefix)

    @Test fun normalSubnetExcludesSelfNetworkAndBroadcast() {
        val result = hosts("192.168.7.20", 24)
        assertEquals(253, result.size)
        assertEquals("192.168.7.1", result.first())
        assertEquals("192.168.7.254", result.last())
        assertFalse(result.contains("192.168.7.20"))
    }

    @Test fun wideSubnetIsBoundedToLocalSlash22AndLocalBlockComesFirst() {
        val result = hosts("10.20.30.40", 8)
        assertEquals(1021, result.size)
        assertEquals(hosts("10.20.30.40", 22), result)
        assertTrue(result.take(255).all { it.startsWith("10.20.30.") })
        assertTrue(result.contains("10.20.29.10"))
        assertFalse(result.contains("10.20.32.10"))
    }

    @Test fun slash23IncludesAdjacentBlockWithinRealSubnet() {
        val result = hosts("192.168.6.20", 23)
        assertEquals(509, result.size)
        assertTrue(result.contains("192.168.7.10"))
        assertFalse(result.contains("192.168.5.10"))
        assertFalse(result.contains("192.168.7.255"))
    }

    @Test fun narrowAndPointToPointSubnetsKeepTheirActualBounds() {
        assertEquals(listOf("192.168.1.2"), hosts("192.168.1.1", 30))
        assertEquals(listOf("192.168.1.0"), hosts("192.168.1.1", 31))
        assertTrue(hosts("192.168.1.1", 32).isEmpty())
    }

    @Test fun invalidOrNonLanAddressesAreExcluded() {
        for (ip in listOf("127.0.0.1", "169.254.1.1", "0.0.0.0", "224.0.0.1")) assertTrue(hosts(ip, 24).isEmpty())
        assertTrue(hosts("192.168.1.2", -1).isEmpty())
        assertTrue(hosts("192.168.1.2", 33).isEmpty())
    }
}
