package com.airplay.streamer.airplay2.timing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PtpMasterClockTest {

    private val clock = PtpMasterClock("127.0.0.1")

    @Test
    fun `announce is 64 bytes with transportSpecific 1 and type ANNOUNCE`() {
        val msg = clock.buildAnnounceMessage(1000)
        assertEquals(64, msg.size)
        assertEquals(0x1B, msg[0].toInt() and 0xFF) // 0x10 (transportSpecific 1) | 0x0B (ANNOUNCE)
    }

    @Test
    fun `sync is 44 bytes with transportSpecific 1 and type SYNC`() {
        val msg = clock.buildSyncMessage(1)
        assertEquals(44, msg.size)
        assertEquals(0x10, msg[0].toInt() and 0xFF) // 0x10 (transportSpecific 1) | 0x00 (SYNC)
    }

    @Test
    fun `follow up is 76 bytes with transportSpecific 1 and type FOLLOW_UP`() {
        val msg = clock.buildFollowUpMessage(1, 0L)
        assertEquals(76, msg.size)
        assertEquals(0x18, msg[0].toInt() and 0xFF) // 0x10 (transportSpecific 1) | 0x08 (FOLLOW_UP)
    }

    @Test
    fun `logMessageInterval is FD for sync and follow up, 00 for announce`() {
        assertEquals(0xFD, clock.buildSyncMessage(1)[33].toInt() and 0xFF)
        assertEquals(0xFD, clock.buildFollowUpMessage(1, 0L)[33].toInt() and 0xFF)
        assertEquals(0x00, clock.buildAnnounceMessage(1)[33].toInt() and 0xFF)
    }

    @Test
    fun `sync has twoStepFlag and ptpTimescale flags`() {
        val msg = clock.buildSyncMessage(1)
        assertEquals(0x02, msg[6].toInt() and 0xFF)
        assertEquals(0x08, msg[7].toInt() and 0xFF)
    }

    @Test
    fun `follow up has ptpTimescale flag only`() {
        val msg = clock.buildFollowUpMessage(1, 0L)
        assertEquals(0x00, msg[6].toInt() and 0xFF)
        assertEquals(0x08, msg[7].toInt() and 0xFF)
    }

    @Test
    fun `follow up carries IEEE 802p1AS Apple org TLV at offset 44`() {
        val msg = clock.buildFollowUpMessage(1, 0L)
        // tlvType = 0x0003 (ORGANIZATION_EXTENSION)
        assertEquals(0x00, msg[44].toInt() and 0xFF)
        assertEquals(0x03, msg[45].toInt() and 0xFF)
        // lengthField = 28 (0x001C)
        assertEquals(0x00, msg[46].toInt() and 0xFF)
        assertEquals(0x1C, msg[47].toInt() and 0xFF)
        // organizationId = 00:17:F2 (Apple)
        assertArrayEquals(byteArrayOf(0x00, 0x17, 0xF2.toByte()), msg.copyOfRange(48, 51))
        // organizationSubtype = 00:00:01
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x01), msg.copyOfRange(51, 54))
        // lastGmPhaseChange (10 bytes, 54-63) zero
        assertArrayEquals(ByteArray(10), msg.copyOfRange(54, 64))
        // lastGmFreqChange (4 bytes, 64-67) zero
        assertArrayEquals(ByteArray(4), msg.copyOfRange(64, 68))
        // gmTimeBaseIndicator (2 bytes, 68-69) zero
        assertArrayEquals(ByteArray(2), msg.copyOfRange(68, 70))
        // scaledLastGmFreqChange (2 bytes, 70-71) zero
        assertArrayEquals(ByteArray(2), msg.copyOfRange(70, 72))
        // TLV total: 44 + 2 + 2 + 3 + 3 + 10 + 4 + 2 + 2 = 76
        assertEquals(76, msg.size)
    }

    @Test
    fun `announce header clockIdentity equals grandmasterIdentity`() {
        val msg = clock.buildAnnounceMessage(1)
        // header clockIdentity at bytes 20-27
        val headerClockId = ByteBuffer.wrap(msg, 20, 8).long
        // grandmasterIdentity at bytes 53-60
        val gmIdentity = ByteBuffer.wrap(msg, 53, 8).long
        assertEquals(clock.clockId, headerClockId)
        assertEquals(clock.clockId, gmIdentity)
    }

    @Test
    fun `start falls back to ephemeral ports without throwing and stop is clean`() = runBlocking {
        // 319/320 binding fails on the test JVM (privileged ports) -> ephemeral fallback
        val scope = CoroutineScope(Dispatchers.IO)
        val mClock = PtpMasterClock("127.0.0.1")
        try {
            mClock.start(scope)
            assertTrue(true) // no exception from start
        } finally {
            mClock.stop()
        }
    }
}
