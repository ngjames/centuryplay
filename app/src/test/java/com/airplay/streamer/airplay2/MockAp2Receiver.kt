package com.airplay.streamer.airplay2

import com.airplay.streamer.airplay2.crypto.Chacha20Cipher
import com.airplay.streamer.airplay2.crypto.Hkdf
import com.airplay.streamer.airplay2.crypto.Srp6aClient
import com.airplay.streamer.airplay2.crypto.Tlv8
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal in-JVM AirPlay 2 receiver mock for the integration test.
 *
 * Plain TCP server on 127.0.0.1:0, strictly one request at a time (the client
 * is request/response-blocking). Before pairing it reads plaintext RTSP
 * requests; after answering pair-setup M3 it switches to HAP framing
 * ([2B LE length][ciphertext][16B tag]) using the control keys derived from
 * the SRP session key K (HKDF-SHA512 "Control-*" infos, same as the client).
 *
 * It answers:
 *  - POST /pair-pin-start -> 200
 *  - POST /pair-setup M1/M3 -> server side of SRP-6a (same SHA-512 math and
 *    RFC 5054 3072-bit group as [Srp6aClient], so the M2 it returns verifies
 *    on the client), with a fixed salt for reproducibility
 *  - SETUP (event) -> plist {eventPort}; SETUP (audio) -> plist {streams}
 *  - RECORD / FLUSH / SET_PARAMETER / POST /feedback / TEARDOWN -> 200
 *
 * The outbound SETUP bodies, FLUSH headers and SET_PARAMETER body are
 * captured so the test can assert them byte-exactly.
 */
class MockAp2Receiver {
    private companion object {
        const val IDENTITY = "Pair-Setup"
        const val PIN = "3939"
        const val HAP_TAG_LENGTH = 16

        /** Fixed 16-byte salt: transient pairing accepts any server salt. */
        val SALT = ByteArray(16) { (it + 1).toByte() }

        val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        fun padToN(value: BigInteger): ByteArray {
            val natural = value.toByteArray().let {
                if (it[0] == 0.toByte() && it.size > 1) it.copyOfRange(1, it.size) else it
            }
            return if (natural.size < Srp6aClient.N_BYTES) {
                ByteArray(Srp6aClient.N_BYTES - natural.size) + natural
            } else {
                natural
            }
        }

        fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) {
                    if (haystack[i + j] != needle[j]) continue@outer
                }
                return i
            }
            return -1
        }
    }

    private val random = SecureRandom()

    // Captured outbound requests (asserted by the test).
    @Volatile private var eventSetupBytes: ByteArray? = null
    @Volatile private var audioSetupBytes: ByteArray? = null
    @Volatile private var flushHeaderMap: Map<String, String>? = null
    @Volatile private var setParameterBodyBytes: ByteArray? = null

    val methodSequence = CopyOnWriteArrayList<String>()

    // SRP-6a server state.
    private var b: BigInteger? = null
    private var B: BigInteger? = null
    private var v: BigInteger? = null

    // HAP encryption state (enabled after M3; null = plaintext).
    private var cipher: Chacha20Cipher? = null

    // Session key from M3, applied to the transport only AFTER the (plaintext)
    // M4 response is written - the client enables its session after verifying
    // M4, so M4 itself must not be HAP-encrypted.
    private var pendingSessionKey: ByteArray? = null

    private var serverSocket: ServerSocket? = null
    private var accepted: Socket? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    fun eventSetupPlist(): ByteArray? = eventSetupBytes
    fun audioSetupPlist(): ByteArray? = audioSetupBytes
    fun flushHeaders(): Map<String, String>? = flushHeaderMap
    fun setParameterBody(): ByteArray? = setParameterBodyBytes

    fun start() {
        serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        Thread { serve() }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { accepted?.close() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        accepted = null
    }

    private fun serve() {
        val ss = serverSocket ?: return
        val socket = try {
            ss.accept()
        } catch (_: Exception) {
            return
        }
        accepted = socket
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            var raw = ByteArray(0)
            var plain = ByteArray(0)
            while (true) {
                val parsed = parseRequest(plain)
                if (parsed.request != null) {
                    plain = plain.copyOfRange(parsed.consumed, plain.size)
                    handle(parsed.request, output)
                    continue
                }
                val buf = ByteArray(4096)
                val n = try {
                    input.read(buf)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    -1
                }
                if (n < 0) return
                raw += buf.copyOf(n)

                val c = cipher
                if (c != null) {
                    // HAP-framed: decrypt each complete [2B LE len][cipher+tag] frame
                    // (one decrypt call per frame keeps the nonce counters in sync).
                    while (raw.size >= 2) {
                        val len = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
                        val total = 2 + len + HAP_TAG_LENGTH
                        if (raw.size < total) break
                        val frame = raw.copyOfRange(0, total)
                        raw = raw.copyOfRange(total, raw.size)
                        plain += c.decrypt(frame.copyOfRange(2, total), aad = frame.copyOfRange(0, 2))
                    }
                } else {
                    plain += raw
                    raw = ByteArray(0)
                }
            }
        } catch (_: Exception) {
            // Client closed or mock stopped; terminate the serve loop.
        }
    }

    private fun handle(req: Request, output: OutputStream) {
        methodSequence.add("${req.method} ${req.path}")
        val (status, contentType, body) = when {
            req.method == "POST" && req.path == "/pair-pin-start" ->
                Triple(200, "application/x-apple-binary-plist", ByteArray(0))
            req.method == "POST" && req.path == "/pair-setup" -> handlePairSetup(req.body)
            req.method == "SETUP" -> handleSetup(req)
            req.method == "RECORD" -> Triple(200, "application/octet-stream", ByteArray(0))
            req.method == "FLUSH" -> {
                flushHeaderMap = req.headers
                Triple(200, "application/octet-stream", ByteArray(0))
            }
            req.method == "SET_PARAMETER" -> {
                setParameterBodyBytes = req.body
                Triple(200, "application/octet-stream", ByteArray(0))
            }
            req.method == "TEARDOWN" -> Triple(200, "application/octet-stream", ByteArray(0))
            else -> Triple(200, "application/octet-stream", ByteArray(0))
        }

        val response = buildResponse(status, req.headers["CSeq"] ?: "0", contentType, body)
        val c = cipher
        if (c != null) {
            val lenBytes = byteArrayOf(
                (response.size and 0xFF).toByte(),
                ((response.size shr 8) and 0xFF).toByte()
            )
            output.write(lenBytes + c.encrypt(response, aad = lenBytes))
        } else {
            output.write(response)
        }
        output.flush()

        // Enable HAP encryption for the NEXT request/response pair only after
        // the plaintext M4 response is on the wire (client enables its session
        // after verifying M4). The mock encrypts requests' decrypt direction
        // with the client's Control-Write key and encrypts its own responses
        // with the client's Control-Read key.
        pendingSessionKey?.let { key ->
            pendingSessionKey = null
            cipher = Chacha20Cipher(
                encryptKey = Hkdf.Control.deriveInputKey(key),
                decryptKey = Hkdf.Control.deriveOutputKey(key)
            )
        }
    }

    private fun handlePairSetup(body: ByteArray): Triple<Int, String, ByteArray> {
        val tlv = Tlv8.decode(body)
        val seq = tlv[Tlv8.Type.SEQ_NO]?.get(0)?.toInt() ?: 0
        val payload = when (seq) {
            1 -> handleM1()
            3 -> handleM3(body)
            else -> Tlv8.encode(Tlv8.Type.ERROR to byteArrayOf(0x06))
        }
        return Triple(200, "application/pairing+tlv8", payload)
    }

    /** M1 -> M2: server salt + server public key B = (k*v + g^b) mod N. */
    private fun handleM1(): ByteArray {
        val bb = BigInteger(256, random)
        b = bb
        val x = Srp6aClient.computeX(SALT, IDENTITY.toByteArray(), PIN.toByteArray())
        v = Srp6aClient.g.modPow(x, Srp6aClient.N)
        val k = Srp6aClient.calculateK()
        B = (k.multiply(v!!).add(Srp6aClient.g.modPow(bb, Srp6aClient.N))).mod(Srp6aClient.N)
        return Tlv8.encode(
            Tlv8.Type.SEQ_NO to byteArrayOf(2),
            Tlv8.Type.SALT to SALT,
            Tlv8.Type.PUBLIC_KEY to padToN(B!!)
        )
    }

    /** M3 -> M4: S = (A * v^u)^b mod N, K = H(S), M2 = H(A|M1|K); then enable HAP. */
    private fun handleM3(body: ByteArray): ByteArray {
        val tlv = Tlv8.decode(body)
        val aBytes = tlv[Tlv8.Type.PUBLIC_KEY] ?: throw IllegalStateException("M3 missing PUBLIC_KEY")
        val m1 = tlv[Tlv8.Type.PROOF] ?: throw IllegalStateException("M3 missing PROOF")
        val A = BigInteger(1, aBytes)
        val u = Srp6aClient.computeU(A, B!!)
        val S = A.multiply(v!!.modPow(u, Srp6aClient.N)).mod(Srp6aClient.N).modPow(b!!, Srp6aClient.N)
        val K = Srp6aClient.computeK(S)
        val m2 = Srp6aClient.computeM2(A, m1, K)

        // Applied in handle() AFTER the plaintext M4 response is written.
        pendingSessionKey = K
        return Tlv8.encode(
            Tlv8.Type.SEQ_NO to byteArrayOf(4),
            Tlv8.Type.PROOF to m2
        )
    }

    private fun handleSetup(req: Request): Triple<Int, String, ByteArray> {
        val plist = PropertyListParser.parse(ByteArrayInputStream(req.body)) as NSDictionary
        val isAudio = plist["streams"] != null
        if (isAudio) {
            audioSetupBytes = req.body
            val response = NSDictionary().apply {
                put("streams", arrayOf(
                    NSDictionary().apply {
                        put("controlPort", 54321)
                        put("dataPort", 54322)
                    }
                ))
            }
            return Triple(200, "application/x-apple-binary-plist", plistBytes(response))
        }
        eventSetupBytes = req.body
        val response = NSDictionary().apply { put("eventPort", 55555) }
        return Triple(200, "application/x-apple-binary-plist", plistBytes(response))
    }

    private fun plistBytes(dict: NSDictionary): ByteArray {
        val baos = ByteArrayOutputStream()
        BinaryPropertyListWriter.write(baos, dict)
        return baos.toByteArray()
    }

    private fun buildResponse(status: Int, cseq: String, contentType: String, body: ByteArray): ByteArray {
        val head = "RTSP/1.0 $status OK\r\n" +
            "CSeq: $cseq\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "\r\n"
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private class Parsed(val request: Request?, val consumed: Int)

    private fun parseRequest(buf: ByteArray): Parsed {
        val headerEnd = indexOfBytes(buf, CRLF_CRLF)
        if (headerEnd < 0) return Parsed(null, 0)
        val headerText = String(buf, 0, headerEnd, Charsets.US_ASCII)
        val lines = headerText.split("\r\n")
        val first = lines.firstOrNull()?.split(" ") ?: return Parsed(null, 0)
        if (first.size < 2) return Parsed(null, 0)
        var contentLength = 0
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (":" in line) {
                val (key, value) = line.split(":", limit = 2)
                headers[key.trim()] = value.trim()
                if (key.trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = value.trim().toIntOrNull() ?: return Parsed(null, 0)
                }
            }
        }
        val bodyStart = headerEnd + 4
        if (buf.size < bodyStart + contentLength) return Parsed(null, 0)
        return Parsed(
            Request(first[0], first[1], headers, buf.copyOfRange(bodyStart, bodyStart + contentLength)),
            bodyStart + contentLength
        )
    }
}
