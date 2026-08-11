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
    fun `announce body matches IEEE 1588-2008 layout at offset 34`() {
        val msg = clock.buildAnnounceMessage(1)
        // messageLength header field must match the 64-byte wire size
        assertEquals(64, msg.size)
        assertEquals(64, ByteBuffer.wrap(msg, 2, 2).short.toInt() and 0xFFFF)
        // 34-35 currentUtcOffset = 37 (TAI-UTC)
        assertEquals(37, ByteBuffer.wrap(msg, 34, 2).short.toInt())
        // 36 reserved = 0
        assertEquals(0, msg[36].toInt() and 0xFF)
        // 37 grandmasterPriority1 = 248
        assertEquals(248, msg[37].toInt() and 0xFF)
        // 38-41 clockQuality = 0xF8FEFFFF (class 248, accuracy 0xFE, variance 0xFFFF)
        assertEquals(0xF8FEFFFF.toInt(), ByteBuffer.wrap(msg, 38, 4).int)
        // 42 grandmasterPriority2 = 248
        assertEquals(248, msg[42].toInt() and 0xFF)
        // 43-50 grandmasterIdentity = clockId
        assertEquals(clock.clockId, ByteBuffer.wrap(msg, 43, 8).long)
        // 51-52 stepsRemoved = 0
        assertEquals(0, ByteBuffer.wrap(msg, 51, 2).short.toInt())
        // 53 timeSource = 0xA0 (Internal Oscillator)
        assertEquals(0xA0, msg[53].toInt() and 0xFF)
        // 54-55 gmTimeBaseIndicator / 56-59 accumulatedSubdomainChangeRate zero
        assertArrayEquals(ByteArray(6), msg.copyOfRange(54, 60))
        // 60-63 zero padding
        assertArrayEquals(ByteArray(4), msg.copyOfRange(60, 64))
    }

    @Test
    fun `announce header clockIdentity equals grandmasterIdentity`() {
        val msg = clock.buildAnnounceMessage(1)
        // header clockIdentity at bytes 20-27
        val headerClockId = ByteBuffer.wrap(msg, 20, 8).long
        // grandmasterIdentity at bytes 43-50 (IEEE 1588-2008 announce body)
        val gmIdentity = ByteBuffer.wrap(msg, 43, 8).long
        assertEquals(clock.clockId, headerClockId)
        assertEquals(clock.clockId, gmIdentity)
    }

    @Test
    fun `sync body is a zeroed 10-byte originTimestamp at offset 34`() {
        val msg = clock.buildSyncMessage(1)
        // SYNC (two-step): originTimestamp (10 bytes) at 34, all zeros
        assertArrayEquals(ByteArray(10), msg.copyOfRange(34, 44))
        assertEquals(44, msg.size)
        assertEquals(44, ByteBuffer.wrap(msg, 2, 2).short.toInt() and 0xFFFF)
    }

    @Test
    fun `follow up carries preciseOriginTimestamp at offset 34`() {
        // originTimestampNs = 2s + 500ms -> seconds=2, nanoseconds=500000000
        val msg = clock.buildFollowUpMessage(1, 2_500_000_000L)
        // 34-35 secondsHi (2 seconds < 2^32)
        assertEquals(0, ByteBuffer.wrap(msg, 34, 2).short.toInt())
        // 36-39 secondsLo = 2
        assertEquals(2, ByteBuffer.wrap(msg, 36, 4).int)
        // 40-43 nanoseconds = 500000000 (0x1DCD6500)
        assertEquals(500_000_000, ByteBuffer.wrap(msg, 40, 4).int)
        // Follow_Up has no reserved padding between timestamp and TLV
        assertEquals(0x0003, ByteBuffer.wrap(msg, 44, 2).short.toInt() and 0xFFFF)
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
