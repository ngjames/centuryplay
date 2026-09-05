package com.airplay.streamer.airplay2.crypto

import com.airplay.streamer.util.ByteArrayFormat.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests for [Hkdf.Control] HKDF-SHA512 key derivation.
 *
 * Golden values computed with python3 (stdlib only: hmac + hashlib) using
 * HKDF-Extract = HMAC-SHA512(salt, ikm), HKDF-Expand first block =
 * HMAC-SHA512(prk, info || 0x01), matching BouncyCastle
 * HKDFParameters(ikm, salt, info) with salt = "Control-Salt" (UTF-8),
 * info = "Control-Write-Encryption-Key" / "Control-Read-Encryption-Key",
 * output length 32 (see .omo/evidence/task-4-centuryplay-airplay2.txt).
 */
class HkdfTest {

    private val ikm = ByteArray(32) { it.toByte() } // 0x00..0x1F

    // python3 golden values
    private val goldenOutputKey = hex("c3ca130c7033dbe5e7ff7f91d117ead869bac476994c7a48ca170c111136ed96")
    private val goldenInputKey = hex("c09403ef8aa6c5045cbd8cf9bf3e665b2caed623af2be0e87c8f80f519914d3d")

    @Test
    fun deriveOutputKeyMatchesGolden() {
        val derived = Hkdf.Control.deriveOutputKey(ikm)
        assertEquals("deriveOutputKey must be 32 bytes", 32, derived.size)
        assertEquals("HKDF-SHA512 Control-Write-Encryption-Key mismatch",
            goldenOutputKey.toHexString(), derived.toHexString())
    }

    @Test
    fun deriveInputKeyMatchesGolden() {
        val derived = Hkdf.Control.deriveInputKey(ikm)
        assertEquals("deriveInputKey must be 32 bytes", 32, derived.size)
        assertEquals("HKDF-SHA512 Control-Read-Encryption-Key mismatch",
            goldenInputKey.toHexString(), derived.toHexString())
    }

    @Test
    fun outputAndInputKeysDiffer() {
        val output = Hkdf.Control.deriveOutputKey(ikm)
        val input = Hkdf.Control.deriveInputKey(ikm)
        assertNotEquals("write and read keys must differ", output.toHexString(), input.toHexString())
    }

    @Test
    fun derivationIsDeterministic() {
        assertEquals(
            Hkdf.Control.deriveOutputKey(ikm).toHexString(),
            Hkdf.Control.deriveOutputKey(ikm).toHexString()
        )
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
