package com.airplay.streamer.raop

import com.airplay.streamer.util.ByteArrayFormat.toHexDump
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RTSP request/response layer for the RAOP (AirPlay 1) transport.
 *
 * Extracted from `RaopClient` (sendRtspRequestDirect / parseRtspResponse /
 * discardResponseBody / readUntilHeaderEnd) with byte-identical serialization:
 * the request line, CSeq, default headers, optional Session, then caller
 * headers in insertion order. The socket/input accessors and the CSeq counter
 * are injected so the same transport survives connection restarts (the RAOP
 * handshake reconnects between OPTIONS and ANNOUNCE).
 *
 * Nothing here is RAOP-specific: the AirPlay 2 stack's `RtspClient` is a
 * candidate consumer for this request/response layer.
 */
class RtspTransport(
    private val socket: () -> Socket?,
    private val input: () -> InputStream?,
    private val cSeq: AtomicInteger,
    private val userAgent: String,
    private val defaultHeaders: () -> Map<String, String>,
    private val logD: (String) -> Unit,
    private val logE: (String) -> Unit
) {
    /**
     * Send an RTSP request (byte-exact: CSeq is incremented here, headers are
     * written in the order: request line, CSeq, default headers, Session,
     * caller headers).
     */
    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        sessionId: String? = null
    ) {
        val sb = StringBuilder()
        val requestUrl = if (url.startsWith("/") || url == "*") url else url

        sb.append("$method $requestUrl RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        if (!headers.containsKey("User-Agent")) {
            sb.append("User-Agent: $userAgent\r\n")
        }
        defaultHeaders().forEach { (key, value) ->
            if (!headers.containsKey(key)) {
                sb.append("$key: $value\r\n")
            }
        }
        if (sessionId != null) {
            sb.append("Session: $sessionId\r\n")
        }
        headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }
        sb.append("\r\n")

        val headerStr = sb.toString()
        if (method != "RECORD") {
            logD("$method request:\n$headerStr")
            if (body != null && (method == "POST" || method == "ANNOUNCE")) {
                logD("$method body (${body.size} bytes): ${body.toHexDump(maxBytes = 48)}")
            }
        }

        try {
            val out = socket()?.getOutputStream() ?: return
            out.write(headerStr.toByteArray(Charsets.ISO_8859_1))
            if (body != null) {
                out.write(body)
            }
            out.flush()
        } catch (e: Exception) {
            logE("Failed to send RTSP request: ${e.message}")
        }
    }

    /** Read a response's status line + headers, leaving the body unread. */
    fun readResponse(): Pair<Int, Map<String, String>>? {
        val headers = mutableMapOf<String, String>()
        try {
            val input = input() ?: return null
            val headerBytes = readUntilHeaderEnd(input)
            if (headerBytes.isEmpty()) {
                logE("parseRtspResponse: statusLine is null (connection closed?)")
                return null
            }
            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val statusLine = lines.firstOrNull { it.isNotEmpty() } ?: return null
            logD("parseRtspResponse: statusLine = $statusLine")
            val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return null

            for (line in lines.drop(1)) {
                if (line.isEmpty()) break
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }
            return statusCode to headers
        } catch (e: Exception) {
            logE("parseRtspResponse error: ${e.message}")
            return null
        }
    }

    /** Read and discard [length] response-body bytes (logging a hex dump). */
    fun readBody(length: Int) {
        if (length <= 0) return
        try {
            val input = input() ?: return
            val body = ByteArray(length)
            var remaining = length
            var offset = 0
            while (remaining > 0) {
                val n = input.read(body, offset, remaining)
                if (n < 0) break
                offset += n
                remaining -= n
            }
            logD("RTSP response body (${length - remaining}/$length bytes): ${body.toHexDump(maxBytes = 48)}")
        } catch (e: Exception) {
            logE("Error discarding response body: ${e.message}")
        }
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            last4 = ((last4 shl 8) or b) and 0xFFFFFFFF.toInt()
            if (last4 == 0x0D0A0D0A) break
        }
        return out.toByteArray()
    }
}
