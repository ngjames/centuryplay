package com.airplay.streamer.discovery

/**
 * Pure selection of the local IPv4 address mDNS should bind and advertise.
 *
 * No android.* types: candidates are plain (interface name, IPv4 bytes) pairs,
 * so the ladder below is unit-testable on the JVM.
 *
 * Why the ladder exists:
 * - VPN-over-Wi-Fi: the default network's capabilities include TRANSPORT_WIFI,
 *   but its LinkProperties hold the tun address (e.g. 10.111.x.x). Binding
 *   mDNS there strands discovery on the tunnel; the real LAN address lives on
 *   wlan0. Tunnel interfaces are therefore skipped by NAME.
 * - Android TV Ethernet: the default network carries TRANSPORT_ETHERNET, not
 *   Wi-Fi; candidates from the default network are accepted regardless of
 *   transport so wired devices advertise eth0 instead of 0.0.0.0.
 * - Carrier CGNAT (100.64/10) and hotspot client ranges are legitimate local
 *   addresses that are not RFC1918: private range is a preference, not a
 *   requirement, with link-local (169.254/16, broken network) ranked last.
 */
internal object LocalIpv4Selector {

    /** Interface-name prefixes that carry VPN/tunnel traffic. */
    private val TUNNEL_PREFIXES = listOf("tun", "tap", "ppp", "vpn")

    data class Candidate(
        /** Address comes from the default network's LinkProperties. */
        val fromDefaultNetwork: Boolean,
        val interfaceName: String,
        /** 4-byte IPv4 address. */
        val ip: ByteArray
    )

    fun isTunnelInterface(name: String): Boolean {
        val n = name.lowercase()
        return TUNNEL_PREFIXES.any { n.startsWith(it) }
    }

    /** RFC 1918 private range (10/8, 172.16/12, 192.168/16). */
    fun isPrivateRange(ip: ByteArray): Boolean = ip.size == 4 && (
        ip[0] == 10.toByte() ||
            (ip[0] == 172.toByte() && ip[1] in 16..31) ||
            (ip[0] == 192.toByte() && ip[1] == 168.toByte())
        )

    /** IPv4 link-local 169.254/16 — no DHCP; ranked below everything else. */
    fun isLinkLocal(ip: ByteArray): Boolean =
        ip.size == 4 && ip[0] == 169.toByte() && ip[1] == 254.toByte()

    private fun Candidate.usableV4() = ip.size == 4
    private fun Candidate.onRealIface() = !isTunnelInterface(interfaceName)

    /**
     * Pick the best candidate, or null when nothing sensible exists (the
     * caller then applies its platform-specific last resort).
     *
     * Order (each pass preserves caller enumeration order):
     * 1. default-network address on a non-tunnel interface, private range
     * 2. any private-range address on a non-tunnel interface (VPN active)
     * 3. default-network address on a non-tunnel interface (CGNAT/hotspot)
     * 4. any non-tunnel, non-link-local address
     * 5. link-local (broken network, better than nothing)
     */
    fun select(candidates: List<Candidate>): ByteArray? {
        val c = candidates.asSequence()
            .filter { it.usableV4() && it.onRealIface() }

        c.filter { it.fromDefaultNetwork && isPrivateRange(it.ip) }
            .firstOrNull()?.let { return it.ip }
        c.filter { isPrivateRange(it.ip) }
            .firstOrNull()?.let { return it.ip }
        c.filter { it.fromDefaultNetwork && !isLinkLocal(it.ip) }
            .firstOrNull()?.let { return it.ip }
        c.filter { !isLinkLocal(it.ip) }
            .firstOrNull()?.let { return it.ip }
        return c.firstOrNull()?.ip
    }
}
