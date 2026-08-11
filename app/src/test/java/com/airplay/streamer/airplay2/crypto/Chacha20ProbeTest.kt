package com.airplay.streamer.airplay2.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Test

class Chacha20ProbeTest {
    @Test
    fun probe() {
        val key = hex("80 81 82 83 84 85 86 87 88 89 8a 8b 8c 8d 8e 8f 90 91 92 93 94 95 96 97 98 99 9a 9b 9c 9d 9e 9f")
        val nonce = hex("07 00 00 00 40 41 42 43 44 45 46 47")
        val aad = hex("50 51 52 53 c0 c1 c2 c3 c4 c5 c6 c7")
        val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(pt.size))
        val l1 = cipher.processBytes(pt, 0, pt.size, out, 0)
        val l2 = cipher.doFinal(out, l1)
        println("PROBE getOutputSize=${cipher.getOutputSize(pt.size)} l1=$l1 l2=$l2 total=${l1 + l2}")
        println("PROBE out=${out.copyOf(l1 + l2).joinToString("") { "%02x".format(it) }}")
    }

    private fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
