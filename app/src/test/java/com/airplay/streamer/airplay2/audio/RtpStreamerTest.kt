package com.airplay.streamer.airplay2.audio

import com.airplay.streamer.airplay2.crypto.Chacha20Cipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Byte-exact tests for [RtpStreamer]:
 *
 * - 12-byte RTP header: V=2, PT=96, seq (2B BE), ts (4B BE), SSRC 0x55667788;
 * - on-wire layout [12B header][ciphertext+16B tag][8B nonce];
 * - nonce = 4 zero bytes + 8-byte LITTLE-ENDIAN per-packet counter
 *   (independent of the RTP sequence number), matching the shairport-sync
 *   decrypt (rtp.c rtp_ap2_audio_receiver: nonce = 4 zeros + 8 received
 *   bytes; AAD = timestamp + SSRC);
 * - AAD = RTP timestamp + SSRC (header bytes 4..11), proven by a decrypt
 *   roundtrip with that AAD;
 * - anchor packet (type 0xD7) layout per dev docs/ANCHOR_PACKET_FORMAT.md
 *   with remote_packet_time_ns derived from ONE monotonic clock
 *   (System.nanoTime by default; injectable timeSource here);
 * - prebuffer: first packet is only sent after >= [prebufferFrames] frames
 *   are buffered, or the injectable timeout elapses.
 */
class RtpStreamerTest {

    private val key = ByteArray(32) { it.toByte() }
    private val payload = ByteArray(20) { 0xAA.toByte() }

    /** Records every packet instead of sending over UDP. */
    private class RecordingTransport : RtpStreamer.RtpTransport {
        val dataPackets = mutableListOf<ByteArray>()
        val controlPackets = mutableListOf<ByteArray>()
        override fun sendData(packet: ByteArray) { dataPackets.add(packet) }
        override fun sendControl(packet: ByteArray) { controlPackets.add(packet) }
    }

    private fun streamer(
        transport: RecordingTransport,
        prebufferFrames: Int = 125,
        prebufferTimeoutMs: Long = 2000L,
        timeSource: () -> Long = System::nanoTime
    ) = RtpStreamer(
        "127.0.0.1", 1, 2, key,
        prebufferFrames = prebufferFrames,
        prebufferTimeoutMs = prebufferTimeoutMs,
        timeSource = timeSource,
        transport = transport
    )

    // ---------------------------------------------------------------- RTP header

    @Test
    fun rtpHeader_byteExactForKnownSeqTsSsrc() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(0x1234, 0x11223344)
        streamer.sendAudioPacket(payload, 0L)

        assertEquals(1, transport.dataPackets.size)
        val pkt = transport.dataPackets[0]
        // V=2 (0x80), PT=96 (0x60), seq 0x1234 BE, ts 0x11223344 BE, ssrc 0x55667788
        assertArrayEquals(
            "RTP header must be byte-exact",
            hex("80 60 12 34 11 22 33 44 55 66 77 88"),
            pkt.copyOfRange(0, 12)
        )
    }

    @Test
    fun rtpHeader_ssrcIsFixed0x55667788() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(0, 0)
        streamer.sendAudioPacket(payload, 0L)

        val ssrc = ByteBuffer.wrap(transport.dataPackets[0], 8, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(0x55667788, ssrc)
    }

    // ---------------------------------------------------------------- ciphertext layout

    @Test
    fun packetLayout_headerThenCipherPlusTagThenNonce() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(0, 0)
        streamer.sendAudioPacket(payload, 0L)

        val pkt = transport.dataPackets[0]
        // 12B header + (20B plaintext + 16B tag) + 8B nonce = 56 bytes
        assertEquals("packet must be header(12) + cipher+tag(36) + nonce(8)", 12 + 36 + 8, pkt.size)

        // nonce at the very end: 4 zeros + 8-byte LE counter (counter 0 for first packet)
        assertArrayEquals("trailing 8 bytes are the nonce", hex("00 00 00 00 00 00 00 00"), pkt.copyOfRange(48, 56))
    }

    @Test
    fun nonce_isLittleEndianCounterNotRtpSequence() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(1000, 0)
        streamer.sendAudioPacket(payload, 0L)
        streamer.sendAudioPacket(payload, 0L)
        streamer.sendAudioPacket(payload, 0L)

        assertEquals(3, transport.dataPackets.size)
        // RTP sequence numbers advance 1000, 1001, 1002 (BE 03 EA)
        assertEquals("seq must be 1002 BE", 0x03EA, ByteBuffer.wrap(transport.dataPackets[2], 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF)
        // ...but the nonce counter is 0, 1, 2 little-endian
        assertArrayEquals("counter 0", hex("00 00 00 00 00 00 00 00"), transport.dataPackets[0].copyOfRange(48, 56))
        assertArrayEquals("counter 1 LE", hex("01 00 00 00 00 00 00 00"), transport.dataPackets[1].copyOfRange(48, 56))
        assertArrayEquals("counter 2 LE", hex("02 00 00 00 00 00 00 00"), transport.dataPackets[2].copyOfRange(48, 56))
    }

    @Test
    fun ciphertext_decryptsWithNonce4zLeCounterAndAadTsPlusSsrc() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(7, 0x01020304)
        streamer.sendAudioPacket(payload, 0L)

        val pkt = transport.dataPackets[0]
        val header = pkt.copyOfRange(0, 12)
        val ciphertext = pkt.copyOfRange(12, pkt.size - 8)   // cipher + 16B tag
        val nonce8 = pkt.copyOfRange(pkt.size - 8, pkt.size) // last 8 bytes

        // Reconstruct the 12-byte nonce exactly as the receiver does:
        // 4 zero bytes + the 8 received nonce bytes.
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(ByteArray(4))
            .put(nonce8)
            .array()

        // AAD = RTP timestamp + SSRC (header bytes 4..11).
        val aad = header.copyOfRange(4, 12)

        val decrypted = Chacha20Cipher(key, key).decrypt(ciphertext, nonce, aad)
        assertArrayEquals("AAD = ts+ssrc and nonce = 4z + LE counter must decrypt the payload", payload, decrypted)
    }

    @Test
    fun ciphertext_tamperedNonceFailsDecrypt() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(0, 0)
        streamer.sendAudioPacket(payload, 0L)

        val pkt = transport.dataPackets[0]
        val ciphertext = pkt.copyOfRange(12, pkt.size - 8)
        // counter 5 LE, not the counter 0 used for this packet
        val wrongNonce = hex("00 00 00 00 05 00 00 00 00 00 00 00")
        val aad = pkt.copyOfRange(4, 12)

        try {
            Chacha20Cipher(key, key).decrypt(ciphertext, wrongNonce, aad)
            throw AssertionError("decrypt with a wrong nonce must fail (MAC mismatch)")
        } catch (expected: Exception) {
            // BouncyCastle InvalidCipherTextException (or wrapped) - expected
        }
    }

    // ---------------------------------------------------------------- anchors

    @Test
    fun anchorPacket_d7LayoutPerDevDoc() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        // sentinel flag set: byte 0 = 0x80 | 0x10 = 0x90
        streamer.sendAnchorPacket(0x00012345, 0x1020304050607080L, 0x1122334455667788L, isSentinel = true)

        assertEquals(1, transport.controlPackets.size)
        val pkt = transport.controlPackets[0]
        assertEquals("anchor packet must be 28 bytes", 28, pkt.size)
        assertArrayEquals(
            "0x90 | 0xD7 | seq 0 | frame_1 = frame_2-77175 | time | frame_2 | clock_id",
            hex(
                "90 D7 00 00" +        // flags+type+seq (BE)
                    "FF FF F5 CE" +    // frame_1 = (0x00012345 - 77175) & 0xFFFFFFFF
                    "10 20 30 40 50 60 70 80" + // remote_packet_time_ns BE
                    "00 01 23 45" +    // frame_2 = 0x00012345
                    "11 22 33 44 55 66 77 88"    // clock_id BE
            ),
            pkt
        )
    }

    @Test
    fun anchorPacket_remoteTimeFromSingleMonotonicClock() {
        val transport = RecordingTransport()
        var now = 1_000_000_000L // fake monotonic clock (System.nanoTime domain)
        val streamer = streamer(transport, prebufferFrames = 0, timeSource = { now })

        streamer.sendSentinelAnchor(0x1122334455667788L)

        val pkt = transport.controlPackets[0]
        // timeSource() + 2s = 3_000_000_000 = 0xB2D05E00, big-endian at bytes 8..15
        assertArrayEquals("sentinel time must derive from the single monotonic timeSource", hex("00 00 00 00 B2 D0 5E 00"), pkt.copyOfRange(8, 16))
        // and the sentinel bit is set
        assertEquals(0x90.toByte(), pkt[0])
    }

    // ---------------------------------------------------------------- prebuffer

    @Test
    fun prebuffer_firstPacketOnlyAfter125Frames() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 125)
        streamer.setStarting(0, 0)

        for (i in 0 until 124) {
            streamer.sendAudioPacket(payload, 0L)
        }
        assertEquals("no packet may be sent before the buffer fills", 0, transport.dataPackets.size)

        streamer.sendAudioPacket(payload, 0L) // 125th frame
        assertEquals("125th frame flushes the whole buffer", 125, transport.dataPackets.size)

        // sequence numbers in order 0..124
        for (i in 0 until 125) {
            val seq = ByteBuffer.wrap(transport.dataPackets[i], 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            assertEquals("seq $i", i, seq)
        }
    }

    @Test
    fun prebuffer_timeoutFlushesPartialBuffer() {
        val transport = RecordingTransport()
        var now = 0L
        val streamer = streamer(transport, prebufferFrames = 125, prebufferTimeoutMs = 100, timeSource = { now })

        streamer.sendAudioPacket(payload, 0L)
        assertEquals(0, transport.dataPackets.size)

        // advance past the 100ms injectable timeout and feed another frame
        now = 101_000_000L
        streamer.sendAudioPacket(payload, 0L)
        assertEquals("timeout must flush the buffered frames", 2, transport.dataPackets.size)
    }

    @Test
    fun prebuffer_disabledSendsImmediately() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 0)
        streamer.setStarting(0, 0)

        streamer.sendAudioPacket(payload, 0L)
        assertEquals("prebufferFrames=0 must bypass buffering", 1, transport.dataPackets.size)
    }

    @Test
    fun prebuffer_afterStartPacketsSendImmediately() {
        val transport = RecordingTransport()
        val streamer = streamer(transport, prebufferFrames = 125)
        streamer.setStarting(0, 0)

        for (i in 0 until 125) streamer.sendAudioPacket(payload, 0L)
        assertEquals(125, transport.dataPackets.size)

        streamer.sendAudioPacket(payload, 0L)
        assertEquals("frames after the prebuffer flush send immediately", 126, transport.dataPackets.size)
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
