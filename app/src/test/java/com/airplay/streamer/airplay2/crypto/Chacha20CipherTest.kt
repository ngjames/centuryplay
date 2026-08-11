package com.airplay.streamer.airplay2.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [Chacha20Cipher] against RFC 8439 Appendix A.5 (AEAD
 * ChaCha20-Poly1305 with a 96-bit nonce).
 *
 * Golden bytes were independently generated with a pure-Python RFC 8439
 * implementation (stdlib only) that reproduces the RFC vector exactly
 * (see .omo/evidence/task-4-centuryplay-airplay2.txt), including the
 * RFC rule that the counter-0 ChaCha20 block is consumed by Poly1305 key
 * generation and the ciphertext keystream starts at block counter 1.
 */
class Chacha20CipherTest {

    // RFC 8439 A.5: key 80..9f, 96-bit nonce, 12-byte AAD, 114-byte plaintext.
    private val key = hex(
        "80 81 82 83 84 85 86 87 88 89 8a 8b 8c 8d 8e 8f" +
            "90 91 92 93 94 95 96 97 98 99 9a 9b 9c 9d 9e 9f"
    )
    private val nonce = hex("07 00 00 00 40 41 42 43 44 45 46 47")
    private val aad = hex("50 51 52 53 c0 c1 c2 c3 c4 c5 c6 c7")
    private val plaintext = ("Ladies and Gentlemen of the class of '99: " +
        "If I could offer you only one tip for the future, sunscreen would be it.")
        .toByteArray(Charsets.US_ASCII)

    // Full expected output: 114 bytes ciphertext + 16 bytes tag (130 bytes).
    private val expectedCiphertextAndTag = hex(
        "d3 1a 8d 34 64 8e 60 db 7b 86 af bc 53 ef 7e c2" +
            "a4 ad ed 51 29 6e 08 fe a9 e2 b5 a7 36 ee 62 d6" +
            "3d be a4 5e 8c a9 67 12 82 fa fb 69 da 92 72 8b" +
            "1a 71 de 0a 9e 06 0b 29 05 d6 a5 b6 7e cd 3b 36" +
            "92 dd bd 7f 2d 77 8b 8c 98 03 ae e3 28 09 1b 58" +
            "fa b3 24 e4 fa d6 75 94 55 85 80 8b 48 31 d7 bc" +
            "3f f4 de f0 8e 4b 7a 9d e5 76 d2 65 86 ce c6 4b" +
            "61 16" +
            "1a e1 0b 59 4f 09 e2 6a 7e 90 2e cb d0 60 06 91"
    )
    private val expectedTag = hex("1a e1 0b 59 4f 09 e2 6a 7e 90 2e cb d0 60 06 91")

    @Test
    fun rfc8439_a5_ciphertextAndTagMatches() {
        val cipher = Chacha20Cipher(key, key)
        val out = cipher.encrypt(plaintext, nonce, aad)

        assertEquals("output must be ciphertext (114) + tag (16) = 130 bytes", 130, out.size)
        assertArrayEquals(
            "RFC 8439 A.5 ciphertext+tag mismatch",
            expectedCiphertextAndTag,
            out
        )
    }

    @Test
    fun rfc8439_a5_tagIsLast16Bytes() {
        val cipher = Chacha20Cipher(key, key)
        val out = cipher.encrypt(plaintext, nonce, aad)

        val tag = out.copyOfRange(out.size - 16, out.size)
        assertArrayEquals("RFC 8439 A.5 tag mismatch", expectedTag, tag)
    }

    @Test
    fun rfc8439_a5_roundtripWithExplicitNonce() {
        val cipher = Chacha20Cipher(key, key)
        val out = cipher.encrypt(plaintext, nonce, aad)
        val decrypted = cipher.decrypt(out, nonce, aad)

        assertArrayEquals("decrypt must recover the plaintext", plaintext, decrypted)
    }

    @Test
    fun tamperedCiphertextRejected() {
        val cipher = Chacha20Cipher(key, key)
        val out = cipher.encrypt(plaintext, nonce, aad)

        // Flip one bit in the middle of the ciphertext: tag verification must fail.
        val tampered = out.copyOf()
        tampered[tampered.size / 2] = (tampered[tampered.size / 2].toInt() xor 0x01).toByte()

        try {
            cipher.decrypt(tampered, nonce, aad)
            fail("decrypt of tampered ciphertext must throw (MAC failure)")
        } catch (expected: InvalidCipherTextException) {
            // expected: BouncyCastle tag check failed
        }
    }

    @Test
    fun tamperedAadRejected() {
        val cipher = Chacha20Cipher(key, key)
        val out = cipher.encrypt(plaintext, nonce, aad)

        val tamperedAad = aad.copyOf()
        tamperedAad[0] = (tamperedAad[0].toInt() xor 0x01).toByte()

        try {
            cipher.decrypt(out, nonce, tamperedAad)
            fail("decrypt with wrong AAD must throw (MAC failure)")
        } catch (expected: InvalidCipherTextException) {
            // expected: BouncyCastle tag check failed
        }
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
