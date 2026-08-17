package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.util.ByteArrayFormat.closeQuietly
import com.airplay.streamer.util.ByteArrayFormat.toHexId
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * RAOP (Remote Audio Output Protocol) client for AirPlay 1 speakers.
 *
 * Orchestrates the RTSP session lifecycle (fp-setup -> OPTIONS -> ANNOUNCE ->
 * SETUP -> RECORD, volume, teardown) and the streaming entry points
 * ([streamAudio], [setVolume]). Protocol mechanics live in the extracted
 * helpers: [RtspTransport] (RTSP request/response), [FairPlayHandshake],
 * [SdpBuilder], [RtpPacketBuilder] and [NtpResponder].
 *
 * Concurrency: the timing responder, sync sender and health monitor run as
 * coroutines on a `SupervisorJob` scope (matching AirPlay2Client's pattern),
 * replacing the former raw Threads.
 */
class RaopClient(
    private val host: String,
    private val port: Int,
    private val deviceFeatures: Map<String, String> = emptyMap()
) {
    companion object {
        private const val TAG = "RaopClient"
        private const val USER_AGENT = "Music/1.5.6 (Macintosh; OS X 15.7.3) AppleWebKit/621.3.11.11.3"

        // Apple's RSA Public Key for AirPlay (2048-bit) - from shairport-sync's super_secret_key
        private const val RSA_MODULUS = "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
                "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
                "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
                "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
                "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
                "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
        private const val RSA_EXPONENT = "AQAB"
    }

    // Client identifiers (as per AirPlay spec)
    private val clientInstance = ByteArray(8).also { Random.nextBytes(it) }.toHexId()
    private val dacpId = clientInstance
    private val activeRemote = Random.nextLong(100000000, 4294967295).toString()

    private var rtspSocket: Socket? = null
    private var rtspInput: InputStream? = null
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    // Background loops (timing responder, sync sender, health monitor) run as
    // coroutines on this scope, replacing the previous raw Threads.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timingJob: Job? = null
    private var syncJob: Job? = null
    private var healthJob: Job? = null
    private var syncSequence = 0

    private val cSeq = AtomicInteger(0)
    private var serverSessionId: String? = null
    private val localSessionId: String = Random.nextLong(0, Long.MAX_VALUE).toString()
    private var localIp: String = "0.0.0.0"
    private var serverPort: Int = 0
    private var serverControlPort: Int = 0  // Server's control port for sync packets

    // Public-API streaming state, read/written across threads by the caller
    // (AudioCaptureService) and the background coroutines.
    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    private var rtpSequence: Int = Random.nextInt(0xFFFF)
    internal var rtpTimestamp: Long = Random.nextLong(0xFFFFFFFFL)
    private val ssrc: Int = Random.nextInt()

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var fairPlaySetupValid = false

    interface StreamingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    var callback: StreamingCallback? = null

    private val rtspTransport = RtspTransport(
        socket = { rtspSocket },
        input = { rtspInput },
        cSeq = cSeq,
        userAgent = USER_AGENT,
        defaultHeaders = {
            mapOf(
                "Client-Instance" to clientInstance,
                "DACP-ID" to dacpId,
                "Active-Remote" to activeRemote
            )
        },
        logD = ::logD,
        logE = ::logE
    )

    private val fairPlayHandshake = FairPlayHandshake(rtspTransport, ::logD)

    private val supportedEncryptionTypes: Set<Int> =
        RaopCapabilities.encryptionTypes(deviceFeatures).ifEmpty { setOf(0, 1) }
    private val useFairPlayStub = RaopCapabilities.requiresUnsupportedFairPlay(deviceFeatures)
    private val useEncryption = 1 in supportedEncryptionTypes

    private val sdpBuilder = SdpBuilder(
        host = host,
        sessionId = localSessionId,
        useFairPlayStub = useFairPlayStub,
        useEncryption = useEncryption,
        logD = ::logD
    )

    private val audioBuffer = java.io.ByteArrayOutputStream()

    /**
     * Connect to the AirPlay speaker.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Connecting to $host:$port")

            // Connection 1: fp-setup × 2 + OPTIONS, then close — matches Apple Music's behaviour.
            rtspSocket = Socket(host, port).apply { soTimeout = WireConstants.Timing.RTSP_SO_TIMEOUT_CONNECTION1_MS }
            localIp = rtspSocket!!.localAddress.hostAddress ?: "0.0.0.0"
            logD("RTSP socket connected, localIp=$localIp")
            rtspInput = rtspSocket!!.getInputStream()

            logD("Connection 1: fp-setup...")
            fairPlayHandshake.performSetup(mode = 0x00, requireValidPhase2 = false)

            logD("Testing OPTIONS...")
            val optionsResult = testOptions()
            logD("OPTIONS result: $optionsResult")

            rtspSocket?.close()
            cSeq.set(0)

            // Connection 2: fp-setup × 2 + ANNOUNCE → SETUP → RECORD
            rtspSocket = Socket(host, port).apply { soTimeout = WireConstants.Timing.RTSP_SO_TIMEOUT_MS }
            rtspInput = rtspSocket!!.getInputStream()
            logD("Reopened RTSP socket for ANNOUNCE")

            logD("Connection 2: fp-setup...")
            fairPlaySetupValid = fairPlayHandshake.performSetup(mode = 0x03, requireValidPhase2 = true)

            // Create UDP sockets for audio/control/timing
            audioSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            timingSocket = DatagramSocket()
            logD("UDP sockets: audio=${audioSocket?.localPort}, ctrl=${controlSocket?.localPort}, time=${timingSocket?.localPort}")

            logD("Starting ANNOUNCE...")
            if (!announce()) {
                logE("ANNOUNCE failed")
                disconnect()
                return@withContext false
            }
            logD("ANNOUNCE succeeded")

            logD("Starting SETUP...")
            if (!setup()) {
                logE("SETUP failed")
                disconnect()
                return@withContext false
            }
            logD("SETUP succeeded - serverPort=$serverPort")

            logD("Starting RECORD...")
            if (!record()) {
                logE("RECORD failed")
                disconnect()
                return@withContext false
            }
            logD("RECORD succeeded - streaming ready!")

            isConnected.set(true)
            callback?.onConnected()
            true
        } catch (e: Exception) {
            logE("Connection failed: ${e.message}")
            callback?.onError("Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    private fun testOptions(): Boolean {
        val challenge = generateAppleChallenge()
        val headers = mapOf(
            "User-Agent" to "iTunes/10.6 (Windows; N)",
            "Apple-Challenge" to challenge
        )
        rtspTransport.send("OPTIONS", "*", headers)
        val response = rtspTransport.readResponse()
        logD("Diagnostic OPTIONS response: code=${response?.first}")
        return response != null
    }

    private fun announce(): Boolean {
        if (useFairPlayStub && !fairPlaySetupValid) {
            logE("FairPlay setup failed; refusing to ANNOUNCE dummy fpaeskey to avoid receiver hang")
            return false
        }
        generateKeys()
        val rsaAesKey = if (useEncryption) encryptRsaAesKey() ?: return false else null
        val sdp = sdpBuilder.build(localIp, rsaAesKey, aesIv)

        val headers = mapOf(
            "Content-Type" to "application/sdp",
            "Content-Length" to sdp.toByteArray(Charsets.ISO_8859_1).size.toString()
        )
        rtspTransport.send("ANNOUNCE", "rtsp://$localIp/$localSessionId", headers, sdp.toByteArray(Charsets.ISO_8859_1))
        return rtspTransport.readResponse()?.first == 200
    }

    private fun setup(): Boolean {
        val localControlPort = controlSocket?.localPort ?: return false
        val localTimingPort = timingSocket?.localPort ?: return false

        startTimingResponder()

        val headers = mapOf(
            "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$localControlPort;timing_port=$localTimingPort"
        )
        rtspTransport.send("SETUP", "rtsp://$localIp/$localSessionId", headers, sessionId = serverSessionId)

        val response = rtspTransport.readResponse()
        if (response != null && response.first == 200) {
            val transportHeader = response.second["Transport"] ?: return false
            parseTransportHeader(transportHeader)

            val sessionVal = response.second["Session"]
            if (sessionVal != null) {
                serverSessionId = sessionVal.split(";")[0].trim()
            }
            return true
        }
        return false
    }

    private fun record(): Boolean {
        val headers = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$rtpSequence;rtptime=${rtpTimestamp and 0xFFFFFFFFL}"
        )
        rtspTransport.send("RECORD", "rtsp://$localIp/$localSessionId", headers, sessionId = serverSessionId)
        val response = rtspTransport.readResponse()
        if (response?.first == 200) {
            isStreaming.set(true)
            startSyncSender()
            startHealthMonitor()
            return true
        }
        return false
    }

    /**
     * Encode and send captured PCM as RTP packets. Buffers until a full
     * 1408-byte frame is available, then drains it in frame-sized chunks.
     */
    suspend fun streamAudio(pcmData: ByteArray) = withContext(Dispatchers.IO) {
        if (!isStreaming.get() || audioSocket == null) return@withContext
        val packetSize = WireConstants.AudioFormat.FRAMES_PER_PACKET *
                WireConstants.AudioFormat.CHANNELS * (WireConstants.AudioFormat.BITS_PER_SAMPLE / 8)

        synchronized(audioBuffer) {
            audioBuffer.write(pcmData)
        }

        val bufferBytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
        if (bufferBytes.size >= packetSize) {
            var offset = 0
            try {
                while (offset + packetSize <= bufferBytes.size) {
                    val chunk = bufferBytes.copyOfRange(offset, offset + packetSize)
                    val beData = RtpPacketBuilder.swapEndianness(chunk)
                    val payloadData = if (useEncryption) {
                        try {
                            encryptAudio(beData)
                        } catch (e: Exception) {
                            // A cipher failure must never fall back to sending
                            // plaintext (silent downgrade): surface it and mark
                            // the stream failed so no further packets go out.
                            LogServer.e(TAG, "RaopClient: audio encryption failed; stopping stream", e)
                            synchronized(audioBuffer) { audioBuffer.reset() }
                            isStreaming.set(false)
                            return@withContext
                        }
                    } else {
                        beData
                    }

                    val rtpPacket = buildRtpPacket(payloadData)
                    val address = InetAddress.getByName(host)
                    val packet = DatagramPacket(rtpPacket, rtpPacket.size, address, serverPort)
                    audioSocket?.send(packet)

                    rtpSequence = (rtpSequence + 1) and 0xFFFF
                    rtpTimestamp += WireConstants.AudioFormat.FRAMES_PER_PACKET
                    offset += packetSize
                }
            } catch (e: java.net.SocketException) {
                // send() on a closed socket (stop/disconnect race) must not
                // crash the capture coroutine. Benign once we're no longer
                // streaming or the socket is gone; real mid-stream failures
                // are surfaced instead of being swallowed.
                synchronized(audioBuffer) { audioBuffer.reset() }
                val benign = !isStreaming.get() || audioSocket == null || audioSocket?.isClosed == true
                if (benign) {
                    LogServer.log("RaopClient: audio socket closed; stopping send (${e.message})")
                } else {
                    LogServer.e(TAG, "RaopClient: audio socket send failed", e)
                    isStreaming.set(false)
                }
                return@withContext
            }
            synchronized(audioBuffer) {
                audioBuffer.reset()
                if (offset < bufferBytes.size) {
                    audioBuffer.write(bufferBytes.copyOfRange(offset, bufferBytes.size))
                }
            }
        }
    }

    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected.get()) return@withContext false
        val dbVolume = if (volume <= 0f) -144f else (volume * 30f - 30f)
        val volumeStr = "volume: $dbVolume\r\n"
        val headers = mapOf(
            "Content-Type" to "text/parameters",
            "Content-Length" to volumeStr.length.toString()
        )
        rtspTransport.send("SET_PARAMETER", "rtsp://$localIp/$localSessionId", headers, volumeStr.toByteArray(Charsets.ISO_8859_1))
        rtspTransport.readResponse()?.first == 200
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        logD("disconnect() called")
        val wasConnected = isConnected.getAndSet(false)
        isStreaming.set(false)
        stopHealthMonitor()
        stopSyncSender()
        stopTimingResponder()

        if (wasConnected) {
            try {
                rtspSocket?.soTimeout = WireConstants.Timing.RTSP_SO_TIMEOUT_TEARDOWN_MS
                rtspTransport.send("TEARDOWN", "rtsp://$localIp/$localSessionId", emptyMap(), sessionId = serverSessionId)
                rtspTransport.readResponse()
            } catch (e: Exception) {}
        }

        closeQuietly(rtspInput)
        closeQuietly(rtspSocket)
        closeQuietly(audioSocket)
        closeQuietly(controlSocket)
        closeQuietly(timingSocket)

        rtspSocket = null
        rtspInput = null
        audioSocket = null
        controlSocket = null
        timingSocket = null
        serverSessionId = null
        // Reset per-stream state so a reconnect starts clean: stale PCM would
        // otherwise be prepended to the first new packet, and a grown-out
        // timestamp would corrupt RTP header fields.
        synchronized(audioBuffer) { audioBuffer.reset() }
        rtpSequence = Random.nextInt(0xFFFF)
        rtpTimestamp = Random.nextLong(0xFFFFFFFFL)
        syncSequence = 0
        serverPort = 0
        serverControlPort = 0
        logD("Teardown complete")
        callback?.onDisconnected()
    }

    private fun generateAppleChallenge(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun generateKeys() {
        aesKey = ByteArray(16); aesIv = ByteArray(16)
        Random.nextBytes(aesKey!!); Random.nextBytes(aesIv!!)
    }

    private fun encryptRsaAesKey(): String? {
        try {
            val modulus = BigInteger(1, Base64.getDecoder().decode(RSA_MODULUS))
            val exponent = BigInteger(1, Base64.getDecoder().decode(RSA_EXPONENT))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return Base64.getEncoder().encodeToString(cipher.doFinal(aesKey))
        } catch (e: Exception) { return null }
    }

    private fun parseTransportHeader(transport: String) {
        transport.split(";").forEach { part ->
            val p = part.trim()
            if (p.startsWith("server_port=")) serverPort = p.substringAfter("=").toIntOrNull() ?: 0
            if (p.startsWith("control_port=")) serverControlPort = p.substringAfter("=").toIntOrNull() ?: 0
        }
    }

    /**
     * AES-128-CBC audio encryption of the (byte-swapped) PCM frame.
     *
     * Wire format unchanged: the leading 16-byte-aligned blocks are CBC
     * encrypted and any trailing partial block is appended verbatim. On any
     * failure (missing key/IV or cipher error) this THROWS instead of
     * returning plaintext — the caller treats it as a fatal stream error.
     */
    private fun encryptAudio(data: ByteArray): ByteArray {
        val key = aesKey ?: throw IllegalStateException("AES key not generated")
        val iv = aesIv ?: throw IllegalStateException("AES IV not generated")
        try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val b = 16; val n = data.size / b; val es = n * b
            if (es == 0) return data
            val enc = cipher.doFinal(data.copyOfRange(0, es))
            return if (data.size > es) enc + data.copyOfRange(es, data.size) else enc
        } catch (e: Exception) {
            throw IllegalStateException("AES audio encryption failed", e)
        }
    }

    // --- RTP builders (internal wrappers; byte-exact, pinned by tests) ---

    internal fun buildRtpPacket(data: ByteArray): ByteArray =
        RtpPacketBuilder.buildRtpPacket(data, rtpSequence, rtpTimestamp, ssrc)

    internal fun buildSyncPacket(rtp: Long, time: Long, lat: Long): ByteArray =
        RtpPacketBuilder.buildSyncPacket(syncSequence, rtp, time, lat)

    // --- Background loops (coroutines on [scope]) ---

    private fun startTimingResponder() {
        val socket = timingSocket ?: return
        timingJob?.cancel()
        timingJob = scope.launch {
            NtpResponder.run(socket)
        }
    }

    private fun stopTimingResponder() {
        timingJob?.cancel()
        timingJob = null
    }

    private fun startSyncSender() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) return
        syncJob?.cancel()
        syncJob = scope.launch {
            val latencyMs = 2500L
            val latencySamples = (latencyMs * WireConstants.AudioFormat.SAMPLE_RATE / 1000)
            val startRtp = rtpTimestamp; val startTime = System.currentTimeMillis()
            try {
                val addr = InetAddress.getByName(host); var lastSync = 0L
                while (isActive && !socket.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastSync >= 300) {
                        val elapsedMs = now - startTime
                        val curPlayRtp = startRtp + (elapsedMs * WireConstants.AudioFormat.SAMPLE_RATE / 1000)
                        val playTime = now + latencyMs
                        val syncPacket = buildSyncPacket(curPlayRtp, playTime, latencySamples)
                        socket.send(DatagramPacket(syncPacket, syncPacket.size, addr, serverControlPort))
                        syncSequence++; lastSync = now
                    }
                    delay(50)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Socket closed during shutdown; benign.
            }
        }
    }

    private fun stopSyncSender() {
        syncJob?.cancel()
        syncJob = null
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = scope.launch {
            try {
                while (isActive && isConnected.get()) {
                    delay(WireConstants.Timing.RAOP_HEALTH_CHECK_INTERVAL_MS)
                    val s = rtspSocket
                    if (s == null || s.isClosed || !s.isConnected) { handleServerDisconnect(); break }
                    try {
                        s.soTimeout = WireConstants.Timing.RTSP_SO_TIMEOUT_HEALTH_PROBE_MS
                        if (s.getInputStream().read() == -1) { handleServerDisconnect(); break }
                    } catch (e: java.net.SocketTimeoutException) {
                    } catch (e: Exception) { handleServerDisconnect(); break }
                    finally { try { s.soTimeout = WireConstants.Timing.RTSP_SO_TIMEOUT_MS } catch (e: Exception) {} }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    private fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
    }

    private fun handleServerDisconnect() {
        if (!isConnected.get()) return
        isConnected.set(false); isStreaming.set(false)
        stopHealthMonitor(); stopSyncSender(); stopTimingResponder()
        closeQuietly(rtspSocket)
        callback?.onDisconnected()
    }

    private fun logD(m: String) { Log.d(TAG, m); LogServer.d(TAG, m) }
    private fun logE(m: String) { Log.e(TAG, m); LogServer.e(TAG, m) }
}
