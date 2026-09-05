package com.airplay.streamer.raop

import java.util.Base64
import kotlin.random.Random

/**
 * FairPlay SAPv2 stub handshake (POST /fp-setup x 2) required by `et=5`-only
 * receivers (e.g. Samsung AirScreen) before they accept ANNOUNCE.
 *
 * Extracted from `RaopClient.doFpSetup` + `buildDummyFpAesKey` with
 * byte-identical request/response bodies. Real streaming over this path would
 * require sender-side FairPlay SAPv2 crypto capable of producing the phase-2
 * key message; the stub only probes whether the receiver accepts the
 * transport exchange.
 */
class FairPlayHandshake(
    private val transport: RtspTransport,
    private val logD: (String) -> Unit
) {
    /**
     * Perform the two-phase POST /fp-setup exchange.
     * Returns true only when phase 2 returns 200 with a 32-byte body.
     */
    fun performSetup(mode: Int, requireValidPhase2: Boolean): Boolean {
        return try {
            // Phase 1 - match macOS Music's FairPlay SAPv2 probe:
            // FPLY 02 01 01 00 00 00 00 04 02 00 <mode> bb
            val phase1 = byteArrayOf(
                0x46, 0x50, 0x4c, 0x59,  // FPLY magic
                0x02, 0x01, 0x01, 0x00,  // version=2, type=1(setup), seq=1
                0x00, 0x00, 0x00, 0x04,  // payload length
                0x02, 0x00, mode.toByte(), 0xbb.toByte()
            )
            val headers1 = mutableMapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to phase1.size.toString()
            )
            transport.send("POST", "/fp-setup", headers1, phase1)

            val resp1 = transport.readResponse()
            logD("fp-setup phase1 response: ${resp1?.first}")
            val len1 = resp1?.second?.get("Content-Length")?.toIntOrNull() ?: 0
            transport.readBody(len1)
            if (resp1?.first != 200 || len1 != 142) {
                logD("fp-setup phase1 invalid: code=${resp1?.first}, length=$len1")
                return false
            }

            // Phase 2 - 164-byte FPLY v2 key message stub. Bytes 12..163 are
            // normally FairPlay output; random bytes let us test whether this
            // receiver only requires the transport exchange before ANNOUNCE.
            val phase2 = ByteArray(164).also { b ->
                Random.nextBytes(b)
                b[0] = 0x46; b[1] = 0x50; b[2] = 0x4c; b[3] = 0x59  // FPLY
                b[4] = 0x02  // version 2
                b[5] = 0x01  // type = setup
                b[6] = 0x03  // seq = 3
                b[7] = 0x00
                b[8] = 0x00; b[9] = 0x00; b[10] = 0x00; b[11] = 0x98.toByte()
                b[12] = mode.toByte()
            }
            val headers2 = mutableMapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to phase2.size.toString()
            )
            transport.send("POST", "/fp-setup", headers2, phase2)

            val resp2 = transport.readResponse()
            logD("fp-setup phase2 response: ${resp2?.first}")
            val len2 = resp2?.second?.get("Content-Length")?.toIntOrNull() ?: 0
            transport.readBody(len2)
            val valid = resp2?.first == 200 && len2 == 32
            if (!valid) {
                logD("fp-setup phase2 invalid: code=${resp2?.first}, length=$len2")
                if (requireValidPhase2) {
                    logD("FairPlay phase2 requires Apple's proprietary key message; dummy body is not enough")
                }
            }
            valid
        } catch (e: Exception) {
            logD("fp-setup skipped or failed: ${e.message}")
            false
        }
    }

    companion object {
        /**
         * 72-byte FPLY-prefixed dummy `fpaeskey` body (byte-exact layout from
         * RaopClient.buildDummyFpAesKey).
         */
        fun buildDummyFpAesKey(): String {
            val data = ByteArray(72)
            Random.nextBytes(data)
            data[0] = 0x46; data[1] = 0x50; data[2] = 0x4c; data[3] = 0x59
            data[4] = 0x01; data[5] = 0x02; data[6] = 0x01; data[7] = 0x00
            data[8] = 0x00; data[9] = 0x00; data[10] = 0x00; data[11] = 0x3c
            data[12] = 0x00; data[13] = 0x00; data[14] = 0x00; data[15] = 0x00
            return Base64.getEncoder().encodeToString(data)
        }
    }
}
