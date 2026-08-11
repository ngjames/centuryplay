package com.airplay.streamer.service

/**
 * Streaming protocol resolved from the user's protocol preference and the
 * receiver's ports. Pure logic (no android.* types) so it can be unit-tested
 * on the JVM.
 */
internal enum class Protocol { AIRPLAY1, AIRPLAY2 }

/**
 * Resolve which AirPlay protocol to use for a receiver.
 *
 * [preference] is the SharedPreferences 'airplay_prefs'/'protocol_preference'
 * value: 0=Auto (default), 1=AirPlay 1 (RAOP), 2=AirPlay 2.
 *
 * Resolution rules (plan todo 12):
 * - Auto (0): AirPlay 2 when [port] == 7000 (the AP2 control port; devices
 *   advertising protocolVersion==2 expose this port), else AirPlay 1.
 * - AirPlay 1 (1): always AirPlay 1; the effective RAOP port is [raopPort]
 *   when present, otherwise [port].
 * - AirPlay 2 (2): always AirPlay 2, connected on port 7000.
 *
 * [raopPort] is the receiver's RAOP port (null for AP2-only devices); it does
 * not influence the Auto choice, which keys off [port] only.
 */
internal fun resolveProtocol(preference: Int, port: Int, raopPort: Int?): Protocol = when (preference) {
    1 -> Protocol.AIRPLAY1
    2 -> Protocol.AIRPLAY2
    else -> if (port == 7000) Protocol.AIRPLAY2 else Protocol.AIRPLAY1
}
