package com.airplay.streamer.airplay2.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [HapSession] HAP framing rules:
 *
 *  - frame = [2-byte little-endian length][ciphertext][16-byte tag]
 *  - plaintext chunk <= 1024 bytes per frame
 *  - AAD = the 2-byte length prefix
 *  - nonce = 4 zero bytes + 8-byte little-endian counter (per direction)
 *  - separate write (output) and read (input) keys, each with its own counter
 *
 * Expected frames are reconstructed independently here by invoking
 * BouncyCastle ChaCha20Poly1305 directly with the exact nonce/AAD spelled
 * out, so the production framing (byte order, nonce layout, AAD choice,
 * counter increments) is verified byte-for-byte rather than just roundtripped.
 */
class HapSessionTest {

    private val writeKey = bytes(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
        0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x10,
        0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x87, 0x98,
        0xa9, 0xba, 0xcb, 0xdc, 0xed, 0xfe, 0x0f, 0x1e)
    private val readKey = bytes(0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11,
        0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,
        0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x21,
        0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29)
    private val otherKey = bytes(0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31,
        0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
        0x3a, 0x3b, 0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41,
        0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49)

    @Test
    fun smallMessageFrameStructure() {
        val session = HapSession()
        session.enable(writeKey, writeKey)

        val msg = "hello".toByteArray(Charsets.US_ASCII)
        val frame = session.encrypt(msg)

        // [2-byte LE length][ciphertext (5)][16-byte tag] = 23 bytes total
        assertEquals(2 + msg.size + HapSession.AUTH_TAG_LENGTH, frame.size)
        // bytes 0-1: little-endian length of the plaintext chunk
        assertEquals(0x05, frame[0].toInt() and 0xFF)
        assertEquals(0x00, frame[1].toInt() and 0xFF)
    }

    @Test
    fun firstFrameMatchesIndependentReference() {
        val session = HapSession()
        session.enable(writeKey, writeKey)

        val msg = "Ladies and Gentlemen of the class of '99".toByteArray(Charsets.US_ASCII)
        val frame = session.encrypt(msg)

        // Independent reconstruction: nonce = 4 zero bytes + 8-byte LE counter 0,
        // AAD = the 2-byte LE length prefix.
        val expected = referenceFrame(writeKey, counter = 0L, plaintext = msg)
        assertArrayEquals("frame must match independent nonce/AAD construction", expected, frame)
    }

    @Test
    fun subsequentFramesUseIncreasingCounter() {
        val session = HapSession()
        session.enable(writeKey, writeKey)

        val msg = "one".toByteArray(Charsets.US_ASCII)
        val first = session.encrypt(msg)
        val second = session.encrypt(msg)

        assertArrayEquals("counter 0 frame", referenceFrame(writeKey, 0L, msg), first)
        assertArrayEquals("counter 1 frame", referenceFrame(writeKey, 1L, msg), second)
        // Frames with different nonces must differ in ciphertext.
        var differs = false
        for (i in first.indices) if (first[i] != second[i]) differs = true
        assertEquals("frames must differ", true, differs)
    }

    @Test
    fun payloadLargerThan1024IsSplitWithIncreasingCounters() {
        val session = HapSession()
        session.enable(writeKey, writeKey)

        // 3000 bytes -> 3 frames: 1024, 1024, 952
        val payload = ByteArray(3000) { (it % 251).toByte() }
        val framed = session.encrypt(payload)

        // Total = 2*len-prefix + 3*16 tag + 3000 plaintext
        assertEquals(2 * 3 + 3 * HapSession.AUTH_TAG_LENGTH + payload.size, framed.size)

        // Build the expected concatenation frame-by-frame with counters 0,1,2.
        val expected = ByteArray(framed.size)
        var pos = 0
        var offset = 0
        var counter = 0L
        while (offset < payload.size) {
            val chunkSize = minOf(HapSession.FRAME_LENGTH, payload.size - offset)
            val chunk = payload.copyOfRange(offset, offset + chunkSize)
            val lenBytes = le16(chunkSize)
            val frame = lenBytes + referenceEncrypt(writeKey, counter++, lenBytes, chunk)
            frame.copyInto(expected, pos)
            pos += frame.size
            offset += chunkSize
        }
        assertArrayEquals("multi-frame layout with counters 0,1,2", expected, framed)

        // Length prefixes must read 1024 / 1024 / 952 (LE).
        assertEquals(1024, le16ToInt(framed, 0))
        assertEquals(1024, le16ToInt(framed, 2 + 1024 + 16))
        assertEquals(952, le16ToInt(framed, 2 * (2 + 1024 + 16)))
    }

    @Test
    fun roundtripSameKeyForEncryptionAndDecryption() {
        val session = HapSession()
        session.enable(writeKey, writeKey)

        val payload = ByteArray(2500) { (it * 7 % 256).toByte() }
        val framed = session.encrypt(payload)
        val decrypted = session.decrypt(framed)

        assertArrayEquals("roundtrip with the same key", payload, decrypted)
    }

    @Test
    fun separateWriteAndReadKeysWithOwnCounters() {
        // Real HAP directionality: each side encrypts with its own write key
        // and decrypts with the peer's key (mirrored), counters independent.
        val sender = HapSession()
        sender.enable(writeKey, readKey)
        val receiver = HapSession()
        receiver.enable(readKey, writeKey)

        val payload = "independent counters".toByteArray(Charsets.US_ASCII)
        val framed = sender.encrypt(payload)
        val decrypted = receiver.decrypt(framed)

        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun wrongReadKeyRejected() {
        val session = HapSession()
        session.enable(writeKey, otherKey)

        val framed = session.encrypt("secret".toByteArray(Charsets.US_ASCII))
        try {
            session.decrypt(framed)
            fail("decrypt with the wrong read key must throw (MAC failure)")
        } catch (expected: InvalidCipherTextException) {
            // expected: tag check failed
        }
    }

    @Test
    fun decryptHandlesMultipleFramesInOneCall() {
        val sender = HapSession()
        sender.enable(writeKey, readKey)
        val receiver = HapSession()
        receiver.enable(readKey, writeKey)

        val a = "frame one".toByteArray(Charsets.US_ASCII)
        val b = "frame two, slightly longer".toByteArray(Charsets.US_ASCII)
        val framed = sender.encrypt(a) + sender.encrypt(b)

        val decrypted = receiver.decrypt(framed)
        val expected = a + b
        assertArrayEquals("both frames decrypted from one buffer", expected, decrypted)
    }

    // ---- independent reference construction ---------------------------------

    /** Full HAP frame: [2-byte LE length][ciphertext+tag], built with the exact
     *  HAP nonce (4 zero bytes + 8-byte LE counter) and AAD (length prefix). */
    private fun referenceFrame(key: ByteArray, counter: Long, plaintext: ByteArray): ByteArray {
        val lenBytes = le16(plaintext.size)
        return lenBytes + referenceEncrypt(key, counter, lenBytes, plaintext)
    }

    /** AEAD encrypt with the HAP nonce layout spelled out explicitly. */
    private fun referenceEncrypt(key: ByteArray, counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12) // 4 zero bytes + 8-byte little-endian counter
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array()
            .copyInto(nonce, destinationOffset = 4)

        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun le16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

    private fun le16ToInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun bytes(vararg b: Int): ByteArray = b.map { it.toByte() }.toByteArray()
}
