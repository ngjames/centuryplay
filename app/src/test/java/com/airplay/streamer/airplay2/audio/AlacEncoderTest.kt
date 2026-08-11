package com.airplay.streamer.airplay2.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tests for [AlacEncoder]'s "uncompressed" ALAC frame builder.
 *
 * The golden was generated with a python3 reference that mirrors the dev
 * scripts/airplay2_transient.py `encode_alac_uncompressed` bit-packing:
 *
 *   header (23 bits): [channels:3=001][0:4][0:12][has_size:1=0][0:2]
 *                      [no_compress:1=1]
 *   then each 16-bit LE PCM sample byte-swapped to big-endian and written
 *   MSB-first.
 *
 * Command (recorded in .omo/evidence/task-8-centuryplay-airplay2.txt):
 *   python3 /tmp/alac_golden.py   # prints GOLDEN_HEX, 1411 bytes
 *
 * Input frame: 1408 bytes, 704 int16 LE stereo samples, sample s =
 * ((s*37+11) %% 65536) - 32768 (deterministic integer pattern, no floats).
 */
class AlacEncoderTest {

    // Golden bytes for the fixed 1408-byte frame described above.
    private val golden = hex(
        "20 00 03 00 17 00 61 00 ab 00 f5 01 3f 01 89 01 d3 02 1d 02 67 02 b1 02 fb 03 45 03 8f 03 d9 04 23 04 6d 04 b7 05 01" +
        "05 4b 05 95 05 df 06 29 06 73 06 bd 07 07 07 51 07 9b 07 e5 08 2f 08 79 08 c3 09 0d 09 57 09 a1 09 eb 0a 35 0a 7f 0a" +
        "c9 0b 13 0b 5d 0b a7 0b f1 0c 3b 0c 85 0c cf 0d 19 0d 63 0d ad 0d f7 0e 41 0e 8b 0e d5 0f 1f 0f 69 0f b3 0f fd 10 47" +
        "10 91 10 db 11 25 11 6f 11 b9 12 03 12 4d 12 97 12 e1 13 2b 13 75 13 bf 14 09 14 53 14 9d 14 e7 15 31 15 7b 15 c5 16" +
        "0f 16 59 16 a3 16 ed 17 37 17 81 17 cb 18 15 18 5f 18 a9 18 f3 19 3d 19 87 19 d1 1a 1b 1a 65 1a af 1a f9 1b 43 1b 8d" +
        "1b d7 1c 21 1c 6b 1c b5 1c ff 1d 49 1d 93 1d dd 1e 27 1e 71 1e bb 1f 05 1f 4f 1f 99 1f e3 20 2d 20 77 20 c1 21 0b 21" +
        "55 21 9f 21 e9 22 33 22 7d 22 c7 23 11 23 5b 23 a5 23 ef 24 39 24 83 24 cd 25 17 25 61 25 ab 25 f5 26 3f 26 89 26 d3" +
        "27 1d 27 67 27 b1 27 fb 28 45 28 8f 28 d9 29 23 29 6d 29 b7 2a 01 2a 4b 2a 95 2a df 2b 29 2b 73 2b bd 2c 07 2c 51 2c" +
        "9b 2c e5 2d 2f 2d 79 2d c3 2e 0d 2e 57 2e a1 2e eb 2f 35 2f 7f 2f c9 30 13 30 5d 30 a7 30 f1 31 3b 31 85 31 cf 32 19" +
        "32 63 32 ad 32 f7 33 41 33 8b 33 d5 34 1f 34 69 34 b3 34 fd 35 47 35 91 35 db 36 25 36 6f 36 b9 37 03 37 4d 37 97 37" +
        "e1 38 2b 38 75 38 bf 39 09 39 53 39 9d 39 e7 3a 31 3a 7b 3a c5 3b 0f 3b 59 3b a3 3b ed 3c 37 3c 81 3c cb 3d 15 3d 5f" +
        "3d a9 3d f3 3e 3d 3e 87 3e d1 3f 1b 3f 65 3f af 3f f9 40 43 40 8d 40 d7 41 21 41 6b 41 b5 41 ff 42 49 42 93 42 dd 43" +
        "27 43 71 43 bb 44 05 44 4f 44 99 44 e3 45 2d 45 77 45 c1 46 0b 46 55 46 9f 46 e9 47 33 47 7d 47 c7 48 11 48 5b 48 a5" +
        "48 ef 49 39 49 83 49 cd 4a 17 4a 61 4a ab 4a f5 4b 3f 4b 89 4b d3 4c 1d 4c 67 4c b1 4c fb 4d 45 4d 8f 4d d9 4e 23 4e" +
        "6d 4e b7 4f 01 4f 4b 4f 95 4f df 50 29 50 73 50 bd 51 07 51 51 51 9b 51 e5 52 2f 52 79 52 c3 53 0d 53 57 53 a1 53 eb" +
        "54 35 54 7f 54 c9 55 13 55 5d 55 a7 55 f1 56 3b 56 85 56 cf 57 19 57 63 57 ad 57 f7 58 41 58 8b 58 d5 59 1f 59 69 59" +
        "b3 59 fd 5a 47 5a 91 5a db 5b 25 5b 6f 5b b9 5c 03 5c 4d 5c 97 5c e1 5d 2b 5d 75 5d bf 5e 09 5e 53 5e 9d 5e e7 5f 31" +
        "5f 7b 5f c5 60 0f 60 59 60 a3 60 ed 61 37 61 81 61 cb 62 15 62 5f 62 a9 62 f3 63 3d 63 87 63 d1 64 1b 64 65 64 af 64" +
        "f9 65 43 65 8d 65 d7 66 21 66 6b 66 b5 66 ff 67 49 67 93 67 dd 68 27 68 71 68 bb 69 05 69 4f 69 99 69 e3 6a 2d 6a 77" +
        "6a c1 6b 0b 6b 55 6b 9f 6b e9 6c 33 6c 7d 6c c7 6d 11 6d 5b 6d a5 6d ef 6e 39 6e 83 6e cd 6f 17 6f 61 6f ab 6f f5 70" +
        "3f 70 89 70 d3 71 1d 71 67 71 b1 71 fb 72 45 72 8f 72 d9 73 23 73 6d 73 b7 74 01 74 4b 74 95 74 df 75 29 75 73 75 bd" +
        "76 07 76 51 76 9b 76 e5 77 2f 77 79 77 c3 78 0d 78 57 78 a1 78 eb 79 35 79 7f 79 c9 7a 13 7a 5d 7a a7 7a f1 7b 3b 7b" +
        "85 7b cf 7c 19 7c 63 7c ad 7c f7 7d 41 7d 8b 7d d5 7e 1f 7e 69 7e b3 7e fd 7f 47 7f 91 7f db 80 25 80 6f 80 b9 81 03" +
        "81 4d 81 97 81 e1 82 2b 82 75 82 bf 83 09 83 53 83 9d 83 e7 84 31 84 7b 84 c5 85 0f 85 59 85 a3 85 ed 86 37 86 81 86" +
        "cb 87 15 87 5f 87 a9 87 f3 88 3d 88 87 88 d1 89 1b 89 65 89 af 89 f9 8a 43 8a 8d 8a d7 8b 21 8b 6b 8b b5 8b ff 8c 49" +
        "8c 93 8c dd 8d 27 8d 71 8d bb 8e 05 8e 4f 8e 99 8e e3 8f 2d 8f 77 8f c1 90 0b 90 55 90 9f 90 e9 91 33 91 7d 91 c7 92" +
        "11 92 5b 92 a5 92 ef 93 39 93 83 93 cd 94 17 94 61 94 ab 94 f5 95 3f 95 89 95 d3 96 1d 96 67 96 b1 96 fb 97 45 97 8f" +
        "97 d9 98 23 98 6d 98 b7 99 01 99 4b 99 95 99 df 9a 29 9a 73 9a bd 9b 07 9b 51 9b 9b 9b e5 9c 2f 9c 79 9c c3 9d 0d 9d" +
        "57 9d a1 9d eb 9e 35 9e 7f 9e c9 9f 13 9f 5d 9f a7 9f f1 a0 3b a0 85 a0 cf a1 19 a1 63 a1 ad a1 f7 a2 41 a2 8b a2 d5" +
        "a3 1f a3 69 a3 b3 a3 fd a4 47 a4 91 a4 db a5 25 a5 6f a5 b9 a6 03 a6 4d a6 97 a6 e1 a7 2b a7 75 a7 bf a8 09 a8 53 a8" +
        "9d a8 e7 a9 31 a9 7b a9 c5 aa 0f aa 59 aa a3 aa ed ab 37 ab 81 ab cb ac 15 ac 5f ac a9 ac f3 ad 3d ad 87 ad d1 ae 1b" +
        "ae 65 ae af ae f9 af 43 af 8d af d7 b0 21 b0 6b b0 b5 b0 ff b1 49 b1 93 b1 dd b2 27 b2 71 b2 bb b3 05 b3 4f b3 99 b3" +
        "e3 b4 2d b4 77 b4 c1 b5 0b b5 55 b5 9f b5 e9 b6 33 b6 7d b6 c7 b7 11 b7 5b b7 a5 b7 ef b8 39 b8 83 b8 cd b9 17 b9 61" +
        "b9 ab b9 f5 ba 3f ba 89 ba d3 bb 1d bb 67 bb b1 bb fb bc 45 bc 8f bc d9 bd 23 bd 6d bd b7 be 01 be 4b be 95 be df bf" +
        "29 bf 73 bf bd c0 07 c0 51 c0 9b c0 e5 c1 2f c1 79 c1 c3 c2 0d c2 57 c2 a1 c2 eb c3 35 c3 7f c3 c9 c4 13 c4 5d c4 a7" +
        "c4 f1 c5 3b c5 85 c5 cf c6 19 c6 63 c6 ad c6 f7 c7 41 c7 8b c7 d5 c8 1f c8 69 c8 b3 c8 fd c9 47 c9 91 c9 db ca 25 ca" +
        "6f ca b9 cb 03 cb 4c"
    )

    @Test
    fun encodeFrame_matchesPythonReferenceGolden() {
        val frame = AlacEncoder.encodeFrame(buildPcmFrame())

        assertEquals("ALAC frame must be 1411 bytes (23-bit header + 704*16 PCM bits + pad)", 1411, frame.size)
        assertArrayEquals("ALAC frame must match the python3 reference golden", golden, frame)
    }

    @Test
    fun encodeFrame_headerBits() {
        val frame = AlacEncoder.encodeFrame(buildPcmFrame())

        // 23-bit header packed MSB-first:
        //   [0,0,1] channels=1 stereo, [0,0,0,0], [0]*12, [0] has_size,
        //   [0,0], [1] no_compression
        // byte 0 = 00100000 = 0x20, byte 1 = 00000000 = 0x00,
        // byte 2 = last 7 header bits (0000001) + first PCM bit.
        assertEquals("byte 0: channels=1 (stereo)", 0x20.toByte(), frame[0])
        assertEquals("byte 1: zero pad field", 0x00.toByte(), frame[1])
        assertEquals("byte 2: no_compression=1 then first PCM bit", 0x03.toByte(), frame[2])
    }

    @Test
    fun encodeFrame_samplesAreBigEndian() {
        val frame = AlacEncoder.encodeFrame(buildPcmFrame())

        // First PCM sample: s=0 -> v = 11-32768 = -32757 = 0x800B.
        // LE input bytes are 0x0B 0x80; the frame must carry them BE: 0x80 0x0B
        // starting at bit 23 -> frame[2] bit0, frame[3], frame[4].
        assertEquals(0x03.toByte(), frame[2])
        assertEquals(0x00.toByte(), frame[3])
        assertEquals(0x17.toByte(), frame[4])
    }

    @Test
    fun encodePcm_partialFramePaddedWithSilence() {
        // 1400 bytes (< 1408): one padded frame; trailing 8 bytes zero-filled.
        val partial = ByteArray(1400) { 0x5A }
        val frames = AlacEncoder.encodePcm(partial)

        assertEquals("1400 bytes must produce exactly one padded frame", 1, frames.size)
        val expected = AlacEncoder.encodeFrame(partial + ByteArray(8))
        assertArrayEquals("padded frame must equal encodeFrame(input + 8 zero bytes)", expected, frames[0])
        assertEquals("last ALAC byte (silence + pad bit) must be zero", 0x00.toByte(), frames[0][frames[0].size - 1])
    }

    @Test
    fun encodePcm_fullFramePlusPartial() {
        // 1408 + 8 bytes: one full frame + one padded frame.
        val full = buildPcmFrame()
        val tail = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x78)
        val frames = AlacEncoder.encodePcm(full + tail)

        assertEquals(2, frames.size)
        assertArrayEquals("first frame is the full 1408-byte frame", AlacEncoder.encodeFrame(full), frames[0])
        assertArrayEquals(
            "second frame: 8 real bytes + 1400 zero bytes",
            AlacEncoder.encodeFrame(tail + ByteArray(1400)),
            frames[1]
        )
    }

    @Test
    fun encodePcm_multipleOfFrameSize() {
        val pcm = buildPcmFrame() + buildPcmFrame()
        val frames = AlacEncoder.encodePcm(pcm)

        assertEquals(2, frames.size)
        assertArrayEquals(AlacEncoder.encodeFrame(buildPcmFrame()), frames[0])
        assertArrayEquals(AlacEncoder.encodeFrame(buildPcmFrame()), frames[1])
    }

    @Test
    fun generateSineFrame_sanity() {
        val pcm = TestAudioFrames.generateSineFrame(440.0, 0)
        assertEquals(1408, pcm.size)

        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var maxAbs = 0
        var zeroCrossings = 0
        var prevSign = 0
        for (i in 0 until AlacEncoder.SAMPLES_PER_FRAME) {
            val left = buf.short.toInt()
            val right = buf.short.toInt()
            assertEquals("stereo channels must carry the same sample", left, right)

            val abs = kotlin.math.abs(left)
            if (abs > maxAbs) maxAbs = abs
            val sign = if (left >= 0) 1 else -1
            if (i > 0 && prevSign != 0 && sign != prevSign) zeroCrossings++
            prevSign = sign
        }

        // Amplitude = 0.5 * 32767; a 440 Hz sine over 352 samples at 44.1 kHz
        // has 2*352*440/44100 ~= 7 zero crossings.
        assertTrue("peak amplitude within 0.5*32767 range, was $maxAbs", maxAbs in 16_000..16_384)
        assertTrue("440 Hz period sanity: ~7 zero crossings, was $zeroCrossings", zeroCrossings in 5..9)
    }

    /** Fixed 1408-byte 16-bit LE stereo frame: sample s = ((s*37+11) % 65536) - 32768. */
    private fun buildPcmFrame(): ByteArray {
        val buf = ByteBuffer.allocate(1408).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until 704) {
            val v = ((s * 37 + 11) % 65536) - 32768
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
