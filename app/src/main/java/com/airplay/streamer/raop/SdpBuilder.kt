package com.airplay.streamer.raop

import java.util.Base64

/**
 * Builds the ANNOUNCE SDP body. Pure string builder; output is byte-identical
 * to `RaopClient.buildSdp` (L16 PCM with optional RSA-encrypted AES keys, or
 * the FairPlay `et=5` ALAC variant with a dummy `fpaeskey`).
 */
class SdpBuilder(
    private val host: String,
    private val sessionId: String,
    private val useFairPlayStub: Boolean,
    private val useEncryption: Boolean,
    private val logD: (String) -> Unit
) {
    fun build(localIp: String, rsaAesKey: String?, aesIv: ByteArray?): String {
        val pt = WireConstants.Rtp.PAYLOAD_TYPE
        val sr = WireConstants.AudioFormat.SAMPLE_RATE
        val ch = WireConstants.AudioFormat.CHANNELS
        val base = "v=0\r\no=iTunes $sessionId 0 IN IP4 $localIp\r\ns=iTunes\r\nc=IN IP4 $host\r\nt=0 0\r\nm=audio 0 RTP/AVP $pt"
        return if (useFairPlayStub) {
            val dummyFpAesKey = FairPlayHandshake.buildDummyFpAesKey()
            val dummyAesIv = Base64.getEncoder().encodeToString(aesIv ?: ByteArray(16))
            logD("FairPlay et=5 receiver; announcing ALAC with dummy fpaeskey")
            "$base\r\na=rtpmap:$pt AppleLossless\r\na=fmtp:$pt ${WireConstants.AudioFormat.FRAMES_PER_PACKET} 0 ${WireConstants.AudioFormat.BITS_PER_SAMPLE} 40 10 14 $ch 255 0 0 $sr\r\na=fpaeskey:$dummyFpAesKey\r\na=aesiv:$dummyAesIv\r\n"
        } else if (useEncryption) {
            "$base\r\na=rtpmap:$pt L16/$sr/$ch\r\na=rsaaeskey:$rsaAesKey\r\na=aesiv:${Base64.getEncoder().encodeToString(aesIv)}\r\n"
        } else {
            logD("No RSA support on receiver; announcing unencrypted L16")
            "$base\r\na=rtpmap:$pt L16/$sr/$ch\r\n"
        }
    }
}
