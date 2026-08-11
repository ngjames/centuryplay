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
}
