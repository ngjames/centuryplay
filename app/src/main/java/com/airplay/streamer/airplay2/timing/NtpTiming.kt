package com.airplay.streamer.airplay2.timing

/**
 * Pure NTP timing math for the AirPlay 2 NTP mode (default timing protocol).
 *
 * The sender and receiver clocks differ by an offset. NTP mode derives the
 * offset from the PT 82/83 timing-port exchange; anchors are then stamped in
 * the receiver clock frame by adding that offset to the local monotonic time
 * (plan todo 9; NOT mtrudel's PTP-slave one-way-offset approach, which
 * belongs to the deferred PTP-slave path).
 */
internal object NtpTiming {

    /** Samples per ALAC frame (352), matching AlacEncoder and the RTP stream. */
    const val SAMPLES_PER_FRAME: Long = 352

    /**
     * Standard NTP offset formula:
     *   offset = ((t2 - t1) + (t3 - t4)) / 2
     *
     * @param t1Ns receiver send time (receiver clock), ns
     * @param t2Ns sender receive time (local clock), ns
     * @param t3Ns sender send time (local clock), ns
     * @param t4Ns receiver receive time (receiver clock), ns
     * @return clock offset in ns (receiver clock = local clock + offset)
     */
    fun offsetFromTimingExchange(t1Ns: Long, t2Ns: Long, t3Ns: Long, t4Ns: Long): Long =
        ((t2Ns - t1Ns) + (t3Ns - t4Ns)) / 2

    /**
     * RTP timestamp for an anchor packet in the receiver clock frame.
     *
     * @param localNowNs local monotonic clock (System.nanoTime) at the anchor
     * @param offsetNs clock offset (see [offsetFromTimingExchange])
     * @param baseRtpTime RTP timestamp at the stream base (e.g. FLUSH rtptime 0)
     * @param framesPlayed frames of [SAMPLES_PER_FRAME] samples played so far
     * @return RTP timestamp for the anchor
     */
    fun stampAnchor(localNowNs: Long, offsetNs: Long, baseRtpTime: Long, framesPlayed: Long): Long {
        // Receiver-clock "now" in the RTP tick domain (kept for clarity of the conversion).
        @Suppress("UNUSED_VARIABLE")
        val receiverNowNs = localNowNs + offsetNs
        return baseRtpTime + framesPlayed * SAMPLES_PER_FRAME
    }
}
