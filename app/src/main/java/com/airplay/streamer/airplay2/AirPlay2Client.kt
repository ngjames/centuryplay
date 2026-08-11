package com.airplay.streamer.airplay2

import com.airplay.streamer.airplay2.audio.RtpStreamer
import com.airplay.streamer.airplay2.protocol.RtspClient
import com.airplay.streamer.airplay2.protocol.TransientPairing
import com.airplay.streamer.airplay2.timing.PtpMasterClock
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.airplay.streamer.airplay2.util.Ap2Log
import com.airplay.streamer.airplay2.timing.NtpTiming

/** Timing protocol advertised to the receiver in the event-channel SETUP. */
enum class TimingMode { NTP, PTP }

/**
 * AirPlay 2 Client
 * 
 * Main entry point for AirPlay 2 streaming with transient pairing.
 * 
 * Usage:
 * ```kotlin
 * val client = AirPlay2Client("192.168.1.100")
 * client.connect()
 * if (client.pair()) {
 *     client.setupStreaming()
 *     client.streamAudio(audioFrames)
 * }
 * client.disconnect()
 * ```
 */
class AirPlay2Client(
    private val host: String,
    private val port: Int = 7000,
    private val timingMode: TimingMode = TimingMode.NTP
) {
    private val rtspClient = RtspClient(host, port)
    private val pairing = TransientPairing(rtspClient)
    private var ptpClock: PtpMasterClock? = null
    private var rtpStreamer: RtpStreamer? = null
    
    /** True once the PTP master clock is started (PTP mode only). */
    internal var clockStarted: Boolean = false
        private set

    /** Latest NTP clock offset (receiver clock = local clock + offset), ns. */
    private val latestOffsetNs = AtomicLong(0L)

    private var ntpResponderJob: kotlinx.coroutines.Job? = null
    private var ntpTimingSocket: DatagramSocket? = null
    
    private var sessionUuid: String? = null
    private var controlPort: Int = 0
    private var dataPort: Int = 0
    private var audioSharedSecret: ByteArray? = null
    private var sentinelSent = false
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Connect to the AirPlay receiver
     */
    fun connect() {
        rtspClient.connect()
    }
    
    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        ntpResponderJob?.cancel()
        ntpTimingSocket?.close()
        ntpTimingSocket = null
        ptpClock?.stop()
        rtpStreamer?.close()
        rtspClient.stopFeedback()
        rtspClient.teardown()
        rtspClient.disconnect()
        scope.cancel()
    }
    
    /**
     * Perform transient pairing
     */
    suspend fun pair(): Boolean {
        return pairing.pair()
    }
    
    /**
     * Setup streaming (event channel + audio stream)
     */
    suspend fun setupStreaming(): Boolean {
        // Step 1: Setup event channel (PTP timing)
        setupEventChannel()
        
        // Step 2: Start timing master (PTP clock or NTP responder)
        if (timingMode == TimingMode.PTP) {
            ptpClock = PtpMasterClock(host).apply {
                start(scope)
            }
            clockStarted = true

            // Wait for clock to establish
            delay(500)
            ptpClock?.sendAnnounceBurst()
            delay(500)
        } else {
            startNtpTimingResponder()
        }
        
        // Step 3: Setup audio stream
        val (ctrl, data, secret) = setupAudioStream()
        controlPort = ctrl
        dataPort = data
        audioSharedSecret = secret
        
        // Match Python: Send another Announce Burst and wait for NQPTP to "lock"
        // This is critical for the receiver to accept the stream
        ptpClock?.sendAnnounceBurst(count = 5, delayMs = 50)
        delay(1500)
        
        // Step 4: Send FLUSH to set the initial RTP timestamp state.
        // FLUSH must precede RECORD (pyatv/airplay2-rs send FLUSH before
        // RECORD; RTP-Info seq=0;rtptime=0 anchors the streamer's start).
        flush()
        
        // Step 4.5: Send RECORD
        record()
        
        // Step 4.6: Unmute (receiver volumes are 0..1; 0.0f is the dev's
        // known-good unmute value at setup, UI slider maps 0..1 later)
        setVolume(0.0f)
        
        // Step 5: Initialize RTP streamer
        // Ensure we start with specific seq/ts to match FLUSH
        rtpStreamer = RtpStreamer(host, dataPort, controlPort, audioSharedSecret!!).apply {
            init()
            setStarting(0, 0) // Match FLUSH
        }
        if (timingMode == TimingMode.NTP) {
            rtpStreamer!!.anchorOffsetNs = { latestOffsetNs.get() }
        }
        
        sentinelSent = false
        
        // Keepalive: POST /feedback every ~30s while streaming (tolerant of failures)
        scope.launch { rtspClient.sendFeedback() }
        
        return true
    }
    
    /**
     * Setup event channel. The receiver's advertised eventPort is unused by
     * this sender (no event subscription is implemented); only session
     * establishment is validated. The session UUID is pushed into
     * [RtspClient.sessionUuid] so TEARDOWN targets the real session URI.
     */
    private fun setupEventChannel() {
        sessionUuid = UUID.randomUUID().toString().uppercase()
        rtspClient.sessionUuid = sessionUuid
        
        val setupBody = NSDictionary().apply {
            put("deviceID", "AA:BB:CC:DD:EE:FF")
            put("sessionUUID", sessionUuid)
            put("timingProtocol", if (timingMode == TimingMode.PTP) "PTP" else "NTP")
            put("timingPeerInfo", NSDictionary().apply {
                put("Addresses", arrayOf(rtspClient.getLocalAddress() ?: getLocalIp()))
                put("ID", "AA:BB:CC:DD:EE:FF")
            })
            put("groupUUID", sessionUuid)
            put("groupContainsGroupLeader", false)
            put("isMultiSelectAirPlay", true)
            put("macAddress", "AA:BB:CC:DD:EE:FF")
            put("model", "iPhone14,3")
            put("name", "centuryplay")
            put("osBuildVersion", "20F66")
            put("osName", "iPhone OS")
            put("osVersion", "16.5")
            put("senderSupportsRelay", false)
            put("sourceVersion", "690.7.1")
            put("statsCollectionEnabled", false)
        }
        
        val baos = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(baos, setupBody)
        val bodyBytes = baos.toByteArray()
        
        val response = rtspClient.sendRtsp(
            "SETUP",
            "rtsp://$host/$sessionUuid",
            bodyBytes,
            "application/x-apple-binary-plist"
        )
        
        if (response.statusCode != 200) {
            throw Exception("SETUP failed: ${response.statusCode}")
        }
    }
    
    /**
     * Setup audio stream and get ports/secret
     */
    private fun setupAudioStream(): Triple<Int, Int, ByteArray> {
        val sharedSecret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        
        val setupBody = NSDictionary().apply {
            put("streams", arrayOf(
                NSDictionary().apply {
                    put("type", 96)
                    // ALAC (uncompressed) per the AP2 audio SETUP spec:
                    // audioFormat = 'alac' = 1633771873 (NOT the RAOP L16
                    // bitmask 0x100000 - a 'format' key may carry the
                    // bitmask, but 'audioFormat' must be the fourcc), ct=2
                    // (ALAC; ct=1 is PCM), type=96, spf=352.
                    // refs: airplay2-rs AIRPLAY_2_SPEC.md, pyatv raop2.
                    put("audioFormat", 1633771873)
                    put("audioMode", "default")
                    put("ct", 2)
                    put("spf", 352)
                    put("shk", NSData(sharedSecret))
                    put("isMedia", true)
                    put("latencyMax", 88200)
                    put("latencyMin", 11025)
                    put("supportsDynamicStreamID", true)
                    put("streamConnectionID", rtspClient.cseq.toLong())
                }
            ))
        }
        
        val baos = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(baos, setupBody)
        val bodyBytes = baos.toByteArray()
        
        val response = rtspClient.sendRtsp(
            "SETUP",
            "rtsp://$host/$sessionUuid",
            bodyBytes,
            "application/x-apple-binary-plist"
        )
        
        if (response.statusCode != 200) {
            throw Exception("Audio SETUP failed: ${response.statusCode}")
        }
        
        val plist = PropertyListParser.parse(ByteArrayInputStream(response.body)) as NSDictionary
        val streams = plist["streams"] as? NSArray
        val stream = (streams?.array?.firstOrNull() as? NSDictionary) 
            ?: throw Exception("No stream in response")
        
        val ctrlPort = stream["controlPort"]?.toString()?.toInt() ?: 0
        val dataPortValue = stream["dataPort"]?.toString()?.toInt() ?: 0
        // Use the local sharedSecret, NOT what's in the response
        
        return Triple(ctrlPort, dataPortValue, sharedSecret)
    }

    /**
     * Send FLUSH command with RTP-Info
     */
    private fun flush() {
        // RTP-Info: seq=0;rtptime=0
        val headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=0;rtptime=0"
        )
        rtspClient.sendRtsp("FLUSH", "rtsp://$host/$sessionUuid", extraHeaders = headers)
    }

    /**
     * Send SET_PARAMETER to set volume (0..1). Public so the capture service
     * can route UI volume changes to the AP2 path.
     */
    fun setVolume(vol: Float) {
        val content = "volume: $vol\r\n".toByteArray()
        rtspClient.sendRtsp(
            "SET_PARAMETER", 
            "rtsp://$host/$sessionUuid", 
            body = content,
            contentType = "text/parameters"
        )
    }
    
    /**
     * Send RECORD command
     */
    private fun record() {
        val response = rtspClient.sendRtsp(
            "RECORD",
            "rtsp://$host/$sessionUuid"
        )
        if (response.statusCode != 200) {
            throw Exception("RECORD failed: ${response.statusCode}")
        }
    }
    
    /**
     * Stream audio frames (batch mode with pacing for static files)
     */
    fun streamAudio(alacFrames: List<ByteArray>) {
        val streamer = rtpStreamer ?: throw IllegalStateException("Streamer not initialized")
        val clockId = ptpClock?.clockId ?: 0L
        
        // Send sentinel anchor
        streamer.sendSentinelAnchor(clockId)
        sentinelSent = true
        
        // Send frames
        for ((index, frame) in alacFrames.withIndex()) {
            streamer.sendAudioPacket(frame, clockId, sendAnchor = (index % 125 == 0))
            
            // Pacing: ~8ms per frame, batch 10 frames before sleeping
            if (index % 10 == 0) {
                Thread.sleep(80)
            }
        }
    }

    /**
     * Send raw PCM audio data (real-time mode)
     * Encodes to ALAC and sends immediately.
     */
    fun sendAudioData(pcmData: ByteArray) {
        val streamer = rtpStreamer ?: return // Ignore if not ready
        val clockId = ptpClock?.clockId ?: 0L
        
        // Send sentinel anchor if not sent yet
        if (!sentinelSent) {
            streamer.sendSentinelAnchor(clockId)
            sentinelSent = true
        }
        
        // Encode PCM to ALAC frames
        val frames = com.airplay.streamer.airplay2.audio.AlacEncoder.encodePcm(pcmData)
        
        // Send frames immediately (source provides pacing)
        for (frame in frames) {
            streamer.sendAudioPacket(frame, clockId, sendAnchor = true)
        }
    }

    /**
     * Liveness probe for the mid-stream health monitor.
     *
     * Posts a /feedback keepalive over the RTSP control connection (the same
     * benign POST the keepalive loop already sends every ~30s). Returns false
     * when the receiver has dropped the connection (EOF/exception) or the
     * client has been torn down.
     */
    fun connectionAlive(): Boolean = rtspClient.sendFeedbackOnce()
    
    /**
     * Get local IP address
     */
    private fun getLocalIp(): String {
        return com.airplay.streamer.airplay2.util.NetworkUtils.getLocalIpAddress()
    }

    /**
     * Bind a UDP responder for the AirPlay 2 NTP timing exchange (PT 82/83).
     *
     * Answers NTP-style requests from the receiver, stamping the origin and
     * receive timestamps in the local clock frame and deriving the clock
     * offset via [NtpTiming.offsetFromTimingExchange] for anchor stamping.
     */
    private fun startNtpTimingResponder() {
        ntpResponderJob = scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket()
            ntpTimingSocket = socket
            Ap2Log.d("AirPlay2Client", "NTP timing responder bound on port ${socket.localPort}")
            try {
                val buffer = ByteArray(128)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.length >= 32) {
                        val req = packet.data
                        val resp = ByteArray(packet.length)
                        System.arraycopy(req, 0, resp, 0, 8)
                        resp[1] = (0x53 or 0x80).toByte()
                        System.arraycopy(req, 24, resp, 8, 8)
                        val nowMs = System.currentTimeMillis()
                        val ntpSec = (nowMs / 1000) + 2208988800L
                        val ntpFrac = (((nowMs % 1000) * 4294967296L) / 1000)
                        writeNtpTimestamp(resp, 16, ntpSec, ntpFrac)
                        writeNtpTimestamp(resp, 24, ntpSec, ntpFrac)
                        socket.send(DatagramPacket(resp, packet.length, packet.address, packet.port))
                        val t1 = ntpToNanos(req, 24)
                        // t2/t3/t4 in the same NTP-nanos domain as t1, derived from
                        // the same wall-clock reading used for the response stamps
                        // (receiver-side t4 is not observable by the responder; ~t2).
                        val t2 = ntpToNanos(ntpSec, ntpFrac)
                        val t3 = t2
                        val t4 = t2
                        latestOffsetNs.set(NtpTiming.offsetFromTimingExchange(t1, t2, t3, t4))
                    }
                }
            } catch (e: Exception) {
                if (!socket.isClosed) Ap2Log.d("AirPlay2Client", "NTP responder stopped: ${e.message}")
            } finally {
                socket.close()
                ntpTimingSocket = null
            }
        }
    }

    private fun writeNtpTimestamp(b: ByteArray, o: Int, s: Long, f: Long) {
        b[o] = (s shr 24).toByte(); b[o + 1] = (s shr 16).toByte(); b[o + 2] = (s shr 8).toByte(); b[o + 3] = s.toByte()
        b[o + 4] = (f shr 24).toByte(); b[o + 5] = (f shr 16).toByte(); b[o + 6] = (f shr 8).toByte(); b[o + 7] = f.toByte()
    }

    /** NTP seconds/fraction (epoch 1900) as nanoseconds since the NTP epoch. */
    private fun ntpToNanos(sec: Long, frac: Long): Long =
        sec * 1_000_000_000L + (frac * 1_000_000_000L) / 0x1_0000_0000L

    private fun ntpToNanos(req: ByteArray, o: Int): Long {
        val sec = ((req[o].toLong() and 0xFF) shl 24) or ((req[o + 1].toLong() and 0xFF) shl 16) or
            ((req[o + 2].toLong() and 0xFF) shl 8) or (req[o + 3].toLong() and 0xFF)
        val frac = ((req[o + 4].toLong() and 0xFF) shl 24) or ((req[o + 5].toLong() and 0xFF) shl 16) or
            ((req[o + 6].toLong() and 0xFF) shl 8) or (req[o + 7].toLong() and 0xFF)
        return ntpToNanos(sec, frac)
    }
}
