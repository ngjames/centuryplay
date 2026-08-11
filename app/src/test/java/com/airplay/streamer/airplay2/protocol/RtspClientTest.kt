package com.airplay.streamer.airplay2.protocol

import com.airplay.streamer.airplay2.crypto.HapSession
import com.airplay.streamer.airplay2.crypto.Hkdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Byte-exact framing tests for [RtspClient]'s pure builders/parsers.
 * No sockets involved: request serialization and response parsing
 * (plaintext and HAP-framed) are exercised end to end.
 */
class RtspClientTest {

    // --- Request serialization ---

    @Test
    fun buildRequest_feedback_exactBytes() {
        val request = buildRequest(
            method = "POST",
            path = "/feedback",
            host = "192.168.1.5:7000",
            cseq = 1,
            body = "volume: 0.0".toByteArray(),
            contentType = "text/parameters"
        )
        val expected = "POST /feedback RTSP/1.0\r\n" +
            "CSeq: 1\r\n" +
            "Host: 192.168.1.5:7000\r\n" +
            "Content-Type: text/parameters\r\n" +
            "Content-Length: 11\r\n" +
            "User-Agent: AirPlay/320.20\r\n" +
            "Connection: keep-alive\r\n" +
            "X-Apple-HKP: 4\r\n" +
            "\r\n" +
            "volume: 0.0"
        assertArrayEquals(expected.toByteArray(Charsets.US_ASCII), request)
    }

    @Test
    fun buildRequest_contentLength_tracksBodySize() {
        val body = "volume: 0.5\r\n".toByteArray()
        val request = buildRequest(
            method = "SET_PARAMETER",
            path = "rtsp://192.168.1.5/AABBCCDD",
            host = "192.168.1.5:7000",
            cseq = 2,
            body = body,
            contentType = "text/parameters"
        )
        val text = String(request, Charsets.US_ASCII)
        assertTrue(text.contains("Content-Length: ${body.size}\r\n"))
        assertTrue(text.startsWith("SET_PARAMETER rtsp://192.168.1.5/AABBCCDD RTSP/1.0\r\n"))
    }

    @Test
    fun buildRequest_teardown_requestLine() {
        val request = buildRequest(
            method = "TEARDOWN",
            path = "rtsp://192.168.1.5/AABBCCDD-EEFF",
            host = "192.168.1.5:7000",
            cseq = 3
        )
        val text = String(request, Charsets.US_ASCII)
        assertTrue(text.startsWith("TEARDOWN rtsp://192.168.1.5/AABBCCDD-EEFF RTSP/1.0\r\n"))
        assertTrue(text.contains("CSeq: 3\r\n"))
        assertTrue(text.contains("X-Apple-HKP: 4\r\n"))
        assertTrue(text.contains("Content-Length: 0\r\n"))
    }

    @Test
    fun buildRequest_extraHeaders_afterAppleHeaders() {
        val request = buildRequest(
            method = "FLUSH",
            path = "rtsp://192.168.1.5/AABBCCDD",
            host = "192.168.1.5:7000",
            cseq = 4,
            extraHeaders = mapOf("Range" to "npt=0-")
        )
        val text = String(request, Charsets.US_ASCII)
        assertTrue(text.contains("X-Apple-HKP: 4\r\n"))
        assertTrue(text.contains("Range: npt=0-\r\n"))
        assertTrue(text.indexOf("X-Apple-HKP") < text.indexOf("Range"))
    }

    // --- Response parsing ---

    @Test
    fun parseResponse_fullResponseWithBody() {
        val raw = "RTSP/1.0 200 OK\r\n" +
            "Content-Type: application/x-apple-binary-plist\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "hello"
        val parsed = parseResponse(raw.toByteArray(Charsets.US_ASCII))
        assertNotNull(parsed)
        assertEquals(200, parsed!!.statusCode)
        assertEquals("application/x-apple-binary-plist", parsed.headers["Content-Type"])
        assertEquals("5", parsed.headers["Content-Length"])
        assertEquals("hello", String(parsed.body))
    }

    @Test
    fun parseResponse_noContentLength_emptyBody() {
        val raw = "RTSP/1.0 200 OK\r\nCSeq: 4\r\n\r\n"
        val parsed = parseResponse(raw.toByteArray(Charsets.US_ASCII))
        assertNotNull(parsed)
        assertEquals(200, parsed!!.statusCode)
        assertEquals(0, parsed.body.size)
        assertEquals("4", parsed.headers["CSeq"])
    }

    @Test
    fun parseResponse_partialBody_returnsNull() {
        val raw = "RTSP/1.0 200 OK\r\nContent-Length: 10\r\n\r\nabc"
        assertNull(parseResponse(raw.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun parseResponse_malformed_returnsNull() {
        assertNull(parseResponse(ByteArray(0)))
        assertNull(parseResponse("RTSP/1.0 200 OK no terminator".toByteArray(Charsets.US_ASCII)))
        assertNull(parseResponse("garbage line\r\n\r\n".toByteArray(Charsets.US_ASCII)))
        assertNull(parseResponse(
            "RTSP/1.0 200 OK\r\nContent-Length: nope\r\n\r\n".toByteArray(Charsets.US_ASCII)
        ))
    }

    @Test
    fun parseResponse_hapFramed_decryptThenParse() {
        val sessionKey = ByteArray(32) { it.toByte() }
        val outKey = Hkdf.Control.deriveOutputKey(sessionKey)
        val inKey = Hkdf.Control.deriveInputKey(sessionKey)
        // Mirror sessions, like HAP: the receiver encrypts responses with the
        // app's read key; the app decrypts them with the same read key.
        val app = HapSession().apply { enable(outKey, inKey) }
        val receiver = HapSession().apply { enable(inKey, outKey) }

        val plaintext = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok"
        val framed = receiver.encrypt(plaintext.toByteArray(Charsets.US_ASCII))

        // [2-byte LE length][ciphertext][16-byte tag], single frame (< 1024)
        assertEquals(2 + plaintext.length + HapSession.AUTH_TAG_LENGTH, framed.size)

        val decrypted = app.decrypt(framed)
        val parsed = parseResponse(decrypted)
        assertNotNull(parsed)
        assertEquals(200, parsed!!.statusCode)
        assertEquals("ok", String(parsed.body))
    }

    @Test
    fun parseResponse_hapFramed_splitReads() {
        // Feed the HAP-framed bytes across multiple decrypt calls (partial reads).
        val sessionKey = ByteArray(32) { 0x42.toByte() }
        val outKey = Hkdf.Control.deriveOutputKey(sessionKey)
        val inKey = Hkdf.Control.deriveInputKey(sessionKey)
        val app = HapSession().apply { enable(outKey, inKey) }
        val receiver = HapSession().apply { enable(inKey, outKey) }

        val plaintext = "RTSP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok"
        val framed = receiver.encrypt(plaintext.toByteArray(Charsets.US_ASCII))

        val decrypted = app.decrypt(framed.copyOfRange(0, 5)) +
            app.decrypt(framed.copyOfRange(5, framed.size))
        val parsed = parseResponse(decrypted)
        assertNotNull(parsed)
        assertEquals(200, parsed!!.statusCode)
        assertEquals("ok", String(parsed.body))
    }

    @Test
    fun buildRequest_hapEncryptedRequest_roundtrips() {
        // The production send path encrypts the serialized request; verify the
        // ciphertext decrypts back to the exact request bytes.
        val sessionKey = ByteArray(32) { 0x24.toByte() }
        val outKey = Hkdf.Control.deriveOutputKey(sessionKey)
        val inKey = Hkdf.Control.deriveInputKey(sessionKey)
        val app = HapSession().apply { enable(outKey, inKey) }
        val receiver = HapSession().apply { enable(inKey, outKey) }

        val request = buildRequest(
            method = "POST",
            path = "/feedback",
            host = "192.168.1.5:7000",
            cseq = 5,
            body = "volume: 0.0".toByteArray(),
            contentType = "text/parameters"
        )
        val encrypted = app.encrypt(request)
        assertFalse(encrypted.contentEquals(request))
        assertArrayEquals(request, receiver.decrypt(encrypted))
    }

    // --- Concurrent request/response serialization ---

    @Test
    fun `concurrent sendRtsp calls are serialized - no response stealing and unique cseq`() {
        // Regression test for the F2 race: /feedback keepalive, health-monitor
        // probes and setVolume can overlap. Without serialization the socket
        // writes/reads interleave and callers steal each other's responses.
        val server = CseqEchoServer()
        server.start()
        val client = RtspClient("127.0.0.1", server.port)
        try {
            client.connect()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val threads = (1..20).map { i ->
                Thread {
                    try {
                        val token = "token-$i"
                        val resp = client.sendRtsp(
                            "POST", "/feedback",
                            body = token.toByteArray(),
                            contentType = "text/parameters"
                        )
                        assertEquals(200, resp.statusCode)
                        // The echo server repeats the request body: a stolen
                        // response would carry another caller's token.
                        assertEquals(token, String(resp.body))
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(15_000) }
            assertTrue("all concurrent callers must finish", threads.all { !it.isAlive })
            assertTrue("no caller saw an exception: ${failures.toList()}", failures.isEmpty())
            // cseq++ is serialized: every value 1..N used exactly once.
            assertEquals(20, server.seenCseqs.size)
            assertEquals((1..20).toSet(), server.seenCseqs.toSet())
        } finally {
            client.disconnect()
            server.stop()
        }
    }

    /**
     * Plaintext RTSP echo server: answers one request at a time, echoing the
     * request body and the request's CSeq. The client serializes requests, so
     * a single accept + sequential serve loop is sufficient.
     */
    private class CseqEchoServer {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        val seenCseqs = CopyOnWriteArrayList<Int>()

        fun start() {
            Thread {
                try {
                    val socket = serverSocket.accept()
                    socket.soTimeout = 15_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val buf = ByteArray(4096)
                    var buffered = ByteArray(0)
                    while (true) {
                        val headerEnd = indexOf(CRLF_CRLF, buffered)
                        if (headerEnd >= 0) {
                            val headerText = String(buffered, 0, headerEnd, Charsets.US_ASCII)
                            var cseq = 0
                            var contentLength = 0
                            for (line in headerText.split("\r\n")) {
                                when {
                                    line.startsWith("CSeq:") ->
                                        cseq = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                    line.startsWith("Content-Length:") ->
                                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                            }
                            val total = headerEnd + 4 + contentLength
                            if (buffered.size >= total) {
                                seenCseqs.add(cseq)
                                val body = buffered.copyOfRange(headerEnd + 4, total)
                                buffered = buffered.copyOfRange(total, buffered.size)
                                val head = "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n"
                                output.write(head.toByteArray(Charsets.US_ASCII))
                                output.write(body)
                                output.flush()
                                continue
                            }
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        buffered += buf.copyOf(n)
                    }
                } catch (_: Exception) {
                    // Client closed or mock stopped; terminate the serve loop.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            runCatching { serverSocket.close() }
        }

        private fun indexOf(needle: ByteArray, haystack: ByteArray): Int {
            if (haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private companion object {
            val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        }
    }
}
