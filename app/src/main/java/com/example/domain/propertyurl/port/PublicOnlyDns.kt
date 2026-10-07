package com.example.domain.propertyurl.port

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS policy for public-property fetches. OkHttp uses these exact validated answers to connect,
 * avoiding a separate check-then-resolve-again window that would permit DNS rebinding.
 */
internal class PublicOnlyDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            // Do not include user-controlled hostnames or resolved addresses in diagnostics.
            throw UnknownHostException("Destination did not resolve exclusively to public addresses.")
        }
        return addresses.toList()
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false // carrier-grade NAT
            a == 169 && b == 254 -> false // link local / cloud metadata
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 -> false // protocol assignments
            a == 192 && b == 88 && c == 99 -> false // deprecated 6to4 relay anycast
            a == 198 && (b == 18 || b == 19) -> false // benchmarking
            a == 198 && b == 51 && c == 100 -> false // documentation
            a == 203 && b == 0 && c == 113 -> false // documentation
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false

        // IPv4-mapped IPv6 answers inherit the IPv4 range policy.
        val mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (mapped) return isPublicIpv4(bytes.copyOfRange(12, 16))

        // IPv4-compatible IPv6, ULA, link-local, multicast and other non-global prefixes
        // are never destinations for this public-listing transport.
        val first = bytes[0].toInt() and 0xff
        if ((first and 0xe0) != 0x20 || (first and 0xfe) == 0xfc || first == 0xff) return false
        if (bytes.take(12).all { it == 0.toByte() }) return false

        // Exclude documentation, 6to4 and Teredo prefixes; all can encode or tunnel to
        // addresses that must not be reached through this public-URL import path.
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return false
        if (first == 0x20 && second == 0x02) return false
        if (first == 0x20 && second == 0x01 && third <= 0x01) return false // 2001::/23 special-purpose
        return true
    }
}
