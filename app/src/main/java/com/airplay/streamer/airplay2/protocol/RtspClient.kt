package com.airplay.streamer.airplay2.protocol

import com.airplay.streamer.airplay2.crypto.*
import com.airplay.streamer.airplay2.util.Ap2Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * RTSP Client for AirPlay 2 communication
 * 
 * Handles encrypted RTSP requests/responses after transient pairing.
 */
class RtspClient(
    private val host: String,
    private val port: Int = 7000
) {
    companion object {
        internal val AIRPLAY_HEADERS = mapOf(
            "User-Agent" to "AirPlay/320.20",
            "Connection" to "keep-alive",
            "X-Apple-HKP" to "4"
        )

        /** Default interval between /feedback keepalive posts (~30s). */
        const val DEFAULT_FEEDBACK_INTERVAL_MS = 30_000L
    }
    
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    var cseq = 0
        private set
    
    // Last protocol error (null when the last request/response was healthy).
    var lastError: String? = null
        private set
    
    // Encryption state
    private val hapSession = HapSession()
    
    // SRP state
    private var srpClient: Srp6aClient? = null
    
    // Feedback keepalive state
    private val feedbackActive = AtomicBoolean(false)
    
    // Session state. sessionUuid is set by AirPlay2Client after the event
    // SETUP generates it, so TEARDOWN targets the real session URI.
    var sessionUuid: String? = null
        internal set

    /**
     * Serializes the full request/response cycle (cseq++ + write + read).
     *
     * Callers that can overlap - the /feedback keepalive loop, the health
     * monitor's connectionAlive(), and setVolume - would otherwise interleave
     * requests on the socket and steal each other's responses (the reader
     * cannot tell which response belongs to which writer).
     */
    private val requestLock = Any()
    
    /**
     * Connect to the AirPlay receiver
     */
    fun connect() {
        socket = Socket(host, port).apply {
            soTimeout = 10000
            keepAlive = true
        }
        input = socket!!.getInputStream()
        output = socket!!.getOutputStream()
    }
    
    /**
     * Disconnect from the receiver
     */
    fun disconnect() {
        socket?.close()
        socket = null
        input = null
        output = null
    }
    
    /**
     * Get local IP address of the connected socket
     */
    fun getLocalAddress(): String? = socket?.localAddress?.hostAddress
    
    /**
     * Send RTSP request and get response.
     *
     * The cseq++ / write / readResponse critical section runs under
     * [requestLock] so concurrent callers cannot interleave requests or
     * consume each other's responses. Blocking socket I/O is serialized by
     * design; callers run on Dispatchers.IO (or a UI-triggered volume change,
     * which already performed blocking I/O here).
     */
    fun sendRtsp(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        contentType: String = "application/octet-stream",
        extraHeaders: Map<String, String> = emptyMap()
    ): RtspResponse {
        return synchronized(requestLock) {
            cseq++

            val request = buildRequest(
                method = method,
                path = path,
                host = "$host:$port",
                cseq = cseq,
                body = body,
                contentType = contentType,
                extraHeaders = extraHeaders
            )

            // Encrypt if HAP session is enabled
            if (hapSession.isEnabled) {
                Ap2Log.log("RtspClient: Encrypting request (${request.size} bytes)")
                val encrypted = hapSession.encrypt(request)
                Ap2Log.log("RtspClient: Sending encrypted request (${encrypted.size} bytes)")
                output!!.write(encrypted)
            } else {
                Ap2Log.log("RtspClient: Sending plaintext request (${request.size} bytes)")
                output!!.write(request)
            }
            output!!.flush()

            val response = try {
                readResponse()
            } catch (e: RtspException) {
                lastError = e.message
                throw e
            }

            // Non-2xx: record + log a typed failure; callers decide how to surface it.
            if (response.statusCode !in 200..299) {
                lastError = "$method $path failed: HTTP ${response.statusCode}"
                Ap2Log.e("RtspClient", lastError!!)
            } else {
                lastError = null
            }

            response
        }
    }
    
    /**
     * Send a single /feedback keepalive post.
     * 
     * Returns true on 2xx; returns false (and sets [lastError]) instead of
     * throwing so a failed keepalive can never kill the stream.
     */
    fun sendFeedbackOnce(volume: Float = 0f): Boolean {
        return try {
            val body = "volume: $volume".toByteArray()
            val response = sendRtsp(
                "POST",
                "/feedback",
                body = body,
                contentType = "text/parameters"
            )
            val ok = response.statusCode in 200..299
            if (!ok) {
                lastError = "POST /feedback failed: HTTP ${response.statusCode}"
                Ap2Log.e("RtspClient", lastError!!)
            }
            ok
        } catch (e: Exception) {
            lastError = "POST /feedback failed: ${e.message}"
            Ap2Log.e("RtspClient", lastError!!, e)
            false
        }
    }
    
    /**
     * Keepalive loop: POST /feedback every [intervalMs] while streaming.
     * 
     * A failed keepalive is logged but never stops the loop or the stream.
     * Stops when [stopFeedback] is called or the calling coroutine is cancelled.
     */
    suspend fun sendFeedback(intervalMs: Long = DEFAULT_FEEDBACK_INTERVAL_MS, volume: Float = 0f) {
        feedbackActive.set(true)
        while (feedbackActive.get() && coroutineContext.isActive) {
            if (!sendFeedbackOnce(volume)) {
                Ap2Log.log("RtspClient: Feedback keepalive failed; continuing stream")
            }
            try {
                delay(intervalMs)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
    
    /**
     * Stop the [sendFeedback] keepalive loop (idempotent).
     */
    fun stopFeedback() {
        feedbackActive.set(false)
    }
    
    /**
     * Send TEARDOWN for the current session and close the socket.
     * 
     * Idempotent: safe to call multiple times (a second call is a no-op).
     */
    fun teardown() {
        if (socket == null) return
        try {
            val uri = sessionUuid?.let { "rtsp://$host/$it" } ?: "/"
            sendRtsp("TEARDOWN", uri)
        } catch (e: Exception) {
            Ap2Log.e("RtspClient", "TEARDOWN failed", e)
        } finally {
            disconnect()
        }
    }
    
    /**
     * Read RTSP response, handling HAP encryption if enabled
     */
    private fun readResponse(): RtspResponse {
        val buffer = ByteArray(4096)
        var responseBuffer = ByteArray(0) // Accumulates PLAINTEXT (decrypted or raw)
        
        Ap2Log.log("RtspClient: Waiting for response...")
        
        while (true) {
            val bytesRead = input!!.read(buffer)
            if (bytesRead < 0) {
                Ap2Log.log("RtspClient: Connection closed by server (EOF)")
                throw RtspException("Connection closed by server")
            }
            
            Ap2Log.log("RtspClient: Read $bytesRead bytes from socket")
            
            val chunk = buffer.copyOf(bytesRead)
            
            // Decrypt if HAP session is enabled
            val data = if (hapSession.isEnabled) {
                val decrypted = hapSession.decrypt(chunk)
                Ap2Log.log("RtspClient: Decrypted ${chunk.size} bytes -> ${decrypted.size} bytes")
                decrypted
            } else {
                chunk
            }
            
            responseBuffer += data
            
            // parseResponse returns null until the full response is buffered
            parseResponse(responseBuffer)?.let {
                Ap2Log.log("RtspClient: Full response received (${it.body.size} body bytes)")
                return it
            }
        }
    }
    
    /**
     * Enable HAP session encryption using derived Control keys
     */
    fun enableEncryption(sessionKey: ByteArray) {
        val outputKey = Hkdf.Control.deriveOutputKey(sessionKey)
        val inputKey = Hkdf.Control.deriveInputKey(sessionKey)
        
        hapSession.enable(outputKey, inputKey)
    }
}

/**
 * Typed transport/protocol failure raised by [RtspClient] on connection
 * problems (EOF, timeouts). Non-2xx HTTP/RTSP responses never throw: they are
 * returned as [RtspResponse] with [RtspClient.lastError] set.
 */
class RtspException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * RTSP Response data class
 */
data class RtspResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtspResponse
        return statusCode == other.statusCode && headers == other.headers && body.contentEquals(other.body)
    }
    
    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

private val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

/**
 * Build an RTSP request with a deterministic header order (byte-exact).
 * 
 * Order: request line, CSeq, Host, Content-Type, Content-Length,
 * User-Agent, Connection, X-Apple-HKP, then [extraHeaders].
 */
internal fun buildRequest(
    method: String,
    path: String,
    host: String,
    cseq: Int,
    body: ByteArray = ByteArray(0),
    contentType: String = "application/octet-stream",
    extraHeaders: Map<String, String> = emptyMap()
): ByteArray {
    val headers = linkedMapOf(
        "CSeq" to cseq.toString(),
        "Host" to host,
        "Content-Type" to contentType,
        "Content-Length" to body.size.toString()
    )
    headers.putAll(RtspClient.AIRPLAY_HEADERS)
    headers.putAll(extraHeaders)
    
    val requestLine = "$method $path RTSP/1.0\r\n"
    val headerLines = headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    return (requestLine + headerLines + "\r\n").toByteArray(Charsets.US_ASCII) + body
}

/**
 * Parse an RTSP response from raw bytes (status line, headers,
 * Content-Length-delimited body).
 * 
 * Returns null while the input is partial or malformed; never throws.
 * A response without a Content-Length header parses with an empty body.
 */
internal fun parseResponse(data: ByteArray): RtspResponse? {
    if (data.isEmpty()) return null
    
    val headerEnd = indexOfBytes(data, CRLF_CRLF)
    if (headerEnd < 0) return null
    
    val headerText = data.copyOfRange(0, headerEnd).toString(Charsets.US_ASCII)
    val headerLines = headerText.split("\r\n")
    
    // Parse status code from first line
    val statusParts = headerLines.firstOrNull()?.split(" ") ?: return null
    val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: return null
    
    // Parse headers
    val headers = mutableMapOf<String, String>()
    var contentLength = 0
    for (line in headerLines.drop(1)) {
        if (":" in line) {
            val (key, value) = line.split(":", limit = 2)
            val trimmedKey = key.trim()
            headers[trimmedKey] = value.trim()
            if (trimmedKey.lowercase() == "content-length") {
                contentLength = value.trim().toIntOrNull() ?: return null
            }
        }
    }
    
    // Extract body (Content-Length-delimited)
    val bodyStart = headerEnd + 4
    if (data.size < bodyStart + contentLength) return null // Partial body
    val body = if (contentLength > 0) {
        data.copyOfRange(bodyStart, bodyStart + contentLength)
    } else {
        ByteArray(0)
    }
    
    return RtspResponse(statusCode, headers, body)
}

/**
 * Find the first occurrence of [needle] in [haystack], or -1.
 */
private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}
