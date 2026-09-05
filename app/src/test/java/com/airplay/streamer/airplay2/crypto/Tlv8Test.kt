package com.airplay.streamer.airplay2.crypto

import com.airplay.streamer.util.ByteArrayFormat.toHexString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TLV8 roundtrip tests for the HAP pair-setup codec.
 *
 * Wire format per entry: [type:1][length:1][value:0..255]. Values longer
 * than 255 bytes are fragmented across consecutive entries carrying the
 * same type; decode reassembles them in order.
 */
class Tlv8Test {

    private fun assertHexEquals(expected: String, actual: ByteArray) {
        assertEquals(expected, actual.toHexString())
    }

    @Test
    fun `constants match HAP pair-setup spec`() {
        assertEquals(0x00, Tlv8.Type.METHOD)
        assertEquals(0x01, Tlv8.Type.IDENTIFIER)
        assertEquals(0x02, Tlv8.Type.SALT)
        assertEquals(0x03, Tlv8.Type.PUBLIC_KEY)
        assertEquals(0x04, Tlv8.Type.PROOF)
        assertEquals(0x05, Tlv8.Type.ENCRYPTED_DATA)
        assertEquals(0x06, Tlv8.Type.SEQ_NO)
        assertEquals(0x07, Tlv8.Type.ERROR)
        assertEquals(0x0A, Tlv8.Type.SIGNATURE)
        assertEquals(0x13, Tlv8.Type.FLAGS)
        assertEquals(0x10, Tlv8.Flags.TRANSIENT_PAIRING)
    }

    @Test
    fun `roundtrip small values preserves all pairs`() {
        val items = mapOf(
            Tlv8.Type.METHOD to byteArrayOf(0x00),
            Tlv8.Type.SEQ_NO to byteArrayOf(0x03),
            Tlv8.Type.PUBLIC_KEY to byteArrayOf(0x11, 0x22, 0x33, 0x44),
            Tlv8.Type.PROOF to ByteArray(64) { it.toByte() },
            Tlv8.Type.SALT to "000102030405060708090A0B0C0D0E0F".chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray()
        )

        val decoded = Tlv8.decode(Tlv8.encode(items))

        assertEquals(items.size, decoded.size)
        for ((type, value) in items) {
            assertTrue("missing type 0x${type.toString(16)}", decoded.containsKey(type))
            assertArrayEquals("type 0x${type.toString(16)}", value, decoded[type])
        }
    }

    @Test
    fun `value over 255 bytes fragments into chunks of 255 plus remainder`() {
        val value = ByteArray(300) { (it % 256).toByte() }
        val wire = Tlv8.encode(mapOf(Tlv8.Type.SIGNATURE to value))

        // Two fragments: [type 0x0A][len 0xFF][255 bytes][type 0x0A][len 0x2D][45 bytes]
        assertEquals(2 + 255 + 2 + 45, wire.size)
        assertEquals(0x0A, wire[0].toInt() and 0xFF)
        assertEquals(0xFF, wire[1].toInt() and 0xFF)
        assertEquals(0x0A, wire[257].toInt() and 0xFF)
        assertEquals(0x2D, wire[258].toInt() and 0xFF)

        assertArrayEquals(value.copyOfRange(0, 255), wire.copyOfRange(2, 2 + 255))
        assertArrayEquals(value.copyOfRange(255, 300), wire.copyOfRange(259, 259 + 45))
    }

    @Test
    fun `fragmented value reassembles on decode`() {
        val value = ByteArray(300) { (it * 7 % 256).toByte() }
        val decoded = Tlv8.decode(Tlv8.encode(mapOf(Tlv8.Type.ENCRYPTED_DATA to value)))

        assertEquals(1, decoded.size)
        assertArrayEquals(value, decoded[Tlv8.Type.ENCRYPTED_DATA])
    }

    @Test
    fun `empty value encodes as type with zero length`() {
        val wire = Tlv8.encode(mapOf(Tlv8.Type.ERROR to ByteArray(0)))

        assertEquals(2, wire.size)
        assertEquals(0x07, wire[0].toInt() and 0xFF)
        assertEquals(0x00, wire[1].toInt() and 0xFF)

        val decoded = Tlv8.decode(wire)
        assertEquals(1, decoded.size)
        assertTrue(decoded.containsKey(Tlv8.Type.ERROR))
        assertArrayEquals(ByteArray(0), decoded[Tlv8.Type.ERROR])
    }

    @Test
    fun `mixed small and large values roundtrip together`() {
        val big = ByteArray(600) { (it % 256).toByte() }
        val items = mapOf(
            Tlv8.Type.METHOD to byteArrayOf(0x00),
            Tlv8.Type.FLAGS to byteArrayOf(0x10),
            Tlv8.Type.PUBLIC_KEY to big
        )

        val decoded = Tlv8.decode(Tlv8.encode(items))

        assertEquals(items.size, decoded.size)
        assertArrayEquals(items[Tlv8.Type.METHOD], decoded[Tlv8.Type.METHOD])
        assertArrayEquals(items[Tlv8.Type.FLAGS], decoded[Tlv8.Type.FLAGS])
        assertArrayEquals(big, decoded[Tlv8.Type.PUBLIC_KEY])
    }

    @Test
    fun `vararg encode matches map encode`() {
        val pair1 = Tlv8.Type.METHOD to byteArrayOf(0x00)
        val pair2 = Tlv8.Type.SEQ_NO to byteArrayOf(0x01)
        assertHexEquals(
            Tlv8.encode(mapOf(pair1, pair2)).toHexString(),
            Tlv8.encode(pair1, pair2)
        )
    }

    @Test
    fun `byteValue wraps to single byte`() {
        assertHexEquals("03", Tlv8.byteValue(0x03))
        assertHexEquals("10", Tlv8.byteValue(Tlv8.Flags.TRANSIENT_PAIRING))
    }

    @Test
    fun `truncated data decodes without throwing`() {
        val wire = byteArrayOf(0x03, 0x05, 0x11, 0x22) // length claims 5, only 2 present
        val decoded = Tlv8.decode(wire)
        assertEquals(0, decoded.size)

        val odd = byteArrayOf(0x03) // header incomplete
        assertEquals(0, Tlv8.decode(odd).size)
    }

    @Test
    fun `M1 transient message encodes to expected wire bytes`() {
        val wire = Tlv8.encode(
            Tlv8.Type.METHOD to Tlv8.byteValue(0x00),
            Tlv8.Type.SEQ_NO to Tlv8.byteValue(0x01),
            Tlv8.Type.FLAGS to Tlv8.byteValue(Tlv8.Flags.TRANSIENT_PAIRING)
        )
        // [00 01 00] [06 01 01] [13 01 10]
        assertHexEquals("000100060101130110", wire)
    }
}
