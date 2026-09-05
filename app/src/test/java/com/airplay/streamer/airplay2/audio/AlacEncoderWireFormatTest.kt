package com.airplay.streamer.airplay2.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-exact golden tests for the AP2 "uncompressed" ALAC wire format used by
 * centuryplay. These pin the CURRENT bytes produced by [AlacEncoder] so that a
 * later refactor is safe: if a refactor changes the wire bytes, these tests
 * fail.
 *
 * The encoder is fed a deterministic 1408-byte frame: 352 frames of 16-bit
 * little-endian stereo PCM, sample s taking value PATTERNS[s % 5] =
 * {0x1234, 0xABCD, 0xFFFF, 0x8000, 0x7FFF}. This set deliberately includes a
 * negative value (0xABCD) and values whose unsigned interpretation exceeds
 * 32767 (0xFFFF, 0x8000) so any byte-swap mistake changes the output.
 *
 * HISTORY: this test previously also pinned a second, divergent raop.AlacEncoder
 * (header 0x20 0x00 0x00, 24-bit byte-aligned, no_compression=0). That encoder
 * was dead code — its only call site lived inside the FairPlay-stub branch of
 * RaopClient.streamAudio, which is unreachable because et=5-only receivers are
 * gated out before a RaopClient is even constructed (see MainActivity.kt,
 * AudioCaptureService.kt, TileDeviceActivity.kt) and require real FairPlay
 * SAPv2 sender crypto (see AGENTS.md and docs/FAIRPLAY_HANDSHAKE.md). It was
 * deleted; this test now pins only the AP2 wire format.
 *
 * Golden bytes were obtained by running the actual encoder (see the
 * temporary WireFormatDumpTest used to generate them), not derived by hand.
 */

class AlacEncoderWireFormatTest {

    /** ap2 encoder full output for the deterministic frame (1411 bytes). */
    private val goldenAp2 = hex(
        "20 00 02 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00 ff fe 24 69 57" +
        "9b ff ff 00 00 ff fe 24 69 57 9b ff" +
        "ff 00 00 ff fe 24 69 57 9b ff ff 00" +
        "00 ff fe 24 69 57 9b ff ff 00 00 ff" +
        "fe 24 69 57 9b ff ff 00 00 ff fe 24" +
        "69 57 9b ff ff 00 00"
    )

    @Test
    fun `ap2 encoder output matches pinned wire bytes`() {
        val out = com.airplay.streamer.airplay2.audio.AlacEncoder.encodeFrame(buildPcmFrame())
        assertEquals(1411, out.size)
        assertArrayEquals(goldenAp2, out)
    }

    @Test
    fun `ap2 header field details`() {
        val out = com.airplay.streamer.airplay2.audio.AlacEncoder.encodeFrame(buildPcmFrame())
        val h = out.take(3)

        // 0x20 0x00: first three bits 001 = stereo; reserved bits zero.
        assertEquals(0x20.toByte(), h[0])
        assertEquals(0x00.toByte(), h[1])
        // 0x02 = 0000 0010: no_compression=1 (bit 1 of byte 2); the LSB holds
        // sample 0's first PCM bit, which is 0 for the first sample 0x1234.
        assertEquals(0x02.toByte(), h[2])
        assertEquals(0x02, h[2].toInt() and 0x02) // no_compress = 1
        assertEquals(0, h[2].toInt() and 0x10)    // has_size = 0
        assertEquals(0, h[2].toInt() and 0x01)    // sample0 MSB = 0

        // Implicit frame shape: 352 samples x 2 ch x 16-bit.
        assertEquals(352, com.airplay.streamer.airplay2.audio.AlacEncoder.SAMPLES_PER_FRAME)
        assertEquals(2, com.airplay.streamer.airplay2.audio.AlacEncoder.CHANNELS)
        assertEquals(16, com.airplay.streamer.airplay2.audio.AlacEncoder.BITS_PER_SAMPLE)
    }

    @Test
    fun `samples are big-endian on the wire`() {
        // First sample 0x1234 in LE is 34 12; the encoder emits the BE
        // bit-pattern 0001 0010 0011 0100 starting right after the header.
        val ap2 = com.airplay.streamer.airplay2.audio.AlacEncoder.encodeFrame(buildPcmFrame())
        // 23-bit header: byte2 LSB = sample0 bit 0 (0), byte3 = sample0 bits 1..8.
        assertEquals(0x02.toByte(), ap2[2])
        assertEquals(0x24.toByte(), ap2[3])
    }

    /** Fixed 1408-byte 16-bit LE stereo frame: sample s = PATTERNS[s % 5]. */
    private fun buildPcmFrame(): ByteArray {
        val buf = ByteBuffer.allocate(1408).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until 704) {
            val v = PATTERNS[s % 5]
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        // 0xABCD = -21555 (negative); 0xFFFF/0x8000 exceed 32767 unsigned:
        // exercising byte-swap correctness in both encoders.
        val PATTERNS = intArrayOf(0x1234, 0xABCD, 0xFFFF, 0x8000, 0x7FFF)
    }
}