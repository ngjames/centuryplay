package com.airplay.streamer.airplay2.timing

/**
 * Pure NTP timing math for the AirPlay 2 NTP mode (default timing protocol).
 *
 * The sender and receiver clocks differ by an offset. NTP mode derives the
 * offset from the PT 82/83 timing-port exchange.
 */
internal object NtpTiming {

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
}
