package com.airplay.streamer.raop

/**
 * Pure RTP / sync-packet builders used by the RAOP sender. Extracted from
 * `RaopClient.buildRtpPacket` / `swapEndianness` / `buildSyncPacket` with
 * byte-identical output, pinned by `RaopRtpPacketTest` and `NtpTimestampTest`.
 */
object RtpPacketBuilder {

    /**
     * Build a 12-byte RTP header + [data] payload.
     * Sequence/timestamp/SSRC are parameters so this stays a pure function;
     * the caller (RaopClient) supplies its live counters.
     */
    fun buildRtpPacket(data: ByteArray, sequence: Int, timestamp: Long, ssrc: Int): ByteArray {
        val h = ByteArray(12)
        h[0] = WireConstants.Rtp.VERSION_BYTE.toByte()
        h[1] = WireConstants.Rtp.PAYLOAD_TYPE.toByte()
        h[2] = (sequence shr 8).toByte(); h[3] = sequence.toByte()
        // RTP timestamps are 32-bit; mask the Long so the header wraps cleanly.
        val ts = timestamp and 0xFFFFFFFFL
        h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte()
        h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
        h[8] = (ssrc shr 24).toByte(); h[9] = (ssrc shr 16).toByte()
        h[10] = (ssrc shr 8).toByte(); h[11] = ssrc.toByte()
        return h + data
    }

    /** Swap every adjacent 16-bit pair (little-endian <-> big-endian PCM). */
    fun swapEndianness(data: ByteArray): ByteArray {
        val r = ByteArray(data.size)
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) { r[i] = data[i + 1]; r[i + 1] = data[i] }
        }
        return r
    }

    /**
     * Build a 20-byte RTP sync packet. The NTP field (offsets 8..15) uses the
     * RAOP fraction variant, matching RaopClient.buildSyncPacket byte-for-byte.
     */
    fun buildSyncPacket(sequence: Int, rtp: Long, time: Long, lat: Long): ByteArray {
        val p = ByteArray(20)
        p[0] = if (sequence == 0) 0x90.toByte() else 0x80.toByte()
        p[1] = 0xd4.toByte(); p[2] = (sequence shr 8).toByte(); p[3] = sequence.toByte()
        val rtp32 = rtp and 0xFFFFFFFFL
        p[4] = (rtp32 shr 24).toByte(); p[5] = (rtp32 shr 16).toByte(); p[6] = (rtp32 shr 8).toByte(); p[7] = rtp32.toByte()
        NtpResponder.writeNtpTimestamp(p, 8, NtpResponder.ntpSeconds(time), NtpResponder.raopFraction(time))
        val n = (rtp + lat) and 0xFFFFFFFFL
        p[16] = (n shr 24).toByte(); p[17] = (n shr 16).toByte(); p[18] = (n shr 8).toByte(); p[19] = n.toByte()
        return p
    }
}
