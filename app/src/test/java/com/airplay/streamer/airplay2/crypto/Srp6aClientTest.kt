package com.airplay.streamer.airplay2.crypto

import com.airplay.streamer.util.ByteArrayFormat.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * SRP-6a golden tests.
 *
 * The ported Srp6aClient does NOT use BouncyCastle's SRP6Client (whose
 * no-arg default is SHA-1): it derives everything with JDK SHA-512 and the
 * RFC 5054 3072-bit group directly. The golden values below were computed
 * with python3 (stdlib hashlib.sha512 + builtin pow) mirroring the author's
 * dev scripts/airplay2_srp.py exactly (k = H(PAD(N)|PAD(g)), u = H(PAD(A)|PAD(B)),
 * x = H(salt|H(I:":":P)), K = H(S_natural), M1 = H(H(N)^H(g)|H(I)|s|A|B|K),
 * M2 = H(A|M1|K)); see .omo/evidence/task-5-centuryplay-airplay2.txt for the
 * derivation command. A SHA-1 slip anywhere would change k/M1, so a match
 * proves SHA-512 end to end.
 */
class Srp6aClientTest {

    private val identity = "Pair-Setup".toByteArray()
    private val password = "3939".toByteArray()

    /** Fixed 16-byte salt from the golden derivation. */
    private val salt = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
    )

    /** Fixed 384-byte server public key B = bytes 0x01..0xFF,0x00.. pattern. */
    private val serverB: BigInteger = BigInteger(
        1, ByteArray(384) { ((it + 1) % 256).toByte() }
    )

    /** Fixed client private key from the golden derivation. */
    private val privateA: BigInteger = BigInteger(
        "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF", 16
    )

    // ---- python3 goldens (see evidence file for the derivation command) ----
    private val goldenK = "a9c2e2559bf0ebb53f0cbbf62282906bede7f2182f00678211fbd5bde5b285033a4993503b87397f9be5ec02080fedbc0835587ad039060879b8621e8c3659e0"
    private val goldenA = "eecc6724ae6254074ff0632985ebd75641c5beaac7c52040a4b5f90cadd01455f1afc84eeb89c383a0fa0a66cd92d876d695d22a381e5168685001e194cb24982f7ce57f26a3467f0efe9dffda740f8ec79d7f33e48db75417569b16523f236d4c88ad77907cf9e6e5af4826382f4040eb06fb6b740602b058625bac0c12f7df534053a296217493378e739984fff377ff0ea140df0edbf7084dcef9063d8e5fb53a04cddd5a82a872c22a6c9c8d2ca006fc8a83f5e900d48fde5d66dd77162f781a3c0101dbdb1a3f253c25e719173bc831b16899153436f3a00de29b9850a7bfc1900d5694c454f4a7d712651b807eee435508ed3dfdf4a83dce0888c295c146ec25ddf02d8fb96f264fe9fc8f7a9750160239fb69ca0964b88f8d0f39a8fa143f1452c3c115d73763ae6286e664d0ad8682b3e05fcca7c377a70bfbf53e8de5e9db3e07b43b3bfeea3cf83884c3148f6fdab49858c16443ba4f51e86f77aaa7fbafe856a606ba0b9ca1651ca0945a99283a7bb8fdd591baee983a5b85ffe0"
    private val goldenSessionK = "690df2e548fb6650159b4d63800de5a5c6401a86fa3cc34a3b3385e65917dfbe0e2866b04b283062127cdd29bc835220e978c232bcfe0f42df739625cbd91259"
    private val goldenM1 = "b2659a5718afc2bda81209902283c1153d6fd93f630c1ef441cf353bb996c75aff9796bca231b18835d05e19f9a205b68a6abf71ccec06d259f28c8e041155c9"
    private val goldenM2 = "0ad791c45ad7021834c9cec91a0b5806f54dd0919acbf938ee46098dc73dd2abf9ef9bcd08abb7f3a4817b084b6b4390e1f2043395945dd02f3dbca67500dd1e"

    /** RFC 5054 Appendix A 3072-bit group prime. */
    private val rfc5054N3072Hex =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B" +
        "139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485" +
        "B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1F" +
        "E649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F83655D23" +
        "DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32" +
        "905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF69558" +
        "17183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521" +
        "ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7ABF5AE8CDB0933D7" +
        "1E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B1817" +
        "7B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82" +
        "D120A93AD2CAFFFFFFFFFFFFFFFF"

    @Test
    fun `uses RFC 5054 3072-bit group with g=5`() {
        assertEquals(rfc5054N3072Hex.lowercase(), Srp6aClient.N.toString(16))
        assertEquals(BigInteger.valueOf(5), Srp6aClient.g)
        assertEquals(384, Srp6aClient.N_BYTES)
    }

    @Test
    fun `multiplier k matches sha512 golden`() {
        // k = H(PAD(N) | PAD(g)) with SHA-512. BouncyCastle's default SRP
        // digest is SHA-1, which would produce a different k - this assertion
        // is the guard that the client really is SRP-6a/SHA-512.
        assertEquals(goldenK, Srp6aClient.calculateK().toString(16))
    }

    @Test
    fun `golden A K and M1 match python derivation`() {
        val client = Srp6aClient(identity, password)
        client.generateClientCredentials(privateA)

        val (A, M1) = client.processChallenge(salt, serverB)

        assertEquals(goldenA, A.toHexString())
        assertEquals(384, A.size)
        assertEquals(goldenSessionK, client.K!!.toHexString())
        assertEquals(goldenM1, M1.toHexString())
        assertEquals(64, M1.size)
    }

    @Test
    fun `verifies server proof M2 from golden derivation`() {
        val client = Srp6aClient(identity, password)
        client.generateClientCredentials(privateA)
        client.processChallenge(salt, serverB)

        assertTrue(client.verifyServerProof(goldenM2.hexToByteArray()))
    }

    @Test
    fun `rejects a tampered server proof`() {
        val client = Srp6aClient(identity, password)
        client.generateClientCredentials(privateA)
        client.processChallenge(salt, serverB)

        val tampered = goldenM2.hexToByteArray()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        assertFalse(client.verifyServerProof(tampered))
    }

    @Test
    fun `random-key path produces valid session and self-consistent proof`() {
        val client = Srp6aClient(identity, password)
        val (A, M1) = client.processChallenge(salt, serverB)

        assertTrue(A.size in 1..384)
        assertEquals(64, M1.size)
        assertEquals(64, client.K!!.size)

        val serverM2 = Srp6aClient.computeM2(client.A!!, client.M1!!, client.K!!)
        assertTrue(client.verifyServerProof(serverM2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid server public key`() {
        val client = Srp6aClient(identity, password)
        client.generateClientCredentials(privateA)
        client.processChallenge(salt, BigInteger.ZERO)
    }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
