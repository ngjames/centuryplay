package com.airplay.streamer.raop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for 32-bit RTP timestamp wrapping in [RaopClient]: the Long
 * timestamp is written into 4-byte RTP header fields, so values >= 2^32
 * (after ~27h of streaming) must wrap to the low 32 bits.
 */
class RaopRtpPacketTest {

    @Test
    fun `rtp header timestamp is masked to 32 bits`() {
        val client = RaopClient("192.168.1.100", 5000)
        client.rtpTimestamp = 0x1_2345_6789L // 33-bit value (> 2^32)

        val packet = client.buildRtpPacket(ByteArray(0))

        assertEquals(0x23, packet[4].toInt() and 0xFF)
        assertEquals(0x45, packet[5].toInt() and 0xFF)
        assertEquals(0x67, packet[6].toInt() and 0xFF)
        assertEquals(0x89, packet[7].toInt() and 0xFF)
    }

    @Test
    fun `sync packet rtp timestamp is masked to 32 bits`() {
        val client = RaopClient("192.168.1.100", 5000)

        val packet = client.buildSyncPacket(0x1_2345_6789L, 0L, 0L)

        // current playback rtp field (bytes 4-7)
        assertEquals(0x23, packet[4].toInt() and 0xFF)
        assertEquals(0x45, packet[5].toInt() and 0xFF)
        assertEquals(0x67, packet[6].toInt() and 0xFF)
        assertEquals(0x89, packet[7].toInt() and 0xFF)
        // rtp + latency field (bytes 16-19) also wraps to 32 bits
        assertEquals(0x23, packet[16].toInt() and 0xFF)
        assertEquals(0x45, packet[17].toInt() and 0xFF)
        assertEquals(0x67, packet[18].toInt() and 0xFF)
        assertEquals(0x89, packet[19].toInt() and 0xFF)
    }
}
