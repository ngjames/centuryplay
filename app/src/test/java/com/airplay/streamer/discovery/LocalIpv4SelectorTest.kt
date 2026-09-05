package com.airplay.streamer.discovery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for the [LocalIpv4Selector] ladder used to pick the address
 * mDNS binds and advertises.
 */
class LocalIpv4SelectorTest {

    private fun ip(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private fun candidate(
        name: String,
        a: Int, b: Int, c: Int, d: Int,
        fromDefaultNetwork: Boolean = false
    ) = LocalIpv4Selector.Candidate(fromDefaultNetwork, name, ip(a, b, c, d))

    // --- helpers ---

    @Test
    fun `tunnel detection covers common prefixes case-insensitively`() {
        assertTrue(LocalIpv4Selector.isTunnelInterface("tun0"))
        assertTrue(LocalIpv4Selector.isTunnelInterface("TUN0"))
        assertTrue(LocalIpv4Selector.isTunnelInterface("tap9"))
        assertTrue(LocalIpv4Selector.isTunnelInterface("ppp0"))
        assertTrue(LocalIpv4Selector.isTunnelInterface("vpn_service"))
        assertFalse(LocalIpv4Selector.isTunnelInterface("wlan0"))
        assertFalse(LocalIpv4Selector.isTunnelInterface("eth0"))
        assertFalse(LocalIpv4Selector.isTunnelInterface("rmnet0"))
    }

    @Test
    fun `private range boundaries are exact`() {
        assertTrue(LocalIpv4Selector.isPrivateRange(ip(10, 0, 0, 1)))
        assertTrue(LocalIpv4Selector.isPrivateRange(ip(172, 16, 0, 1)))
        assertTrue(LocalIpv4Selector.isPrivateRange(ip(172, 31, 255, 255)))
        assertTrue(LocalIpv4Selector.isPrivateRange(ip(192, 168, 1, 50)))
        // Just outside each range.
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(172, 15, 255, 255)))
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(172, 32, 0, 0)))
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(11, 0, 0, 1)))
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(193, 168, 1, 1)))
        // CGNAT and link-local are NOT RFC1918.
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(100, 64, 0, 1)))
        assertFalse(LocalIpv4Selector.isPrivateRange(ip(169, 254, 3, 4)))
        // Not IPv4 -> not private.
        assertFalse(LocalIpv4Selector.isPrivateRange(byteArrayOf(1, 2)))
    }

    // --- VPN-over-Wi-Fi: the defect that motivated the selector ---

    @Test
    fun `vpn over wifi picks wlan LAN address not the tun address`() {
        val chosen = LocalIpv4Selector.select(
            listOf(
                candidate("tun0", 10, 111, 222, 5, fromDefaultNetwork = true),
                candidate("wlan0", 192, 168, 1, 42)
            )
        )
        assertArrayEquals(ip(192, 168, 1, 42), chosen)
    }

    @Test
    fun `vpn over wifi with wlan listed first still avoids the tunnel`() {
        val chosen = LocalIpv4Selector.select(
            listOf(
                candidate("wlan0", 192, 168, 1, 42),
                candidate("tun0", 10, 111, 222, 5, fromDefaultNetwork = true)
            )
        )
        assertArrayEquals(ip(192, 168, 1, 42), chosen)
    }

    // --- hotspot / AP mode: no wlan IPv4, must not pick the bridge subnet of a phone behind it ---

    @Test
    fun `hotspot client sees ap bridge subnet as private and picks it over wan`() {
        // A phone on its own hotspot network: ap0 192.168.x (private) beats
        // rmnet carrier address in enumeration order.
        val chosen = LocalIpv4Selector.select(
            listOf(
                candidate("rmnet0", 100, 92, 10, 7),
                candidate("ap0", 192, 168, 43, 1)
            )
        )
        assertArrayEquals(ip(192, 168, 43, 1), chosen)
    }

    // --- Ethernet / Android TV: default network wins without Wi-Fi ---

    @Test
    fun `ethernet device advertises eth0 from default network`() {
        val chosen = LocalIpv4Selector.select(
            listOf(candidate("eth0", 10, 0, 0, 5, fromDefaultNetwork = true))
        )
        assertArrayEquals(ip(10, 0, 0, 5), chosen)
    }

    // --- CGNAT: private-range preference, not requirement ---

    @Test
    fun `cgnat-only connectivity still yields the default network address`() {
        val chosen = LocalIpv4Selector.select(
            listOf(candidate("rmnet0", 100, 70, 1, 2, fromDefaultNetwork = true))
        )
        assertArrayEquals(ip(100, 70, 1, 2), chosen)
    }

    // --- link-local ranking ---

    @Test
    fun `link local loses to any non link local address`() {
        val chosen = LocalIpv4Selector.select(
            listOf(
                candidate("wlan0", 169, 254, 9, 9),
                candidate("eth0", 172, 20, 1, 5)
            )
        )
        assertArrayEquals(ip(172, 20, 1, 5), chosen)
    }

    @Test
    fun `link local is the last resort rather than nothing`() {
        val chosen = LocalIpv4Selector.select(
            listOf(candidate("wlan0", 169, 254, 9, 9))
        )
        assertArrayEquals(ip(169, 254, 9, 9), chosen)
    }

    // --- degenerate inputs ---

    @Test
    fun `empty candidate list selects nothing`() {
        assertNull(LocalIpv4Selector.select(emptyList()))
    }

    @Test
    fun `only tunnel interfaces selects nothing`() {
        assertNull(
            LocalIpv4Selector.select(
                listOf(
                    candidate("tun0", 10, 111, 222, 5, fromDefaultNetwork = true),
                    candidate("ppp0", 192, 168, 7, 7)
                )
            )
        )
    }

    @Test
    fun `malformed non-ipv4 candidates are skipped`() {
        val chosen = LocalIpv4Selector.select(
            listOf(
                candidate("wlan0", 192, 168, 1, 42).copy(ip = byteArrayOf(1, 2, 3)),
                candidate("eth0", 10, 1, 2, 3)
            )
        )
        assertArrayEquals(ip(10, 1, 2, 3), chosen)
    }
}
