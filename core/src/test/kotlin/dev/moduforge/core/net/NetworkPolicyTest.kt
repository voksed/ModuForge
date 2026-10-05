package dev.moduforge.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkPolicyTest {

    private fun address(literal: String) = InetAddress.getByName(literal)

    @Test
    fun `public addresses are allowed`() {
        listOf("1.1.1.1", "149.154.167.220", "93.184.216.34", "2606:4700:4700::1111").forEach {
            assertTrue(it, NetworkPolicy.isPublic(address(it)))
        }
    }

    @Test
    fun `device, local network and reserved ranges are refused`() {
        listOf(
            "127.0.0.1", "0.0.0.0", "10.0.0.5", "172.16.3.1", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "192.0.0.8", "198.18.0.1", "224.0.0.1", "255.255.255.255", "240.0.0.1",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:192.168.1.1",
        ).forEach {
            assertFalse(it, NetworkPolicy.isPublic(address(it)))
        }
    }
}
