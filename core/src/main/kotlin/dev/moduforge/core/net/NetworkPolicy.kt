package dev.moduforge.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Rules for connections the host opens on behalf of modules. */
object NetworkPolicy {

    /**
     * True for addresses on the public internet. `NETWORK_OUTBOUND` does not reach the device
     * itself, the local network or other non-routable ranges.
     */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                when {
                    first == 0 -> false
                    first == 100 && second in 64..127 -> false // carrier-grade NAT, 100.64.0.0/10
                    first == 192 && second == 0 && (bytes[2].toInt() and 0xFF) == 0 -> false // 192.0.0.0/24
                    first == 198 && second in 18..19 -> false // benchmarking, 198.18.0.0/15
                    first >= 240 -> false // reserved and broadcast
                    else -> true
                }
            }
            is Inet6Address -> (bytes[0].toInt() and 0xFE) != 0xFC // unique local, fc00::/7
            else -> false
        }
    }
}
