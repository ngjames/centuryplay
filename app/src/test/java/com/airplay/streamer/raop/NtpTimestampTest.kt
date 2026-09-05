package com.airplay.streamer.raop

import com.airplay.streamer.util.ByteArrayFormat.toHexDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-exact golden tests pinning the NTP timestamp math used by the RAOP
 * and AirPlay 2 senders to stamp their NTP timing packets.
 *
 * The seconds field is `(ms / 1000) + 2208988800L` (1970 epoch + 70-year NTP
 * offset to 1900) in both senders:
 *   - RaopClient.kt:668 and :715 (startTimingResponder / buildSyncPacket)
 *   - AirPlay2Client.kt:391 (startNtpTimingResponder)
 *
 * The fraction field (ms into the current second scaled to 2^32) is computed
 * differently in the two files:
 *   - RAOP:  ((ms % 1000) * 4294967296.0 / 1000.0).toLong()   (Double, then
 *     truncate to Long)  -- RaopClient.kt:669 and :715
 *   - AP2:   ((ms % 1000) * 4294967296L) / 1000               (Long integer
 *     arithmetic)        -- AirPlay2Client.kt:392
 *
 * For all wall-clock inputs pinned here the two variants agree exactly (both
 * truncate the same quotient; the Double product never rounds across an
 * integer boundary for ms % 1000 in [0,999]). The expected values below were
 * computed by RUNNING the production formulas (and, for the RAOP bytes, the
 * actual [RaopClient.buildSyncPacket] serialization) and are hardcoded so any
 * refactor that perturbs the math fails the test.
 */
class NtpTimestampTest {

    private data class NtpCase(val ms: Long, val sec: Long, val frac: Long, val ntpBytes: String)

    // Observed values: sec = (ms/1000)+2208988800, frac as above, ntpBytes =
    // the 8 NTP bytes written by RaopClient.buildSyncPacket at offsets 8..15.
    private val cases = listOf(
        NtpCase(0, 2208988800, 0, "83 aa 7e 80 00 00 00 00"),
        NtpCase(1, 2208988800, 4294967, "83 aa 7e 80 00 41 89 37"),
        NtpCase(999, 2208988800, 4290672328, "83 aa 7e 80 ff be 76 c8"),
        NtpCase(1000, 2208988801, 0, "83 aa 7e 81 00 00 00 00"),
        NtpCase(123456789, 2209112256, 3388729196, "83 ac 60 c0 c9 fb e7 6c"),
        NtpCase(987654321, 2209976454, 1378684502, "83 b9 90 86 52 2d 0e 56")
    )

    @Test
    fun `seconds field is unix epoch shifted by the 1900 offset`() {
        for (c in cases) {
            val sec = (c.ms / 1000) + 2208988800L
            assertEquals("sec for ms=${c.ms}", c.sec, sec)
        }
    }

    @Test
    fun `raop fraction field uses double arithmetic truncated to long`() {
        for (c in cases) {
            val frac = ((c.ms % 1000) * 4294967296.0 / 1000.0).toLong()
            assertEquals("raop frac for ms=${c.ms}", c.frac, frac)
        }
    }

    @Test
    fun `ap2 fraction field uses long integer arithmetic`() {
        for (c in cases) {
            val frac = (((c.ms % 1000) * 4294967296L) / 1000)
            assertEquals("ap2 frac for ms=${c.ms}", c.frac, frac)
        }
    }

    @Test
    fun `raop and ap2 fraction variants agree over a wide sweep`() {
        // ms in 0..2000 plus a few large values: both variants must truncate
        // to the same integer for every millisecond.
        val extra = longArrayOf(0, 1, 124, 125, 126, 499, 500, 501, 874, 875, 876, 999, 1000,
            123456, 123456789, 999999999, 2147483647)
        for (ms in 0L..2000L) {
            val raop = ((ms % 1000) * 4294967296.0 / 1000.0).toLong()
            val ap2 = ((ms % 1000) * 4294967296L) / 1000
            assertEquals("variants differ for ms=$ms", ap2, raop)
        }
        for (ms in extra) {
            val raop = ((ms % 1000) * 4294967296.0 / 1000.0).toLong()
            val ap2 = ((ms % 1000) * 4294967296L) / 1000
            assertEquals("variants differ for ms=$ms", ap2, raop)
        }
    }

    @Test
    fun `buildSyncPacket writes the pinned ntp bytes into offsets 8-15`() {
        val client = RaopClient("192.168.1.100", 5000)
        for (c in cases) {
            val pkt = client.buildSyncPacket(0L, c.ms, 0L)
            val actual = pkt.copyOfRange(8, 16).toHexDump()
            assertEquals("ntp bytes for ms=${c.ms}", c.ntpBytes, actual)
        }
    }

    @Test
    fun `buildSyncPacket ntp field decodes back to the expected seconds`() {
        val client = RaopClient("192.168.1.100", 5000)
        val pkt = client.buildSyncPacket(0L, 123456789L, 0L)
        var sec = 0L
        for (i in 0..3) sec = (sec shl 8) or (pkt[8 + i].toLong() and 0xFF)
        assertEquals(2209112256L, sec)
    }
}
