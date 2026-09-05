package com.airplay.streamer.airplay2.util

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Network utilities for AirPlay 2
 */
object NetworkUtils {
    
    /**
     * Get the local IPv4 address of this device.
     * Prefers WiFi interfaces over other types.
     * 
     * @return Local IPv4 address as string, or "127.0.0.1" if not found
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"
            
            // First try to find WiFi interface
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                
                // Common WiFi interface names
                val name = intf.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("wl") || name.startsWith("en")) {
                    val address = getIpv4FromInterface(intf)
                    if (address != null) return address
                }
            }
            
            // Fallback: any non-loopback IPv4 address
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
                
                val address = getIpv4FromInterface(intf)
                if (address != null) return address
            }
        } catch (e: Exception) {
            Ap2Log.e("NetworkUtils", "Failed to resolve local IPv4 address", e)
        }
        
        return "127.0.0.1"
    }
    
    /**
     * Get IPv4 address from a network interface
     */
    private fun getIpv4FromInterface(intf: NetworkInterface): String? {
        for (addr in intf.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
        return null
    }
}
