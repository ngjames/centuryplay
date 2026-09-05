package com.airplay.streamer.discovery

import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * Represents a discovered AirPlay speaker
 */
data class AirPlayDevice(
    val name: String,
    val host: String,
    val port: Int,
    val deviceId: String, // 'pi' or 'deviceid'
    val publicKey: String? = null, // 'pk'
    val features: Map<String, String> = emptyMap(),
    val protocolVersion: Int = 2, // 1 = RAOP (AirPlay 1), 2 = AirPlay 2
    val raopPort: Int? = null // Port for RAOP protocol if discovered via _raop._tcp
) {
    val displayName: String
        get() = name.substringAfter("@").ifEmpty { name }
    
    val isAirPlay2: Boolean
        get() = protocolVersion == 2
}

/**
 * Discovers AirPlay devices on the local network using mDNS/Bonjour
 * Supports both:
 * - AirPlay 1 (RAOP): _raop._tcp.local. (ports 5000-5005)
 * - AirPlay 2: _airplay._tcp.local. (port 7000)
 */
class AirPlayDiscovery(
    private val wifiManager: WifiManager,
    private val connectivityManager: android.net.ConnectivityManager
) {
    companion object {
        private const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp.local."  // AirPlay 2
        private const val RAOP_SERVICE_TYPE = "_raop._tcp.local."        // AirPlay 1
        private const val TAG = "AirPlayDiscovery"
    }

    private var jmDNS: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // Track discovered devices to merge RAOP/AirPlay2 for same device
    private val discoveredDevices = mutableMapOf<String, AirPlayDevice>()

    /**
     * Start discovering AirPlay devices. Returns a Flow that emits discovery events.
     */
    fun discoverDevices(): Flow<DiscoveryEvent> = callbackFlow {
        // Get local IP address. Selection ladder in [LocalIpv4Selector]; the
        // legacy WifiManager call is the last resort (deprecated on API 31+).
        val localAddress = withContext(Dispatchers.IO) {
            val ipBytes = resolveLocalIpv4() ?: legacyWifiIpv4() ?: return@withContext null
            InetAddress.getByAddress(ipBytes)
        }

        if (localAddress == null) {
            Log.e(TAG, "No local IPv4 address available; cannot start mDNS discovery")
            trySend(DiscoveryEvent.DiscoveryFailed("no local IPv4 address available"))
            return@callbackFlow
        }

        // Create jmDNS instance. JmDNS.create can throw on some Android
        // 14/15 builds (e.g. EPERM); never let that crash the collector.
        val jmdnsStartTime = System.currentTimeMillis()
        val jmdnsResult = runCatching {
            withContext(Dispatchers.IO) {
                JmDNS.create(localAddress, "AirPlayDiscovery")
            }
        }
        android.util.Log.d("PROFILING", "JmDNS.create finished in ${System.currentTimeMillis() - jmdnsStartTime}ms")

        val createdJmDns = jmdnsResult.getOrElse { e ->
            Log.e(TAG, "JmDNS.create failed: ${e.message}")
            trySend(DiscoveryEvent.DiscoveryFailed(e.message ?: "JmDNS.create failed"))
            return@callbackFlow
        }
        jmDNS = createdJmDns

        // Acquire the multicast lock only once mDNS exists: every failure
        // return before this point would otherwise leak it (awaitClose never
        // runs when the callbackFlow block exits early).
        multicastLock = wifiManager.createMulticastLock("airplay_discovery").apply {
            setReferenceCounted(true)
            acquire()
        }

        // Listener for AirPlay 2 services (_airplay._tcp)
        val airplay2Listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmDNS?.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = false)
                if (device != null) {
                    discoveredDevices.remove(device.host)
                    trySend(DiscoveryEvent.DeviceLost(device))
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = false)
                if (device != null) {
                    val existingDevice = discoveredDevices[device.host]
                    val mergedDevice = if (existingDevice != null) {
                        // Merge: keep RAOP port if already discovered
                        device.copy(raopPort = existingDevice.raopPort)
                    } else {
                        device
                    }
                    discoveredDevices[device.host] = mergedDevice
                    trySend(DiscoveryEvent.DeviceFound(mergedDevice))
                    Log.d(TAG, "AirPlay 2 device found: ${device.displayName} at ${device.host}:${device.port}")
                }
            }
        }

        // Listener for RAOP services (_raop._tcp) - AirPlay 1
        val raopListener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jmDNS?.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = true)
                if (device != null) {
                    // Only remove if not also an AirPlay 2 device
                    val existing = discoveredDevices[device.host]
                    if (existing?.protocolVersion == 1) {
                        discoveredDevices.remove(device.host)
                        trySend(DiscoveryEvent.DeviceLost(device))
                    }
                }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val device = parseServiceEvent(event, isRaop = true)
                if (device != null) {
                    val existingDevice = discoveredDevices[device.host]
                    if (existingDevice != null) {
                        // Merge: keep AirPlay 2 identity but use RAOP port and RAOP TXT features
                        // (RAOP TXT record contains et=, cn= etc. needed for AirPlay 1 connection)
                        val mergedDevice = existingDevice.copy(
                            raopPort = device.port,
                            features = device.features
                        )
                        discoveredDevices[device.host] = mergedDevice
                        trySend(DiscoveryEvent.DeviceFound(mergedDevice))
                    } else {
                        // New RAOP-only device (AirPlay 1)
                        discoveredDevices[device.host] = device
                        trySend(DiscoveryEvent.DeviceFound(device))
                    }
                    Log.d(TAG, "RAOP device found: ${device.displayName} at ${device.host}:${device.port}")
                }
            }
        }

        // Start listening for both service types
        withContext(Dispatchers.IO) {
            jmDNS?.addServiceListener(AIRPLAY_SERVICE_TYPE, airplay2Listener)
            jmDNS?.addServiceListener(RAOP_SERVICE_TYPE, raopListener)
            Log.d(TAG, "Started listening for AirPlay 2 and RAOP services")
        }

        trySend(DiscoveryEvent.DiscoveryStarted)

        awaitClose {
            jmDNS?.removeServiceListener(AIRPLAY_SERVICE_TYPE, airplay2Listener)
            jmDNS?.removeServiceListener(RAOP_SERVICE_TYPE, raopListener)
            jmDNS?.close()
            jmDNS = null
            // Guard against a concurrent stop() releasing the lock first.
            multicastLock?.let { lock -> if (lock.isHeld) lock.release() }
            multicastLock = null
            discoveredDevices.clear()
        }
    }

    /**
     * Collect IPv4 candidates (default network first, then every up,
     * non-loopback interface) and run them through [LocalIpv4Selector] so
     * VPN-over-Wi-Fi advertises the real LAN address, not the tun one.
     */
    private fun resolveLocalIpv4(): ByteArray? {
        val candidates = mutableListOf<LocalIpv4Selector.Candidate>()

        // 1) Default network's LinkProperties, non-deprecated and reliable
        // under MAC randomization. Skipped silently on SecurityException.
        try {
            val la = connectivityManager.activeNetwork
                ?.let { connectivityManager.getLinkProperties(it) }
            la?.linkAddresses?.forEach { linkAddr ->
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates.add(
                        LocalIpv4Selector.Candidate(
                            fromDefaultNetwork = true,
                            interfaceName = la.interfaceName ?: "",
                            ip = addr.address
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "ConnectivityManager lookup failed: ${e.message}")
        }

        // 2) Every up, non-loopback interface, preserving enumeration order.
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.forEach { intf ->
                    intf.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .forEach { addr ->
                            candidates.add(
                                LocalIpv4Selector.Candidate(
                                    fromDefaultNetwork = false,
                                    interfaceName = intf.name,
                                    ip = addr.address
                                )
                            )
                        }
                }
        }

        return LocalIpv4Selector.select(candidates)
    }

    /** Last-resort legacy WifiManager lookup (deprecated on API 31+). */
    private fun legacyWifiIpv4(): ByteArray? {
        val ipInt = try {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo.ipAddress
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager lookup failed: ${e.message}")
            0
        }
        if (ipInt == 0) return null
        return byteArrayOf(
            (ipInt and 0xff).toByte(),
            (ipInt shr 8 and 0xff).toByte(),
            (ipInt shr 16 and 0xff).toByte(),
            (ipInt shr 24 and 0xff).toByte()
        )
    }

    private fun parseServiceEvent(event: ServiceEvent, isRaop: Boolean): AirPlayDevice? {
        val info = event.info ?: return null

        // inet4Addresses can be empty for devices with UUID hostnames (e.g. AirScreen).
        // Fall back to resolving the server hostname. Always use IPv4 — IPv6 link-local
        // addresses cause port 7000 (AirPlay 2) to be selected and RTSP connections to fail.
        val host: String = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: runCatching {
                val server = info.server?.trimEnd('.')
                if (server.isNullOrEmpty()) null
                else InetAddress.getAllByName(server)
                    ?.filterIsInstance<java.net.Inet4Address>()
                    ?.firstOrNull()?.hostAddress
            }.getOrNull()
            ?: run {
                Log.w(TAG, "Could not resolve IPv4 address for ${event.name} (server=${info.server})")
                return null
            }
        val port = info.port
        val name = event.name

        // Parse TXT record
        val features = mutableMapOf<String, String>()
        info.propertyNames?.iterator()?.forEach { key ->
            val value = info.getPropertyString(key)
            if (value != null) {
                features[key] = value
            }
        }

        // Device ID from various TXT record fields
        val deviceId = features["pi"] ?: features["deviceid"] ?: name 
        
        // Public Key 'pk' is needed for AirPlay 2 auth
        val publicKey = features["pk"]

        return AirPlayDevice(
            name = name,
            host = host,
            port = port,
            deviceId = deviceId,
            publicKey = publicKey,
            features = features,
            protocolVersion = if (isRaop) 1 else 2,
            raopPort = if (isRaop) port else null
        )
    }

    fun stop() {
        jmDNS?.close()
        jmDNS = null
        // Only release if the lock is held
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
        discoveredDevices.clear()
    }
}

sealed class DiscoveryEvent {
    data object DiscoveryStarted : DiscoveryEvent()
    data class DeviceFound(val device: AirPlayDevice) : DiscoveryEvent()
    data class DeviceLost(val device: AirPlayDevice) : DiscoveryEvent()
    data class DiscoveryFailed(val error: String) : DiscoveryEvent()
}
