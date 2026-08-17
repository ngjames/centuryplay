package com.airplay.streamer.airplay2.audio

import com.airplay.streamer.airplay2.crypto.Chacha20Cipher
import com.airplay.streamer.raop.WireConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * RTP Audio Streamer for AirPlay 2
 *
 * Sends encrypted ALAC audio packets over UDP with proper RTP headers.
 *
 * On-wire layout (verified against shairport-sync rtp.c
 * rtp_ap2_audio_receiver -> decipher_player_put_packet, and the dev
 * scripts/airplay2_transient.py reference):
 *
 *   [12-byte RTP header][ciphertext + 16-byte tag][8-byte nonce]
 *
 * - RTP header: V=2 (0x80), PT=96, sequence (2B BE), timestamp (4B BE),
 *   SSRC (4B, fixed 0x55667788).
 * - ChaCha20-Poly1305 audio encryption:
 *   - nonce = 4 zero bytes + 8-byte LITTLE-ENDIAN per-packet counter
 *     (a counter independent of the RTP sequence number; shairport
 *     front-pads the received 8 bytes to a 12-byte nonce);
 *   - AAD = RTP timestamp + SSRC, i.e. the 8 bytes at RTP header offsets
 *     4..11 (shairport: "authenticated additional data" = 8 bytes starting
 *     2 bytes after the RTP sequence, which is ts+ssrc).
 *
 * Anchors (type 0xD7, control port) and every timing decision derive from
 * ONE consistent monotonic clock ([timeSource], default System.nanoTime).
 * The wall-vs-monotonic discrepancy flagged in the dev TESTING_RULES.md
 * (time.time_ns() in the PTP master vs time.monotonic_ns() in audio
 * anchors) is avoided by routing every anchor timestamp through the same
 * clock; PtpMasterClock also stamps with System.nanoTime.
 *
 * Prebuffer: the first [prebufferFrames] frames (~1s of audio at 352 spf /
 * 44100 Hz) are held before the first packet is sent (a "cold start"
 * latency buffer, per the plan's C4 scope), then pacing is natural: the
 * capture loop feeds 1408-byte chunks in real time. If the buffer never
 * fills, it is flushed after [prebufferTimeoutMs].
 */
class RtpStreamer(
    private val targetHost: String,
    private val dataPort: Int,
    private val controlPort: Int,
    sharedSecret: ByteArray,
    /** Frames to buffer before the first packet is sent (0 disables prebuffering). */
    private val prebufferFrames: Int = DEFAULT_PREBUFFER_FRAMES,
    /** If the prebuffer never fills, flush after this many ms. */
    private val prebufferTimeoutMs: Long = DEFAULT_PREBUFFER_TIMEOUT_MS,
    /** Single monotonic clock used for anchor timestamps (injectable for tests). */
    private val timeSource: () -> Long = System::nanoTime,
    /**
     * Clock offset (ns) added to [timeSource] when stamping anchor packet
     * times, converting local time into the receiver clock frame in NTP
     * timing mode. Default 0 keeps PTP/current behavior identical.
     */
    internal var anchorOffsetNs: () -> Long = { 0L },
    /** Injectable packet sink; defaults to real UDP sockets (injectable for byte-exact tests). */
    private val transport: RtpTransport? = null
) {
    interface RtpTransport {
        fun sendData(packet: ByteArray)
        fun sendControl(packet: ByteArray)
    }

    companion object {
        const val SAMPLES_PER_FRAME = WireConstants.AudioFormat.SAMPLES_PER_FRAME
        const val SAMPLE_RATE = WireConstants.AudioFormat.SAMPLE_RATE
        const val SSRC = WireConstants.Rtp.SSRC
        const val LATENCY_FRAMES = WireConstants.Streaming.LATENCY_FRAMES
        /** ~1 second of audio (125 * 352 / 44100 ~= 0.998s). */
        const val DEFAULT_PREBUFFER_FRAMES = WireConstants.Streaming.PREBUFFER_FRAMES
        const val DEFAULT_PREBUFFER_TIMEOUT_MS = WireConstants.Streaming.PREBUFFER_TIMEOUT_MS
        private const val ANCHOR_INTERVAL_FRAMES = WireConstants.Streaming.ANCHOR_INTERVAL_FRAMES
    }

    private val cipher = Chacha20Cipher(sharedSecret, sharedSecret)
    private val socketTransport: SocketTransport? = if (transport == null) SocketTransport() else null
    private val out: RtpTransport get() = transport ?: socketTransport!!

    private var sequenceNumber = Random.nextInt(0, 65536)
    private var rtpTimestamp = 0
    private var encryptionCounter = 0L
    private var anchorPacketSeq = 0

    private data class PendingFrame(val alacPayload: ByteArray, val clockId: Long, val sendAnchor: Boolean)

    private val pending = ArrayDeque<PendingFrame>()
    private var prebufferActive = false
    private var prebufferStartNs = 0L
    private var firstPacketSent = false

    /** Real UDP transport used when no [transport] is injected. */
    private inner class SocketTransport : RtpTransport {
        private var dataSocket: DatagramSocket? = null
        private var controlSocket: DatagramSocket? = null
        private var targetAddress: InetAddress? = null

        fun initSockets() {
            dataSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            targetAddress = InetAddress.getByName(targetHost)
        }

        fun closeSockets() {
            dataSocket?.close()
            controlSocket?.close()
            dataSocket = null
            controlSocket = null
        }

        override fun sendData(data: ByteArray) {
            val packet = DatagramPacket(data, data.size, targetAddress, dataPort)
            dataSocket?.send(packet)
        }

        override fun sendControl(data: ByteArray) {
            val packet = DatagramPacket(data, data.size, targetAddress, controlPort)
            controlSocket?.send(packet)
        }
    }

    /**
     * Initialize the streamer
     */
    fun init() {
        socketTransport?.initSockets()
    }

    /**
     * Close the streamer
     */
    fun close() {
        socketTransport?.closeSockets()
    }

    /**
     * Set the starting RTP sequence and timestamp (from FLUSH command)
     */
    fun setStarting(seq: Int, timestamp: Int) {
        sequenceNumber = seq
        rtpTimestamp = timestamp
    }

    /**
     * Send a sentinel anchor packet to establish timing.
     *
     * The timestamp derives from the single monotonic [timeSource] clock,
     * never from a wall clock.
     */
    fun sendSentinelAnchor(clockId: Long) {
        val ptpTimeNs = timeSource() + anchorOffsetNs() + 2_000_000_000L // 2 seconds in future
        sendAnchorPacket(rtpTimestamp, ptpTimeNs, clockId, isSentinel = true)
    }

    /**
     * Send an anchor packet (Type 0xD7) to the control port.
     *
     * Layout (28 bytes, per dev docs/ANCHOR_PACKET_FORMAT.md and
     * shairport-sync rtp.c case 215):
     *   byte 0: 0x80 (V=2) | 0x10 if sentinel
     *   byte 1: 0xD7 (215)
     *   bytes 2-3: sequence number (BE)
     *   bytes 4-7: frame_1 = frame_2 - LATENCY_FRAMES (BE)
     *   bytes 8-15: remote_packet_time_ns (BE, monotonic clock domain)
     *   bytes 16-19: frame_2 (BE)
     *   bytes 20-27: clock_id (BE)
     *
     * Note: shairport computes notified_latency = frame_2 - frame_1 and
     * expects the standard 77175-frame AirPlay latency, so frame_1 must be
     * frame_2 - 77175 (matches dev scripts/airplay2_transient.py; the
     * "+77175" wording in the dev doc's Key Points is stale).
     */
    fun sendAnchorPacket(rtpTs: Int, ptpTimeNs: Long, clockId: Long, isSentinel: Boolean = false) {
        val packet = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)

        packet.put((0x80 or (if (isSentinel) 0x10 else 0x00)).toByte())
        packet.put(0xD7.toByte())
        packet.putShort((anchorPacketSeq++ and 0xFFFF).toShort())

        val frame2 = rtpTs
        val frame1 = frame2 - LATENCY_FRAMES
        packet.putInt(frame1)
        packet.putLong(ptpTimeNs)
        packet.putInt(frame2)
        packet.putLong(clockId)

        out.sendControl(packet.array())
    }

    /**
     * Send a single ALAC audio packet (through the prebuffer gate).
     */
    fun sendAudioPacket(alacPayload: ByteArray, clockId: Long, sendAnchor: Boolean = false) {
        if (prebufferFrames > 0 && !firstPacketSent) {
            if (!prebufferActive) {
                prebufferActive = true
                prebufferStartNs = timeSource()
            }
            pending.addLast(PendingFrame(alacPayload, clockId, sendAnchor))
            if (pending.size >= prebufferFrames || elapsedPrebufferNs() >= prebufferTimeoutMs * 1_000_000L) {
                flushPrebuffer()
            }
            return
        }
        sendAudioPacketNow(alacPayload, clockId, sendAnchor)
    }

    private fun flushPrebuffer() {
        prebufferActive = false
        val frames = pending.toList()
        pending.clear()
        for (frame in frames) {
            sendAudioPacketNow(frame.alacPayload, frame.clockId, frame.sendAnchor)
        }
    }

    private fun elapsedPrebufferNs(): Long = timeSource() - prebufferStartNs

    /**
     * Build and send one RTP packet:
     *   [12-byte header][ciphertext+16B tag][8B nonce]
     */
    private fun sendAudioPacketNow(alacPayload: ByteArray, clockId: Long, sendAnchor: Boolean) {
        // RTP header (12 bytes): V=2, PT=96, seq (2B BE), ts (4B BE), ssrc (4B)
        val rtpHeader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        rtpHeader.put(WireConstants.Rtp.VERSION_BYTE.toByte()) // V=2
        rtpHeader.put(WireConstants.Rtp.PAYLOAD_TYPE.toByte()) // M=0, PT=96
        rtpHeader.putShort(sequenceNumber.toShort())
        rtpHeader.putInt(rtpTimestamp)
        rtpHeader.putInt(SSRC)

        // Nonce (12 bytes): 4 zero bytes + 8-byte LITTLE-ENDIAN per-packet counter
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        nonce.putInt(0)
        nonce.putLong(encryptionCounter++)

        // AAD: RTP header bytes 4..11 (timestamp + SSRC)
        val aad = rtpHeader.array().copyOfRange(4, 12)

        // Encrypt payload (output = ciphertext + 16-byte tag)
        val encryptedPayload = cipher.encrypt(alacPayload, nonce.array(), aad)

        // Final packet: RTP header + encrypted payload + 8-byte nonce
        val packet = ByteBuffer.allocate(12 + encryptedPayload.size + 8)
        packet.put(rtpHeader.array())
        packet.put(encryptedPayload)
        packet.put(nonce.array().copyOfRange(4, 12)) // Append 8-byte nonce

        out.sendData(packet.array())

        // Update sequence and timestamp
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        rtpTimestamp += SAMPLES_PER_FRAME
        firstPacketSent = true

        // Send anchor packet periodically (~every 1 second = 125 packets)
        if (sendAnchor && sequenceNumber % ANCHOR_INTERVAL_FRAMES == 0) {
            sendAnchorPacket(rtpTimestamp, timeSource() + anchorOffsetNs() + 2_000_000_000L, clockId)
        }
    }
}
